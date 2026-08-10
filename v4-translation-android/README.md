# Embedded v4 Android module

This Android library calls the same Rust regions-first planner and Qwen/OpenAI-compatible client
used by `demo-server`. The API key is supplied at runtime and must not be stored in source control.

Build the native library before assembling the AAR:

```bash
./scripts/build-v4-android.sh
./gradlew :v4-translation-android:assembleRelease
```

The first command currently builds the production `arm64-v8a` ABI. Consumers call
`EmbeddedV4TranslationService.translate` on a worker dispatcher with the same schema-v4 JSON body
accepted by `/api/v4/translate/layout-plan`.
