use axum::{
    Form, Json,
    body::Bytes,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::Deserialize;
use std::collections::HashMap;

use crate::{
    archive_import::import_archive,
    config::{Config, TranslationProvider},
    database::{
        PaginatedRequestAudits, RequestPayload, RequestRecord, schema_version_from_request_json,
    },
    qwen::TranslationProviderStatus,
    routes::AppState,
};

const DEFAULT_HISTORY_PAGE_SIZE: i64 = 20;
const PASTE_BACK_MISSING_STATUS: &str = "PASTE_BACK_MISSING_ONE";
const PASTE_BACK_MISSING_MESSAGE: &str = "1 translated region(s) were not pasted back";

pub async fn admin_root() -> Redirect {
    Redirect::temporary("/admin/requests")
}

pub async fn import_request_archive(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    match import_archive(&state, &body).await {
        Ok(outcome) => Json(serde_json::json!({
            "auditId": outcome.audit_id(),
            "location": format!("/admin/requests/{}", outcome.audit_id()),
            "disposition": outcome.disposition(),
        }))
        .into_response(),
        Err(error) => (StatusCode::BAD_REQUEST, error.to_string()).into_response(),
    }
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryFilter {
    status: Option<String>,
    version: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

pub async fn request_history(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(filter): Query<HistoryFilter>,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    let selected_status = filter.status.as_deref().filter(|status| {
        matches!(
            *status,
            "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED" | PASTE_BACK_MISSING_STATUS
        )
    });
    let audit_status = selected_status.filter(|status| *status != PASTE_BACK_MISSING_STATUS);
    let render_failure_message =
        (selected_status == Some(PASTE_BACK_MISSING_STATUS)).then_some(PASTE_BACK_MISSING_MESSAGE);
    let selected_version = filter
        .version
        .as_deref()
        .and_then(parse_schema_version_filter);
    let page = filter.page.unwrap_or(1).max(1);
    let page_size = match filter.page_size {
        Some(50) => 50,
        Some(100) => 100,
        _ => DEFAULT_HISTORY_PAGE_SIZE,
    };
    let provider_statuses = state.models.statuses().await;
    match state
        .database
        .paginate_audits_with_version_filtered(
            state.config.request_history_limit,
            page,
            page_size,
            audit_status,
            selected_version,
            render_failure_message,
        )
        .await
    {
        Ok(audits) => {
            let audit_ids = audits
                .items
                .iter()
                .map(|item| item.audit.id.clone())
                .collect::<Vec<_>>();
            let payloads = state
                .database
                .request_payloads_for_audits(&audit_ids)
                .await
                .unwrap_or_default();
            Html(history_page(
                audits,
                &payloads,
                state.config.request_history_limit,
                selected_status,
                selected_version,
                &provider_statuses,
            ))
            .into_response()
        }
        Err(error) => server_error(error.to_string()),
    }
}

#[derive(Deserialize)]
pub struct ProviderSelection {
    provider: String,
    model: String,
}

pub async fn select_translation_provider(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(selection): Form<ProviderSelection>,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    let Some(provider) = TranslationProvider::parse(&selection.provider) else {
        return (
            StatusCode::BAD_REQUEST,
            "provider must be qwen, openlux, or gemini-native",
        )
            .into_response();
    };
    match state.models.select(provider, &selection.model).await {
        Ok(()) => (
            StatusCode::SEE_OTHER,
            [(header::LOCATION, "/admin/requests")],
        )
            .into_response(),
        Err(error) => (StatusCode::CONFLICT, error.to_string()).into_response(),
    }
}

fn parse_schema_version_filter(version: &str) -> Option<u32> {
    match version {
        "2" => Some(2),
        "3" => Some(3),
        "4" => Some(4),
        _ => None,
    }
}

pub async fn request_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    match state.database.request_record(&id).await {
        Ok(Some(record)) => Html(detail_page(record, &state.config)).into_response(),
        Ok(None) => (StatusCode::NOT_FOUND, "request record not found").into_response(),
        Err(error) => server_error(error.to_string()),
    }
}

pub async fn request_image(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    let record = match state.database.request_record(&id).await {
        Ok(Some(record)) => record,
        Ok(None) => return (StatusCode::NOT_FOUND, "request record not found").into_response(),
        Err(error) => return server_error(error.to_string()),
    };
    let Some(image) = record.image else {
        return (StatusCode::NOT_FOUND, "request image not found").into_response();
    };
    match tokio::fs::read(&image.image_path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, image.mime_type),
                (header::CACHE_CONTROL, "private, no-store".to_owned()),
            ],
            bytes,
        )
            .into_response(),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            (StatusCode::NOT_FOUND, "request image file not found").into_response()
        }
        Err(error) => server_error(error.to_string()),
    }
}

pub async fn rendered_request_image(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !admin_authorized(&headers, state.config.bearer_token.as_deref()) {
        return unauthorized();
    }
    let record = match state.database.request_record(&id).await {
        Ok(Some(record)) => record,
        Ok(None) => return (StatusCode::NOT_FOUND, "request record not found").into_response(),
        Err(error) => return server_error(error.to_string()),
    };
    let Some(image) = record.rendered_image else {
        return (StatusCode::NOT_FOUND, "rendered request image not found").into_response();
    };
    match tokio::fs::read(&image.image_path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, image.mime_type),
                (header::CACHE_CONTROL, "private, no-store".to_owned()),
            ],
            bytes,
        )
            .into_response(),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => (
            StatusCode::NOT_FOUND,
            "rendered request image file not found",
        )
            .into_response(),
        Err(error) => server_error(error.to_string()),
    }
}

