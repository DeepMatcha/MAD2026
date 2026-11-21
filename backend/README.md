# 백엔드 학교 증분 동기화

앱에서 사용하는 Repository와 Service의 공통 계약은 [API v0.5](../API.md)에 정의되어 있다. `:backend`의 `SchoolBackend.openSynthetic(context)`는 합성 학교 수집원과 기기 로컬 저장을 사용하는 학교·동기화·캘린더·설정 계약 구현을 연다. 앱의 기존 프론트엔드 개발용 진입점은 `:demo-data`를 사용한다.

캘린더 구현은 학교 과제의 조회, 수동 수정과 날짜 승인에 한정된다. 앱 통합 시 로컬 일정과 시간표에 사용할 구현도 함께 구성한다.

```kotlin
val backend = SchoolBackend.openSynthetic(context)
val courses = backend.school.observeCourses()
val refresh = backend.sync.startSync()
```

`startSync()`는 수동 새로고침이 필요할 때 호출한다. 백엔드를 열거나 목록을 관찰하면 로컬 데이터부터 반환한다. Android JobScheduler에는 60분 기본 주기를 등록한다. `updateSettings`로 15분 이상으로 변경할 수 있으며 실제 실행 시각은 OS 정책과 네트워크 상태를 따른다.

- 과목별 변경 자료와 마지막 성공 체크포인트를 버전이 있는 로컬 스냅샷에 함께 기록한다. 실패하거나 중단한 과목은 기존 데이터와 체크포인트를 보존한다.
- 원본과 사용자 수정을 분리하고 과제 ID·완료·숨김을 재수집 후에도 유지한다. 학교 또는 사용자 측에서 완료하면 과제가 완료로 표시된다. 삭제 확인을 받은 원본만 제거한다.
- 학교가 과제의 날짜를 바꾸면 `calendar.observePendingChanges()`에 표시한다. `resolvePendingChange()`로 승인하기 전까지 기존 날짜를 사용한다. 날짜만 수집된 값은 시각 없이 보존한다.
- `observeCourseSyncStatus()`로 부분 실패를 과목별로 확인한다. 비활성 과목은 제외하고, `retrySync()`는 실패 또는 중단한 작업의 남은 과목을 처리한다. 앱 프로세스가 중단되면 작업을 재시도할 수 있다.

학교 실연동에서 인증 및 세션 갱신을 연결하면 `SchoolAssignmentSource`에서 합성 수집원을 교체할 수 있다.

## 공통 계약 검증

`FrontendStub`은 JVM 테스트에서 화면 없이 `MadDependencies`를 호출한다. 기존 데모 구현과 함께, 제어 가능한 합성 응답과 임시 파일을 사용하는 백엔드 테스트를 실행한다. 부분 성공, 실패한 과목의 체크포인트, 취소·프로세스 중단 후 재시도, 확인된 삭제, 사용자 상태와 날짜 승인, 동기화 주기 설정을 검증한다.

`backend`에 푸시하거나 `backend`를 대상으로 PR을 열면 [Backend CI](../.github/workflows/backend-ci.yml)가 아래 빌드와 테스트를 실행한다.

```sh
./gradlew --max-workers=2 :backend:assembleDebug :backend:testDebugUnitTest \
  :contract:testDebugUnitTest :demo-data:testDebugUnitTest
```
