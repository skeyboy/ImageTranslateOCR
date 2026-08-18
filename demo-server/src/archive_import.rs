use std::{
    collections::HashMap,
    io::{Cursor, Read},
    path::PathBuf,
};

use chrono::Utc;
use ocr_translation_core::contract::{REGIONS_FIRST_SCHEMA_VERSION, SemanticTranslationRequest};
use serde_json::{Value, json};
use uuid::Uuid;
use zip::ZipArchive;

use crate::{
    database::{NewRenderedRequestImage, NewRequestAudit, NewRequestImage, NewRequestPayload},
    error::AppError,
    routes::AppState,
};

pub async fn import_archive(state: &AppState, bytes: &[u8]) -> Result<String, AppError> {
    if bytes.is_empty() || bytes.len() > MAX_ARCHIVE_BYTES {
        return Err(AppError::invalid("archive must contain at most 16 MiB"));
    }
    let entries = read_entries(bytes)?;
    let request_raw = required_text(&entries, "request.json")?;
    let request: SemanticTranslationRequest = serde_json::from_str(request_raw)
        .map_err(|error| AppError::invalid(format!("request.json is invalid: {error}")))?;
    request
        .validate_schema(REGIONS_FIRST_SCHEMA_VERSION)
        .map_err(AppError::from)?;
    let manifest = entries
        .get("manifest.json")
        .map(|raw| serde_json::from_slice::<Value>(raw))
        .transpose()
        .map_err(|error| AppError::invalid(format!("manifest.json is invalid: {error}")))?
        .unwrap_or_else(|| json!({}));
    if manifest
        .get("archiveSchemaVersion")
        .and_then(Value::as_u64)
        .unwrap_or(1)
        != 1
    {
        return Err(AppError::invalid("unsupported archiveSchemaVersion"));
    }
    let response_raw = entries
        .get("response.json")
        .map(|raw| std::str::from_utf8(raw))
        .transpose()
        .map_err(|_| AppError::invalid("response.json must be UTF-8"))?;
    let response = response_raw
        .map(serde_json::from_str::<Value>)
        .transpose()
        .map_err(|error| AppError::invalid(format!("response.json is invalid: {error}")))?;
    if let Some(response) = &response
        && (response.get("requestId").and_then(Value::as_str) != Some(&request.request_id)
            || response.get("schemaVersion").and_then(Value::as_u64)
                != Some(REGIONS_FIRST_SCHEMA_VERSION as u64))
    {
        return Err(AppError::invalid(
            "response.json does not match request.json",
        ));
    }
    let error_message = entries
        .get("error.json")
        .and_then(|raw| serde_json::from_slice::<Value>(raw).ok())
        .and_then(|value| {
            value
                .get("message")
                .and_then(Value::as_str)
                .map(str::to_owned)
        });
    let audit_id = Uuid::new_v4().to_string();
    let source_image = save_imported_image(
        state,
        &audit_id,
        "source",
        find_image(&entries, "source-capture"),
    )
    .await?;
    let rendered_image = save_imported_image(
        state,
        &audit_id,
        "rendered",
        find_image(&entries, "rendered-capture"),
    )
    .await?;
    let source_dimensions = request.debug_capture.as_ref().map(|capture| {
        (
            capture.mime_type.as_str(),
            capture.pixel_width,
            capture.pixel_height,
        )
    });
    let render_audit = entries
        .get("render-audit.json")
        .map(|raw| serde_json::from_slice::<Value>(raw))
        .transpose()
        .map_err(|error| AppError::invalid(format!("render-audit.json is invalid: {error}")))?
        .unwrap_or_else(|| json!({}));
    let provider_audit = json!({
        "request": optional_json(&entries, "provider-request.json")?,
        "response": optional_json(&entries, "provider-response.json")?,
        "execution": "android-archive-import",
        "archiveManifest": manifest,
    });
    let model_request_json = serde_json::to_string(&provider_audit)
        .map_err(|error| AppError::invalid(error.to_string()))?;
    let failed_groups = response
        .as_ref()
        .and_then(|value| value.pointer("/metrics/failedGroupCount"))
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let status = if error_message.is_some() {
        "FAILED"
    } else if failed_groups > 0 {
        "PARTIAL"
    } else {
        "SUCCEEDED"
    };
    let provider = manifest
        .get("provider")
        .and_then(Value::as_str)
        .unwrap_or("imported");
    let model = manifest
        .get("model")
        .and_then(Value::as_str)
        .unwrap_or("unknown");
    let audit_model = format!("{provider}:{model} (imported)");
    let created_at = manifest
        .get("createdAt")
        .and_then(Value::as_str)
        .filter(|value| value.len() <= 64)
        .map(str::to_owned)
        .unwrap_or_else(|| Utc::now().to_rfc3339());
    let duration_ms = response
        .as_ref()
        .and_then(|value| value.pointer("/metrics/totalMs"))
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let input_chars = request
        .regions
        .iter()
        .map(|region| region.text.chars().count())
        .sum::<usize>();
    let source_path = source_image
        .as_ref()
        .map(|(path, _, _)| path.to_string_lossy().into_owned());
    let image_record =
        source_image
            .as_ref()
            .zip(source_path.as_deref())
            .map(|((_, mime, size), path)| NewRequestImage {
                audit_id: &audit_id,
                image_path: path,
                mime_type: source_dimensions.map(|item| item.0).unwrap_or(mime),
                pixel_width: source_dimensions.map(|item| item.1).unwrap_or(1),
                pixel_height: source_dimensions.map(|item| item.2).unwrap_or(1),
                byte_size: *size as i64,
            });
    let audit = NewRequestAudit {
        id: &audit_id,
        request_id: &request.request_id,
        session_id: &request.session_id,
        generation: request.generation,
        scene: &request.scene,
        group_count: request.groups.len() as i32,
        region_count: request.regions.len() as i32,
        input_chars: input_chars.min(i32::MAX as usize) as i32,
        status,
        model: &audit_model,
        duration_ms,
        created_at: &created_at,
    };
    let payload = NewRequestPayload {
        audit_id: &audit_id,
        request_json: request_raw,
        response_json: response_raw,
        error_message: error_message.as_deref(),
        model_request_json: Some(&model_request_json),
    };
    let stale_paths = state
        .database
        .insert_record(
            audit,
            payload,
            image_record,
            state.config.request_history_limit,
        )
        .await?;
    for path in stale_paths {
        let _ = tokio::fs::remove_file(path).await;
    }
    if let Some((path, mime, size)) = rendered_image.as_ref() {
        let rendered_path = path.to_string_lossy().into_owned();
        let diagnostics = render_audit
            .get("layoutDiagnostics")
            .filter(|value| !value.is_null())
            .and_then(|value| serde_json::to_string(value).ok());
        let rendered = NewRenderedRequestImage {
            audit_id: &audit_id,
            image_path: &rendered_path,
            mime_type: render_audit
                .get("mimeType")
                .and_then(Value::as_str)
                .unwrap_or(mime),
            pixel_width: render_audit
                .get("pixelWidth")
                .and_then(Value::as_i64)
                .unwrap_or(1) as i32,
            pixel_height: render_audit
                .get("pixelHeight")
                .and_then(Value::as_i64)
                .unwrap_or(1) as i32,
            byte_size: *size as i64,
            outcome: render_audit
                .get("outcome")
                .and_then(Value::as_str)
                .unwrap_or("PRESENTED"),
            stage: render_audit.get("stage").and_then(Value::as_str),
            failure_code: render_audit.get("failureCode").and_then(Value::as_str),
            failure_message: render_audit.get("failureMessage").and_then(Value::as_str),
            layout_diagnostics_json: diagnostics.as_deref(),
        };
        state.database.replace_rendered_image(rendered).await?;
    }
    Ok(audit_id)
}