pub async fn admin_styles() -> impl IntoResponse {
    (
        [(header::CONTENT_TYPE, "text/css; charset=utf-8")],
        include_str!("../assets/admin.css"),
    )
}

pub async fn admin_script() -> impl IntoResponse {
    (
        [(header::CONTENT_TYPE, "text/javascript; charset=utf-8")],
        include_str!("../assets/admin.js"),
    )
}

fn history_page(
    pagination: PaginatedRequestAudits,
    payloads: &HashMap<String, RequestPayload>,
    history_limit: i64,
    selected_status: Option<&str>,
    selected_version: Option<u32>,
    provider_statuses: &[TranslationProviderStatus],
) -> String {
    let rows = if pagination.items.is_empty() {
        "<tr><td class=\"empty\" colspan=\"10\">暂无请求记录</td></tr>".to_owned()
    } else {
        pagination
            .items
            .iter()
            .map(|entry| {
                let audit = &entry.audit;
                let status_class = audit.status.to_ascii_lowercase();
                let (version_class, version_label) = version_badge(entry.schema_version);
                format!(
                    "<tr>\
                        <td><a class=\"request-link\" target=\"_blank\" href=\"/admin/requests/{id}\">{request_id}</a></td>\
                        <td><span class=\"api-version {version_class}\">{version_label}</span></td>\
                        <td><span class=\"status status-{status_class}\">{status}</span></td>\
                        <td>{scene}</td>\
                        <td class=\"numeric\">{groups}</td>\
                        <td class=\"numeric\">{regions}</td>\
                        <td class=\"numeric\">{chars}</td>\
                        <td>{model}</td>\
                        <td class=\"numeric\">{duration} ms</td>\
                        <td>{created}</td>\
                    </tr>",
                    id = escape_html(&audit.id),
                    request_id = escape_html(&audit.request_id),
                    version_class = version_class,
                    version_label = version_label,
                    status = escape_html(&audit.status),
                    scene = escape_html(&audit.scene),
                    groups = audit.group_count,
                    regions = audit.region_count,
                    chars = audit.input_chars,
                    model = escape_html(&audit.model),
                    duration = audit.duration_ms,
                    created = timestamp_html(&audit.created_at),
                )
            })
            .collect::<Vec<_>>()
            .join("")
    };
    let range_start = if pagination.total == 0 {
        0
    } else {
        ((pagination.page - 1) * pagination.page_size + 1) as usize
    };
    let range_end = (pagination.page * pagination.page_size).min(pagination.total as i64) as usize;
    let pagination_controls = pagination_controls(&pagination, selected_status, selected_version);
    let provider_controls = provider_controls(provider_statuses);
    let reasoning_statistics = reasoning_statistics_html(&pagination, payloads);
    page_shell(
        "请求历史",
        &format!(
            "<header class=\"topbar\">\
                <div><span class=\"product\">OCR Translation Trace</span><h1>请求历史</h1></div>\
                <div class=\"topbar-actions\">{provider_controls}<div class=\"archive-import\"><input id=\"archive-import-file\" type=\"file\" accept=\".zip,application/zip\" multiple hidden><button id=\"archive-import-button\" type=\"button\">批量导入 ZIP</button><span id=\"archive-import-status\" role=\"status\" aria-live=\"polite\"></span></div><a class=\"health-link\" href=\"/healthz\">服务状态</a></div>\
            </header>\
            <main>\
                <section class=\"summary-band\">\
                    <span>匹配记录</span><strong>{total}</strong>\
                    <span>当前范围</span><strong>{range_start}-{range_end}</strong>\
                    <span>页码</span><strong>{page}/{total_pages}</strong>\
                    <span>保留上限</span><strong>{history_limit}</strong>\
                </section>\
                {reasoning_statistics}\
                <form class=\"history-filters\" method=\"get\" action=\"/admin/requests\">\
                    <label for=\"status-filter\">状态</label>\
                    <select id=\"status-filter\" name=\"status\">{status_options}</select>\
                    <label for=\"version-filter\">版本</label>\
                    <select id=\"version-filter\" name=\"version\">{version_options}</select>\
                    <label for=\"page-size-filter\">每页</label>\
                    <select id=\"page-size-filter\" name=\"pageSize\">{page_size_options}</select>\
                    <button type=\"submit\">筛选</button>\
                    <a href=\"/admin/requests\">重置</a>\
                </form>\
                <section class=\"table-section\">\
                    <div class=\"table-scroll\"><table>\
                        <thead><tr><th>Request ID</th><th>版本</th><th>状态</th><th>场景</th><th>组</th><th>区域</th><th>字符</th><th>模型</th><th>耗时</th><th>时间</th></tr></thead>\
                        <tbody>{rows}</tbody>\
                    </table></div>{pagination_controls}\
                </section>\
            </main>",
            total = pagination.total,
            page = pagination.page,
            total_pages = pagination.total_pages,
            status_options = status_options(selected_status),
            version_options = version_options(selected_version),
            page_size_options = page_size_options(pagination.page_size),
            provider_controls = provider_controls,
            reasoning_statistics = reasoning_statistics,
        ),
        "history-page",
    )
}

#[derive(Default)]
struct ReasoningStatistics {
    requests: usize,
    succeeded: usize,
    duration_ms: Vec<i64>,
    provider_ms: Vec<i64>,
    reasoning_tokens: Vec<i64>,
}

