
package org.deepmatcha.mad.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.deepmatcha.mad.MadApplication
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.ui.components.Button
import org.deepmatcha.mad.ui.components.DeadlineBadge
import org.deepmatcha.mad.ui.theme.MoaBlue
import org.deepmatcha.mad.ui.theme.MoaRed
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DetailBackground = Color(0xFFF3F5F6)
private val DetailSheetColor = Color(0xFFFAFAFC)
private val DetailText = Color(0xFF171923)
private val DetailGray = Color(0xFF9297A2)

@Composable
fun TaskDetailScreen(
    assignmentId: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val dependencies = remember(context) {
        (context.applicationContext as MadApplication).dependencies
    }

    var assignment by remember(assignmentId) {
        mutableStateOf<Assignment?>(null)
    }

    var courseName by remember(assignmentId) {
        mutableStateOf("과목")
    }

    var isLoading by remember(assignmentId) {
        mutableStateOf(true)
    }

    var isSaving by remember(assignmentId) {
        mutableStateOf(false)
    }

    var errorMessage by remember(assignmentId) {
        mutableStateOf<String?>(null)
    }

    val scope = rememberCoroutineScope()

    // 과제 및 과목 정보 불러오기
    LaunchedEffect(assignmentId, dependencies) {
        isLoading = true
        errorMessage = null

        when (
            val result = dependencies.school.getAssignment(assignmentId)
        ) {
            is ApiResult.Success -> {
                assignment = result.data

                when (
                    val courseResult = dependencies.school.getCourse(
                        result.data.courseId
                    )
                ) {
                    is ApiResult.Success -> {
                        courseName = courseResult.data.name
                    }

                    is ApiResult.Failure -> {
                        courseName = "과목 정보 없음"
                    }
                }
            }

            is ApiResult.Failure -> {
                errorMessage = "과제 정보를 불러오지 못했어요."
            }
        }

        isLoading = false
    }

    val currentAssignment = assignment

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DetailBackground)
    ) {

        // 상단 뒤로 가기 / 과목명 / 마감 배지
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 18.dp,
                    end = 24.dp,
                    top = 18.dp,
                    bottom = 20.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "뒤로 가기",
                    tint = DetailText,
                    modifier = Modifier.size(29.dp)
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = courseName,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .shadow(
                            elevation = 5.dp,
                            shape = RoundedCornerShape(100.dp)
                        )
                        .clip(RoundedCornerShape(100.dp))
                        .background(MoaBlue.copy(alpha = 0.88f))
                        .padding(
                            horizontal = 15.dp,
                            vertical = 10.dp
                        ),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            currentAssignment?.dueAt?.let { due ->
                DeadlineBadge(
                    dueDate = due.date,
                    dueTime = due.time
                )
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MoaBlue)
                }
            }

            currentAssignment == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage
                            ?: "과제를 찾을 수 없어요.",
                        color = DetailGray
                    )
                }
            }

            else -> {

                // 상단 과제 제목
                Text(
                    text = currentAssignment.title,
                    modifier = Modifier.padding(
                        horizontal = 29.dp
                    ),
                    color = DetailText,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))

                // 제출 마감 날짜
                Text(
                    text = formatDueDate(
                        currentAssignment.dueAt
                    ),
                    modifier = Modifier.padding(
                        horizontal = 29.dp
                    ),
                    color = DetailGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(48.dp))

                // 날짜 아래에서 시작하는 큰 Bottom Sheet
                val sheetShape = RoundedCornerShape(
                    topStart = 32.dp,
                    topEnd = 32.dp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .shadow(
                            elevation = 12.dp,
                            shape = sheetShape,
                            clip = false
                        )
                        .clip(sheetShape)
                        .background(DetailSheetColor)
                ) {

                    // 과제 설명과 제출 정보
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(
                                start = 34.dp,
                                end = 34.dp,
                                top = 38.dp,
                                bottom = 24.dp
                            )
                    ) {

                        Text(
                            text = currentAssignment.description
                                ?.takeIf { it.isNotBlank() }
                                ?: "과제 설명이 제공되지 않았어요.",
                            color = DetailText,
                            fontSize = 15.sp,
                            lineHeight = 24.sp
                        )

                        Spacer(Modifier.height(72.dp))

                        TaskInfoRow(
                            title = "제출 형식",
                            value = currentAssignment.submissionFormat
                        )

                        Spacer(Modifier.height(32.dp))

                        TaskInfoRow(
                            title = "제출 방법",
                            value = currentAssignment.submissionMethod
                        )

                        Spacer(Modifier.height(32.dp))

                        TaskInfoRow(
                            title = "평가 기준",
                            value = currentAssignment.gradingCriteria
                        )
                    }

                    // 패널 하단에 고정되는 완료 버튼
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(
                                horizontal = 34.dp,
                                vertical = 22.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = MoaRed,
                                fontSize = 13.sp
                            )
                        }

                        // 기존 공통 Button 컴포넌트 사용
                        Button(
                            text = when {
                                currentAssignment.schoolCompleted ->
                                    "제출 완료"

                                currentAssignment.userCompleted ->
                                    "완료 취소"

                                else -> "완료하기"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !currentAssignment.schoolCompleted,
                            isLoading = isSaving,
                            onClick = {
                                if (!isSaving) {
                                    scope.launch {
                                        isSaving = true
                                        errorMessage = null

                                        try {
                                            val result = dependencies.school
                                                .setUserCompleted(
                                                    assignmentId,
                                                    !currentAssignment.userCompleted
                                                )

                                            when (result) {
                                                is ApiResult.Success -> {
                                                    assignment = result.data
                                                }

                                                is ApiResult.Failure -> {
                                                    errorMessage =
                                                        "완료 상태를 변경하지 못했어요."
                                                }
                                            }
                                        } catch (e: Exception) {
                                            errorMessage =
                                                "완료 상태를 변경하지 못했어요."
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// 제출 정보 한 줄
@Composable
private fun TaskInfoRow(
    title: String,
    value: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {

        Icon(
            imageVector = Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = DetailText,
            modifier = Modifier.size(30.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = DetailText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = value?.takeIf { it.isNotBlank() }
                    ?: "안내되어 있지 않아요.",
                color = DetailGray,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

// 마감 날짜 포맷
private fun formatDueDate(
    dueAt: KstMoment?
): String {
    if (dueAt == null) {
        return "마감일이 안내되지 않았어요."
    }

    val date = dueAt.date.format(
        DateTimeFormatter.ofPattern(
            "yyyy년 M월 d일 (E)",
            Locale.KOREAN
        )
    )

    val time = dueAt.time?.format(
        DateTimeFormatter.ofPattern("HH:mm")
    ) ?: "시간 미정"

    return "$date $time"
}
