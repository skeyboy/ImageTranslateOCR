pub mod admin;
pub mod config;
pub mod contract;
pub mod database;
pub mod error;
pub mod planning;
pub mod planning_v4;
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
        request_history, request_image,
    },
    config::Config,
    database::Database,
    qwen::TranslationModel,
    routes::{
        AppState, RequestCancellationRegistry, cancel_translation, health, translate_groups,
        translate_layout_plan, translate_regions_first_layout_plan, upload_rendered_capture,
    },
};

pub fn app(config: Config, database: Database, model: Arc<dyn TranslationModel>) -> Router {
    let state = AppState {
        config: Arc::new(config),
        database,
        model,
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