fn reasoning_statistics_html(
    pagination: &PaginatedRequestAudits,
    payloads: &HashMap<String, RequestPayload>,
) -> String {
    let mut by_level = HashMap::<String, ReasoningStatistics>::new();
    for item in &pagination.items {
        let Some(payload) = payloads.get(&item.audit.id) else {
            continue;
        };
        let Some(raw) = payload.model_request_json.as_deref() else {
            continue;
        };
        let Ok(value) = serde_json::from_str::<serde_json::Value>(raw) else {
            continue;
        };
        let request = value.get("request").unwrap_or(&value);
        let timings = value.get("_timings").or_else(|| value.get("timings"));
        let level = request
            .get("reasoning_effort")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("default");
        if !matches!(level, "default" | "none" | "low" | "medium") {
            continue;
        }
        let entry = by_level.entry(level.to_owned()).or_default();
        entry.requests += 1;
        entry.succeeded += usize::from(item.audit.status == "SUCCEEDED");
        entry.duration_ms.push(item.audit.duration_ms);
        if let Some(provider_ms) = timings
            .and_then(|item| item.get("providerTotalMs"))
            .and_then(serde_json::Value::as_i64)
        {
            entry.provider_ms.push(provider_ms);
        }
        let reasoning_tokens = timings
            .and_then(|item| item.get("reasoningTokens"))
            .and_then(serde_json::Value::as_i64)
            .or_else(|| {
                value
                    .pointer("/response/usage/completion_tokens_details/reasoning_tokens")
                    .and_then(serde_json::Value::as_i64)
            });
        if let Some(reasoning_tokens) = reasoning_tokens {
            entry.reasoning_tokens.push(reasoning_tokens);
        }
    }
    let median = |values: &Vec<i64>| -> String {
        if values.is_empty() {
            return "-".to_owned();
        }
        let mut values = values.clone();
        values.sort_unstable();
        values[values.len() / 2].to_string()
    };
    let rows = ["default", "none", "low", "medium"].into_iter().map(|level| {
        let stats = by_level.get(level);
        format!(
            "<tr><th>{level}</th><td>{requests}</td><td>{succeeded}/{requests}</td><td>{duration} ms</td><td>{provider} ms</td><td>{reasoning}</td></tr>",
            requests = stats.map_or(0, |item| item.requests),
            succeeded = stats.map_or(0, |item| item.succeeded),
            duration = stats.map_or("-".to_owned(), |item| median(&item.duration_ms)),
            provider = stats.map_or("-".to_owned(), |item| median(&item.provider_ms)),
            reasoning = stats.map_or("-".to_owned(), |item| median(&item.reasoning_tokens)),
        )
    }).collect::<String>();
    format!(
        "<section class=\"reasoning-statistics\"><div><h2>推理档位统计</h2><span>当前页真实端侧审计，中位数</span></div><div class=\"reasoning-statistics-scroll\"><table><thead><tr><th>档位</th><th>请求</th><th>成功</th><th>端侧总耗时</th><th>Provider 耗时</th><th>推理 tokens</th></tr></thead><tbody>{rows}</tbody></table></div></section>"
    )
}

fn provider_controls(statuses: &[TranslationProviderStatus]) -> String {
    let options = statuses
        .iter()
        .map(|status| {
            let active_class = status.active.then_some(" is-active").unwrap_or_default();
            let disabled = (!status.configured).then_some(" disabled").unwrap_or_default();
            let state = if !status.configured {
                "未配置"
            } else if status.reachable && status.model_available {
                "可用"
            } else if status.reachable {
                "模型不可用"
            } else {
                "不可达"
            };
            format!(
                "<form method=\"post\" action=\"/admin/translation-provider\" class=\"provider-choice\"><input type=\"hidden\" name=\"provider\" value=\"{provider}\"><button type=\"submit\" name=\"model\" value=\"{model}\" class=\"provider-option{active_class}\" title=\"{provider} · {model} · {state}\"{disabled}><strong>{provider}</strong><span>{model}</span><small>{state}</small></button></form>",
                provider = escape_html(status.provider),
                model = escape_html(&status.model),
            )
        })
        .collect::<Vec<_>>()
        .join("");
    format!(
        "<div class=\"provider-switch\"><span>翻译 Provider / Model</span><div>{options}</div></div>"
    )
}

fn page_size_options(selected: i64) -> String {
    [20, 50, 100]
        .into_iter()
        .map(|value| {
            let selected_attribute = (selected == value)
                .then_some(" selected")
                .unwrap_or_default();
            format!("<option value=\"{value}\"{selected_attribute}>{value} 条</option>")
        })
        .collect::<Vec<_>>()
        .join("")
}

fn pagination_controls(
    pagination: &PaginatedRequestAudits,
    selected_status: Option<&str>,
    selected_version: Option<u32>,
) -> String {
    let link = |page, label: &str, class_name: &str| {
        format!(
            "<a class=\"pagination-link {class_name}\" href=\"{}\">{label}</a>",
            pagination_url(
                page,
                pagination.page_size,
                selected_status,
                selected_version
            )
        )
    };
    let disabled = |label: &str, class_name: &str| {
        format!(
            "<span class=\"pagination-link {class_name} is-disabled\" aria-disabled=\"true\">{label}</span>"
        )
    };
    let first = if pagination.page > 1 {
        link(1, "首页", "pagination-first")
    } else {
        disabled("首页", "pagination-first")
    };
    let previous = if pagination.page > 1 {
        link(pagination.page - 1, "上一页", "pagination-previous")
    } else {
        disabled("上一页", "pagination-previous")
    };
    let next = if pagination.page < pagination.total_pages {
        link(pagination.page + 1, "下一页", "pagination-next")
    } else {
        disabled("下一页", "pagination-next")
    };
    let last = if pagination.page < pagination.total_pages {
        link(pagination.total_pages, "末页", "pagination-last")
    } else {
        disabled("末页", "pagination-last")
    };
    format!(
        "<nav class=\"pagination\" aria-label=\"请求历史分页\">\
            <div>{first}{previous}</div>\
            <span class=\"pagination-status\">第 {page} / {total_pages} 页 · 共 {total} 条</span>\
            <div>{next}{last}</div>\
        </nav>",
        page = pagination.page,
        total_pages = pagination.total_pages,
        total = pagination.total,
    )
}

