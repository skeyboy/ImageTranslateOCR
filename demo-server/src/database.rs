use std::collections::HashMap;

use diesel::{
    ExpressionMethods, Insertable, QueryDsl, Queryable, QueryableByName, sql_query,
    sql_types::Text, table,
};
use diesel_async::{
    AsyncConnection, RunQueryDsl, SimpleAsyncConnection,
    sync_connection_wrapper::SyncConnectionWrapper,
};

use crate::error::AppError;

type AsyncSqliteConnection = SyncConnectionWrapper<diesel::sqlite::SqliteConnection>;

table! {
    request_audits (id) {
        id -> Text,
        request_id -> Text,
        session_id -> Text,
        generation -> BigInt,
        scene -> Text,
        group_count -> Integer,
        region_count -> Integer,
        input_chars -> Integer,
        status -> Text,
        model -> Text,
        duration_ms -> BigInt,
        created_at -> Text,
    }
}

table! {
    request_payloads (audit_id) {
        audit_id -> Text,
        request_json -> Text,
        response_json -> Nullable<Text>,
        error_message -> Nullable<Text>,
        model_request_json -> Nullable<Text>,
    }
}

table! {
    request_images (audit_id) {
        audit_id -> Text,
        image_path -> Text,
        mime_type -> Text,
        pixel_width -> Integer,
        pixel_height -> Integer,
        byte_size -> BigInt,
    }
}

table! {
    rendered_request_images (audit_id) {
        audit_id -> Text,
        image_path -> Text,
        mime_type -> Text,
        pixel_width -> Integer,
        pixel_height -> Integer,
        byte_size -> BigInt,
        outcome -> Text,
        stage -> Nullable<Text>,
        failure_code -> Nullable<Text>,
        failure_message -> Nullable<Text>,
        layout_diagnostics_json -> Nullable<Text>,
    }
}

diesel::allow_tables_to_appear_in_same_query!(
    request_audits,
    request_payloads,
    request_images,
    rendered_request_images
);

#[derive(Clone, Debug)]
pub struct Database {
    url: String,
}

#[derive(Debug, Insertable)]
#[diesel(table_name = request_audits)]
pub struct NewRequestAudit<'a> {
    pub id: &'a str,
    pub request_id: &'a str,
    pub session_id: &'a str,
    pub generation: i64,
    pub scene: &'a str,
    pub group_count: i32,
    pub region_count: i32,
    pub input_chars: i32,
    pub status: &'a str,
    pub model: &'a str,
    pub duration_ms: i64,
    pub created_at: &'a str,
}

#[derive(Clone, Debug, Queryable)]
#[diesel(table_name = request_audits)]
pub struct RequestAudit {
    pub id: String,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub scene: String,
    pub group_count: i32,
    pub region_count: i32,
    pub input_chars: i32,
    pub status: String,
    pub model: String,
    pub duration_ms: i64,
    pub created_at: String,
}

#[derive(Clone, Debug)]
pub struct VersionedRequestAudit {
    pub audit: RequestAudit,
    pub schema_version: Option<u32>,
}

#[derive(Clone, Debug)]
pub struct PaginatedRequestAudits {
    pub items: Vec<VersionedRequestAudit>,
    pub total: usize,
    pub page: i64,
    pub page_size: i64,
    pub total_pages: i64,
}

#[derive(Debug, Insertable)]
#[diesel(table_name = request_payloads)]
pub struct NewRequestPayload<'a> {
    pub audit_id: &'a str,
    pub request_json: &'a str,
    pub response_json: Option<&'a str>,
    pub error_message: Option<&'a str>,
    pub model_request_json: Option<&'a str>,
}

#[derive(Clone, Debug, Queryable)]
pub struct RequestPayload {
    pub audit_id: String,
    pub request_json: String,
    pub response_json: Option<String>,
    pub error_message: Option<String>,
    pub model_request_json: Option<String>,
}

