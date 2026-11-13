# MAD 과제 관리 앱 — Frontend ↔ Backend API 계약 v0.3

- 기준: 2026-11-14 KST
- 상태: **합의된 정책 확정본(API 계약 v0.3)**. 구현상의 기술 검증 및 계약 정합성 테스트는 별도 수행.
- 계약 형태: **Android 앱 내부 Kotlin Repository/Service 인터페이스**. HTTP REST endpoint가 아님. 향후 별도 서버를 추가하더라도 앱 측 계약은 유지.

## 1. 공통 계약

**함수 규칙**
- 조회 목록: `Flow<List<T>>`. 먼저 기기에 저장된 결과를 반환하고 DB 변경 시 재방출. 앱 진입만으로 원격 동기화를 시작하지 않음.
- 단건 조회/명령: `suspend fun ...: ApiResult<T>` (`Success(data)` / `Failure(code, retryable, detail?)`). 실패 문구는 UI가 정함.
- 장시간 작업: `start...(): ApiResult<JobId>` → `observeJob(jobId): Flow<JobState>`; 필요 시 `cancel...` / `retry...`. 작업 중 앱 프로세스가 종료되어도 재시도 가능한 체크포인트를 내부에 보존.
- 식별자: 앱 내부 `id: String`(UUID). 원본 시스템의 ID는 `source.externalId`로 별도 보존하며, 사용자에게는 `source.url`을 우선 노출.
- 날짜: KST(`Asia/Seoul`). 정확한 시각이 없는 마감은 임의로 자정/23:59로 채우지 않음. UI/API용 `KstMoment(date: LocalDate, time: LocalTime?)`; 변경·동기화 시각은 절대 시점 `Instant` 사용.
- 수집·AI의 불완전한 값은 nullable. 누락·충돌 사유는 `ReviewItem`에서 구분.
- 인증 정보/API Key는 로컬 설정에 저장하고, 목록·상태 조회 DTO 및 로그에는 반환하지 않음. 저장 시 암호화 여부는 내부 구현상 보안 권고사항이며 사용자 UI/API 계약에 영향을 주지 않음.
- `ApiErrorCode`: `INVALID_ARGUMENT`, `NOT_FOUND`, `UNAUTHENTICATED`, `AUTH_EXPIRED`, `FORBIDDEN`, `UNSUPPORTED`, `NETWORK`, `RATE_LIMIT`, `SOURCE_UNAVAILABLE`, `PARSE_FAILED`, `CONFLICT`, `CANCELLED`, `INTERNAL`.

### 1.1 핵심 타입

