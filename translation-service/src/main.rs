use anyhow::Result;
use image_translate_service::{config::Config, run};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("image_translate_service=info,tower_http=info")),
        )
        .init();
    run(Config::from_env()?).await
}
