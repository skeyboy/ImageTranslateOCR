use std::sync::Arc;

use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::jstring,
};
use serde::Serialize;

use crate::{
    V4TranslationService, config::QwenConfig, contract::SemanticTranslationRequest,
    qwen::QwenClient,
};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct JniResult<T: Serialize> {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    response: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_v4translation_EmbeddedV4TranslationService_nativeTranslate(
    mut env: JNIEnv,
    _object: JObject,
    config_json: JString,
    request_json: JString,
) -> jstring {
    let result = run_translation(&mut env, config_json, request_json);
    let envelope = match result {
        Ok(response) => JniResult {
            ok: true,
            response: Some(response),
            error: None,
        },
        Err(error) => JniResult::<serde_json::Value> {
            ok: false,
            response: None,
            error: Some(error),
        },
    };
    let json = serde_json::to_string(&envelope).unwrap_or_else(|error| {
        format!(r#"{{"ok":false,"error":"JNI serialization failed: {error}"}}"#)
    });
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn run_translation(
    env: &mut JNIEnv,
    config_json: JString,
    request_json: JString,
) -> Result<serde_json::Value, String> {
    let config_json: String = env
        .get_string(&config_json)
        .map_err(|error| error.to_string())?
        .into();
    let request_json: String = env
        .get_string(&request_json)
        .map_err(|error| error.to_string())?
        .into();
    let config: QwenConfig =
        serde_json::from_str(&config_json).map_err(|error| format!("invalid config: {error}"))?;
    config.validate().map_err(|error| error.to_string())?;
    let request: SemanticTranslationRequest = serde_json::from_str(&request_json)
        .map_err(|error| format!("invalid v4 request: {error}"))?;
    let model_version = config.qwen_model.clone();
    let model = Arc::new(QwenClient::new(&config).map_err(|error| error.to_string())?);
    let service = V4TranslationService::new(model, model_version);
    let runtime = tokio::runtime::Runtime::new().map_err(|error| error.to_string())?;
    let response = runtime
        .block_on(service.translate(request))
        .map_err(|error| error.to_string())?;
    serde_json::to_value(response).map_err(|error| error.to_string())
}
