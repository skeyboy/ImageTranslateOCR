use std::time::Instant;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::{config::Config, contract::OcrOptions};

#[derive(Debug, Clone)]
pub struct OcrInput {
    pub image_base64: String,
    pub options: OcrOptions,
}

#[derive(Debug, Clone)]
pub struct OcrOutput {
    pub width: u32,
    pub height: u32,
    pub regions: Vec<OcrRegion>,
    pub latency_ms: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OcrRegion {
    pub text: String,
    pub bounds: [i32; 4],
    pub confidence: f32,
}

#[derive(Debug, thiserror::Error)]
pub enum OcrError {
    #[error("PaddleOCR timed out")]
    Timeout,
    #[error("PaddleOCR is unavailable: {0}")]
    Unavailable(String),
    #[error("PaddleOCR returned an invalid response: {0}")]
    InvalidResponse(String),
}

impl OcrError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Timeout => "OCR_TIMEOUT",
            Self::Unavailable(_) => "OCR_UNAVAILABLE",
            Self::InvalidResponse(_) => "INVALID_OCR_RESPONSE",
        }
    }
}

#[async_trait]
pub trait OcrProvider: Send + Sync {
    async fn recognize(&self, input: OcrInput) -> Result<OcrOutput, OcrError>;
    async fn health(&self) -> Result<(), OcrError>;
}

#[derive(Clone)]
pub struct PaddleOcrClient {
    client: reqwest::Client,
    base_url: String,
}

impl PaddleOcrClient {
    pub fn new(config: &Config) -> anyhow::Result<Self> {
        Ok(Self {
            client: reqwest::Client::builder()
                .connect_timeout(std::time::Duration::from_millis(1_500))
                .timeout(config.paddle_ocr_timeout)
                .pool_max_idle_per_host(1)
                .build()?,
            base_url: config.paddle_ocr_base_url.clone(),
        })
    }
}

#[async_trait]
impl OcrProvider for PaddleOcrClient {
    async fn recognize(&self, input: OcrInput) -> Result<OcrOutput, OcrError> {
        let started = Instant::now();
        let request = PaddleRequest {
            file: input.image_base64,
            file_type: 1,
            use_doc_orientation_classify: false,
            use_doc_unwarping: false,
            use_textline_orientation: false,
            text_det_limit_side_len: input.options.text_det_limit_side_len,
            text_det_limit_type: "max",
            text_rec_score_thresh: input.options.text_rec_score_thresh,
            return_word_box: false,
            visualize: false,
        };
        let response = self
            .client
            .post(format!("{}/ocr", self.base_url))
            .json(&request)
            .send()
            .await
            .map_err(map_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(OcrError::Unavailable(format!("HTTP {status}")));
        }
        let response: PaddleResponse = response
            .json()
            .await
            .map_err(|error| OcrError::InvalidResponse(error.to_string()))?;
        parse_response(response, started.elapsed().as_millis() as u64)
    }

    async fn health(&self) -> Result<(), OcrError> {
        let response = self
            .client
            .get(format!("{}/health", self.base_url))
            .send()
            .await
            .map_err(map_reqwest)?;
        if response.status().is_success() {
            Ok(())
        } else {
            Err(OcrError::Unavailable(format!("HTTP {}", response.status())))
        }
    }
}

fn map_reqwest(error: reqwest::Error) -> OcrError {
    if error.is_timeout() {
        OcrError::Timeout
    } else {
        OcrError::Unavailable(error.to_string())
    }
}

fn parse_response(response: PaddleResponse, latency_ms: u64) -> Result<OcrOutput, OcrError> {
    if response.error_code != 0 {
        return Err(OcrError::Unavailable(response.error_msg));
    }
    let result = response
        .result
        .ok_or_else(|| OcrError::InvalidResponse("missing result".to_owned()))?;
    let page = result
        .ocr_results
        .into_iter()
        .next()
        .ok_or_else(|| OcrError::InvalidResponse("missing OCR page".to_owned()))?;
    let values = page.pruned_result;
    if values.rec_texts.len() != values.rec_scores.len()
        || values.rec_texts.len() != values.rec_boxes.len()
    {
        return Err(OcrError::InvalidResponse(
            "text, score, and bounds counts differ".to_owned(),
        ));
    }
    let regions = values
        .rec_texts
        .into_iter()
        .zip(values.rec_scores)
        .zip(values.rec_boxes)
        .filter_map(|((text, confidence), bounds)| {
            let text = text.trim().to_owned();
            let valid_bounds = bounds[0] >= 0
                && bounds[1] >= 0
                && bounds[0] < bounds[2]
                && bounds[1] < bounds[3]
                && bounds[2] as u32 <= result.data_info.width
                && bounds[3] as u32 <= result.data_info.height;
            (!text.is_empty() && valid_bounds).then_some(OcrRegion {
                text,
                bounds,
                confidence: confidence.clamp(0.0, 1.0),
            })
        })
        .collect();
    Ok(OcrOutput {
        width: result.data_info.width,
        height: result.data_info.height,
        regions,
        latency_ms,
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaddleRequest {
    file: String,
    file_type: u8,
    use_doc_orientation_classify: bool,
    use_doc_unwarping: bool,
    use_textline_orientation: bool,
    text_det_limit_side_len: u32,
    text_det_limit_type: &'static str,
    text_rec_score_thresh: f32,
    return_word_box: bool,
    visualize: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PaddleResponse {
    error_code: i32,
    error_msg: String,
    result: Option<PaddleResult>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PaddleResult {
    ocr_results: Vec<PaddleOcrResult>,
    data_info: PaddleDataInfo,
}

#[derive(Deserialize)]
struct PaddleOcrResult {
    #[serde(rename = "prunedResult")]
    pruned_result: PaddlePrunedResult,
}

#[derive(Deserialize)]
struct PaddlePrunedResult {
    rec_texts: Vec<String>,
    rec_scores: Vec<f32>,
    rec_boxes: Vec<[i32; 4]>,
}

#[derive(Deserialize)]
struct PaddleDataInfo {
    width: u32,
    height: u32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_paddle_regions_and_rejects_blank_text() {
        let response: PaddleResponse = serde_json::from_value(serde_json::json!({
            "errorCode": 0,
            "errorMsg": "Success",
            "result": {
                "dataInfo": {"width": 400, "height": 800},
                "ocrResults": [{
                    "prunedResult": {
                        "rec_texts": [" Hello ", ""],
                        "rec_scores": [0.98, 0.0],
                        "rec_boxes": [[10, 20, 110, 60], [0, 0, 1, 1]]
                    }
                }]
            }
        }))
        .unwrap();

        let output = parse_response(response, 42).unwrap();

        assert_eq!(output.width, 400);
        assert_eq!(output.height, 800);
        assert_eq!(output.latency_ms, 42);
        assert_eq!(
            output.regions,
            vec![OcrRegion {
                text: "Hello".to_owned(),
                bounds: [10, 20, 110, 60],
                confidence: 0.98,
            }]
        );
    }

}