| 타입 | 필드 (타입) | 의미/규칙 |
|---|---|---|
| `SourceRef` | `provider: Provider`, `externalId: String?`, `url: String?`, `remoteUpdatedAt: Instant?` | 알려진 출처 URL은 접근 권한이 없더라도 보존. URL 자체를 알 수 없을 때만 null. 중복 판별에는 원본 ID 우선. |
| `Course` | `id: String`, `name: String`, `source: SourceRef`, `syncEnabled: Boolean`, `sync: SyncStatus` | 학교 과목. |
| `Assignment` | `id: String`, `courseId: String`, `title: String`, `description: String?`, `availableAt: KstMoment?`, `dueAt: KstMoment?`, `schoolCompleted: Boolean`, `userCompleted: Boolean`, `completed: Boolean`, `hidden: Boolean`, `source: SourceRef?` | `completed = schoolCompleted || userCompleted`. 사용자가 변경한 값은 원본과 분리해 우선 적용. |
| `Resource` | `id: String`, `courseId: String`, `kind: ResourceKind`, `title: String`, `contentText: String?`, `source: SourceRef`, `fileInfo: FileInfo?` | 공지/과제/파일/강의 등의 원본 자료. 파일은 기본 메타데이터 비교. |
| `CalendarItem` | `id: String`, `origin: Origin`, `kind: CalendarKind`, `title: String`, `description: String?`, `startAt: KstMoment`, `endAt: KstMoment?`, `courseId: String?`, `assignmentId: String?`, `classMode: ClassMode?`, `completed: Boolean?`, `hidden: Boolean`, `source: SourceRef?` | `origin = USER / SCHOOL / GOOGLE`. Google에서 읽은 항목은 기본 읽기 전용. |
| `Evidence` | `sourceUrl: String?`, `quote: String?`, `page: Int?`, `locator: String?` | 분석 근거. 확인할 수 없는 페이지/구간은 생성하지 않음. |
| `SyncStatus` | `state: SyncState`, `lastAttemptAt: Instant?`, `lastSuccessAt: Instant?`, `errorCode: ApiErrorCode?` | 서비스/과목 단위 동기화 상태. |
| `IntegrationStatus` | `provider: Provider`, `connected: Boolean`, `authState: AuthState`, `sync: SyncStatus?` | SCHOOL / GOOGLE_CALENDAR / AI. |
| `JobState` | `jobId: String`, `kind: JobKind`, `state: JobStatus`, `progress: Float?`, `processed: Int?`, `total: Int?`, `errorCode: ApiErrorCode?` | 진행률을 알 수 없으면 null. `QUEUED/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED`. |
| `AnalysisResult` | `resourceId: String`, `state: AnalysisState`, `candidates: List<ExtractedItem>`, `evidence: List<Evidence>`, `analyzedAt: Instant?` | 정보가 모호하면 `MANUAL_REVIEW`; 근거 없는 확정 금지. |
| `ReviewItem` | `id: String`, `resourceId: String`, `reason: ReviewReason`, `candidates: List<ExtractedItem>`, `status: ReviewStatus` | `UNAVAILABLE/INCOMPLETE/CONFLICT/ANALYSIS_FAILED`. 승인/수정/무시 가능. |
| `PendingChange` | `id: String`, `itemId: String`, `field: String`, `oldValue: String?`, `newValue: String?`, `source: SourceRef?` | 원본 일정의 **날짜** 변경은 적용 전 사용자 승인 필요. |
| `WorkSummary` | `total: Int`, `completed: Int`, `remaining: Int`, `upcoming: Int` | 추론 기반 ‘소요시간 예측’은 없음. |

보조 타입: `DateRange(from: LocalDate, to: LocalDate)`, `LocalEventInput`, `CalendarItemPatch`(변경 필드만 포함), `AppSettingsPatch`, `NotificationSettingsPatch`, `ManualReviewDecision(action: APPROVE / EDIT / IGNORE, patch: ExtractedItemPatch?)`. `ResourceKind = ASSIGNMENT/NOTICE/FILE/LECTURE/OTHER`. `ClassMode = NORMAL/ONLINE/CANCELLED`.

### 1.2 입력·반환 보조 DTO (구현팀에서 바로 사용할 계약)