fn pagination_url(
    page: i64,
    page_size: i64,
    selected_status: Option<&str>,
    selected_version: Option<u32>,
) -> String {
    let mut parameters = vec![
        format!("page={}", page.max(1)),
        format!("pageSize={page_size}"),
    ];
    if let Some(status) = selected_status {
        parameters.push(format!("status={status}"));
    }
    if let Some(version) = selected_version {
        parameters.push(format!("version={version}"));
    }
    format!("/admin/requests?{}", parameters.join("&amp;"))
}

fn version_options(selected: Option<u32>) -> String {
    [
        (None, "全部"),
        (Some(2), "v2"),
        (Some(3), "v3"),
        (Some(4), "v4"),
    ]
    .into_iter()
    .map(|(value, label)| {
        let selected_attribute = (selected == value)
            .then_some(" selected")
            .unwrap_or_default();
        let value = value.map(|version| version.to_string()).unwrap_or_default();
        format!("<option value=\"{value}\"{selected_attribute}>{label}</option>")
    })
    .collect::<Vec<_>>()
    .join("")
}

fn version_badge(schema_version: Option<u32>) -> (&'static str, String) {
    match schema_version {
        Some(2) => ("version-v2", "v2".to_owned()),
        Some(3) => ("version-v3", "v3".to_owned()),
        Some(4) => ("version-v4", "v4".to_owned()),
        Some(version) => ("version-other", format!("v{version}")),
        None => ("version-unknown", "未知".to_owned()),
    }
}

fn status_options(selected: Option<&str>) -> String {
    [
        ("", "全部"),
        ("SUCCEEDED", "成功"),
        ("PARTIAL", "部分成功"),
        ("FAILED", "失败"),
        ("CANCELLED", "已取消"),
        (PASTE_BACK_MISSING_STATUS, "回贴缺失（1 个区域）"),
    ]
    .into_iter()
    .map(|(value, label)| {
        let selected_attribute = (selected.unwrap_or_default() == value)
            .then_some(" selected")
            .unwrap_or_default();
        format!("<option value=\"{value}\"{selected_attribute}>{label}</option>")
    })
    .collect::<Vec<_>>()
    .join("")
}

fn provider_audit_sections(raw: &str) -> (String, String, String) {
    let Ok(mut value) = serde_json::from_str::<serde_json::Value>(raw) else {
        return (
            raw.to_owned(),
            "当前记录未保存 Provider 原始响应。".to_owned(),
            "当前记录未保存分段计时。".to_owned(),
        );
    };
    let is_edge_wrapper = value.get("request").is_some();
    let timing = value
        .get("_timings")
        .or_else(|| value.get("timings"))
        .cloned();
    let request = if is_edge_wrapper {
        value.get("request").cloned().unwrap_or_default()
    } else {
        if let Some(object) = value.as_object_mut() {
            object.remove("_timings");
            object.remove("timings");
        }
        value.clone()
    };
    let response = is_edge_wrapper
        .then(|| value.get("response").cloned())
        .flatten();
    (
        serde_json::to_string_pretty(&request).unwrap_or_else(|_| raw.to_owned()),
        response
            .and_then(|item| serde_json::to_string_pretty(&item).ok())
            .unwrap_or_else(|| "当前记录未保存 Provider 原始响应。".to_owned()),
        timing
            .and_then(|item| serde_json::to_string_pretty(&item).ok())
            .unwrap_or_else(|| "当前记录未保存分段计时。".to_owned()),
    )
}

fn provider_thinking_summary(raw: &str) -> (String, String, String) {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(raw) else {
        return ("旧记录未标注".to_owned(), "-".to_owned(), "未知".to_owned());
    };
    let request = value.get("request").unwrap_or(&value);
    let timings = value.get("_timings").or_else(|| value.get("timings"));
    let provider_timings = timings.and_then(|item| item.get("provider")).or(timings);
    let configured_mode = provider_timings
        .and_then(|item| item.get("thinkingControlMode"))
        .and_then(serde_json::Value::as_str);
    let configured_level = provider_timings
        .and_then(|item| item.get("thinkingLevel"))
        .and_then(serde_json::Value::as_str);
    let (actual_parameter, actual_level) = if let Some(level) = request
        .get("reasoning_effort")
        .and_then(serde_json::Value::as_str)
    {
        ("reasoning_effort", Some(level))
    } else if let Some(level) = request
        .pointer("/google/thinking_config/thinking_level")
        .and_then(serde_json::Value::as_str)
    {
        ("google.thinking_config.thinking_level", Some(level))
    } else if let Some(level) = request
        .pointer("/extra_body/google/thinking_config/thinking_level")
        .and_then(serde_json::Value::as_str)
    {
        // Preserve readability for audits captured before the wire-format fix.
        (
            "legacy extra_body.google.thinking_config.thinking_level",
            Some(level),
        )
    } else {
        ("none", None)
    };
    let fallback = provider_timings
        .and_then(|item| {
            item.get("thinkingParameterFallback")
                .or_else(|| item.get("reasoningParameterFallback"))
        })
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    let configured = match configured_mode.unwrap_or(actual_parameter) {
        "NONE" | "none" => "none",
        "REASONING_EFFORT" | "reasoning_effort" => "reasoning_effort",
        "THINKING_LEVEL" | "thinking_level" => "google.thinking_config.thinking_level",
        value => value,
    };
    let control = if configured == actual_parameter {
        actual_parameter.to_owned()
    } else {
        format!("{configured} -> {actual_parameter}")
    };
    (
        control,
        actual_level.or(configured_level).unwrap_or("-").to_owned(),
        if fallback { "是" } else { "否" }.to_owned(),
    )
}

