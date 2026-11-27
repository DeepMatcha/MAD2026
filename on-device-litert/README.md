# 기기 내 자료 요약

사용자가 앱의 자료 요약에서 Gemma 4 E2B 또는 E4B를 선택하면 Android 앱이 해당 모델만 내려받는다. 파일 검증에 성공한 뒤 로컬 선택을 저장한다. 다음 실행부터 설치한 모델은 오프라인에서도 선택할 수 있다. 모델 데이터는 앱 전용 저장소에 있고 빌드에는 포함되지 않는다.

| 선택 | 다운로드 크기(대략) | 배포 파일 |
|---|---:|---|
| Gemma 4 E2B | 2.59 GB | [LiteRT E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Gemma 4 E4B | 3.66 GB | [LiteRT E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) |

다운로드 크기 외에 CPU 가중치 캐시를 생성할 공간이 필요하다. 첫 실행은 캐시를 만드는 시간이 추가된다. 두 모델을 모두 설치하면 두 파일이 저장된다.

## 앱 통합

`GemmaModelStore.get(context).state`는 현재 선택, 설치된 모델, 다운로드 중인 모델과 받은 바이트 수를 제공한다. 모델 선택은 사용자가 다운로드 또는 전환을 요청할 때 호출한다.

```kotlin
val store = GemmaModelStore.get(context)
store.select(GemmaModel.E2B) // suspend, 새 다운로드나 이미 설치된 모델로 전환
val result: ApiResult<String> = OnDeviceSummarizer(context).summarize(resourceText)
```

다운로드 오류는 호출자가 표시하고 다시 시도할 수 있다. 선택된 모델 파일이 없으면 요약은 `NOT_FOUND`를 반환한다. 추론 실패도 `ApiResult.Failure`로 전달한다. 원문과 요약은 기기 안에서 처리한다.

## 실행 설정

Android API 26 이상, 64비트 ARM 또는 x86 프로세스에서 LiteRT-LM 0.16.1의 CPU 백엔드를 사용한다. 두 모델의 K/V 캐시는 배포 그래프에 고정된 INT8이다. 자료 요약은 2,048토큰 컨텍스트에서 구간별로 최대 160토큰을 생성한다. 긴 자료는 단어 경계를 우선해 나누고 원문 순서대로 각 구간의 요약을 표시한다. 추론 중 앱이 취소를 요청하면 진행 중인 생성을 중단한다.

자료 요약은 날짜·수치와 조건의 대응을 틀릴 수 있으므로 화면에서 원문과 함께 확인한다. 기존 일정·과제 추출에 모델을 연결하는 앱은 `store.selectedFile()`로 설치 파일을 얻고 `LiteRtTextEngine`을 `CanvasSchoolBackend.open`의 `device` 인자에 전달할 수 있다.
