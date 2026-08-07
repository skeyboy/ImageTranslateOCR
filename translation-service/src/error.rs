use axum::{Json, http::StatusCode, response::IntoResponse};

use crate::contract::{ErrorEnvelope, RegionError};

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("{message}")]
    Client {
        status: StatusCode,
        schema_version: u16,
        request_id: Option<String>,
        code: &'static str,
        message: String,
        retryable: bool,
    },
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl ApiError {
    pub fn bad_request(
        schema_version: u16,
        request_id: Option<String>,
        message: impl Into<String>,
    ) -> Self {
        Self::Client {
            status: StatusCode::BAD_REQUEST,
            schema_version,
            request_id,
            code: "INVALID_REQUEST",
            message: message.into(),
            retryable: false,
        }
    }

    pub fn conflict(
        schema_version: u16,
        request_id: Option<String>,
        code: &'static str,
        message: impl Into<String>,
        retryable: bool,
    ) -> Self {
        Self::Client {
            status: StatusCode::CONFLICT,
            schema_version,
            request_id,
            code,
            message: message.into(),
            retryable,
        }
    }

    pub fn unauthorized(schema_version: u16, request_id: Option<String>) -> Self {
        Self::Client {
            status: StatusCode::UNAUTHORIZED,
            schema_version,
            request_id,
            code: "UNAUTHORIZED",
            message: "A valid bearer token is required".to_owned(),
            retryable: false,
        }
    }

    pub fn service_unavailable(
        schema_version: u16,
        request_id: Option<String>,
        code: &'static str,
        message: impl Into<String>,
    ) -> Self {
        Self::Client {
            status: StatusCode::SERVICE_UNAVAILABLE,
            schema_version,
            request_id,
            code,
            message: message.into(),
            retryable: true,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> axum::response::Response {
        match self {
            Self::Client {
                status,
                schema_version,
                request_id,
                code,
                message,
                retryable,
            } => (
                status,
                Json(ErrorEnvelope {
                    schema_version,
                    request_id,
                    error: RegionError {
                        code: code.to_owned(),
                        message,
                        retryable,
                    },
                }),
            )
                .into_response(),
            Self::Internal(error) => {
                tracing::error!(error = %error, "request failed");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorEnvelope {
                        schema_version: 2,
                        request_id: None,
                        error: RegionError {
                            code: "INTERNAL_ERROR".to_owned(),
                            message: "The translation service failed".to_owned(),
                            retryable: true,
                        },
                    }),
                )
                    .into_response()
            }
        }
    }
}