fn detail_page(record: RequestRecord, config: &Config) -> String {
    let audit = &record.audit;
    let schema_version = record
        .payload
        .as_ref()
        .and_then(|payload| schema_version_from_request_json(&payload.request_json));
    let (version_class, version_label) = version_badge(schema_version);
    let (request_json, response_json, model_request_json, error_message, payload_attributes) =
        match &record.payload {
            Some(payload) => {
                let request_pretty = pretty_json(&payload.request_json);
                let response_pretty = payload
                    .response_json
                    .as_deref()
                    .map(pretty_json)
                    .unwrap_or_else(|| "无响应数据".to_owned());
                let attributes = format!(
                    "data-request-json=\"{}\" data-response-json=\"{}\"",
                    STANDARD.encode(payload.request_json.as_bytes()),
                    payload
                        .response_json
                        .as_deref()
                        .map(|value| STANDARD.encode(value.as_bytes()))
                        .unwrap_or_default(),
                );
                let model_request_pretty = payload
                    .model_request_json
                    .as_deref()
                    .map(pretty_json)
                    .unwrap_or_else(|| {
                        "本次请求未调用模型，或记录创建于模型请求审计功能启用前。".to_owned()
                    });
                (
                    request_pretty,
                    response_pretty,
                    model_request_pretty,
                    payload.error_message.clone(),
                    attributes,
                )
            }
            None => (
                "旧记录未保存请求正文".to_owned(),
                "旧记录未保存响应正文".to_owned(),
                "旧记录未保存模型请求正文".to_owned(),
                None,
                "data-request-json=\"\" data-response-json=\"\"".to_owned(),
            ),
        };
    let (provider_request_json, provider_response_json, provider_timing_json) =
        provider_audit_sections(&model_request_json);
    let (thinking_control_label, thinking_level_label, thinking_fallback_label) =
        provider_thinking_summary(&model_request_json);
    let direct_output_label = serde_json::from_str::<serde_json::Value>(&request_json)
        .ok()
        .and_then(|value| {
            value
                .pointer("/translation/directStructuredOutput")
                .and_then(serde_json::Value::as_bool)
        })
        .map(|enabled| if enabled { "开启" } else { "关闭" })
        .unwrap_or("旧记录未标注");
    let error_html = error_message
        .filter(|value| !value.is_empty())
        .map(|value| {
            format!(
                "<section class=\"error-band\"><strong>请求错误</strong><span>{}</span></section>",
                escape_html(&value)
            )
        })
        .unwrap_or_default();
    let (source_image_view, source_image_meta) = record.image.as_ref().map_or_else(
        || {
            (
                "<div id=\"source-capture-view\" class=\"capture-view capture-view-placeholder\"><span>本次请求未上传翻译前截图</span></div>"
                    .to_owned(),
                "<p id=\"source-capture-meta\" class=\"capture-view-meta\">无原图 · 服务端布局还原仍可正常查看</p>"
                    .to_owned(),
            )
        },
        |image| {
            (
                format!(
                    "<div id=\"source-capture-view\" class=\"capture-view\">\
                        <button type=\"button\" class=\"fullscreen-image-button\" data-fullscreen-image aria-label=\"全屏查看本次 OCR 采集原图\">\
                            <img src=\"/admin/requests/{id}/image\" alt=\"本次 OCR 全屏采集原图\">\
                        </button>\
                     </div>",
                    id = escape_html(&audit.id),
                ),
                format!(
                    "<p id=\"source-capture-meta\" class=\"capture-view-meta\">{width} x {height} · {bytes} bytes · 翻译前原图</p>",
                    width = image.pixel_width,
                    height = image.pixel_height,
                    bytes = image.byte_size,
                ),
            )
        },
    );
    let (
        rendered_image_view,
        rendered_image_meta,
        rendered_toggle_attributes,
        rendered_toggle_label,
    ) = record
        .rendered_image
        .as_ref()
        .map_or_else(
            || {
                (
                    "<div id=\"rendered-capture-view\" class=\"capture-view capture-view-placeholder\" hidden><span>本次请求未上传端侧实际回贴截图</span></div>"
                        .to_owned(),
                    "<p id=\"rendered-capture-meta\" class=\"capture-view-meta\" hidden>无回贴截图</p>"
                        .to_owned(),
                    " disabled aria-disabled=\"true\"",
                    "未上传端侧实际回贴",
                )
            },
            |image| {
                let is_failed_capture = image.outcome == "RENDER_FAILED";
                let capture_label = if is_failed_capture {
                    "端侧回贴失败现场"
                } else {
                    "端侧实际回贴"
                };
                let stage = image
                    .stage
                    .as_deref()
                    .map(|value| format!(" · {}", escape_html(value)))
                    .unwrap_or_default();
                let failure = image
                    .failure_message
                    .as_deref()
                    .map(|value| format!("<p class=\"capture-audit-error\">{}</p>", escape_html(value)))
                    .unwrap_or_default();
                let diagnostics = image
                    .layout_diagnostics_json
                    .as_deref()
                    .map(|value| {
                        format!(
                            "<details class=\"capture-audit-details\"><summary>查看端侧布局诊断</summary><pre>{}</pre></details>",
                            escape_html(value)
                        )
                    })
                    .unwrap_or_default();
                (
                    format!(
                        "<div id=\"rendered-capture-view\" class=\"capture-view\" hidden>\
                            <button type=\"button\" class=\"fullscreen-image-button\" data-fullscreen-image aria-label=\"全屏查看{capture_label}\">\
                                <img loading=\"lazy\" src=\"/admin/requests/{id}/rendered-image\" alt=\"{capture_label}\">\
                            </button>\
                         </div>",
                        id = escape_html(&audit.id),
                        capture_label = capture_label,
                    ),
                    format!(
                        "<div id=\"rendered-capture-meta\" class=\"capture-view-meta\" hidden>\
                            <p>{width} x {height} · {bytes} bytes · {outcome}{stage}</p>\
                            {failure}{diagnostics}\
                         </div>",
                        width = image.pixel_width,
                        height = image.pixel_height,
                        bytes = image.byte_size,
                        outcome = escape_html(&image.outcome),
                        stage = stage,
                        failure = failure,
                        diagnostics = diagnostics,
                    ),
                    "",
                    if is_failed_capture {
                        "显示端侧失败现场"
                    } else {
                        "显示端侧实际回贴"
                    },
                )
            },
        );
    let (
        translation_background,
        translation_background_toggle_attributes,
        translation_background_toggle_label,
    ) = if record.image.is_some() {
        (
            format!(
                "<img id=\"translation-source-background\" class=\"layout-background\" src=\"/admin/requests/{id}/image\" alt=\"\" aria-hidden=\"true\">",
                id = escape_html(&audit.id),
            ),
            " checked",
            "显示原图",
        )
    } else {
        (
            String::new(),
            " disabled aria-disabled=\"true\"",
            "无采集图",
        )
    };
    let status_class = audit.status.to_ascii_lowercase();
    let (curl_endpoint, curl_api_key_env, curl_requires_auth) =
        provider_curl_settings(&audit.model, config);
    page_shell(
        &format!("请求 {}", audit.request_id),
        &format!(
            "<header class=\"topbar detail-topbar\">\
                <div><a class=\"back-link\" href=\"/admin/requests\">请求历史</a><h1>{request_id}</h1></div>\
                <div class=\"record-badges\"><span class=\"api-version {version_class}\">{version_label}</span><span class=\"status status-{status_class}\">{status}</span></div>\
            </header>\
            <main id=\"record-detail\" class=\"detail-main\" {payload_attributes}>\
                <section class=\"metadata-band\">\
                    <div><span>协议版本</span><strong>{version_label}</strong></div>\
                    <div><span>场景</span><strong>{scene}</strong></div>\
                    <div><span>模型</span><strong>{model}</strong></div>\
                    <div><span>推理参数</span><strong>{thinking_control_label}</strong></div>\
                    <div><span>思考深度</span><strong>{thinking_level_label}</strong></div>\
                    <div><span>语义组</span><strong>{groups}</strong></div>\
                    <div><span>OCR 区域</span><strong>{regions}</strong></div>\
                    <div><span>输入字符</span><strong>{chars}</strong></div>\
                    <div><span>耗时</span><strong>{duration} ms</strong></div>\
                    <div><span>记录时间</span><strong>{created}</strong></div>\
                </section>\
                {error_html}\
                <section class=\"capture-comparison-section\">\
                    <div class=\"section-heading\"><h2>采集参考</h2><span>同一画框切换前后结果</span></div>\
                    <div class=\"capture-reference-layout\">\
                        <div class=\"capture-viewer\">\
                            <div class=\"capture-viewer-toolbar\">\
                                <h3 id=\"capture-view-title\">翻译前 OCR 采集图</h3>\
                                <label class=\"capture-view-toggle\">\
                                    <input id=\"rendered-capture-toggle\" type=\"checkbox\" aria-controls=\"source-capture-view rendered-capture-view\"{rendered_toggle_attributes}>\
                                    <span>{rendered_toggle_label}</span>\
                                </label>\
                            </div>\
                            <div class=\"capture-stage\">\
                                {source_image_view}\
                                {rendered_image_view}\
                            </div>\
                            {source_image_meta}\
                            {rendered_image_meta}\
                        </div>\
                    </div>\
                </section>\
                <section class=\"layout-section\">\
                    <div class=\"section-heading\"><h2>页面布局还原</h2><span id=\"layout-summary\"></span></div>\
                    <div class=\"legend\"><span class=\"legend-cover\">原文覆盖范围</span><span class=\"legend-body\">正文</span><span class=\"legend-title\">标题</span><span class=\"legend-meta\">元数据</span><span class=\"legend-control\">控件</span><span class=\"legend-id\">标识符</span></div>\
                    <div class=\"layout-grid analysis-grid\">\
                        <div class=\"preview-panel\"><h3>OCR 与语义组</h3><div id=\"source-layout\" class=\"layout-canvas\"></div></div>\
                        <div class=\"preview-panel\"><h3>服务端语义计划</h3><div id=\"server-layout\" class=\"layout-canvas\"></div></div>\
                        <div class=\"preview-panel\">\
                            <div class=\"preview-panel-heading\">\
                                <h3>译文与 renderSlots</h3>\
                                <label class=\"layout-background-toggle\">\
                                    <input id=\"translation-background-toggle\" type=\"checkbox\" aria-controls=\"translation-source-background\"{translation_background_toggle_attributes}>\
                                    <span>{translation_background_toggle_label}</span>\
                                </label>\
                            </div>\
                            <div id=\"translation-layout\" class=\"layout-canvas\">{translation_background}</div>\
                        </div>\
                    </div>\
                </section>\
                <section class=\"payload-section\">\
                    <div class=\"payload-pane\">\
                        <div class=\"payload-heading\"><h2>请求数据</h2><button type=\"button\" class=\"copy-button\" data-copy-target=\"request-payload-json\">复制</button></div>\
                        <pre id=\"request-payload-json\">{request_json}</pre>\
                    </div>\
                    <div class=\"payload-pane\">\
                        <div class=\"payload-heading\"><h2>翻译响应</h2><button type=\"button\" class=\"copy-button\" data-copy-target=\"response-payload-json\">复制</button></div>\
                        <pre id=\"response-payload-json\">{response_json}</pre>\
                    </div>\
                </section>\
                <section class=\"model-request-section\">\
                    <details class=\"model-request-details\">\
                        <summary><span>Provider 交互审计</span><small>推理参数：{thinking_control_label} · 深度：{thinking_level_label} · 参数回退：{thinking_fallback_label} · 直接输出：{direct_output_label}</small></summary>\
                        <div class=\"model-request-content\">\
                            <div class=\"model-request-toolbar\">\
                                <span>curl 使用当前 Provider 地址，API Key 仅引用环境变量</span>\
                                <div>\
                                    <button type=\"button\" class=\"copy-button\" data-copy-provider-request=\"model-provider-request-json\">复制请求 JSON</button>\
                                    <button type=\"button\" class=\"copy-button copy-button-primary\" data-copy-curl=\"model-provider-request-json\" data-endpoint=\"{curl_endpoint}\" data-api-key-env=\"{curl_api_key_env}\" data-requires-auth=\"{curl_requires_auth}\">复制 curl</button>\
                                </div>\
                            </div>\
                            <details class=\"provider-audit-part\" open><summary>实际请求</summary><pre id=\"model-provider-request-json\">{provider_request_json}</pre></details>\
                            <details class=\"provider-audit-part\"><summary>原始响应</summary><pre>{provider_response_json}</pre></details>\
                            <details class=\"provider-audit-part\"><summary>分段计时</summary><pre>{provider_timing_json}</pre></details>\
                        </div>\
                    </details>\
                </section>\
            </main>\
            <dialog id=\"image-lightbox\" class=\"image-lightbox\" aria-labelledby=\"image-lightbox-caption\">\
                <button type=\"button\" class=\"image-lightbox-close\" aria-label=\"关闭全屏图片\">&#215;</button>\
                <img id=\"image-lightbox-image\" alt=\"\">\
                <p id=\"image-lightbox-caption\"></p>\
            </dialog>",
            request_id = escape_html(&audit.request_id),
            version_class = version_class,
            version_label = escape_html(&version_label),
            status = escape_html(&audit.status),
            scene = escape_html(&audit.scene),
            model = escape_html(&audit.model),
            groups = audit.group_count,
            regions = audit.region_count,
            chars = audit.input_chars,
            duration = audit.duration_ms,
            created = timestamp_html(&audit.created_at),
            request_json = escape_html(&request_json),
            response_json = escape_html(&response_json),
            provider_request_json = escape_html(&provider_request_json),
            provider_response_json = escape_html(&provider_response_json),
            provider_timing_json = escape_html(&provider_timing_json),
            direct_output_label = direct_output_label,
            thinking_control_label = escape_html(&thinking_control_label),
            thinking_level_label = escape_html(&thinking_level_label),
            thinking_fallback_label = escape_html(&thinking_fallback_label),
            curl_endpoint = escape_html(&curl_endpoint),
            curl_api_key_env = curl_api_key_env,
            curl_requires_auth = curl_requires_auth,
            rendered_toggle_attributes = rendered_toggle_attributes,
            rendered_toggle_label = rendered_toggle_label,
            translation_background = translation_background,
            translation_background_toggle_attributes = translation_background_toggle_attributes,
            translation_background_toggle_label = translation_background_toggle_label,
        ),
        "detail-page",
    )
}