#[derive(Debug, QueryableByName)]
struct TableColumnName {
    #[diesel(sql_type = Text)]
    name: String,
}

#[derive(Debug, Insertable)]
#[diesel(table_name = request_images)]
pub struct NewRequestImage<'a> {
    pub audit_id: &'a str,
    pub image_path: &'a str,
    pub mime_type: &'a str,
    pub pixel_width: i32,
    pub pixel_height: i32,
    pub byte_size: i64,
}

#[derive(Clone, Debug, Queryable)]
pub struct RequestImage {
    pub audit_id: String,
    pub image_path: String,
    pub mime_type: String,
    pub pixel_width: i32,
    pub pixel_height: i32,
    pub byte_size: i64,
}

#[derive(Debug, Insertable)]
#[diesel(table_name = rendered_request_images)]
pub struct NewRenderedRequestImage<'a> {
    pub audit_id: &'a str,
    pub image_path: &'a str,
    pub mime_type: &'a str,
    pub pixel_width: i32,
    pub pixel_height: i32,
    pub byte_size: i64,
    pub outcome: &'a str,
    pub stage: Option<&'a str>,
    pub failure_code: Option<&'a str>,
    pub failure_message: Option<&'a str>,
    pub layout_diagnostics_json: Option<&'a str>,
}

#[derive(Clone, Debug, Queryable)]
pub struct RenderedRequestImage {
    pub audit_id: String,
    pub image_path: String,
    pub mime_type: String,
    pub pixel_width: i32,
    pub pixel_height: i32,
    pub byte_size: i64,
    pub outcome: String,
    pub stage: Option<String>,
    pub failure_code: Option<String>,
    pub failure_message: Option<String>,
    pub layout_diagnostics_json: Option<String>,
}

#[derive(Clone, Debug)]
pub struct RequestRecord {
    pub audit: RequestAudit,
    pub payload: Option<RequestPayload>,
    pub image: Option<RequestImage>,
    pub rendered_image: Option<RenderedRequestImage>,
}

impl Database {
    pub fn new(url: String) -> Self {
        Self { url }
    }

    async fn connect(&self) -> Result<AsyncSqliteConnection, AppError> {
        let mut connection = AsyncSqliteConnection::establish(&self.url)
            .await
            .map_err(AppError::database)?;
        connection
            .batch_execute("PRAGMA foreign_keys = ON;")
            .await
            .map_err(AppError::database)?;
        Ok(connection)
    }

