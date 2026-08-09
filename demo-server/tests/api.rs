use std::sync::Arc;

use async_trait::async_trait;
use axum::{
    body::{Body, to_bytes},
    http::{Request, StatusCode},
};
use image_translate_demo_server::{
    app,
    config::Config,
    contract::SemanticTranslationRequest,
    database::Database,
    error::AppError,
    qwen::{ModelTranslation, TranslationModel},
};
use serde_json::{Value, json};
use tempfile::TempDir;
use tokio::sync::Notify;
use tower::ServiceExt;

struct FakeQwen;

#[async_trait]
impl TranslationModel for FakeQwen {
    fn request_json(
        &self,
        _request: &SemanticTranslationRequest,
        groups: &[image_translate_demo_server::contract::TranslationGroup],
    ) -> Option<String> {
        Some(
            json!({
                "model": "fake-qwen",
                "messages": [{
                    "role": "user",
                    "content": {"groupIds": groups.iter().map(|group| &group.group_id).collect::<Vec<_>>()}
                }]
            })
            .to_string(),
        )
    }

    async fn translate(
        &self,
        _request: &SemanticTranslationRequest,
        groups: &[image_translate_demo_server::contract::TranslationGroup],
    ) -> Result<Vec<ModelTranslation>, AppError> {
        Ok(groups
            .iter()
            .map(|group| ModelTranslation {
                group_id: group.group_id.clone(),
                translated_text: "习近平在北京会见斯洛伐克总统".to_owned(),
                detected_source_language: "en".to_owned(),
                target_language: "zh".to_owned(),
            })
            .collect())
    }
}

#[test]
fn xi_news_fixture_satisfies_the_v2_contract() {
    let request: SemanticTranslationRequest =
        serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
    request.validate().unwrap();
    assert_eq!(request.viewport.width, 381);
    assert_eq!(request.groups.len(), 6);
    assert_eq!(request.regions.len(), 10);
}