fn provider_curl_settings(model: &str, config: &Config) -> (String, &'static str, bool) {
    let is_openlux = model.to_ascii_lowercase().starts_with("openlux:");
    let (base_url, api_key_env, requires_auth) = if is_openlux {
        (&config.openlux_base_url, "OPENLUX_API_KEY", true)
    } else {
        (
            &config.qwen_base_url,
            "QWEN_API_KEY",
            config.qwen_api_key.is_some(),
        )
    };
    let base_url = base_url.trim_end_matches('/');
    let endpoint = if base_url.ends_with("/chat/completions") {
        base_url.to_owned()
    } else {
        format!("{base_url}/chat/completions")
    };
    (endpoint, api_key_env, requires_auth)
}

fn page_shell(title: &str, content: &str, body_class: &str) -> String {
    format!(
        "<!doctype html><html lang=\"zh-CN\"><head>\
            <meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
            <title>{title} · OCR Translation Trace</title>\
            <link rel=\"stylesheet\" href=\"/admin/assets/admin.css\">\
        </head><body class=\"{body_class}\">{content}<script src=\"/admin/assets/admin.js\"></script></body></html>",
        title = escape_html(title),
    )
}

fn pretty_json(value: &str) -> String {
    serde_json::from_str::<serde_json::Value>(value)
        .and_then(|parsed| serde_json::to_string_pretty(&parsed))
        .unwrap_or_else(|_| value.to_owned())
}

