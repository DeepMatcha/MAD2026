# 기기 내 AI 런타임

`:on-device-litert`는 Android 기기의 CPU에서 LiteRT-LM으로 텍스트를 생성한다. 앱에서 모듈을 의존성으로 추가하고, 모델 식별자와 읽을 수 있는 앱 내부 `.litertlm` 파일로 `LiteRtTextEngine`을 만든 다음 백엔드에 전달한다. 선택적으로 쓰기 가능한 앱 캐시 디렉터리를 넘겨 모델 로딩 캐시를 사용한다.

기기 요구 사항은 앱의 최소 API 26과 64비트 ARM 또는 x86 Android 프로세스다. 모델에 따라 사용 가능한 메모리와 생성 속도가 달라진다. 사용자에게 모델 파일을 제공하거나 설치하는 흐름은 앱에서 별도로 구성한다. [LiteRT-LM Kotlin 안내](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)

현재 런타임 버전 0.16.1은 프로젝트의 Kotlin 2.2 도구 체인과 함께 검증했다.

```kotlin
val engine = LiteRtTextEngine(
    modelId = "local-model",
    modelFile = File(context.filesDir, "local-model.litertlm"),
    cacheDirectory = context.cacheDir,
)
```
