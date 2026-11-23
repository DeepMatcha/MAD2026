# 백엔드 학교 증분 동기화

앱에서 사용하는 Repository와 Service의 공통 계약은 [API v0.5](../API.md)에 정의되어 있다. `:backend`의 `SchoolBackend.openSynthetic(context)`는 합성 학교 수집원과 기기 로컬 저장을 사용하는 학교·동기화·캘린더·설정 계약 구현을 연다. 앱의 기존 프론트엔드 개발용 진입점은 `:demo-data`를 사용한다.

캘린더는 학교 과제, 사용자 일정, 주간 시간표의 날짜별 수업, 동기화한 Google 일정을 함께 조회한다. Notes에는 학교 과제·수집한 공지와 사용자 메모가 표시된다.

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

## 승인된 환경에서 eClass 조회

`CanvasSchoolBackend.open(file, scope, credential)`은 실행 중 공급되는 Canvas 토큰으로 중앙대 eClass REST API를 조회하는 백엔드 진입점이다. 호출자가 승인된 계정별로 앱 전용 저장 파일과 해당 계정의 토큰 공급 함수를 전달한다. 열기와 Flow 구독은 캐시만 읽는다. `refreshCourses()`로 활성 수강 과목을 확인한 뒤 `sync.startSync()`로 해당 과목의 과제와 본인 제출 상태를 수집한다. 과목 ID는 활성 수강 응답에 따라 정해진다.

Canvas가 `active`로 반환하는 수강 목록에는 이전 학기 과목이 포함될 수 있다. 특정 과목만 확인하려면 `refreshCourses()` 결과의 로컬 `Course.id`를 `sync.startSync(courseIds = listOf(course.id))`에 전달한다. `source.externalId`는 eClass 원본 ID다.

토큰은 지정된 eClass HTTPS API의 Bearer 인증에만 쓰이며 로컬 스냅샷에는 과목·과제와 동기화 상태가 저장된다. 과목이나 과제 목록 조회가 중간에 실패하면 해당 목록의 마지막 성공 결과를 유지한다. Canvas 과제 설명은 원본 HTML이고, 마감은 Canvas가 현재 계정에 제공한 값을 KST로 변환한다.

과제 목록의 모든 페이지를 읽은 다음 체크포인트와 비교해 변경 항목만 저장한다. 로컬 삭제는 현재 계정의 전체 과제 목록에서 부재가 확인된 항목에 적용한다. 활성 수강 목록에서 빠진 과목은 캐시를 유지하고 동기화 대상에서 제외한다.

이 진입점은 승인된 호출자가 시작하는 조회용이다. Android의 앱 로그인 연결, eClass 웹 세션, LearningX 조회와 쓰기 작업은 별도 통합 범위다.

## 시간표와 Notes

`backend.timetable`에서 매주 반복할 수업을 등록한다. `backend.calendar.observeCalendar(range)`가 기간 안의 회차를 전개한다. 주말과 겹치는 시간도 허용한다. 개별 회차에서 수업을 열면 `timetableEntryId`로 원본을 수정할 수 있다.

`backend.notes`는 과목 기본 폴더와 사용자 폴더를 제공한다. 과제의 완료·숨김은 학교 저장소와 공유한다. Canvas 공지는 `refreshNotices(courseId)`로 해당 과목을 명시적으로 새로고침한다. 페이지 조회가 모두 성공했을 때 과목별 캐시를 교체한다. 공지 발행일은 마감일로 추정하지 않는다.

## Google Calendar와 기기 알림

Android에서는 `CanvasSchoolBackend.open(context, file, scope, credential = { ... })` 또는 `SchoolBackend.openSynthetic(context)`를 사용한다. Canvas 저장 파일은 학교 계정마다 앱 전용 경로에 둔다. 파일만 받는 기존 진입점은 기기 알림과 계정 선택 UI를 등록하지 않는다.

1. Google Cloud 프로젝트에서 Calendar API를 사용 설정하고 OAuth 동의 화면과 Android 클라이언트(앱 패키지 및 서명 SHA-1)를 등록한다. Google Play services가 설치된 기기에서 `integrations.beginGoogleAuth()`가 제공하는 Intent를 실행한 뒤 같은 요청 ID로 `completeGoogleAuth`에 결과를 전달한다. 연결 해제는 로컬 연결을 끊고 Google 승인 취소를 요청한다. 네트워크 오류가 나면 연결 해제를 재시도할 수 있다.
2. `googleCalendar.getGoogleCalendars()`로 대상을 확인하고 `refreshGoogleEvents(range)`로 화면에 필요한 기간을 읽는다. `sync.startSync(Provider.GOOGLE_CALENDAR)`의 기본 조회 범위는 서울 기준 오늘 전후 1년이다. 구독은 저장된 일정만 읽는다.
3. 사용자 일정 생성 전 `setAutoExport(true, calendarId)`를 선택하면 새 사용자 일정의 내보내기가 예약된다. 학교 과제는 명시적으로 내보낼 수 있다. 수정·삭제와 내보내기 대기는 저장 파일에 함께 기록되며 Android 백엔드가 열린 동안 및 다시 열릴 때 재시도한다. 일괄 내보내기는 먼저 모든 ID를 검사하며, 계약의 단일 영수증에는 처음 대기한 항목 또는 모두 전송된 경우 첫 항목이 담긴다.
4. Android 13 이상에서는 화면에서 `POST_NOTIFICATIONS` 권한을 요청하고 결과를 받은 뒤 `refreshNotificationDelivery()`를 호출한다. 백엔드는 마감·이용 가능 시각, 신규 과제, 휴강·온라인 전환, 날짜 변경 승인 요청을 처리한다. 시각이 없는 마감에는 사전 알림을 예약하지 않는다. 기기는 재부팅 후 예약을 복원하며 실제 전달 시각은 OS의 절전 정책을 따른다.

## AI 분석 연결

`CanvasSchoolBackend.analysis`에서 [AI 계약과 프론트 연결 순서](../API.md#27-ai수동검토)를 사용한다. `setAIConfig()`로 개인 서버를 지정하거나, 설치된 모델로 만든 `OnDeviceTextEngine`을 `CanvasSchoolBackend.open()`에 넘겨 `ON_DEVICE`를 선택한다. 개인 서버 키는 Android Keystore로 암호화해 저장한다. 기본값인 `DISABLED`에서는 AI 호출이 발생하지 않는다.

분석은 자료 한 건을 대상으로 사용자가 요청할 때 시작한다. 후보와 근거는 수동 검토로 보이며 승인·수정·무시한 결정은 로컬에 저장된다. 기기용 모델 준비와 ABI 요구 사항은 [LiteRT-LM 모듈 안내](../on-device-litert/README.md)에 있다.

## 공통 계약 검증

`FrontendStub`은 JVM 테스트에서 화면 없이 `MadDependencies`를 호출한다. 기존 데모 구현과 함께, 제어 가능한 합성 응답과 임시 파일을 사용하는 백엔드 테스트를 실행한다. 부분 성공, 실패한 과목의 체크포인트, 취소·프로세스 중단 후 재시도, 확인된 삭제, 사용자 상태와 날짜 승인, 동기화 주기 설정을 검증한다.

`backend`에 푸시하거나 `backend`를 대상으로 PR을 열면 [Backend CI](../.github/workflows/backend-ci.yml)가 아래 빌드와 테스트를 실행한다.

```sh
./gradlew --max-workers=2 :backend:assembleDebug :backend:testDebugUnitTest \
  :contract:testDebugUnitTest :demo-data:testDebugUnitTest :on-device-litert:assembleDebug
```