fn read_entries(bytes: &[u8]) -> Result<HashMap<String, Vec<u8>>, AppError> {
    let mut archive = ZipArchive::new(Cursor::new(bytes))
        .map_err(|error| AppError::invalid(format!("invalid ZIP archive: {error}")))?;
    if archive.len() == 0 || archive.len() > MAX_ENTRY_COUNT {
        return Err(AppError::invalid(
            "archive must contain between 1 and 16 files",
        ));
    }
    let mut total = 0usize;
    let mut entries = HashMap::new();
    for index in 0..archive.len() {
        let mut file = archive
            .by_index(index)
            .map_err(|error| AppError::invalid(format!("invalid ZIP entry: {error}")))?;
        if file.is_dir()
            || file.name().contains('/')
            || file.name().contains('\\')
            || !ALLOWED_FILES.iter().any(|allowed| file.name() == *allowed)
        {
            return Err(AppError::invalid(format!(
                "unsupported archive entry: {}",
                file.name()
            )));
        }
        if file.size() as usize > MAX_ENTRY_BYTES {
            return Err(AppError::invalid(format!(
                "archive entry is too large: {}",
                file.name()
            )));
        }
        let mut value = Vec::with_capacity(file.size() as usize);
        file.read_to_end(&mut value)
            .map_err(|error| AppError::invalid(format!("cannot read ZIP entry: {error}")))?;
        total = total.saturating_add(value.len());
        if total > MAX_TOTAL_UNCOMPRESSED_BYTES {
            return Err(AppError::invalid("archive expands beyond 24 MiB"));
        }
        if entries.insert(file.name().to_owned(), value).is_some() {
            return Err(AppError::invalid("archive contains duplicate entries"));
        }
    }
    Ok(entries)
}

