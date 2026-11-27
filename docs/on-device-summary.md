# 자료 요약 화면과 E2B·E4B 모델 선택

사용자는 자료 요약을 처음 실행할 때 Gemma 4 E2B와 E4B 중 하나를 선택하고 모델을 내려받는다. 두 모델 중 선택한 것만 설치한다. 이후에는 설치된 모델로 오프라인 요약이 가능하며, 모델을 바꿀 때 이미 설치한 파일은 다시 받지 않는다.

## 프론트 작업을 시작할 때 사용자에게 확인할 사항

- 자료 상세의 요약 버튼에서 모델을 처음 선택한 뒤, 나중에 모델을 변경하는 메뉴는 요약 화면과 앱 설정 중 어디에 둘까요?
- 모델 용량은 E2B 약 2.6 GB, E4B 약 3.7 GB입니다. 모바일 데이터 연결에서는 용량 안내 후 다운로드를 허용할까요, Wi-Fi 연결을 기다리게 할까요?

## 백엔드 연동

참조 화면과 연동 코드는 `backend` 브랜치에 있다. [앱 진입점](https://github.com/DeepMatcha/MAD2026/blob/backend/app/src/main/kotlin/org/deepmatcha/mad/MainActivity.kt)과 [모델 모듈 사용법](https://github.com/DeepMatcha/MAD2026/blob/backend/on-device-litert/README.md)을 참고한다.

- 모델 선택지와 표시 용량은 `GemmaModel.E2B`, `GemmaModel.E4B`의 `label`, `sizeBytes`를 사용한다.
- `GemmaModelStore.get(context).state`에서 현재 선택 모델(`selected`), 설치된 모델(`installed`), 다운로드 상태(`download`)를 관찰한다. 받는 중에는 `download.bytes`와 `download.model.sizeBytes`로 진행량을 표시한다. 사용자가 진행을 선택한 시점에 `select(model)`을 호출한다. 파일 검증이 끝나야 선택이 바뀌므로, 다운로드 실패 시 기존 선택을 그대로 표시하고 재시도할 수 있게 한다. 공간 부족(`GemmaStorageException`)과 파일 검증 실패(`GemmaIntegrityException`)를 구분해 안내한다.
- 요약을 요청하면 `OnDeviceSummarizer(context).summarize(text)`에 자료 본문을 전달하고 반환된 `ApiResult<String>`에 따라 요약문 또는 오류를 표시한다. 긴 대기나 오류가 있어도 원문을 계속 읽을 수 있게 한다.
