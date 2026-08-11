use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("{0}")]
    InvalidRequest(String),
    #[error("{0}")]
    Unauthorized(String),
    #[error("{0}")]
    ModelUnavailable(String),
    #[error("{0}")]
    Upstream(String),
    #[error("{0}")]
    Cancelled(String),
    #[error("{0}")]
    Database(String),
    #[error("{0}")]
    Configuration(String),
}

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

impl AppError {
    pub fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidRequest(message.into())
    }

    pub fn configuration(message: impl Into<String>) -> Self {
        Self::Configuration(message.into())
    }

    pub fn database(error: impl std::fmt::Display) -> Self {
        Self::Database(error.to_string())
    }

    pub fn with_request_id(self, request_id: impl Into<String>) -> RequestError {
        RequestError {
            request_id: request_id.into(),
            source: self,
        }
    }

    fn response_parts(&self) -> (StatusCode, &'static str, bool) {
        match self {
            Self::InvalidRequest(_) => (StatusCode::BAD_REQUEST, "INVALID_REQUEST", false),
            Self::Unauthorized(_) => (StatusCode::UNAUTHORIZED, "UNAUTHORIZED", false),
            Self::ModelUnavailable(_) => (
                StatusCode::SERVICE_UNAVAILABLE,
                "MODEL_NOT_CONFIGURED",
                true,
            ),
            Self::Upstream(_) => (StatusCode::BAD_GATEWAY, "UPSTREAM_MODEL_FAILED", true),
            Self::Cancelled(_) => (StatusCode::CONFLICT, "REQUEST_CANCELLED", true),
            Self::Database(_) => (StatusCode::INTERNAL_SERVER_ERROR, "DATABASE_ERROR", true),
            Self::Configuration(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "CONFIGURATION_ERROR",
                false,
            ),
        }
    }
}

impl From<ocr_translation_core::CoreError> for AppError {
    fn from(error: ocr_translation_core::CoreError) -> Self {
        match error {
            ocr_translation_core::CoreError::InvalidRequest(message) => {
                Self::InvalidRequest(message)
            }
            ocr_translation_core::CoreError::InvalidModelResponse(message) => {
                Self::Upstream(message)
            }
            ocr_translation_core::CoreError::Serialization(message) => Self::Upstream(message),
        }
    }
}

#[derive(Debug)]
pub struct RequestError {
    pub request_id: String,
    pub source: AppError,
}

impl IntoResponse for RequestError {
    fn into_response(self) -> Response {
        let (status, code, retryable) = self.source.response_parts();
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