async fn save_imported_image(
    state: &AppState,
    audit_id: &str,
    kind: &str,
    image: Option<(&str, &[u8])>,
) -> Result<Option<(PathBuf, &'static str, usize)>, AppError> {
    let Some((name, bytes)) = image else {
        return Ok(None);
    };
    if bytes.is_empty() || bytes.len() > MAX_IMAGE_BYTES {
        return Err(AppError::invalid(
            "imported image must contain at most 8 MiB",
        ));
    }
    let (extension, mime) = if name.ends_with(".png") {
        ("png", "image/png")
    } else if name.ends_with(".webp") {
        ("webp", "image/webp")
    } else {
        ("jpg", "image/jpeg")
    };
    tokio::fs::create_dir_all(&state.config.request_image_dir)
        .await
        .map_err(AppError::database)?;
    let path = state
        .config
        .request_image_dir
        .join(format!("{audit_id}.imported-{kind}.{extension}"));
    tokio::fs::write(&path, bytes)
        .await
        .map_err(AppError::database)?;
    Ok(Some((path, mime, bytes.len())))
}

fn find_image<'a>(
    entries: &'a HashMap<String, Vec<u8>>,
    stem: &str,
) -> Option<(&'a str, &'a [u8])> {
    ["jpg", "png", "webp"].into_iter().find_map(|extension| {
        let name = format!("{stem}.{extension}");
        entries
            .get_key_value(&name)
            .map(|(name, bytes)| (name.as_str(), bytes.as_slice()))
    })
}

fn required_text<'a>(
    entries: &'a HashMap<String, Vec<u8>>,
    name: &str,
) -> Result<&'a str, AppError> {
    std::str::from_utf8(
        entries
            .get(name)
            .ok_or_else(|| AppError::invalid(format!("archive is missing {name}")))?,
    )
    .map_err(|_| AppError::invalid(format!("{name} must be UTF-8")))
}

fn optional_json(entries: &HashMap<String, Vec<u8>>, name: &str) -> Result<Value, AppError> {
    entries
        .get(name)
        .map(|raw| {
            serde_json::from_slice(raw)
                .map_err(|error| AppError::invalid(format!("{name} is invalid: {error}")))
        })
        .transpose()
        .map(|value| value.unwrap_or(Value::Null))
}

const MAX_ARCHIVE_BYTES: usize = 16 * 1024 * 1024;
const MAX_ENTRY_BYTES: usize = 12 * 1024 * 1024;
const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
const MAX_TOTAL_UNCOMPRESSED_BYTES: usize = 24 * 1024 * 1024;
const MAX_ENTRY_COUNT: usize = 16;
const ALLOWED_FILES: &[&str] = &[
    "manifest.json",
    "request.json",
    "response.json",
    "provider-request.json",
    "provider-response.json",
    "error.json",
    "render-audit.json",
    "source-capture.jpg",
    "source-capture.png",
    "source-capture.webp",
    "rendered-capture.jpg",
    "rendered-capture.png",
    "rendered-capture.webp",
];

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::{ZipWriter, write::SimpleFileOptions};

    fn archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut output = Cursor::new(Vec::new());
        {
            let mut writer = ZipWriter::new(&mut output);
            for (name, contents) in entries {
                writer
                    .start_file(*name, SimpleFileOptions::default())
                    .unwrap();
                writer.write_all(contents).unwrap();
            }
            writer.finish().unwrap();
        }
        output.into_inner()
    }

    #[test]
    fn accepts_only_the_versioned_flat_archive_contract() {
        let bytes = archive(&[
            ("manifest.json", br#"{"archiveSchemaVersion":1}"#),
            ("request.json", b"{}"),
        ]);
        let entries = read_entries(&bytes).unwrap();
        assert!(entries.contains_key("request.json"));
    }

    #[test]
    fn rejects_zip_path_traversal() {
        let bytes = archive(&[("../request.json", b"{}")]);
        assert!(
            read_entries(&bytes)
                .unwrap_err()
                .to_string()
                .contains("unsupported")
        );
    }
}