    pub async fn migrate(&self) -> Result<(), AppError> {
        let mut connection = self.connect().await?;
        connection
            .batch_execute(
                "CREATE TABLE IF NOT EXISTS request_audits (\
                    id TEXT PRIMARY KEY NOT NULL,\
                    request_id TEXT NOT NULL,\
                    session_id TEXT NOT NULL,\
                    generation BIGINT NOT NULL,\
                    scene TEXT NOT NULL,\
                    group_count INTEGER NOT NULL,\
                    region_count INTEGER NOT NULL,\
                    input_chars INTEGER NOT NULL,\
                    status TEXT NOT NULL,\
                    model TEXT NOT NULL,\
                    duration_ms BIGINT NOT NULL,\
                    created_at TEXT NOT NULL\
                );\
                CREATE INDEX IF NOT EXISTS request_audits_request_id_idx \
                    ON request_audits(request_id);\
                CREATE TABLE IF NOT EXISTS request_payloads (\
                    audit_id TEXT PRIMARY KEY NOT NULL,\
                    request_json TEXT NOT NULL,\
                    response_json TEXT,\
                    error_message TEXT,\
                    model_request_json TEXT,\
                    FOREIGN KEY(audit_id) REFERENCES request_audits(id) ON DELETE CASCADE\
                );\
                CREATE TABLE IF NOT EXISTS request_images (\
                    audit_id TEXT PRIMARY KEY NOT NULL,\
                    image_path TEXT NOT NULL,\
                    mime_type TEXT NOT NULL,\
                    pixel_width INTEGER NOT NULL,\
                    pixel_height INTEGER NOT NULL,\
                    byte_size BIGINT NOT NULL,\
                    FOREIGN KEY(audit_id) REFERENCES request_audits(id) ON DELETE CASCADE\
                );\
                CREATE TABLE IF NOT EXISTS rendered_request_images (\
                    audit_id TEXT PRIMARY KEY NOT NULL,\
                    image_path TEXT NOT NULL,\
                    mime_type TEXT NOT NULL,\
                    pixel_width INTEGER NOT NULL,\
                    pixel_height INTEGER NOT NULL,\
                    byte_size BIGINT NOT NULL,\
                    outcome TEXT NOT NULL DEFAULT 'PRESENTED',\
                    stage TEXT,\
                    failure_code TEXT,\
                    failure_message TEXT,\
                    layout_diagnostics_json TEXT,\
                    FOREIGN KEY(audit_id) REFERENCES request_audits(id) ON DELETE CASCADE\
                );",
            )
            .await
            .map_err(AppError::database)?;
        let payload_columns = sql_query("PRAGMA table_info(request_payloads)")
            .load::<TableColumnName>(&mut connection)
            .await
            .map_err(AppError::database)?;
        if !payload_columns
            .iter()
            .any(|column| column.name == "model_request_json")
        {
            connection
                .batch_execute("ALTER TABLE request_payloads ADD COLUMN model_request_json TEXT;")
                .await
                .map_err(AppError::database)?;
        }
        let rendered_columns = sql_query("PRAGMA table_info(rendered_request_images)")
            .load::<TableColumnName>(&mut connection)
            .await
            .map_err(AppError::database)?;
        for (name, definition) in [
            ("outcome", "TEXT NOT NULL DEFAULT 'PRESENTED'"),
            ("stage", "TEXT"),
            ("failure_code", "TEXT"),
            ("failure_message", "TEXT"),
            ("layout_diagnostics_json", "TEXT"),
        ] {
            if !rendered_columns.iter().any(|column| column.name == name) {
                connection
                    .batch_execute(&format!(
                        "ALTER TABLE rendered_request_images ADD COLUMN {name} {definition};"
                    ))
                    .await
                    .map_err(AppError::database)?;
            }
        }
        Ok(())
    }

    pub async fn insert_audit(&self, audit: NewRequestAudit<'_>) -> Result<(), AppError> {
        let mut connection = self.connect().await?;
        diesel::insert_into(request_audits::table)
            .values(audit)
            .execute(&mut connection)
            .await
            .map(|_| ())
            .map_err(AppError::database)
    }

    pub async fn insert_record(
        &self,
        audit: NewRequestAudit<'_>,
        payload: NewRequestPayload<'_>,
        image: Option<NewRequestImage<'_>>,
        history_limit: i64,
    ) -> Result<Vec<String>, AppError> {
        let mut connection = self.connect().await?;
        diesel::insert_into(request_audits::table)
            .values(audit)
            .execute(&mut connection)
            .await
            .map_err(AppError::database)?;
        diesel::insert_into(request_payloads::table)
            .values(payload)
            .execute(&mut connection)
            .await
            .map_err(AppError::database)?;
        if let Some(image) = image {
            diesel::insert_into(request_images::table)
                .values(image)
                .execute(&mut connection)
                .await
                .map_err(AppError::database)?;
        }

        let stale_ids = request_audits::table
            .select(request_audits::id)
            .order(request_audits::created_at.desc())
            .offset(history_limit.max(1))
            .load::<String>(&mut connection)
            .await
            .map_err(AppError::database)?;
        let mut stale_image_paths = if stale_ids.is_empty() {
            Vec::new()
        } else {
            request_images::table
                .filter(request_images::audit_id.eq_any(&stale_ids))
                .select(request_images::image_path)
                .load::<String>(&mut connection)
                .await
                .map_err(AppError::database)?
        };
        if !stale_ids.is_empty() {
            stale_image_paths.extend(
                rendered_request_images::table
                    .filter(rendered_request_images::audit_id.eq_any(&stale_ids))
                    .select(rendered_request_images::image_path)
                    .load::<String>(&mut connection)
                    .await
                    .map_err(AppError::database)?,
            );
        }
        if !stale_ids.is_empty() {
            diesel::delete(request_audits::table.filter(request_audits::id.eq_any(stale_ids)))
                .execute(&mut connection)
                .await
                .map_err(AppError::database)?;
        }
        Ok(stale_image_paths)
    }

