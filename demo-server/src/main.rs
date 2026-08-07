use std::sync::Arc;

use image_translate_demo_server::{app, config::Config, database::Database, qwen::QwenClient};
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = Config::from_env()?;
    let database = Database::new(config.database_url.clone());
    database.migrate().await?;
    let model = Arc::new(QwenClient::new(&config)?);
    let listener = TcpListener::bind(&config.server_addr).await?;
    info!(address = %config.server_addr, model = %config.qwen_model, "translation server started");
    axum::serve(listener, app(config, database, model)).await?;
    Ok(())
}