| 타입 | 필드 (타입) | 비고 |
|---|---|---|
| `KstMoment` | `date: LocalDate`, `time: LocalTime?` | `time == null`은 날짜만 확인됨. 항상 `Asia/Seoul`로 해석. |
| `DateRange` | `from: LocalDate`, `to: LocalDate` | 시작·종료일 포함. `from <= to`. |
| `FileInfo` | `name: String?`, `mimeType: String?`, `sizeBytes: Long?`, `remoteUpdatedAt: Instant?`, `downloadUrl: String?`, `contentHash: String?` | 해시값은 실제로 내려받은 경우에만 저장. |
| `LocalEventInput` | `title: String`, `description: String?`, `kind: CalendarKind`, `startAt: KstMoment`, `endAt: KstMoment?`, `courseId: String?`, `classMode: ClassMode?` | `origin`은 항상 `USER`. |
| `CalendarItemPatch` | `title`, `description`, `startAt`, `endAt`, `classMode`, `completed` 각각 `PatchField<T>` | `PatchField = Unset / Set(value)`로 **수정 안 함**과 **명시적 null 설정** 구분. `startAt`은 null 불가. |
| `AppSettings` | `showCompletedItems: Boolean`, `syncIntervalMinutes: Int`, `autoAnalyze: Boolean`, `autoApplyAIResult: Boolean`, `googleAutoExport: Boolean`, `googleAutoExportCalendarId: String?` | 자동 내보내기는 **앱에서 직접 생성한 일정(`Origin.USER`)만** 대상. 학교 수집 일정은 사용자가 수동으로 내보낼 수 있음. |
| `AppSettingsPatch` | `AppSettings`의 각 필드를 `PatchField<T>`로 표현 | 설정값 하나만 변경해도 다른 값 유지. |
| `NotificationSettings` | `dueReminderEnabled: Boolean`, `dueReminderMinutes: Int`, `assignmentAvailableEnabled: Boolean`, `newAssignmentEnabled: Boolean`, `classChangeEnabled: Boolean`, `dateChangeApprovalNotificationEnabled: Boolean` | 기본 알림 활성화. |
| `NotificationSettingsPatch` | `NotificationSettings` 각 필드를 `PatchField<T>`로 표현 | 마감 알림은 시각이 확인된 미완료 과제만 대상. |
| `ExtractedItem` | `kind: ExtractedKind`, `title: String?`, `description: String?`, `availableAt: KstMoment?`, `dueAt: KstMoment?`, `evidence: List<Evidence>` | 필드가 불완전하면 임의 보정하지 않고 수동검토. |
| `ManualReviewDecision` | `action: APPROVE / EDIT / IGNORE`, `patch: ExtractedItemPatch?` | `EDIT`일 때만 `patch` 필수. |
| `AIConfigPublic` | `baseUrl: String?`, `modelId: String?`, `configured: Boolean`, `connected: Boolean` | API Key 등 비밀정보 없음. |
| `ConnectionTestResult` | `connected: Boolean`, `capabilities: Set<String>`, `errorCode: ApiErrorCode?` | 실제 테스트 결과 기반 기능 목록. |
| `GoogleCalendarInfo` | `id: String`, `name: String`, `primary: Boolean`, `writable: Boolean` | 내보내기는 writable 대상만. |
| `ExportReceipt` | `localItemId: String`, `googleEventId: String?`, `state: ExportState`, `errorCode: ApiErrorCode?` | `SENT / QUEUED / FAILED / NOT_APPLICABLE`. |
| `DeleteReceipt` | `localDeleted: Boolean`, `remoteState: ExportState` | 원격 오프라인이면 로컬 삭제 후 원격 삭제를 큐잉. |
| `AuthLaunchRequest` | `requestId: String`, `launchIntent: Intent?`, `intentSender: IntentSenderRequest?` | Android 인증 런처에 전달. 플랫폼 요구에 따라 한쪽만 설정. |
| `GoogleAuthResult` | `requestId: String`, `resultCode: Int`, `data: Intent?` | Android 인증 결과를 서비스에 반환. |

`Provider = SCHOOL / GOOGLE_CALENDAR / AI`; `Origin = USER / SCHOOL / GOOGLE`; `CalendarKind = ASSIGNMENT / CLASS / EVENT / LECTURE`; `SyncState = IDLE / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / AUTH_REQUIRED / UNSUPPORTED`; `ReviewStatus = OPEN / APPROVED / EDITED / IGNORED`. 별도 UI용 History 테이블은 만들지 않음.

## 2. 함수 계약 (Android local API)

### 2.1 학교/과제/원본 자료

| Function | args: 이름·타입·기본값 | return | 역할 |
|---|---|---|---|
| `observeCourses` | 없음 | `Flow<List<Course>>` | 로컬 과목 목록 |
| `getCourse` | `courseId: String` | `ApiResult<Course>` | 과목 상세 |
| `setCourseSyncEnabled` | `courseId: String, enabled: Boolean` | `ApiResult<Unit>` | 과목별 동기화 선택 |
| `observeAssignments` | `courseId: String? = null, includeCompleted: Boolean = true, includeHidden: Boolean = false` | `Flow<List<Assignment>>` | 과제 목록·상태 |
| `getAssignment` | `assignmentId: String` | `ApiResult<Assignment>` | 과제 상세 |
| `setUserCompleted` | `assignmentId: String, completed: Boolean` | `ApiResult<Assignment>` | 로컬 수동 완료 변경 |
| `setSchoolItemHidden` | `itemId: String, hidden: Boolean` | `ApiResult<Unit>` | 학교 자료 로컬 표시/숨김(원본 삭제 아님) |
| `observeResources` | `courseId: String, kinds: Set<ResourceKind>? = null` | `Flow<List<Resource>>` | 원본 공지·파일·강의 조회 |
| `getResource` | `resourceId: String` | `ApiResult<Resource>` | 원본 자료 상세 |
| `getSource` | `itemId: String` | `ApiResult<SourceRef?>` | 사용자에게 출처 URL 제공 |