    pub async fn list_audits(&self, limit: i64) -> Result<Vec<RequestAudit>, AppError> {
        self.list_audits_filtered(limit, None).await
    }

    pub async fn list_audits_filtered(
        &self,
        limit: i64,
        status: Option<&str>,
    ) -> Result<Vec<RequestAudit>, AppError> {
        let mut connection = self.connect().await?;
        let mut query = request_audits::table.into_boxed();
        if let Some(status) = status {
            query = query.filter(request_audits::status.eq(status));
        }
        query
            .order(request_audits::created_at.desc())
            .limit(limit.clamp(1, 5_000))
            .load(&mut connection)
            .await
            .map_err(AppError::database)
    }

    pub async fn list_audits_with_version_filtered(
        &self,
        limit: i64,
        status: Option<&str>,
        schema_version: Option<u32>,
    ) -> Result<Vec<VersionedRequestAudit>, AppError> {
        let requested_limit = limit.clamp(1, 500);
        let candidate_limit = if schema_version.is_some() {
            5_000
        } else {
            requested_limit
        };
        let audits = self.list_audits_filtered(candidate_limit, status).await?;
        if audits.is_empty() {
            return Ok(Vec::new());
        }

        let audit_ids = audits
            .iter()
            .map(|audit| audit.id.as_str())
            .collect::<Vec<_>>();
        let mut connection = self.connect().await?;
        let payloads = request_payloads::table
            .filter(request_payloads::audit_id.eq_any(audit_ids))
            .select((request_payloads::audit_id, request_payloads::request_json))
            .load::<(String, String)>(&mut connection)
            .await
            .map_err(AppError::database)?;
        let versions = payloads
            .into_iter()
            .map(|(audit_id, request_json)| {
                (audit_id, schema_version_from_request_json(&request_json))
            })
            .collect::<HashMap<_, _>>();

        Ok(audits
            .into_iter()
            .filter_map(|audit| {
                let version = versions.get(&audit.id).copied().flatten();
                (schema_version.is_none() || version == schema_version).then_some(
                    VersionedRequestAudit {
                        audit,
                        schema_version: version,
                    },
                )
            })
            .take(requested_limit as usize)
            .collect())
    }

    pub async fn paginate_audits_with_version_filtered(
        &self,
        retained_limit: i64,
        requested_page: i64,
        requested_page_size: i64,
        status: Option<&str>,
        schema_version: Option<u32>,
    ) -> Result<PaginatedRequestAudits, AppError> {
        let retained_limit = retained_limit.clamp(1, 5_000);
        let page_size = requested_page_size.clamp(1, 100);
        let audits = self.list_audits_filtered(retained_limit, status).await?;
        if audits.is_empty() {
            return Ok(PaginatedRequestAudits {
                items: Vec::new(),
                total: 0,
                page: 1,
                page_size,
                total_pages: 1,
            });
        }

        let audit_ids = audits
            .iter()
            .map(|audit| audit.id.as_str())
            .collect::<Vec<_>>();
        let mut connection = self.connect().await?;
        let payloads = request_payloads::table
            .filter(request_payloads::audit_id.eq_any(audit_ids))
            .select((request_payloads::audit_id, request_payloads::request_json))
            .load::<(String, String)>(&mut connection)
            .await
            .map_err(AppError::database)?;
        let versions = payloads
            .into_iter()
            .map(|(audit_id, request_json)| {
                (audit_id, schema_version_from_request_json(&request_json))
            })
            .collect::<HashMap<_, _>>();
        let filtered = audits
            .into_iter()
            .filter_map(|audit| {
                let version = versions.get(&audit.id).copied().flatten();
                (schema_version.is_none() || version == schema_version).then_some(
                    VersionedRequestAudit {
                        audit,
                        schema_version: version,
                    },
                )
            })
            .collect::<Vec<_>>();
        let total = filtered.len();
        let total_pages = ((total as i64 + page_size - 1) / page_size).max(1);
        let page = requested_page.max(1).min(total_pages);
        let offset = ((page - 1) * page_size) as usize;
        let items = filtered
            .into_iter()
            .skip(offset)
            .take(page_size as usize)
            .collect();
        Ok(PaginatedRequestAudits {
            items,
            total,
            page,
            page_size,
            total_pages,
        })
    }

