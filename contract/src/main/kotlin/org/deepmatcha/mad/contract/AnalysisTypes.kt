package org.deepmatcha.mad.contract

import java.time.Instant

enum class AIMode { ON_DEVICE, PERSONAL_SERVER, CHATGPT_SUBSCRIPTION, DISABLED }

enum class AIReadiness { DISABLED, UNAVAILABLE, NEEDS_SETUP, CONFIGURED, CONNECTED }

enum class AnalysisState { SUCCEEDED, MANUAL_REVIEW, FAILED }
enum class ExtractedKind { EVENT, TODO }
enum class ReviewReason { UNAVAILABLE, INCOMPLETE, CONFLICT, ANALYSIS_FAILED, NEEDS_CONFIRMATION }
enum class ReviewStatus { OPEN, APPROVED, EDITED, IGNORED }
enum class ReviewAction { APPROVE, EDIT, IGNORE }

data class AIConfigPublic(
    val baseUrl: String?,
    val modelId: String?,
    val configured: Boolean,
    val connected: Boolean,
    val mode: AIMode = AIMode.DISABLED,
    val readiness: AIReadiness = AIReadiness.DISABLED,
)

data class Evidence(
    val sourceUrl: String?,
    val quote: String?,
    val page: Int?,
    val locator: String?,
)

data class ExtractedItem(
    val kind: ExtractedKind,
    val title: String?,
    val description: String?,
    val submissionFormat: String?,
    val submissionMethod: String?,
    val gradingCriteria: String?,
    val availableAt: KstMoment?,
    val dueAt: KstMoment?,
    val evidence: List<Evidence>,
)

data class ExtractedItemPatch(
    val kind: PatchField<ExtractedKind> = PatchField.Unset,
    val title: PatchField<String?> = PatchField.Unset,
    val description: PatchField<String?> = PatchField.Unset,
    val submissionFormat: PatchField<String?> = PatchField.Unset,
    val submissionMethod: PatchField<String?> = PatchField.Unset,
    val gradingCriteria: PatchField<String?> = PatchField.Unset,
    val availableAt: PatchField<KstMoment?> = PatchField.Unset,
    val dueAt: PatchField<KstMoment?> = PatchField.Unset,
    val evidence: PatchField<List<Evidence>> = PatchField.Unset,
)

data class AnalysisResult(
    val resourceId: String,
    val state: AnalysisState,
    val candidates: List<ExtractedItem>,
    val evidence: List<Evidence>,
    val analyzedAt: Instant?,
)

data class ReviewItem(
    val id: String,
    val resourceId: String,
    val reason: ReviewReason,
    val candidates: List<ExtractedItem>,
    val status: ReviewStatus,
)

/** The service requires a patch when the action is EDIT. */
data class ManualReviewDecision(
    val action: ReviewAction,
    val patch: ExtractedItemPatch? = null,
)
