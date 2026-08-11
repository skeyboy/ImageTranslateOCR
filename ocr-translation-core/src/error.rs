use thiserror::Error;

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("{0}")]
    InvalidRequest(String),
    #[error("{0}")]
    InvalidModelResponse(String),
    #[error("{0}")]
    Serialization(String),
}

impl CoreError {
    pub fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidRequest(message.into())
    }

    pub fn model(message: impl Into<String>) -> Self {
        Self::InvalidModelResponse(message.into())
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(error: serde_json::Error) -> Self {
        Self::Serialization(error.to_string())
    }
}
