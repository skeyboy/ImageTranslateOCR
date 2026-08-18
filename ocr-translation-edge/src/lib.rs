use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JObject, JString},
    sys::jlong,
};
use ocr_translation_core::{complete_translation, prepare_translation};
use openai_oxide::{ClientConfig, OpenAI};
use serde::Serialize;
use serde_json::{Value, json};
use std::time::Instant;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EdgeResult<T: Serialize> {
    ok: bool,
    value: Option<T>,
    error: Option<String>,
}

pub fn prepare(request: &str, provider: &str, model: &str) -> String {
    encode(prepare_translation(request, provider, model))
}

pub fn complete(prepared: &str, completion: &str, total_ms: u64) -> String {
    encode(complete_translation(prepared, completion, total_ms).map(|value| value.response))
}

pub fn translate_openai(request: &str, base_url: &str, api_key: &str, model: &str) -> String {
    encode(translate_openai_inner(request, base_url, api_key, model))
}

fn translate_openai_inner(
    request: &str,
    base_url: &str,
    api_key: &str,
    model: &str,
) -> Result<Value, String> {
    if api_key.trim().is_empty() {
        return Err("OpenAI API key is not configured".to_owned());
    }
    let normalized_base_url = base_url.trim().trim_end_matches('/');
    if !normalized_base_url.starts_with("https://") {
        return Err("OpenAI base URL must use HTTPS".to_owned());
    }

    let prepared =
        prepare_translation(request, "openai-oxide", model).map_err(|error| error.to_string())?;
    let prompt = &prepared.model_prompt;
    let body = json!({
        "model": model,
        "messages": [
            {"role": "system", "content": prompt.system},
            {"role": "user", "content": prompt.user}
        ],
        "max_tokens": prompt.recommended_max_tokens,
        "response_format": prompt.response_format,
        "temperature": 0.0,
        "seed": 0
    });
    let client = OpenAI::with_config(
        ClientConfig::new(api_key.trim())
            .base_url(normalized_base_url)
            .timeout_secs(60)
            .max_retries(2),
    );
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("OpenAI runtime initialization failed: {error}"))?;
    let started = Instant::now();
    let completion = runtime
        .block_on(client.chat().completions().create_raw(&body))
        .map_err(|error| format!("OpenAI request failed: {error}"))?;
    let completed = complete_translation(
        &serde_json::to_string(&prepared).map_err(|error| error.to_string())?,
        &serde_json::to_string(&completion).map_err(|error| error.to_string())?,
        started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
    )
    .map_err(|error| error.to_string())?;
    serde_json::to_value(completed.response).map_err(|error| error.to_string())
}

fn encode<T: Serialize, E: std::fmt::Display>(result: Result<T, E>) -> String {
    let envelope = match result {
        Ok(value) => EdgeResult {
            ok: true,
            value: Some(value),
            error: None,
        },
        Err(error) => EdgeResult::<T> {
            ok: false,
            value: None,
            error: Some(error.to_string()),
        },
    };
    serde_json::to_string(&envelope).unwrap_or_else(|error| {
        format!("{{\"ok\":false,\"error\":\"serialization failed: {error}\"}}")
    })
}

fn return_string<'local>(
    env: &mut Env<'local>,
    value: String,
) -> jni::errors::Result<JString<'local>> {
    JString::from_str(env, value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativeInitialize(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    context: JObject,
) -> bool {
    unowned_env
        .with_env(|env| -> jni::errors::Result<bool> {
            #[cfg(target_os = "android")]
            rustls_platform_verifier::android::init_with_env(env, context)?;
            #[cfg(not(target_os = "android"))]
            let _ = (env, context);
            Ok(true)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativePrepare<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
    provider: JString<'local>,
    model: JString<'local>,
) -> JString<'local> {
    unowned_env
        .with_env(|env| {
            let result = prepare(
                &request.to_string(),
                &provider.to_string(),
                &model.to_string(),
            );
            return_string(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativeComplete<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    prepared: JString<'local>,
    completion: JString<'local>,
    total_ms: jlong,
) -> JString<'local> {
    unowned_env
        .with_env(|env| {
            let result = complete(
                &prepared.to_string(),
                &completion.to_string(),
                total_ms.max(0) as u64,
            );
            return_string(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_imagetranslate_translate_NativeEdgeTranslationBridge_nativeTranslateOpenAi<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
    base_url: JString<'local>,
    api_key: JString<'local>,
    model: JString<'local>,
) -> JString<'local> {
    unowned_env
        .with_env(|env| {
            let result = translate_openai(
                &request.to_string(),
                &base_url.to_string(),
                &api_key.to_string(),
                &model.to_string(),
            );
            return_string(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_are_returned_as_json_instead_of_crossing_ffi() {
        let result: serde_json::Value =
            serde_json::from_str(&prepare("{}", "openlux", "gpt-4.1")).unwrap();
        assert_eq!(result["ok"], false);
        assert!(result["error"].as_str().unwrap().contains("missing field"));
    }

    #[test]
    fn openai_translation_rejects_missing_key_before_network_access() {
        let request = include_str!("../../ocr-translation-core/examples/v4-minimal-request.json");
        let result: serde_json::Value = serde_json::from_str(&translate_openai(
            request,
            "https://api.openai.com/v1",
            "",
            "gpt-4.1-mini",
        ))
        .unwrap();
        assert_eq!(result["ok"], false);
        assert_eq!(result["error"], "OpenAI API key is not configured");
    }
}
