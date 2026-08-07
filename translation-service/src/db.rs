use anyhow::{Context, Result};
use diesel::{
    OptionalExtension, sql_query,
    sql_types::{BigInt, Integer, Jsonb, SmallInt, Text},
};
use diesel_async::{
    AsyncPgConnection, RunQueryDsl,
    pooled_connection::{AsyncDieselConnectionManager, bb8::Pool},
};
use serde_json::Value;

pub type DbPool = Pool<AsyncPgConnection>;

const MIGRATION: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/migrations/00000000000000_create_translation_batches/up.sql"
));

#[derive(Debug, diesel::QueryableByName)]
pub struct StoredBatch {
    #[diesel(sql_type = Text)]
    pub request_hash: String,
    #[diesel(sql_type = Text)]
    pub status: String,
    #[diesel(sql_type = diesel::sql_types::Nullable<Jsonb>)]
    pub response_json: Option<Value>,
}

#[derive(Debug, diesel::QueryableByName)]
struct InsertedId {
    #[diesel(sql_type = Text)]
    request_id: String,
}

#[derive(Debug, diesel::QueryableByName)]
struct HealthRow {
    #[diesel(sql_type = Integer)]
    value: i32,
}

pub async fn connect(database_url: &str) -> Result<DbPool> {
    let manager = AsyncDieselConnectionManager::<AsyncPgConnection>::new(database_url);
    Pool::builder()
        .max_size(8)
        .build(manager)
        .await
        .context("failed to create PostgreSQL pool")
}

pub async fn migrate(pool: &DbPool) -> Result<()> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    for statement in MIGRATION
        .split(';')
        .map(str::trim)
        .filter(|statement| !statement.is_empty())
    {
        sql_query(statement)
            .execute(&mut connection)
            .await
            .context("failed to apply translation service migration")?;
    }
    Ok(())
}

pub async fn health(pool: &DbPool) -> Result<()> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    let row = sql_query("SELECT 1 AS value")
        .get_result::<HealthRow>(&mut connection)
        .await
        .context("database health query failed")?;
    anyhow::ensure!(row.value == 1, "database returned an invalid health value");
    Ok(())
}

pub async fn reserve(
    pool: &DbPool,
    request_id: &str,
    api_version: i16,
    request_hash: &str,
    model_id: &str,
    region_count: i32,
) -> Result<bool> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    let inserted = sql_query(
        "INSERT INTO translation_batch_idempotency \
         (request_id, api_version, request_hash, status, model_id, region_count) \
         VALUES ($1, $2, $3, 'IN_PROGRESS', $4, $5) \
         ON CONFLICT (request_id) DO NOTHING RETURNING request_id",
    )
    .bind::<Text, _>(request_id)
    .bind::<SmallInt, _>(api_version)
    .bind::<Text, _>(request_hash)
    .bind::<Text, _>(model_id)
    .bind::<Integer, _>(region_count)
    .get_result::<InsertedId>(&mut connection)
    .await
    .optional()
    .context("failed to reserve idempotency key")?;
    Ok(inserted.map(|row| row.request_id).is_some())
}

pub async fn load(pool: &DbPool, request_id: &str) -> Result<Option<StoredBatch>> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    sql_query(
        "SELECT request_hash, status, response_json \
         FROM translation_batch_idempotency WHERE request_id = $1",
    )
    .bind::<Text, _>(request_id)
    .get_result::<StoredBatch>(&mut connection)
    .await
    .optional()
    .context("failed to load idempotency record")
}

pub async fn complete(pool: &DbPool, request_id: &str, response: &Value) -> Result<()> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    sql_query(
        "UPDATE translation_batch_idempotency \
         SET status = 'COMPLETED', response_json = $2, completed_at = NOW() \
         WHERE request_id = $1",
    )
    .bind::<Text, _>(request_id)
    .bind::<Jsonb, _>(response)
    .execute(&mut connection)
    .await
    .context("failed to persist idempotent response")?;
    Ok(())
}

pub async fn cleanup(pool: &DbPool, retention_seconds: i64) -> Result<usize> {
    let mut connection = pool
        .get()
        .await
        .context("failed to acquire database connection")?;
    sql_query(
        "DELETE FROM translation_batch_idempotency \
         WHERE (completed_at IS NOT NULL \
             AND completed_at < NOW() - ($1 * INTERVAL '1 second')) \
         OR (status = 'IN_PROGRESS' \
             AND created_at < NOW() - ($1 * INTERVAL '1 second'))",
    )
    .bind::<BigInt, _>(retention_seconds)
    .execute(&mut connection)
    .await
    .context("failed to clean expired idempotency records")
}
