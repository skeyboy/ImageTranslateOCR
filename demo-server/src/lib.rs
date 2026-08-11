pub mod admin;
pub mod config;
pub use ocr_translation_core::{contract, planning, planning_v4};
pub mod database;
pub mod error;
pub mod qwen;
pub mod routes;

use std::sync::Arc;

use axum::{
    Router,
    extract::DefaultBodyLimit,
    routing::{get, post},
};
use tower_http::trace::TraceLayer;

use crate::{
    admin::{
        admin_root, admin_script, admin_styles, rendered_request_image, request_detail,
        request_history, request_image, select_translation_provider,
    },
    config::Config,
    database::Database,
    qwen::{TranslationModel, TranslationModelRegistry},
    routes::{
        AppState, RequestCancellationRegistry, cancel_translation, health, translate_groups,
        translate_layout_plan, translate_regions_first_layout_plan, upload_edge_audit,
        upload_rendered_capture,
    },
};

pub fn app(config: Config, database: Database, model: Arc<dyn TranslationModel>) -> Router {
    let models = Arc::new(TranslationModelRegistry::qwen(
        config.qwen_model.clone(),
        model,
    ));
    app_with_models(config, database, models)
}

pub fn app_with_models(
    config: Config,
    database: Database,
    models: Arc<TranslationModelRegistry>,
) -> Router {
    let state = AppState {
        config: Arc::new(config),
        database,
        models,
        cancellations: RequestCancellationRegistry::default(),
    };
    Router::new()
        .route("/healthz", get(health))
        .route("/api/v2/translate/groups", post(translate_groups))
        .route("/api/v3/translate/layout-plan", post(translate_layout_plan))
        .route(
            "/api/v4/translate/layout-plan",
            post(translate_regions_first_layout_plan),
        )
        .route("/api/v4/edge-audits", post(upload_edge_audit))
        .route(
            "/api/v2/translate/requests/{request_id}/cancel",
            post(cancel_translation),
        )
        .route(
            "/api/v3/translate/requests/{request_id}/rendered-capture",
            post(upload_rendered_capture),
        )
        .route(
            "/api/v4/translate/requests/{request_id}/rendered-capture",
            post(upload_rendered_capture),
        )
        .route("/admin", get(admin_root))
        .route("/admin/requests", get(request_history))
        .route("/admin/requests/{id}", get(request_detail))
        .route(
            "/admin/translation-provider",
            post(select_translation_provider),
        )
        .route("/admin/requests/{id}/image", get(request_image))
        .route(
            "/admin/requests/{id}/rendered-image",
            get(rendered_request_image),
        )
        .route("/admin/assets/admin.css", get(admin_styles))
        .route("/admin/assets/admin.js", get(admin_script))
        .with_state(state)
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024))
        .layer(TraceLayer::new_for_http())
}