fn timestamp_html(value: &str) -> String {
    let escaped = escape_html(value);
    format!(
        "<time class=\"timestamp\" datetime=\"{escaped}\" data-format-local-time>{escaped}</time>"
    )
}

fn escape_html(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn admin_authorized(headers: &HeaderMap, expected: Option<&str>) -> bool {
    let Some(expected) = expected else {
        return true;
    };
    let Some(value) = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    if value.strip_prefix("Bearer ") == Some(expected) {
        return true;
    }
    let Some(encoded) = value.strip_prefix("Basic ") else {
        return false;
    };
    STANDARD
        .decode(encoded)
        .ok()
        .and_then(|decoded| String::from_utf8(decoded).ok())
        .and_then(|credentials| {
            credentials
                .split_once(':')
                .map(|(_, password)| password.to_owned())
        })
        .is_some_and(|password| password == expected)
}

fn unauthorized() -> Response {
    (
        StatusCode::UNAUTHORIZED,
        [(
            header::WWW_AUTHENTICATE,
            "Basic realm=\"OCR translation history\"",
        )],
        "admin authentication required",
    )
        .into_response()
}

fn server_error(message: String) -> Response {
    (StatusCode::INTERNAL_SERVER_ERROR, message).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn admin_uses_existing_bearer_token_as_basic_auth_password() {
        let mut headers = HeaderMap::new();
        assert!(admin_authorized(&headers, None));
        assert!(!admin_authorized(&headers, Some("secret")));

        headers.insert(
            header::AUTHORIZATION,
            format!("Basic {}", STANDARD.encode("admin:secret"))
                .parse()
                .unwrap(),
        );
        assert!(admin_authorized(&headers, Some("secret")));
        assert!(!admin_authorized(&headers, Some("other")));
    }

    #[test]
    fn version_filter_and_badges_distinguish_v2_v3_and_v4() {
        assert_eq!(parse_schema_version_filter("2"), Some(2));
        assert_eq!(parse_schema_version_filter("3"), Some(3));
        assert_eq!(parse_schema_version_filter("4"), Some(4));
        assert_eq!(parse_schema_version_filter("5"), None);
        let options = version_options(Some(4));
        assert!(options.contains("value=\"2\">v2"));
        assert!(options.contains("value=\"3\">v3"));
        assert!(options.contains("value=\"4\" selected>v4"));
        assert_eq!(version_badge(Some(2)).1, "v2");
        assert_eq!(version_badge(Some(3)).1, "v3");
        assert_eq!(version_badge(Some(4)).1, "v4");
        assert_eq!(version_badge(None).1, "未知");
    }

    #[test]
    fn pagination_url_preserves_filters_and_page_size() {
        assert_eq!(
            pagination_url(2, 50, Some("FAILED"), Some(3)),
            "/admin/requests?page=2&amp;pageSize=50&amp;status=FAILED&amp;version=3"
        );
        assert!(page_size_options(20).contains("value=\"20\" selected"));
        assert!(
            status_options(Some(PASTE_BACK_MISSING_STATUS))
                .contains("value=\"PASTE_BACK_MISSING_ONE\" selected>回贴缺失（1 个区域）")
        );
    }

    #[test]
    fn timestamp_keeps_machine_value_for_browser_local_formatting() {
        let html = timestamp_html("2026-08-18T03:21:09.123Z");
        assert!(html.contains("class=\"timestamp\""));
        assert!(html.contains("datetime=\"2026-08-18T03:21:09.123Z\""));
        assert!(html.contains("data-format-local-time"));
    }

    #[test]
    fn provider_edge_audit_is_split_into_request_response_and_timings() {
        let raw = serde_json::json!({
            "request": {"model":"gemini", "messages":[]},
            "response": {"choices":[{"message":{"content":"你好"}}]},
            "timings": {"providerTotalMs":123}
        })
        .to_string();

        let (request, response, timings) = provider_audit_sections(&raw);

        assert!(request.contains("gemini"));
        assert!(!request.contains("你好"));
        assert!(response.contains("你好"));
        assert!(timings.contains("providerTotalMs"));
    }

    #[test]
    fn provider_thinking_summary_reports_actual_mutually_exclusive_parameter() {
        let reasoning = serde_json::json!({
            "request": {"reasoning_effort":"minimal"},
            "timings": {
                "thinkingControlMode":"REASONING_EFFORT",
                "thinkingLevel":"minimal",
                "thinkingParameterFallback":false
            }
        })
        .to_string();
        assert_eq!(
            provider_thinking_summary(&reasoning),
            (
                "reasoning_effort".to_owned(),
                "minimal".to_owned(),
                "否".to_owned()
            )
        );

        let native = serde_json::json!({
            "request": {
                "google":{"thinking_config":{"thinking_level":"low"}}
            },
            "timings": {"thinkingControlMode":"THINKING_LEVEL","thinkingLevel":"low"}
        })
        .to_string();
        let summary = provider_thinking_summary(&native);
        assert!(summary.0.contains("google.thinking_config.thinking_level"));
        assert_eq!(summary.1, "low");
        assert_eq!(summary.2, "否");

        let baseline = serde_json::json!({
            "request":{"model":"gemini"},
            "timings":{"thinkingControlMode":"NONE","thinkingParameterFallback":true}
        })
        .to_string();
        assert_eq!(provider_thinking_summary(&baseline).2, "是");
    }
}