    pub async fn request_record(&self, id: &str) -> Result<Option<RequestRecord>, AppError> {
        use diesel::OptionalExtension;

        let mut connection = self.connect().await?;
        let audit = request_audits::table
            .filter(request_audits::id.eq(id))
            .first::<RequestAudit>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        let Some(audit) = audit else {
            return Ok(None);
        };
        let payload = request_payloads::table
            .filter(request_payloads::audit_id.eq(id))
            .first::<RequestPayload>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        let image = request_images::table
            .filter(request_images::audit_id.eq(id))
            .first::<RequestImage>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        let rendered_image = rendered_request_images::table
            .filter(rendered_request_images::audit_id.eq(id))
            .first::<RenderedRequestImage>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        Ok(Some(RequestRecord {
            audit,
            payload,
            image,
            rendered_image,
        }))
    }

    pub async fn matching_request_record(
        &self,
        request_id: &str,
        session_id: &str,
        generation: i64,
    ) -> Result<Option<RequestRecord>, AppError> {
        use diesel::OptionalExtension;

        let mut connection = self.connect().await?;
        let audit_id = request_audits::table
            .filter(request_audits::request_id.eq(request_id))
            .filter(request_audits::session_id.eq(session_id))
            .filter(request_audits::generation.eq(generation))
            .order(request_audits::created_at.desc())
            .select(request_audits::id)
            .first::<String>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        drop(connection);
        match audit_id {
            Some(audit_id) => self.request_record(&audit_id).await,
            None => Ok(None),
        }
    }

    pub async fn replace_rendered_image(
        &self,
        image: NewRenderedRequestImage<'_>,
    ) -> Result<Option<String>, AppError> {
        use diesel::OptionalExtension;

        let mut connection = self.connect().await?;
        let old_path = rendered_request_images::table
            .filter(rendered_request_images::audit_id.eq(image.audit_id))
            .select(rendered_request_images::image_path)
            .first::<String>(&mut connection)
            .await
            .optional()
            .map_err(AppError::database)?;
        diesel::delete(
            rendered_request_images::table
                .filter(rendered_request_images::audit_id.eq(image.audit_id)),
        )
        .execute(&mut connection)
        .await
        .map_err(AppError::database)?;
        diesel::insert_into(rendered_request_images::table)
            .values(image)
            .execute(&mut connection)
            .await
            .map_err(AppError::database)?;
        Ok(old_path)
    }

    pub async fn audit_count(&self) -> Result<i64, AppError> {
        use diesel::QueryDsl;
        use diesel_async::RunQueryDsl;

        let mut connection = self.connect().await?;
        request_audits::table
            .count()
            .get_result(&mut connection)
            .await
            .map_err(AppError::database)
    }
}

