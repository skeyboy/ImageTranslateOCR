use thiserror::Error;

#[derive(Debug, Error)]
pub enum V4ServiceError {
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

impl V4ServiceError {
    pub fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidRequest(message.into())
    }

    pub fn configuration(message: impl Into<String>) -> Self {
        Self::Configuration(message.into())
    }

    pub fn database(error: impl std::fmt::Display) -> Self {
        Self::Database(error.to_string())
    }
}