### 2.2 캘린더/완료 필터/변경 승인

| Function | args | return | 역할 |
|---|---|---|---|
| `observeCalendar` | `range: DateRange, includeCompleted: Boolean? = null` | `Flow<List<CalendarItem>>` | 로컬 캘린더. null이면 앱 설정 적용 |
| `getCalendarItem` | `itemId: String` | `ApiResult<CalendarItem>` | 일정 상세 |
| `createLocalEvent` | `input: LocalEventInput` | `ApiResult<CalendarItem>` | 사용자 생성 일정 |
| `updateCalendarItem` | `itemId: String, patch: CalendarItemPatch` | `ApiResult<CalendarItem>` | 사용자 일정 수정 또는 원본 일정에 사용자 우선 override 생성. Google 수입 항목은 읽기 전용 |
| `deleteLocalEvent` | `itemId: String` | `ApiResult<DeleteReceipt>` | 사용자 생성 일정 삭제. Google에 내보낸 앱 생성 일정은 연결된 원격 삭제도 큐잉 |
| `observePendingChanges` | 없음 | `Flow<List<PendingChange>>` | 외부 날짜 변경 승인 대기 목록 |
| `resolvePendingChange` | `changeId: String, approve: Boolean` | `ApiResult<Unit>` | 변경 승인/거부(거부 시 기존 표시값 유지) |
| `observeWorkSummary` | `range: DateRange` | `Flow<WorkSummary>` | 해야 할 항목 수·완료 수 집계 |

### 2.3 연결·인증

| Function | args | return | 역할 |
|---|---|---|---|
| `observeIntegrations` | 없음 | `Flow<List<IntegrationStatus>>` | 학교·Google·AI 연결/오류 상태 |
| `connectSchool` | `studentId: String, password: String` | `ApiResult<IntegrationStatus>` | 확인된 API 기반 ID/PW 인증. 구현 내부에서 세션/토큰 관리 |
| `refreshSchoolAuth` | 없음 | `ApiResult<IntegrationStatus>` | 필요 시 재인증/세션 갱신 |
| `beginGoogleAuth` | 없음 | `ApiResult<AuthLaunchRequest>` | Android 공식 Google 인증 UI 호출 준비 |
| `completeGoogleAuth` | `result: GoogleAuthResult` | `ApiResult<IntegrationStatus>` | Android 인증 결과 연동 |
| `disconnectGoogle` | 없음 | `ApiResult<Unit>` | Google 연결 해제 |
| `testIntegration` | `provider: Provider` | `ApiResult<ConnectionTestResult>` | 연결·권한 검사 |

`AuthLaunchRequest` 및 `GoogleAuthResult`는 Android 인증 라이브러리의 플랫폼 값을 감싸는 타입이다. UI는 로그인 화면/승인만 수행하고, 토큰 관리와 원격 데이터 접근은 연결 계층이 담당한다.

### 2.4 동기화

| Function | args | return | 역할 |
|---|---|---|---|
| `startSync` | `provider: Provider = SCHOOL, courseIds: List<String>? = null` | `ApiResult<String>` (`jobId`) | 기본 **증분 동기화**. `null`은 활성화된 모든 과목 |
| `observeSyncJob` | `jobId: String` | `Flow<JobState>` | 진행 상황 관찰 |
| `observeSyncStatus` | `provider: Provider` | `Flow<SyncStatus>` | 마지막 동기화·오류·진행 상태 |
| `observeCourseSyncStatus` | `courseId: String` | `Flow<SyncStatus>` | 부분 실패 과목별 확인 |
| `cancelSync` | `jobId: String` | `ApiResult<Unit>` | 진행 작업 중단, 체크포인트 보존 |
| `retrySync` | `jobId: String` | `ApiResult<String>` (`newJobId`) | 실패/중단 작업의 남은 부분 재시도 |

