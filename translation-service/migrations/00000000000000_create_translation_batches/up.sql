CREATE TABLE IF NOT EXISTS translation_batch_idempotency (
    request_id TEXT PRIMARY KEY,
    api_version SMALLINT NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    response_json JSONB,
    model_id TEXT NOT NULL,
    region_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS translation_batch_idempotency_completed_at_idx
    ON translation_batch_idempotency (completed_at);