#[tokio::test]
async fn translates_semantic_group_and_echoes_generation() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("test.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(
        Config::for_test(database_url),
        database.clone(),
        Arc::new(FakeQwen),
    );
    let mut request = valid_request();
    request["debugCapture"] = json!({
        "mimeType": "image/png",
        "dataBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        "pixelWidth": 1,
        "pixelHeight": 1
    });
    let response = router
        .clone()
        .oneshot(
            Request::post("/api/v2/translate/groups")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["generation"], 42);
    assert_eq!(body["results"][0]["groupId"], "group-title");
    assert_eq!(body["results"][0]["status"], "TRANSLATED");
    assert_eq!(body["results"][0]["memberRegionIds"][1], "region-title-2");
    assert!(body["results"][0]["layoutHint"]["minimumTextScale"].is_number());
    assert_eq!(body["results"][0]["layoutHint"]["sourceLineCount"], 2);
    assert_eq!(body["results"][0]["layoutHint"]["layoutShape"], "RECT");
    assert_eq!(body["documentPlan"]["mode"], "SHADOW");
    assert_eq!(body["results"][0]["sourceGroupIds"][0], "group-title");
    assert_eq!(
        body["results"][0]["layoutHint"]["renderSlots"][0],
        json!({"left": 70, "top": 2200, "right": 1100, "bottom": 2350})
    );
    assert_eq!(
        body["results"][0]["layoutHint"]["sourceCoverSlots"],
        json!([
            {"left": 70, "top": 2200, "right": 800, "bottom": 2260},
            {"left": 70, "top": 2280, "right": 1100, "bottom": 2350}
        ])
    );
    assert_eq!(database.audit_count().await.unwrap(), 1);
    let audits = database.list_audits(10).await.unwrap();
    assert_eq!(audits.len(), 1);
    let record = database
        .request_record(&audits[0].id)
        .await
        .unwrap()
        .unwrap();
    let payload = record.payload.unwrap();
    assert!(payload.request_json.contains("request-1"));
    assert!(payload.request_json.contains("debugCapture"));
    assert!(!payload.request_json.contains("dataBase64"));
    let model_request: Value =
        serde_json::from_str(payload.model_request_json.as_deref().unwrap()).unwrap();
    assert_eq!(model_request["model"], "fake-qwen");
    assert_eq!(
        model_request["messages"][0]["content"]["groupIds"][0],
        "group-title"
    );
    assert!(payload.response_json.unwrap().contains("promptVersion"));
    let image = record.image.unwrap();
    assert_eq!(image.mime_type, "image/png");
    assert_eq!(image.byte_size, 68);

    let rendered_upload = router
        .clone()
        .oneshot(
            Request::post("/api/v3/translate/requests/request-1/rendered-capture")
                .header("content-type", "application/json")
                .body(Body::from(
                    json!({
                        "sessionId": "session-1",
                        "generation": 42,
                        "translationRevision": 3,
                        "outcome": "RENDER_FAILED",
                        "stage": "OVERLAY_PARTIAL_DRAW",
                        "failureCode": "PARTIAL_RENDER",
                        "failureMessage": "one translated region was not pasted back",
                        "layoutDiagnostics": {
                            "schemaVersion": 1,
                            "renderedPatchCount": 1,
                            "failedRegionCount": 1
                        },
                        "capture": {
                            "mimeType": "image/png",
                            "dataBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                            "pixelWidth": 1,
                            "pixelHeight": 1
                        }
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(rendered_upload.status(), StatusCode::CREATED);
    let rendered_upload_body: Value = serde_json::from_slice(
        &to_bytes(rendered_upload.into_body(), 1024 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(rendered_upload_body["auditId"], record.audit.id);
    let record_with_rendered_image = database
        .request_record(&record.audit.id)
        .await
        .unwrap()
        .unwrap();
    let rendered_image = record_with_rendered_image.rendered_image.unwrap();
    assert_eq!(rendered_image.mime_type, "image/png");
    assert_eq!(rendered_image.byte_size, 68);
    assert_eq!(rendered_image.outcome, "RENDER_FAILED");
    assert_eq!(
        rendered_image.stage.as_deref(),
        Some("OVERLAY_PARTIAL_DRAW")
    );
    assert!(
        rendered_image
            .layout_diagnostics_json
            .as_deref()
            .unwrap()
            .contains("failedRegionCount")
    );

    let history = router
        .clone()
        .oneshot(Request::get("/admin/requests").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(history.status(), StatusCode::OK);
    let history_body = String::from_utf8(
        to_bytes(history.into_body(), 1024 * 1024)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(history_body.contains("request-1"));
    assert!(history_body.contains("target=\"_blank\""));
    assert!(history_body.contains("status-filter"));
    assert!(history_body.contains("page-size-filter"));
    assert!(history_body.contains("第 1 / 1 页"));
    assert!(history_body.contains("name=\"pageSize\""));

    let failed_history = router
        .clone()
        .oneshot(
            Request::get("/admin/requests?status=FAILED")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let failed_history_body = String::from_utf8(
        to_bytes(failed_history.into_body(), 1024 * 1024)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(!failed_history_body.contains("request-1"));

    let detail = router
        .clone()
        .oneshot(
            Request::get(format!("/admin/requests/{}", record.audit.id))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(detail.status(), StatusCode::OK);
    let detail_body = String::from_utf8(
        to_bytes(detail.into_body(), 4 * 1024 * 1024)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(detail_body.contains("页面布局还原"));
    assert!(detail_body.contains("采集参考"));
    assert!(detail_body.contains("翻译前 OCR 采集图"));
    assert!(detail_body.contains("显示端侧失败现场"));
    assert!(detail_body.contains("RENDER_FAILED"));
    assert!(detail_body.contains("查看端侧布局诊断"));
    assert!(detail_body.contains("loading=\"lazy\""));
    assert!(detail_body.contains("id=\"rendered-capture-toggle\" type=\"checkbox\""));
    assert!(detail_body.contains("id=\"source-capture-view\" class=\"capture-view\""));
    assert!(detail_body.contains("id=\"rendered-capture-view\" class=\"capture-view\" hidden"));
    assert!(!detail_body.contains("rendered-capture-details"));
    assert!(detail_body.contains("data-request-json="));
    assert!(detail_body.contains("renderSlots"));
    assert!(detail_body.contains("全屏采集原图"));
    assert!(detail_body.contains("发送给 Ollama / Qwen 的请求"));
    assert!(detail_body.contains("默认折叠 · 不包含 API Key"));
    assert!(detail_body.contains("fake-qwen"));
    assert!(!detail_body.contains("<details class=\"model-request-details\" open"));

    let admin_script = router
        .clone()
        .oneshot(
            Request::get("/admin/assets/admin.js")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(admin_script.status(), StatusCode::OK);
    let admin_script_body = String::from_utf8(
        to_bytes(admin_script.into_body(), 1024 * 1024)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(admin_script_body.contains("medianSourceLineHeight"));
    assert!(admin_script_body.contains("lines.join(\"\\n\")"));
    assert!(admin_script_body.contains("rendered-capture-toggle"));
    assert!(admin_script_body.contains("captureToggle?.addEventListener(\"change\""));
    assert!(admin_script_body.contains("lines.slice(cursor).join(\"\\n\")"));
    assert!(!admin_script_body.contains("Math.min(11, slotHeight"));

    let admin_styles = router
        .clone()
        .oneshot(
            Request::get("/admin/assets/admin.css")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(admin_styles.status(), StatusCode::OK);
    let admin_styles_body = String::from_utf8(
        to_bytes(admin_styles.into_body(), 1024 * 1024)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(admin_styles_body.contains(".history-page { height: 100vh; height: 100dvh"));
    assert!(admin_styles_body.contains(".history-page .table-scroll { flex: 1 1 auto"));
    assert!(admin_styles_body.contains("thead { position: sticky; top: 0"));
    assert!(admin_styles_body.contains(
        ".capture-stage { position: relative; width: 100%; height: clamp(300px, 58vh, 460px)"
    ));
    assert!(admin_styles_body.contains(
        ".capture-view img { position: absolute; inset: 0; display: block; width: 100%; height: 100%; object-fit: contain; }"
    ));

    let rendered_image_response = router
        .clone()
        .oneshot(
            Request::get(format!(
                "/admin/requests/{}/rendered-image",
                record.audit.id
            ))
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(rendered_image_response.status(), StatusCode::OK);
    assert_eq!(
        rendered_image_response.headers()["content-type"],
        "image/png"
    );

    let image_response = router
        .oneshot(
            Request::get(format!("/admin/requests/{}/image", record.audit.id))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(image_response.status(), StatusCode::OK);
    assert_eq!(image_response.headers()["content-type"], "image/png");
}

#[tokio::test]
async fn v3_returns_authoritative_layout_plan_and_declarative_rendering_fields() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("layout-plan.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let mut request = valid_request();
    request["schemaVersion"] = json!(3);
    let response = router
        .oneshot(
            Request::post("/api/v3/translate/layout-plan")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["schemaVersion"], 3);
    assert_eq!(body["documentPlan"]["mode"], "AUTHORITATIVE");
    assert_eq!(
        body["documentPlan"]["planVersion"],
        "server-semantic-plan-v3"
    );
    assert_eq!(body["results"][0]["sourceGroupIds"][0], "group-title");
    assert!(body["results"][0]["layoutHint"]["maximumTextScale"].is_number());
    assert!(body["results"][0]["layoutHint"]["lineSpacingMultiplier"].is_number());
    assert!(body["results"][0]["layoutHint"]["allowMore"].is_boolean());
    assert_eq!(
        body["results"][0]["layoutHint"]["sourceCoverSlots"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
}

#[tokio::test]
async fn v4_builds_an_authoritative_plan_from_regions_without_client_groups() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("regions-first.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let mut request = valid_request();
    request["schemaVersion"] = json!(4);
    request["groups"] = json!([]);

    let response = router
        .oneshot(
            Request::post("/api/v4/translate/layout-plan")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["schemaVersion"], 4);
    assert_eq!(body["provider"], "self-hosted-qwen-regions-first-v4");
    assert_eq!(body["documentPlan"]["mode"], "AUTHORITATIVE");
    assert_eq!(
        body["documentPlan"]["planVersion"],
        "server-regions-first-plan-v4"
    );
    assert!(
        !body["documentPlan"]["groups"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        body["documentPlan"]["groups"][0]["memberRegionIds"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    assert!(body["results"].as_array().unwrap().iter().all(|result| {
        !result["memberRegionIds"].as_array().unwrap().is_empty()
            && !result["layoutHint"]["sourceCoverSlots"]
                .as_array()
                .unwrap()
                .is_empty()
    }));
}

struct SlowQwen {
    started: Arc<Notify>,
}

#[async_trait]
impl TranslationModel for SlowQwen {
    async fn translate(
        &self,
        _request: &SemanticTranslationRequest,
        _groups: &[image_translate_demo_server::contract::TranslationGroup],
    ) -> Result<Vec<ModelTranslation>, AppError> {
        self.started.notify_one();
        std::future::pending().await
    }
}

#[tokio::test]
async fn cancels_an_active_model_request_and_records_terminal_status() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("cancel.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let started = Arc::new(Notify::new());
    let router = app(
        Config::for_test(database_url),
        database.clone(),
        Arc::new(SlowQwen {
            started: started.clone(),
        }),
    );
    let translation_router = router.clone();
    let translation = tokio::spawn(async move {
        translation_router
            .oneshot(
                Request::post("/api/v2/translate/groups")
                    .header("content-type", "application/json")
                    .body(Body::from(valid_request().to_string()))
                    .unwrap(),
            )
            .await
            .unwrap()
    });
    started.notified().await;

    let cancellation = router
        .clone()
        .oneshot(
            Request::post("/api/v2/translate/requests/request-1/cancel")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cancellation.status(), StatusCode::ACCEPTED);
    let cancellation_body: Value = serde_json::from_slice(
        &to_bytes(cancellation.into_body(), 1024 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(cancellation_body["active"], true);

    let translation_response = tokio::time::timeout(std::time::Duration::from_secs(2), translation)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(translation_response.status(), StatusCode::CONFLICT);
    let audits = database
        .list_audits_filtered(10, Some("CANCELLED"))
        .await
        .unwrap();
    assert_eq!(audits.len(), 1);
    assert_eq!(audits[0].request_id, "request-1");
}

#[tokio::test]
async fn reports_model_health_separately_from_http_service_health() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("test.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let response = router
        .oneshot(Request::get("/healthz").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["modelConfigured"], true);
    assert_eq!(body["modelReachable"], true);
    assert_eq!(body["modelAvailable"], true);
    assert_eq!(body["modelExecutionMode"], "embedded");
}

#[tokio::test]
async fn rejects_region_outside_viewport_before_model_call() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("test.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let mut request = valid_request();
    request["regions"][0]["bounds"]["right"] = json!(2000);
    let response = router
        .oneshot(
            Request::post("/api/v2/translate/groups")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn accepts_null_languages_for_preserved_ocr_regions() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("test.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let mut request = valid_request();
    request["groups"][0]["role"] = json!("TIMESTAMP");
    request["groups"][0]["translationUnit"] = json!("PRESERVED");
    request["groups"][0]["sourceText"] = json!("22:43\n22:43");
    request["regions"][0]["text"] = json!("22:43");
    request["regions"][1]["text"] = json!("22:43");
    request["regions"][0]["sourceLanguage"] = Value::Null;
    request["regions"][0]["targetLanguage"] = Value::Null;
    request["regions"][1]["sourceLanguage"] = Value::Null;
    request["regions"][1]["targetLanguage"] = Value::Null;
    let response = router
        .oneshot(
            Request::post("/api/v2/translate/groups")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["results"][0]["status"], "PRESERVED");
}

#[tokio::test]
async fn translates_legacy_preserved_metadata_when_it_contains_a_sentence_and_date() {
    let temporary = TempDir::new().unwrap();
    let database_url = temporary
        .path()
        .join("mixed-time.sqlite3")
        .to_string_lossy()
        .into_owned();
    let database = Database::new(database_url.clone());
    database.migrate().await.unwrap();
    let router = app(Config::for_test(database_url), database, Arc::new(FakeQwen));
    let mut request = valid_request();
    request["schemaVersion"] = json!(3);
    request["groups"][0]["role"] = json!("METADATA");
    request["groups"][0]["translationUnit"] = json!("PRESERVED");
    request["groups"][0]["sourceText"] =
        json!("The March ended in 1956 but,\nthe consequences remained.");
    request["regions"][0]["text"] = json!("The March ended in 1956 but,");
    request["regions"][1]["text"] = json!("the consequences remained.");
    let response = router
        .oneshot(
            Request::post("/api/v3/translate/layout-plan")
                .header("content-type", "application/json")
                .body(Body::from(request.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(body["results"][0]["status"], "TRANSLATED");
    assert_eq!(body["results"][0]["role"], "BODY");
    assert_eq!(
        body["documentPlan"]["groups"][0]["translationUnit"],
        "GROUP"
    );
    assert_eq!(body["documentPlan"]["groups"][0]["role"], "BODY");
}

fn valid_request() -> Value {
    json!({
        "schemaVersion": 2,
        "requestId": "request-1",
        "sessionId": "session-1",
        "generation": 42,
        "translationRevision": 3,
        "scene": "LIVE_SCREEN",
        "viewport": {"width": 1440, "height": 3200, "rotationDegrees": 0},
        "translation": {
            "mode": "ENGLISH_TO_CHINESE",
            "sourceLanguage": "auto",
            "targetLanguage": "zh",
            "preserveIdentifiers": true,
            "useDocumentContext": true
        },
        "documentContext": {
            "text": "Xi holds talks with Slovak president in Beijing",
            "sourceLanguage": "en",
            "readingOrderRegionIds": ["region-title-1", "region-title-2"]
        },
        "groups": [{
            "groupId": "group-title",
            "role": "TITLE",
            "translationUnit": "GROUP",
            "sourceText": "Xi holds talks with\nSlovak president in Beijing",
            "memberRegionIds": ["region-title-1", "region-title-2"],
            "readingOrder": 0,
            "groupingConfidence": 0.96,
            "groupingEvidence": ["OCR_BLOCK"],
            "bounds": {"left": 70, "top": 2200, "right": 1385, "bottom": 2350}
        }],
        "regions": [
            {
                "regionId": "region-title-1",
                "groupId": "group-title",
                "sourceRevision": 1,
                "text": "Xi holds talks with",
                "sourceLanguage": "en",
                "targetLanguage": "zh",
                "readingOrder": 0,
                "blockId": "block-1",
                "lineIndex": 0,
                "confidence": 0.94,
                "bounds": {"left": 70, "top": 2200, "right": 800, "bottom": 2260}
            },
            {
                "regionId": "region-title-2",
                "groupId": "group-title",
                "sourceRevision": 1,
                "text": "Slovak president in Beijing",
                "sourceLanguage": "en",
                "targetLanguage": "zh",
                "readingOrder": 1,
                "blockId": "block-1",
                "lineIndex": 1,
                "confidence": 0.95,
                "bounds": {"left": 70, "top": 2280, "right": 1100, "bottom": 2350}
            }
        ]
    })
}
