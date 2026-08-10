use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
pub use image_translate_v4_service::V4ServiceError as AppError;
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorEnvelope {
    request_id: String,
    error: ErrorBody,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorBody {
    code: &'static str,
    message: String,
    retryable: bool,
}

pub trait AppErrorRequestExt {
    fn with_request_id(self, request_id: impl Into<String>) -> RequestError;
}

impl AppErrorRequestExt for AppError {
    fn with_request_id(self, request_id: impl Into<String>) -> RequestError {
        RequestError {
            request_id: request_id.into(),
            source: self,
        }
    }
}

fn response_parts(error: &AppError) -> (StatusCode, &'static str, bool) {
    match error {
        AppError::InvalidRequest(_) => (StatusCode::BAD_REQUEST, "INVALID_REQUEST", false),
        AppError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, "UNAUTHORIZED", false),
        AppError::ModelUnavailable(_) => (
            StatusCode::SERVICE_UNAVAILABLE,
            "MODEL_NOT_CONFIGURED",
            true,
        ),
        AppError::Upstream(_) => (StatusCode::BAD_GATEWAY, "UPSTREAM_MODEL_FAILED", true),
        AppError::Cancelled(_) => (StatusCode::CONFLICT, "REQUEST_CANCELLED", true),
        AppError::Database(_) => (StatusCode::INTERNAL_SERVER_ERROR, "DATABASE_ERROR", true),
        AppError::Configuration(_) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "CONFIGURATION_ERROR",
            false,
        ),
    }
}

#[derive(Debug)]
pub struct RequestError {
    pub request_id: String,
    pub source: AppError,
}

impl IntoResponse for RequestError {
    fn into_response(self) -> Response {
        let (status, code, retryable) = response_parts(&self.source);
        (
            status,
            Json(ErrorEnvelope {
                request_id: self.request_id,
                error: ErrorBody {
                    code,
                    message: self.source.to_string(),
                    retryable,
                },
            }),
        )
            .into_response()
    }
}
