use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).expect("read stdin");
    let mut args = std::env::args().skip(1);
    let operation = args.next().unwrap_or_default();
    let output = match operation.as_str() {
        "prepare" => ocr_translation_edge::prepare(
            &input,
            &args.next().unwrap_or_else(|| "openlux".to_owned()),
            &args.next().unwrap_or_else(|| "gpt-4.1".to_owned()),
        ),
        "complete" => {
            let value: serde_json::Value = serde_json::from_str(&input).expect("complete input JSON");
            ocr_translation_edge::complete(
                value["prepared"].as_str().expect("prepared string"),
                value["completion"].as_str().expect("completion string"),
                value["totalMs"].as_u64().unwrap_or_default(),
            )
        }
        _ => "{\"ok\":false,\"error\":\"usage: ocr-translation-edge prepare <provider> <model> | complete\"}".to_owned(),
    };
    println!("{output}");
}