주기 60분 기본, 사용자 변경 가능(`>=15`분). 앱 실행만으로 강제 동기화하지 않음. 읽을 수 있는 최소 원격 목록/메타데이터를 비교해 신규·수정·삭제 탐지, 실제 내용/첨부파일 재다운로드 최소화. 파일 원문 변경은 수정 공지 등 확인 가능한 신호가 있을 때 재확인. 원본 삭제는 **목록을 정상적으로 확인해 부재가 확실할 때만** 로컬에도 반영. 일부 과목 실패 시 기존 데이터 유지 + 해당 과목 오류 표시. WebView 필요 작업까지 백그라운드 우선 시도하고 불가능한 경우 앱 실행 상태에서 처리.

### 2.5 AI·수동검토

| Function | args | return | 역할 |
|---|---|---|---|
| `getAIConfig` | 없음 | `ApiResult<AIConfigPublic>` | endpoint/model/연결 상태 조회. API Key 미반환 |
| `setAIConfig` | `baseUrl: String, apiKey: String, modelId: String? = null` | `ApiResult<Unit>` | 온디바이스(127.0.0.1) 또는 외부 HTTP API 설정 |
| `testAIConnection` | 없음 | `ApiResult<ConnectionTestResult>` | 모델 API 접근 테스트 |
| `analyzeResource` | `resourceId: String` | `ApiResult<String>` (`jobId`) | 단일 자료 분석(일괄 분석 API 없음) |
| `observeAnalysisJob` | `jobId: String` | `Flow<JobState>` | 분석 진행 상태 |
| `getAnalysisResult` | `jobId: String` | `ApiResult<AnalysisResult>` | 추출 결과·근거 URL·인용 구간 |
| `observeManualReviews` | `status: ReviewStatus? = OPEN` | `Flow<List<ReviewItem>>` | 수동검토 목록 |
| `resolveManualReview` | `reviewId: String, decision: ManualReviewDecision` | `ApiResult<Unit>` | 승인·수정·무시 |

AI 분석은 개별 자료 단위. 자동 분석은 사용자가 켜면 신규 자료에 적용을 시도하며, 백그라운드에서 불가할 경우 신규 자료 알림 후 앱 실행 시 수행. 자동 반영은 **별도 opt-in**. 충돌하는 후보는 확인 가능한 최신 원본 우선, 최신을 판별할 근거가 없거나 데이터가 불완전하면 `MANUAL_REVIEW`. 사용자 수동 수정은 자동 분석 결과보다 우선.

### 2.6 Google Calendar

| Function | args | return | 역할 |
|---|---|---|---|
| `getGoogleCalendars` | 없음 | `ApiResult<List<GoogleCalendarInfo>>` | 접근 가능한 캘린더 조회 |
| `observeGoogleEvents` | `calendarId: String, range: DateRange` | `Flow<List<CalendarItem>>` | 마지막으로 조회한 Google 일정 캐시. 실제 새로고침은 동기화 작업 |
| `exportToGoogle` | `localItemIds: List<String>, calendarId: String` | `ApiResult<ExportReceipt>` | 사용자의 명시적 내보내기 |
| `setAutoExport` | `enabled: Boolean, calendarId: String?` | `ApiResult<Unit>` | 자동 내보내기 opt-in 및 대상 Google 캘린더 선택. 대상 일정은 `Origin.USER`만 |
| `syncExportedLocalEvent` | `localItemId: String` | `ApiResult<ExportReceipt>` | Google에 내보낸 **앱 생성 일정**의 수정 반영 |
| `deleteExportedLocalEvent` | `localItemId: String` | `ApiResult<ExportReceipt>` | Google에 내보낸 **앱 생성 일정**의 삭제 반영 |

Google 일정은 읽기 중심. **자동 내보내기는 앱에서 직접 생성한 일정만 대상**이고, 학교에서 수집한 과제·강의는 사용자가 버튼으로 수동 내보내기 가능. Google에서 읽은 원본 일정은 앱이 임의 변경/삭제하지 않음. 사용자 생성 일정이 이미 내보내졌다면 사용자 수정·삭제가 원격에도 반영될 수 있음. 중복 내보내기 방지를 위해 `localItemId ↔ googleEventId` 매핑을 로컬에 보존. 원격 접근이 안 되면 수정·삭제를 재시도 큐에 보관.

### 2.7 사용자 설정 및 알림

