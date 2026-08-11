use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jlong, jstring},
};
use ocr_translation_core::{complete_translation, prepare_translation};
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EdgeResult<T: Serialize> {
    ok: bool,
    value: Option<T>,
    error: Option<String>,
}

pub fn prepare(request: &str, provider: &str, model: &str) -> String {
    encode(prepare_translation(request, provider, model))
}

pub fn complete(prepared: &str, completion: &str, total_ms: u64) -> String {
    encode(complete_translation(prepared, completion, total_ms).map(|value| value.response))
}

fn encode<T: Serialize, E: std::fmt::Display>(result: Result<T, E>) -> String {
    let envelope = match result {
        Ok(value) => EdgeResult {
            ok: true,
            value: Some(value),
            error: None,
        },
        Err(error) => EdgeResult::<T> {
            ok: false,
            value: None,
            error: Some(error.to_string()),
        },
    };
    serde_json::to_string(&envelope).unwrap_or_else(|error| {
        format!("{{\"ok\":false,\"error\":\"serialization failed: {error}\"}}")
    })
}

fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| error.to_string())
}

fn return_string(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativePrepare(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
    provider: JString,
    model: JString,
) -> jstring {
    let result = java_string(&mut env, request)
        .and_then(|request| {
            let provider = java_string(&mut env, provider)?;
            let model = java_string(&mut env, model)?;
            Ok(prepare(&request, &provider, &model))
        })
        .unwrap_or_else(|error| {
            format!(
                "{{\"ok\":false,\"error\":{}}}",
                serde_json::to_string(&error).unwrap()
            )
        });
    return_string(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativeComplete(
    mut env: JNIEnv,
    _class: JClass,
    prepared: JString,
    completion: JString,
    total_ms: jlong,
) -> jstring {
    let result = java_string(&mut env, prepared)
        .and_then(|prepared| {
            let completion = java_string(&mut env, completion)?;
            Ok(complete(&prepared, &completion, total_ms.max(0) as u64))
        })
        .unwrap_or_else(|error| {
            format!(
                "{{\"ok\":false,\"error\":{}}}",
                serde_json::to_string(&error).unwrap()
            )
        });
    return_string(&mut env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_are_returned_as_json_instead_of_crossing_ffi() {
        let result: serde_json::Value =
            serde_json::from_str(&prepare("{}", "openlux", "gpt-4.1")).unwrap();
        assert_eq!(result["ok"], false);
        assert!(result["error"].as_str().unwrap().contains("missing field"));
    }
}
