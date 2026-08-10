use std::sync::Arc;

use image_translate_demo_server::{
    app_with_models,
    config::{Config, TranslationProvider},
    database::Database,
    qwen::{QwenClient, TranslationModel, TranslationModelRegistry},
};
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
    let mut configured_models = Vec::new();
    for model in &config.qwen_models {
        configured_models.push((
            TranslationProvider::Qwen,
            model.clone(),
            Arc::new(QwenClient::new_qwen_model(&config, model)?) as Arc<dyn TranslationModel>,
        ));
    }
    for model in &config.openlux_models {
        configured_models.push((
            TranslationProvider::Openlux,
            model.clone(),
            Arc::new(QwenClient::new_openlux_model(&config, model)?) as Arc<dyn TranslationModel>,
        ));
    }
    let active_model = match config.translation_provider {
        TranslationProvider::Qwen => config.qwen_model.clone(),
        TranslationProvider::Openlux => config
            .openlux_model
            .clone()
            .expect("validated OpenLux default model"),
    };
    let models = Arc::new(TranslationModelRegistry::new(
        config.translation_provider,
        active_model,
        configured_models,
    ));
    let listener = TcpListener::bind(&config.server_addr).await?;
    let active = models.active();
    info!(
        address = %config.server_addr,
        provider = active.provider.as_str(),
        model = %active.model_name,
        "translation server started"
    );
    axum::serve(listener, app_with_models(config, database, models)).await?;
    Ok(())
}
