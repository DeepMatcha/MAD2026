# MAD 과제 관리 앱 — Frontend ↔ Backend API 계약 v0.5

- 기준: 2026-11-19 KST
- 상태: **API 계약 v0.5 (11/18 MAD 회의 결과 반영)**. v0.4의 확정 정책과 공개 함수는 유지하면서, 시간표 직접 등록·Notes 폴더명 관리·과제 상세 필드를 보완함. 구현상의 기술 검증 및 계약 정합성 테스트는 별도 수행.
- AI 연결 방식과 준비 상태는 [§2.7](#27-ai수동검토)에 추가로 정의한다.
- 계약 형태: **Android 앱 내부 Kotlin Repository/Service 인터페이스**. HTTP REST endpoint가 아님. 향후 별도 서버를 추가하더라도 앱 측 계약은 유지.

## 1. 공통 계약

**함수 규칙**
- 조회 목록: `Flow<List<T>>`. 먼저 기기에 저장된 결과를 반환하고 DB 변경 시 재방출. 앱 진입만으로 원격 동기화를 시작하지 않음.
- 단건 조회/명령: `suspend fun ...: ApiResult<T>` (`Success(data)` / `Failure(code, retryable, detail?)`). 실패 문구는 UI가 정함.
- 장시간 작업: `start...(): ApiResult<JobId>` → `observeJob(jobId): Flow<JobState>`; 필요 시 `cancel...` / `retry...`. 작업 중 앱 프로세스가 종료되어도 재시도 가능한 체크포인트를 내부에 보존.
- 식별자: 앱 내부 `id: String`(UUID). 원본 시스템의 ID는 `source.externalId`로 별도 보존하며, 사용자에게는 `source.url`을 우선 노출.
- 날짜: KST(`Asia/Seoul`). 정확한 시각이 없는 마감은 임의로 자정/23:59로 채우지 않음. UI/API용 `KstMoment(date: LocalDate, time: LocalTime?)`; 변경·동기화 시각은 절대 시점 `Instant` 사용.
- 수집·AI의 불완전한 값은 nullable. 누락·충돌 사유는 `ReviewItem`에서 구분.
- Notes는 과제/공지 기반 항목과 사용자 메모를 하나의 목록으로 제공하되, 날짜가 없는 메모를 허용함. Figma의 **No Dates** 항목은 null 날짜의 정상 표현이며, 무조건 분석 오류로 간주하지 않음.
- Notes 폴더의 기본 이름은 학교 과목명에서 자동 생성/추천하고 사용자가 표시 이름을 변경할 수 있음. **학교 과목 원본명은 수정하지 않으며** 폴더 이름 override는 로컬에 보존함.
- 시간표는 외부 시간표 서비스 연동 없이 **사용자가 매주 반복하는 수업을 직접 등록**함. 주말·겹치는 수업 모두 허용하고, 날짜 범위가 주어진 캘린더 조회에서는 시간표 항목을 해당 날짜의 수업으로 투영함.
- 인증 정보/API Key는 로컬 설정에 저장하고, 목록·상태 조회 DTO 및 로그에는 반환하지 않음. 저장 시 암호화 여부는 내부 구현상 보안 권고사항이며 사용자 UI/API 계약에 영향을 주지 않음.
- `ApiErrorCode`: `INVALID_ARGUMENT`, `NOT_FOUND`, `UNAUTHENTICATED`, `AUTH_EXPIRED`, `FORBIDDEN`, `UNSUPPORTED`, `NETWORK`, `RATE_LIMIT`, `SOURCE_UNAVAILABLE`, `PARSE_FAILED`, `CONFLICT`, `CANCELLED`, `INTERNAL`.

### 1.1 핵심 타입

| 타입 | 필드 (타입) | 의미/규칙 |
|---|---|---|
| `SourceRef` | `provider: Provider`, `externalId: String?`, `url: String?`, `remoteUpdatedAt: Instant?` | 알려진 출처 URL은 접근 권한이 없더라도 보존. URL 자체를 알 수 없을 때만 null. 중복 판별에는 원본 ID 우선. |
| `Course` | `id: String`, `name: String`, `source: SourceRef`, `syncEnabled: Boolean`, `sync: SyncStatus` | 학교 과목. |
| `Assignment` | `id: String`, `courseId: String`, `title: String`, `description: String?`, `submissionFormat: String?`, `submissionMethod: String?`, `gradingCriteria: String?`, `availableAt: KstMoment?`, `dueAt: KstMoment?`, `schoolCompleted: Boolean`, `userCompleted: Boolean`, `completed: Boolean`, `hidden: Boolean`, `source: SourceRef?` | `completed = schoolCompleted || userCompleted`. 사용자가 변경한 값은 원본과 분리해 우선 적용. |
| `Resource` | `id: String`, `courseId: String`, `kind: ResourceKind`, `title: String`, `contentText: String?`, `source: SourceRef`, `fileInfo: FileInfo?` | 공지/과제/파일/강의 등의 원본 자료. 파일은 기본 메타데이터 비교. |
| `NoteItem` | `id: String`, `kind: NoteKind`, `courseId: String?`, `folderId: String?`, `title: String`, `body: String?`, `dateAt: KstMoment?`, `completed: Boolean`, `hidden: Boolean`, `link: NoteLink?`, `source: SourceRef?` | **Notes 탭 공통 표시 모델**. 학교 과제·공지에서 유래한 항목과 사용자 직접 추가한 메모를 한 목록으로 표시. `folderId`는 Notes 폴더, `courseId`는 학교 원본 과목을 가리킴. `dateAt == null`이면 **No Dates**에 분류. |
| `NoteLink` | `entityKind: NoteLinkedKind`, `entityId: String` | 학교 유래 Notes가 기존 `Assignment` 또는 `Resource`를 가리키는 링크. Notes와 기존 원본 데이터를 별도로 중복 저장하지 않음. |
| `NoteFolder` | `id: String`, `kind: FolderKind`, `sourceCourseId: String?`, `defaultName: String`, `displayName: String` | Notes 그룹/폴더. 학교 과목 기반 폴더는 `SCHOOL`, 직접 생성한 폴더는 `LOCAL`. 사용자 수정 이름은 `displayName`으로 관리하고 학교 과목 원본명은 보존. |
| `NoteCourseSummary` | `folderId: String`, `courseId: String?`, `courseName: String?`, `displayName: String`, `noteCount: Int` | **Courses** 카드에 표시할 폴더 이름과 Notes 개수. `courseId`/`courseName`은 학교 과목 연결이 있을 때만 설정. 카드 선택 시 `folderId`로 필터. |
| `CalendarItem` | `id: String`, `origin: Origin`, `kind: CalendarKind`, `title: String`, `description: String?`, `startAt: KstMoment`, `endAt: KstMoment?`, `courseId: String?`, `assignmentId: String?`, `classMode: ClassMode?`, `location: String?`, `timetableEntryId: String?`, `completed: Boolean?`, `hidden: Boolean`, `source: SourceRef?` | `origin = USER / SCHOOL / GOOGLE`. Google에서 읽은 항목은 기본 읽기 전용. |
| `TimetableEntry` | `id: String`, `courseId: String?`, `title: String`, `dayOfWeek: DayOfWeek`, `startTime: LocalTime`, `endTime: LocalTime`, `location: String?`, `validFrom: LocalDate?`, `validUntil: LocalDate?` | 사용자가 직접 등록한 매주 반복 수업. 과목 연동은 선택. 종료 시각은 시작 시각보다 늦어야 함. 겹치는 수업 허용. |
| `Evidence` | `sourceUrl: String?`, `quote: String?`, `page: Int?`, `locator: String?` | 분석 근거. 확인할 수 없는 페이지/구간은 생성하지 않음. |
| `SyncStatus` | `state: SyncState`, `lastAttemptAt: Instant?`, `lastSuccessAt: Instant?`, `errorCode: ApiErrorCode?` | 서비스/과목 단위 동기화 상태. |
| `IntegrationStatus` | `provider: Provider`, `connected: Boolean`, `authState: AuthState`, `sync: SyncStatus?` | SCHOOL / GOOGLE_CALENDAR / AI. |
| `JobState` | `jobId: String`, `kind: JobKind`, `state: JobStatus`, `progress: Float?`, `processed: Int?`, `total: Int?`, `errorCode: ApiErrorCode?` | 진행률을 알 수 없으면 null. `QUEUED/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED`. |
| `AnalysisResult` | `resourceId: String`, `state: AnalysisState`, `candidates: List<ExtractedItem>`, `evidence: List<Evidence>`, `analyzedAt: Instant?` | 정보가 모호하면 `MANUAL_REVIEW`; 근거 없는 확정 금지. |
| `ReviewItem` | `id: String`, `resourceId: String`, `reason: ReviewReason`, `candidates: List<ExtractedItem>`, `status: ReviewStatus` | `UNAVAILABLE/INCOMPLETE/CONFLICT/ANALYSIS_FAILED/NEEDS_CONFIRMATION`. 승인/수정/무시 가능. |
| `PendingChange` | `id: String`, `itemId: String`, `field: String`, `oldValue: String?`, `newValue: String?`, `source: SourceRef?` | 원본 일정의 **날짜** 변경은 적용 전 사용자 승인 필요. |
| `WorkSummary` | `total: Int`, `completed: Int`, `remaining: Int`, `upcoming: Int` | 추론 기반 ‘소요시간 예측’은 없음. |

보조 타입: `DateRange(from: LocalDate, to: LocalDate)`, `LocalEventInput`, `CalendarItemPatch`(변경 필드만 포함), `AppSettingsPatch`, `NotificationSettingsPatch`, `ManualReviewDecision(action: APPROVE / EDIT / IGNORE, patch: ExtractedItemPatch?)`. `ResourceKind = ASSIGNMENT/NOTICE/FILE/LECTURE/OTHER`. `ClassMode = NORMAL/ONLINE/CANCELLED`.

### 1.2 입력·반환 보조 DTO (구현팀에서 바로 사용할 계약)

| 타입 | 필드 (타입) | 비고 |
|---|---|---|
| `KstMoment` | `date: LocalDate`, `time: LocalTime?` | `time == null`은 날짜만 확인됨. 항상 `Asia/Seoul`로 해석. |
| `DateRange` | `from: LocalDate`, `to: LocalDate` | 시작·종료일 포함. `from <= to`. |
| `FileInfo` | `name: String?`, `mimeType: String?`, `sizeBytes: Long?`, `remoteUpdatedAt: Instant?`, `downloadUrl: String?`, `contentHash: String?` | 해시값은 실제로 내려받은 경우에만 저장. |
| `LocalEventInput` | `title: String`, `description: String?`, `kind: CalendarKind`, `startAt: KstMoment`, `endAt: KstMoment?`, `courseId: String?`, `classMode: ClassMode?`, `location: String?` | `origin`은 항상 `USER`. |
| `LocalNoteInput` | `title: String`, `body: String?`, `courseId: String?`, `folderId: String? = null`, `dateAt: KstMoment?` | 사용자 메모 생성. 과목 지정 시 기본 폴더를 자동 선택할 수 있음. `folderId`를 명시하면 해당 폴더에 저장. 모두 null이면 All Notes의 미분류 항목. 빈 제목 불허. |
| `LocalNotePatch` | `title`, `body`, `courseId`, `folderId`, `dateAt` 각각 `PatchField<T>` | 사용자 메모 수정용. `Unset`과 `Set(null)` 구분. 폴더 이동 가능. `title`은 null/공백 불허. 완료 여부는 별도 함수로 변경. |
| `CalendarItemPatch` | `title`, `description`, `startAt`, `endAt`, `classMode`, `completed` 각각 `PatchField<T>` | `PatchField = Unset / Set(value)`로 **수정 안 함**과 **명시적 null 설정** 구분. `startAt`은 null 불가. `timetableEntryId != null`인 반복 수업은 이 API 대신 시간표 CRUD로 수정. |
| `TimetableEntryInput` | `courseId: String?`, `title: String`, `dayOfWeek: DayOfWeek`, `startTime: LocalTime`, `endTime: LocalTime`, `location: String?`, `validFrom: LocalDate?`, `validUntil: LocalDate?` | 직접 입력 시간표. 반복 범위가 둘 다 null이면 기간 제한 없이 매주 반복. 기간 중 한쪽만 있으면 열린 기간으로 해석하며, 양쪽이 있으면 `validFrom <= validUntil`. |
| `TimetableEntryPatch` | `courseId`, `title`, `dayOfWeek`, `startTime`, `endTime`, `location`, `validFrom`, `validUntil` 각각 `PatchField<T>` | 부분 수정. `dayOfWeek`, `startTime`, `endTime`, `title`에는 null 불허. 변경 뒤 시간·기간 검증. |
| `AppSettings` | `showCompletedItems: Boolean`, `syncIntervalMinutes: Int`, `autoAnalyze: Boolean`, `autoApplyAIResult: Boolean`, `googleAutoExport: Boolean`, `googleAutoExportCalendarId: String?` | 자동 내보내기는 **앱에서 직접 생성한 일정(`Origin.USER`)만** 대상. 학교 수집 일정은 사용자가 수동으로 내보낼 수 있음. |
| `AppSettingsPatch` | `AppSettings`의 각 필드를 `PatchField<T>`로 표현 | 설정값 하나만 변경해도 다른 값 유지. |
| `NotificationSettings` | `dueReminderEnabled: Boolean`, `dueReminderMinutes: Int`, `assignmentAvailableEnabled: Boolean`, `newAssignmentEnabled: Boolean`, `classChangeEnabled: Boolean`, `dateChangeApprovalNotificationEnabled: Boolean` | 기본 알림 활성화. |
| `NotificationSettingsPatch` | `NotificationSettings` 각 필드를 `PatchField<T>`로 표현 | 마감 알림은 시각이 확인된 미완료 과제만 대상. |
| `ExtractedItem` | `kind: ExtractedKind`, `title: String?`, `description: String?`, `submissionFormat: String?`, `submissionMethod: String?`, `gradingCriteria: String?`, `availableAt: KstMoment?`, `dueAt: KstMoment?`, `evidence: List<Evidence>` | Figma 과제 상세의 세 분리 항목을 분석 결과에도 보존. 근거로 확정할 수 없으면 null. 불완전한 결과는 수동검토. |
| `ManualReviewDecision` | `action: APPROVE / EDIT / IGNORE`, `patch: ExtractedItemPatch?` | `EDIT`일 때만 `patch` 필수. |
| `AIConfigPublic` | `baseUrl: String?`, `modelId: String?`, `configured: Boolean`, `connected: Boolean`, `mode: AIMode = DISABLED`, `readiness: AIReadiness = DISABLED` | 선택한 AI 모드의 설정과 준비 상태. API Key 등 비밀정보 없음. |
| `ConnectionTestResult` | `connected: Boolean`, `capabilities: Set<String>`, `errorCode: ApiErrorCode?` | 실제 테스트 결과 기반 기능 목록. |
| `GoogleCalendarInfo` | `id: String`, `name: String`, `primary: Boolean`, `writable: Boolean` | 내보내기는 writable 대상만. |
| `ExportReceipt` | `localItemId: String`, `googleEventId: String?`, `state: ExportState`, `errorCode: ApiErrorCode?` | `SENT / QUEUED / FAILED / NOT_APPLICABLE`. |
| `DeleteReceipt` | `localDeleted: Boolean`, `remoteState: ExportState` | 원격 오프라인이면 로컬 삭제 후 원격 삭제를 큐잉. |
| `AuthLaunchRequest` | `requestId: String`, `launchIntent: Intent?`, `intentSender: IntentSenderRequest?` | Android 인증 런처에 전달. 플랫폼 요구에 따라 한쪽만 설정. |
| `GoogleAuthResult` | `requestId: String`, `resultCode: Int`, `data: Intent?` | Android 인증 결과를 서비스에 반환. |

`Provider = SCHOOL / GOOGLE_CALENDAR / AI`; `Origin = USER / SCHOOL / GOOGLE`; `CalendarKind = ASSIGNMENT / CLASS / EVENT / LECTURE`; `NoteKind = LOCAL / SCHOOL_ASSIGNMENT / SCHOOL_RESOURCE`; `FolderKind = SCHOOL / LOCAL`; `NoteLinkedKind = ASSIGNMENT / RESOURCE`; `SyncState = IDLE / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / AUTH_REQUIRED / UNSUPPORTED`; `ReviewStatus = OPEN / APPROVED / EDITED / IGNORED`. 별도 UI용 History 테이블은 만들지 않음.

## 2. 함수 계약 (Android local API)

### 2.1 학교/과제/원본 자료

| Function | args: 이름·타입·기본값 | return | 역할 |
|---|---|---|---|
| `observeCourses` | 없음 | `Flow<List<Course>>` | 로컬 과목 목록 |
| `getCourse` | `courseId: String` | `ApiResult<Course>` | 과목 상세 |
| `setCourseSyncEnabled` | `courseId: String, enabled: Boolean` | `ApiResult<Unit>` | 과목별 동기화 선택 |
| `observeAssignments` | `courseId: String? = null, includeCompleted: Boolean = true, includeHidden: Boolean = false` | `Flow<List<Assignment>>` | 과제 목록·상태 |
| `getAssignment` | `assignmentId: String` | `ApiResult<Assignment>` | 과제 상세: 제출 형식·제출 방법·평가 기준 Nullable 필드 포함 |
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

### 2.3 직접 등록 시간표 (11/18 회의 확정)

**기능 범위:** Everytime 시간표 API 연동은 사용하지 않고 사용자가 수업을 직접 입력한다. `TimetableEntry`는 매주 반복하는 원본 데이터이고, `observeCalendar()`는 요청한 기간에 해당하는 수업만 전개하여 `CalendarItem`으로 표시한다.

| Function | args | return | 역할 |
|---|---|---|---|
| `observeTimetableEntries` | 없음 | `Flow<List<TimetableEntry>>` | 기기에 저장된 시간표 원본 목록. |
| `getTimetableEntry` | `entryId: String` | `ApiResult<TimetableEntry>` | 시간표 항목 상세(수정 화면용). |
| `createTimetableEntry` | `input: TimetableEntryInput` | `ApiResult<TimetableEntry>` | 직접 입력한 매주 반복 수업 저장. |
| `updateTimetableEntry` | `entryId: String, patch: TimetableEntryPatch` | `ApiResult<TimetableEntry>` | 시간표의 요일·시각·장소·반복 기간 수정. |
| `deleteTimetableEntry` | `entryId: String` | `ApiResult<Unit>` | 반복 수업 항목 삭제. |

**시간표 → 캘린더 대응**
- `observeCalendar(range, ...)`는 날짜 범위에 해당하는 시간표 수업을 `origin=USER`, `kind=CLASS`, `timetableEntryId=<원본 ID>`인 `CalendarItem`으로 투영한다. `startAt`/`endAt`은 해당 일자의 KST 시각, `location`은 입력된 강의실 위치이다.
- 전개된 일정 `id`는 `entryId + 해당 날짜`로 안정적으로 식별되도록 구성하여 `getCalendarItem(id)`가 해당 건을 반환할 수 있게 한다. 일회성 직접 일정은 기존 `createLocalEvent`로 관리한다.
- 반복 수업 전체를 수정하려면 시간표 CRUD를 사용한다. `updateCalendarItem`/`deleteLocalEvent`에 전개된 반복 수업 ID를 전달하면 `UNSUPPORTED`를 반환하며, 단일 회차 예외 수정은 이번 범위에 포함하지 않는다.
- **토·일요일 포함, 수업 시간 겹침 허용.** 겹칠 때 반반 나누어 표시하는 것과 과목별 색상 자동 배정은 프론트 표현 정책이며, 백엔드는 양쪽 항목을 모두 반환한다.
- 기간을 지정하지 않은 수업은 매주 반복한다. 중복 출력 방지를 위해 같은 시간표 항목과 날짜는 하나의 인스턴스만 생성한다.

### 2.4 Notes (Figma 확정 기능)

**화면 기준:** `Note Screen_Course`는 과목별/직접 생성한 폴더의 Notes 카드 및 개수, `Note Screen_all_tasks`는 전체 Notes, `Note Screen_Each_course`는 선택한 폴더의 날짜별 목록과 **No Dates** 그룹을 표시한다. `+`로 Notes 또는 수동 폴더를 추가할 수 있다. 학교 과제·공지에서 생성된 항목도 동일한 Notes 목록에 표시한다.

| Function | args | return | 역할 |
|---|---|---|---|
| `observeNoteCourseSummaries` | 없음 | `Flow<List<NoteCourseSummary>>` | **Courses** 탭 폴더 카드·표시 이름 및 Notes 개수. 숨김을 제외하고 완료 항목은 포함한 개수. |
| `observeNotes` | `courseId: String? = null, includeCompleted: Boolean = true, includeHidden: Boolean = false, folderId: String? = null` | `Flow<List<NoteItem>>` | `courseId`와 `folderId`가 모두 null이면 **All Notes**. 폴더 카드 선택 시 `folderId`로 조회. `courseId` 조회는 기존 호출 호환용. 두 필터를 동시에 지정하면 `folderId`를 우선 적용. `dateAt == null`은 **No Dates**. |
| `getNote` | `noteId: String` | `ApiResult<NoteItem>` | Notes 항목 상세. `link`가 있으면 `getAssignment` / `getResource`로 원문 상세 이동 가능. |
| `createLocalNote` | `input: LocalNoteInput` | `ApiResult<NoteItem>` | 사용자가 메모 직접 추가. 날짜와 과목은 선택 사항. |
| `updateLocalNote` | `noteId: String, patch: LocalNotePatch` | `ApiResult<NoteItem>` | **사용자 생성 메모만** 수정. 학교 원본은 기존 원본·override API 사용. |
| `deleteLocalNote` | `noteId: String` | `ApiResult<Unit>` | **사용자 생성 메모만** 삭제. 학교 유래 항목의 표시 제외는 `setSchoolItemHidden` 이용. |
| `setNoteCompleted` | `noteId: String, completed: Boolean` | `ApiResult<NoteItem>` | Notes 목록의 체크 원형 처리. 과제 링크가 있으면 `setUserCompleted`와 동일한 상태를 갱신. 일반 메모·학교 공지 기반 항목은 로컬 완료 상태를 갱신. |
| `createNoteFolder` | `name: String` | `ApiResult<NoteFolder>` | **Courses의 + 버튼**에 대응하는 사용자 지정 폴더 생성. 빈 이름 불허. |
| `renameNoteFolder` | `folderId: String, displayName: String` | `ApiResult<NoteFolder>` | 학교 과목 기본 폴더·직접 생성한 폴더 모두 표시 이름 변경. 학교 `Course.name`은 변경하지 않음. |

**데이터 매핑 원칙**
- `SCHOOL_ASSIGNMENT`는 기존 `Assignment`를 투영하여 표시한다. `dateAt = Assignment.dueAt`, `completed = schoolCompleted || userCompleted`, `link = ASSIGNMENT(id)`로 정의. 기존 과제를 Notes용으로 별도 복제하지 않음. `courseId`에 연동된 기본 Notes 폴더를 `folderId`에 기록한다.
- `SCHOOL_RESOURCE`는 수집한 공지 등에서 만들어진 Notes 항목이다. `folderId`는 해당 과목의 기본 폴더. 확인된 날짜가 있으면 `dateAt`에 설정하고, 없거나 모호하면 null을 유지하여 **No Dates**에 표시하며 필요한 경우 기존 `ReviewItem`으로 수동검토. 원본 URL은 `source.url`로 제공.
- `LOCAL`은 사용자가 직접 입력한 메모다. 날짜가 없는 메모도 생성 가능하다. 기본 학교 폴더를 선택하면 `courseId`와 `folderId`를 함께 연결하고, 직접 생성한 폴더를 선택하면 `courseId = null`, `folderId`만 설정할 수 있다. `folderId`가 명시되면 해당 폴더의 과목 연결 정보를 우선하여 `courseId`를 정규화한다. 둘 다 없으면 All Notes에서만 조회된다.
- 학교 과목이 새로 수집되면 기본 Notes 폴더를 과목명으로 자동 생성/추천한다. 사용자가 폴더를 재명명하면 `displayName` override를 로컬에 저장하여 이후 과목명 갱신·재동기화에도 유지한다. 원본 과목명은 `Course.name` / `courseName`으로 별도 유지한다.
- 목록은 날짜가 있는 항목을 날짜 오름차순으로 묶고, 날짜 없는 항목은 마지막 **No Dates** 그룹에 표시할 수 있도록 한다. `D-1` 등 상대 날짜 문자열, 과목별 카드 스타일, 탭 라벨은 프론트가 계산·표시한다.
- Notes 항목은 그 자체로 캘린더 일정이 아니다. `dateAt`은 선택적 표시/분류 날짜이며, **날짜 없는 Notes를 임의로 `CalendarItem.startAt`에 끼워 맞추지 않는다.** 사용자가 별도의 일정을 만들 때는 기존 `createLocalEvent` 사용.
- 학교 원본이 실제로 삭제되면 해당 원본에 연결된 Notes 표시도 제거한다. 사용자 직접 작성 메모는 학교 원본과 별개로 보존한다. 별도 History는 만들지 않는다.

### 2.5 연결·인증

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

### 2.6 동기화

| Function | args | return | 역할 |
|---|---|---|---|
| `startSync` | `provider: Provider = SCHOOL, courseIds: List<String>? = null` | `ApiResult<String>` (`jobId`) | 기본 **증분 동기화**. `null`은 활성화된 모든 과목 |
| `observeSyncJob` | `jobId: String` | `Flow<JobState>` | 진행 상황 관찰 |
| `observeSyncStatus` | `provider: Provider` | `Flow<SyncStatus>` | 마지막 동기화·오류·진행 상태 |
| `observeCourseSyncStatus` | `courseId: String` | `Flow<SyncStatus>` | 부분 실패 과목별 확인 |
| `cancelSync` | `jobId: String` | `ApiResult<Unit>` | 진행 작업 중단, 체크포인트 보존 |
| `retrySync` | `jobId: String` | `ApiResult<String>` (`newJobId`) | 실패/중단 작업의 남은 부분 재시도 |

주기 60분 기본, 사용자 변경 가능(`>=15`분). 앱 실행만으로 강제 동기화하지 않음. 읽을 수 있는 최소 원격 목록/메타데이터를 비교해 신규·수정·삭제 탐지, 실제 내용/첨부파일 재다운로드 최소화. 파일 원문 변경은 수정 공지 등 확인 가능한 신호가 있을 때 재확인. 원본 삭제는 **목록을 정상적으로 확인해 부재가 확실할 때만** 로컬에도 반영. 일부 과목 실패 시 기존 데이터 유지 + 해당 과목 오류 표시. WebView 필요 작업까지 백그라운드 우선 시도하고 불가능한 경우 앱 실행 상태에서 처리.

### 2.7 AI·수동검토

| Function | args | return | 역할 |
|---|---|---|---|
| `getAIConfig` | 없음 | `ApiResult<AIConfigPublic>` | 선택 모드, 준비 상태, endpoint/model 조회. API Key 미반환 |
| `setAIMode` | `mode: AIMode` | `ApiResult<Unit>` | AI 연결 방식 선택. 미구현 구현체의 기본 응답은 `UNSUPPORTED` |
| `setAIConfig` | `baseUrl: String, apiKey: String, modelId: String? = null` | `ApiResult<Unit>` | OpenAI 호환 endpoint를 저장하고 `PERSONAL_SERVER` 선택 |
| `testAIConnection` | 없음 | `ApiResult<ConnectionTestResult>` | 모델 API 접근 테스트 |
| `analyzeResource` | `resourceId: String` | `ApiResult<String>` (`jobId`) | 단일 자료 분석(일괄 분석 API 없음) |
| `observeAnalysisJob` | `jobId: String` | `Flow<JobState>` | 분석 진행 상태 |
| `getAnalysisResult` | `jobId: String` | `ApiResult<AnalysisResult>` | 추출 결과·근거 URL·인용 구간 |
| `observeManualReviews` | `status: ReviewStatus? = OPEN` | `Flow<List<ReviewItem>>` | 수동검토 목록 |
| `resolveManualReview` | `reviewId: String, decision: ManualReviewDecision` | `ApiResult<Unit>` | 승인·수정·무시 결정 저장 |

#### 연결 방식과 화면 연결

| `AIMode` | 용도 | 첫 구현 범위 |
|---|---|---|
| `ON_DEVICE` | Android 기기에서 모델을 직접 실행 | 선택 모듈 `:on-device-litert`와 기기에 공급한 CPU용 `.litertlm` 모델로 실행. 런타임이 없으면 `UNAVAILABLE` |
| `PERSONAL_SERVER` | 사용자가 설정한 OpenAI 호환 서버 | 주소, 선택 API Key와 모델 설정, 연결 테스트, 자료 분석 |
| `CHATGPT_SUBSCRIPTION` | 공식 지원 시 ChatGPT 구독 연결 | 구독 추론 권한과 공식 OAuth 콜백·계정 동작 검증 전까지 `UNAVAILABLE` |
| `DISABLED` | AI 사용 안 함 | 기본 모드. AI 호출 없이 기존 앱 기능 사용 |

기기 내 모델은 앱이 기기용 런타임과 설치된 모델을 연결할 때 공급한다. 모델 파일 선택·설치는 앱 통합에서 구성한다. ChatGPT 구독의 계정 연결, 이용 권한·모델 조회와 연결 해제 API는 공식 OAuth 연동을 검증할 때 추가한다.

`AIReadiness`는 `DISABLED`(꺼짐), `UNAVAILABLE`(현재 빌드에서 미지원), `NEEDS_SETUP`(설정 없음), `CONFIGURED`(설정 완료, 연결 확인 필요), `CONNECTED`(현재 설정으로 연결 테스트 성공)를 구분한다. `setAIMode()`는 미지원 모드도 선택할 수 있게 하며, 해당 모드는 요청을 시작하지 않는다. 설정 화면은 `getAIConfig()` 결과로 선택 모드와 상태를 표시하고, 저장 또는 연결 테스트 후 상태를 다시 읽는다. 자료 분석 버튼은 지원되고 설정이 완료된 모드에서 제공한다. 데모는 연결 상태와 분석 결과를 각각 모의로 제공한다.

모드를 변경하면 진행 중인 분석 작업을 `CANCELLED`로 전환하고 뒤늦게 받은 결과를 폐기한다. 서버 설정과 이미 저장된 결과·검토 내역은 유지한다. 분석을 시작할 수 없는 모드는 `UNSUPPORTED`, 필요한 개인 서버 설정이 비어 있으면 `INVALID_ARGUMENT`, 분석 중 추가 실행은 재시도 가능한 `CONFLICT`, 분석할 본문이 없으면 `SOURCE_UNAVAILABLE`로 응답한다.

개인 서버에는 주소와 선택 모델을 표시하고 API Key는 마스킹해 입력받는다. 키는 앱 전용 저장소에 암호화하며 HTTPS 연결에서만 사용한다. `apiKey`를 빈 문자열로 저장하면 인증 헤더를 생략한다. 평문 HTTP는 키가 없는 루프백 또는 사설 IP 주소에 한하며 Android 네트워크 정책이 허용해야 한다. 서버 경로는 `/v1/models`와 `/v1/chat/completions`를 제공해야 한다. 주소에 프록시 접두 경로나 `/v1`을 포함할 수 있다. 모델을 지정하지 않으면 서버가 제공한 모델 목록의 첫 모델을 사용한다. 연결 테스트는 모델 목록과 선택 모델을 확인한다.

#### 자료 분석과 수동 검토

자료 상세는 `analyzeResource(resourceId)`의 작업 ID로 `observeAnalysisJob()`을 구독한다. `JobState`는 작업 진행 상태이고, 작업 성공 후 `getAnalysisResult()`의 `AnalysisState`로 결과 또는 수동 검토를 표시한다. 후보 근거는 `Evidence`의 출처 URL, 인용, 페이지·위치에서 확인 가능한 값을 사용한다.

첫 구현은 자료의 제목과 본문을 사용한다. 분석 응답마다 후보를 최대 한 개 만들고 `MANUAL_REVIEW`를 반환한다. 후보의 제목과 본문에서 확인한 인용이 있으면 `NEEDS_CONFIRMATION`, 후보나 근거가 부족하면 `INCOMPLETE`를 기록한다. 출처 자료 URL은 검토 화면을 위해 로컬에서 붙인다. 승인·수정·무시는 검토 상태를 저장하며 일정·과제 반영은 후속 작업이다. 승인·수정에는 한 개의 후보가 필요하고 수정에는 `ExtractedItemPatch`도 필요하다. 후보가 여러 개인 경우의 수정 대상 식별 방식은 별도 계약으로 합의해야 한다.

자동 분석과 자동 반영은 별개의 설정이며 기본값은 둘 다 꺼짐이다. 자동 실행을 연결할 때 자동 분석은 새 자료에 적용하고, 백그라운드에서 완료할 수 없으면 알림 후 앱 실행 시 처리한다. 최신 원본을 확인할 수 있는 후보를 우선하고, 근거가 부족하거나 데이터가 충돌하면 수동 검토로 보낸다. 사용자의 수동 수정은 자동 분석 결과보다 우선한다.

### 2.8 Google Calendar

| Function | args | return | 역할 |
|---|---|---|---|
| `getGoogleCalendars` | 없음 | `ApiResult<List<GoogleCalendarInfo>>` | 접근 가능한 캘린더 조회 |
| `observeGoogleEvents` | `calendarId: String, range: DateRange` | `Flow<List<CalendarItem>>` | 마지막으로 조회한 Google 일정 캐시. 실제 새로고침은 동기화 작업 |
| `exportToGoogle` | `localItemIds: List<String>, calendarId: String` | `ApiResult<ExportReceipt>` | 사용자의 명시적 내보내기 |
| `setAutoExport` | `enabled: Boolean, calendarId: String?` | `ApiResult<Unit>` | 자동 내보내기 opt-in 및 대상 Google 캘린더 선택. 대상 일정은 `Origin.USER`만 |
| `syncExportedLocalEvent` | `localItemId: String` | `ApiResult<ExportReceipt>` | Google에 내보낸 **앱 생성 일정**의 수정 반영 |
| `deleteExportedLocalEvent` | `localItemId: String` | `ApiResult<ExportReceipt>` | Google에 내보낸 **앱 생성 일정**의 삭제 반영 |

Google 일정은 읽기 중심. **자동 내보내기는 앱에서 직접 생성한 일정만 대상**이고, 학교에서 수집한 과제·강의는 사용자가 버튼으로 수동 내보내기 가능. Google에서 읽은 원본 일정은 앱이 임의 변경/삭제하지 않음. 사용자 생성 일정이 이미 내보내졌다면 사용자 수정·삭제가 원격에도 반영될 수 있음. 중복 내보내기 방지를 위해 `localItemId ↔ googleEventId` 매핑을 로컬에 보존. 원격 접근이 안 되면 수정·삭제를 재시도 큐에 보관.

### 2.9 사용자 설정 및 알림

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
9. **Notes 화면**: 폴더 카드 수/전체 Notes/폴더별 Notes/사용자 메모 CRUD/체크 완료/No Dates를 포함. 기본 폴더명 자동 생성과 표시명 변경 지원. 학교 과제 완료 표시는 기존 `Assignment`와 동일한 값이며, 수집 공지와 메모의 날짜가 불명확해도 `NoteItem.dateAt = null`로 저장 가능.
10. **직접 등록 시간표**: 매주 반복 수업의 원본은 `TimetableEntry`. 주말과 겹치는 수업을 포함하여 캘린더에 날짜별로 투영하며, 단일 회차 수동 수정은 이번 범위에서 제외.
11. **과제 상세 데이터**: `submissionFormat`, `submissionMethod`, `gradingCriteria`는 근거 확인 시에만 채우며, 정보가 없으면 `null`. 프론트는 Figma의 각 섹션에 값 또는 미확인 상태를 표시.

## 4. 최종 결정 반영

- **A 확정:** Google Calendar 자동 내보내기(opt-in)는 앱에서 직접 만든 일정(`Origin.USER`)만 대상. 학교 수집 일정은 수동 내보내기 허용. 자동 내보내기 대상 선택 설정은 두지 않음.
- **B 범위 제외:** 학교 계정 변경·연결 해제 및 그에 따른 로컬 데이터 유지/삭제 정책은 일반적인 사용 시나리오에 포함하지 않음. `disconnectSchool()` API를 현재 범위에서 제거. 세션이 일시적으로 만료되거나 로그인이 풀린 경우에는 `AUTH_EXPIRED`, `refreshSchoolAuth()` 또는 필요 시 `connectSchool()` 재인증을 사용. 데이터 삭제 또는 계정 전환 동작은 정의하지 않음.

## 5. v0.4 → v0.5 변경 요약

- **시간표 직접 등록**: `TimetableEntry`, `TimetableEntryInput`, `TimetableEntryPatch`와 시간표 CRUD/조회 함수 **5개** 추가. Everytime API 없이 직접 입력하며 `observeCalendar`에 주간 반복 수업을 투영. 주말·겹치는 수업 허용.
- **Notes 폴더명 자동 생성·사용자 변경**: `NoteFolder`, `FolderKind`와 `createNoteFolder`, `renameNoteFolder` 함수 **2개** 추가. Notes DTO에 `folderId`, `displayName` 계약 반영. 학교 과목명은 원본대로 보존.
- **과제 상세 표시 필드**: `Assignment`와 `ExtractedItem`에 `submissionFormat`, `submissionMethod`, `gradingCriteria` 세 Nullable 필드 추가. 기존 `getAssignment()` 반환 모델로 확인 가능.
- 기존 과제/공지 ↔ Notes 연결, 학교 인증, 증분 동기화, AI·수동검토, Google Calendar 및 알림 정책은 v0.4대로 유지.
- 11/18 회의의 나머지 UI 결정(주말 표출, 스크롤 힌트, 겹치는 수업 분할 배치, 색상 자동 지정, 날짜 선택 후 목록 스크롤)은 프론트 디자인·표현 동작으로 구분.

추가 사용자 정책 결정 항목 없음. 수동 폴더 생성과 반복 수업의 일회성 예외 편집 제외는 이번 계약에 대한 최소 구현 결정이며, 사용자가 별도로 확정한 세부 요구사항은 아님. 내부 저장용 private 필드 및 플랫폼별 어댑터는 구현팀이 결정하되, 프론트에 공개된 계약은 유지해야 함.

## 6. Kotlin 코드에서 사용하는 보조 타입

공개 패키지는 `org.deepmatcha.mad.contract`입니다. `MadDependencies`가 학교, 캘린더, 시간표, Notes, 인증, 동기화, 분석, Google Calendar, 설정 인터페이스를 제공합니다.

- `AuthState`는 `DISCONNECTED / AUTHENTICATED / EXPIRED`, `JobKind`는 `SYNC / ANALYSIS`를 사용합니다.
- `AnalysisState`는 `SUCCEEDED / MANUAL_REVIEW / FAILED`, `ExtractedKind`는 `EVENT / TODO`를 사용합니다.
- `ExtractedItemPatch`는 `ExtractedItem`의 각 필드를 `PatchField`로 표현합니다. `ApiResult.Failure.detail`은 선택적인 진단 문자열이며 비밀정보를 담지 않습니다.
