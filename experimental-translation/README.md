# Experimental Translation Library

Optional, default-off Android translation providers for comparing Marian INT8 and
TranslateGemma 4B with an existing production translator.

The library contains runtimes and adapters, but no model weights. Models are installed under the
host application's `noBackupFilesDir`, so they do not increase the APK model payload and are not
copied by Android backup.

## Public API

- `ExperimentalTranslationLibrary`: provider lifecycle and translation entry point.
- `ExperimentalTranslationEngine`: `DISABLED`, `MARIAN_INT8`, or `TRANSLATEGEMMA_4B`.
- `ExperimentalModelRepository`: status, URL download, ZIP import, and removal.
- `ExperimentalModelManagerActivity`: optional model management UI for a host app.

The host must keep its production translator as the fallback. Selecting an experimental engine
does not imply that its model is installed or that a particular device can initialize it.

## Model archives

### Marian INT8

`marian-int8.zip` must contain both directions:

```text
zh-en/encoder_model_quantized.onnx
zh-en/decoder_model_quantized.onnx
zh-en/source.spm
zh-en/target.spm
zh-en/vocab.json
zh-en/tokenizer.properties
en-zh/encoder_model_quantized.onnx
en-zh/decoder_model_quantized.onnx
en-zh/source.spm
en-zh/target.spm
en-zh/vocab.json
en-zh/tokenizer.properties
```

The decoder must be the non-merged ONNX decoder with these logical inputs:

- `input_ids` or `decoder_input_ids`
- `encoder_hidden_states`
- `encoder_attention_mask`
- optional decoder `attention_mask`

The first decoder output must be float logits shaped `[1, sequence, vocabulary]`. The provider uses
greedy decoding and intentionally avoids beam search for live latency.

Example `tokenizer.properties`:

```properties
source_unk_id=1
source_bos_id=0
source_eos_id=0
source_pad_id=65000
target_unk_id=1
target_bos_id=0
target_eos_id=0
target_pad_id=65000
decoder_start_id=65000
```

IDs must be taken from the exported tokenizer rather than copied from this example. Export and
quantize a model using a current Optimum/ONNX Runtime toolchain, then verify the Android output
against the source Transformers model before packaging it. Model licensing and attribution remain
the distributor's responsibility.

### TranslateGemma 4B

`translategemma-4b.zip` must contain:

```text
translategemma-4b.task
prompt_template.txt       # optional
```

The `.task` file must be a MediaPipe LLM Inference-compatible, mobile-quantized model. Raw
Hugging Face `safetensors` files cannot be renamed to `.task`. TranslateGemma access requires
accepting Google's Gemma terms; the library therefore does not embed a gated download URL.

`prompt_template.txt` can override the conservative fallback prompt and supports:

- `{source_language}`
- `{target_language}`
- `{text}`

Use the exact prompt template produced by the model conversion pipeline when available.

## Install on a test device

The in-app manager supports a configured ZIP URL or Android's system file picker. For computer
import, put the archive in the device Downloads folder and select it in the manager:

```bash
adb push marian-int8.zip /sdcard/Download/
adb push translategemma-4b.zip /sdcard/Download/
```

The repository extracts into a staging directory, rejects path traversal and archives larger than
12 GiB, validates all required files, then atomically replaces the previous model.

## Host integration policy

1. Default to `DISABLED`.
2. Do not initialize either runtime until the user selects it and translation is requested.
3. Include the engine in translation cache keys.
4. Propagate coroutine cancellation so stale screen frames cannot finish inference.
5. Fall back to the production translator for missing models, initialization errors, inference
   errors, or invalid output.
6. Unload the active provider when the host processor closes.

For release builds, use Android App Bundles or ABI splits. ONNX Runtime and MediaPipe include native
runtime libraries; the model weights are external, but a universal APK still contains runtime code
for multiple ABIs.