pub fn schema_version_from_request_json(request_json: &str) -> Option<u32> {
    serde_json::from_str::<serde_json::Value>(request_json)
        .ok()?
        .get("schemaVersion")?
        .as_u64()
        .and_then(|value| u32::try_from(value).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use diesel_async::SimpleAsyncConnection;
    use tempfile::TempDir;

    #[test]
    fn reads_schema_version_from_saved_request_json() {
        assert_eq!(
            schema_version_from_request_json(r#"{"schemaVersion":3,"requestId":"request"}"#),
            Some(3)
        );
        assert_eq!(schema_version_from_request_json("{}"), None);
        assert_eq!(schema_version_from_request_json("not-json"), None);
    }

    #[tokio::test]
    async fn migration_adds_model_request_column_to_existing_payload_table() {
        let temporary = TempDir::new().unwrap();
        let database_url = temporary
            .path()
            .join("existing.sqlite3")
            .to_string_lossy()
            .into_owned();
        let database = Database::new(database_url);
        let mut connection = database.connect().await.unwrap();
        connection
            .batch_execute(
                "CREATE TABLE request_payloads (\
                    audit_id TEXT PRIMARY KEY NOT NULL,\
                    request_json TEXT NOT NULL,\
                    response_json TEXT,\
                    error_message TEXT\
                );",
            )
            .await
            .unwrap();
        drop(connection);

        database.migrate().await.unwrap();
        database.migrate().await.unwrap();

        let mut connection = database.connect().await.unwrap();
        let columns = sql_query("PRAGMA table_info(request_payloads)")
            .load::<TableColumnName>(&mut connection)
            .await
            .unwrap();
        assert_eq!(
            columns
                .iter()
                .filter(|column| column.name == "model_request_json")
                .count(),
            1
        );
    }

    #[tokio::test]
    async fn paginates_after_status_and_schema_version_filters() {
        let temporary = TempDir::new().unwrap();
        let database_url = temporary
            .path()
            .join("pagination.sqlite3")
            .to_string_lossy()
            .into_owned();
        let database = Database::new(database_url);
        database.migrate().await.unwrap();

        for index in 0..45 {
            let id = format!("audit-{index:02}");
            let request_id = format!("request-{index:02}");
            let created_at = format!("2026-08-07T00:{index:02}:00Z");
            let request_json = format!(
                r#"{{"schemaVersion":{},"requestId":"{request_id}"}}"#,
                if index % 2 == 0 { 3 } else { 2 }
            );
            database
                .insert_record(
                    NewRequestAudit {
                        id: &id,
                        request_id: &request_id,
                        session_id: "session",
                        generation: index,
                        scene: "LIVE_SCREEN",
                        group_count: 1,
                        region_count: 1,
                        input_chars: 10,
                        status: if index % 7 == 0 {
                            "FAILED"
                        } else {
                            "SUCCEEDED"
                        },
                        model: "fake",
                        duration_ms: 1,
                        created_at: &created_at,
                    },
                    NewRequestPayload {
                        audit_id: &id,
                        request_json: &request_json,
                        response_json: None,
                        error_message: None,
                        model_request_json: None,
                    },
                    None,
                    100,
                )
                .await
                .unwrap();
        }

        let first = database
            .paginate_audits_with_version_filtered(100, 1, 20, None, Some(3))
            .await
            .unwrap();
        assert_eq!(first.total, 23);
        assert_eq!(first.items.len(), 20);
        assert_eq!(first.page, 1);
        assert_eq!(first.total_pages, 2);
        assert!(
            first
                .items
                .iter()
                .all(|entry| entry.schema_version == Some(3))
        );

        let last = database
            .paginate_audits_with_version_filtered(100, 99, 20, None, Some(3))
            .await
            .unwrap();
        assert_eq!(last.page, 2);
        assert_eq!(last.items.len(), 3);

        let failed = database
            .paginate_audits_with_version_filtered(100, 1, 20, Some("FAILED"), Some(3))
            .await
            .unwrap();
        assert_eq!(failed.total, 4);
        assert!(
            failed
                .items
                .iter()
                .all(|entry| entry.audit.status == "FAILED")
        );
    }
}