| Function | args | return | 역할 |
|---|---|---|---|
| `observeSettings` | 없음 | `Flow<AppSettings>` | UI 설정 구독 |
| `updateSettings` | `patch: AppSettingsPatch` | `ApiResult<AppSettings>` | 부분 변경. 변경 없는 설정은 유지 |
| `observeNotificationSettings` | 없음 | `Flow<NotificationSettings>` | 알림 설정 구독 |
| `updateNotificationSettings` | `patch: NotificationSettingsPatch` | `ApiResult<NotificationSettings>` | 유형별 알림 변경 |

기본 설정:
- `showCompletedItems = true` (캘린더에서 완료 항목 표시/숨김)
- `syncIntervalMinutes = 60` (자동 동기화 주기; 백그라운드 OS 정책상 정확한 실행 시각은 보장하지 않음)
- `autoAnalyze = false`, `autoApplyAIResult = false`, `googleAutoExport = false` (각각 독립 opt-in)
- 알림 **전부 기본 활성화**: `dueReminderEnabled = true`, `dueReminderMinutes = 60`, `assignmentAvailableEnabled = true`, `newAssignmentEnabled = true`, `classChangeEnabled = true`, `dateChangeApprovalNotificationEnabled = true`.
- 날짜 변경은 사용자 승인 후 반영(`requireDateChangeApproval = true`).
- OS 알림 권한이 허용되지 않으면 실제 푸시를 보내지 못함. 시각 정보가 없는 마감은 1시간 전 알림 시각을 임의로 생성하지 않음.

## 3. 동작 규칙과 경계 조건

1. **사용자 입력 우선**: 학교 원본과 로컬 override를 따로 저장. 표시값은 로컬 override 우선. 완료 여부는 `schoolCompleted OR userCompleted`.
2. **원본 삭제**: 학교에서 자료가 실제로 삭제됐다고 확인하면 해당 원본 기반 로컬 엔티티를 삭제. 사용자 직접 생성한 독립 일정은 유지. UI용 History는 생성하지 않음.
3. **일정 날짜 변경**: 원격 날짜 변경을 감지하면 `PendingChange` 생성. 승인 전에는 기존 표시값 유지. 같은 변경을 매 동기화마다 반복 통지하지 않도록 처리 상태만 보존.
4. **증분 수집**: 원본 ID/URL/제목/수정 시각/텍스트 등의 메타데이터를 비교하고, 바뀐 항목만 상세 수집. 본문을 무조건 다시 다운로드하지 않음. 파일 내용의 무신호 변경은 100% 탐지할 수 없음.
5. **수동검토**: 접근 실패·불완전·분석 실패·최신 여부 불명확한 충돌을 구분. 프론트가 문구를 결정할 수 있도록 `code/reason`만 제공.
6. **취소·재시도**: UI용 작업 이력은 없지만 복구를 위한 현재 상태/체크포인트는 저장. 네트워크 작업은 중복 적용이 일어나지 않도록 설계.
7. **권한/비밀정보**: 사용자 비밀번호·AI API Key를 읽기 DTO나 로그에 싣지 않음. 앱 데이터 서버는 현재 범위에 포함하지 않음.
8. **프론트 독립 개발**: 이 인터페이스를 프론트와 공유한 뒤 백엔드가 구현. 프론트는 동일 타입을 반환하는 in-memory fake repository로 화면 개발 가능. 별도 mock server 구축은 불필요.

## 4. 최종 결정 반영

- **A 확정:** Google Calendar 자동 내보내기(opt-in)는 앱에서 직접 만든 일정(`Origin.USER`)만 대상. 학교 수집 일정은 수동 내보내기 허용. 자동 내보내기 대상 선택 설정은 두지 않음.
- **B 범위 제외:** 학교 계정 변경·연결 해제 및 그에 따른 로컬 데이터 유지/삭제 정책은 일반적인 사용 시나리오에 포함하지 않음. `disconnectSchool()` API를 현재 범위에서 제거. 세션이 일시적으로 만료되거나 로그인이 풀린 경우에는 `AUTH_EXPIRED`, `refreshSchoolAuth()` 또는 필요 시 `connectSchool()` 재인증을 사용. 데이터 삭제 또는 계정 전환 동작은 정의하지 않음.

추가 사용자 정책 결정 항목 없음. 내부 저장용 private 필드 및 플랫폼별 어댑터는 구현팀이 결정하되, 프론트에 공개된 계약은 유지해야 함.
