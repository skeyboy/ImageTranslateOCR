pub mod config;
pub mod contract;
pub mod db;
pub mod error;
pub mod model;
pub mod ocr;
pub mod routes;

use std::sync::Arc;

use anyhow::Result;
use config::Config;
use model::HyMt2Client;
use ocr::PaddleOcrClient;
use routes::AppState;

pub async fn run(config: Config) -> Result<()> {
    let db = db::connect(&config.database_url).await?;
    db::migrate(&db).await?;
    let model = Arc::new(HyMt2Client::new(&config)?);
    let ocr = Arc::new(PaddleOcrClient::new(&config)?);
    let state = AppState {
        config: config.clone(),
        db: db.clone(),
        model,
        ocr,
    };
    spawn_cleanup(db, config.idempotency_retention_seconds);
    let app = routes::router(state);
    let listener = tokio::net::TcpListener::bind(config.bind_addr).await?;
    tracing::info!(address = %config.bind_addr, model = %config.model_id, "translation service started");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

fn spawn_cleanup(pool: db::DbPool, retention_seconds: i64) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(60));
        loop {
            interval.tick().await;
            match db::cleanup(&pool, retention_seconds).await {
                Ok(count) if count > 0 => {
                    tracing::info!(count, "expired idempotency records removed")
                }
                Ok(_) => {}
                Err(error) => tracing::warn!(error = %error, "idempotency cleanup failed"),
            }
        }
    });
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
