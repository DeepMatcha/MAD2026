//메인 화면 관리자입니당
package org.deepmatcha.mad.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.deepmatcha.mad.ui.components.BottomBar
import org.deepmatcha.mad.ui.components.MoaTab
import org.deepmatcha.mad.ui.home.HomeScreen
import org.deepmatcha.mad.ui.tasks.TaskDetailScreen

@Composable
fun MoaMainScreen() {
    var selectedTab by rememberSaveable {
        mutableStateOf(MoaTab.HOME)
    }

    var selectedAssignmentId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    // 안드로이드 시스템 뒤로 가기 처리
    BackHandler(
        enabled = selectedAssignmentId != null
    ) {
        selectedAssignmentId = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 메인 탭 화면
            when (selectedTab) {
                MoaTab.HOME -> HomeScreen(
                    onTaskClick = { assignmentId ->
                        selectedAssignmentId = assignmentId
                    }
                )

                MoaTab.WEEKLY -> ScreenPlaceholder(
                    "Weekly",
                    "시간표 화면을 여기에 구현할 예정"
                )

                MoaTab.NOTES -> ScreenPlaceholder(
                    "Notes",
                    "노트 화면을 여기에 구현할 예정"
                )
            }

            // 과제 상세 화면은 메인 화면 위에 표시
            val assignmentId = selectedAssignmentId

            if (assignmentId != null) {
                TaskDetailScreen(
                    assignmentId = assignmentId,
                    onBackClick = {
                        selectedAssignmentId = null
                    }
                )
            }
        }

        // 상세 화면에서는 BottomBar 숨김
        if (selectedAssignmentId == null) {
            BottomBar(
                selectedTab = selectedTab,
                onTabSelected = {
                    selectedTab = it
                },
                modifier = Modifier.align(
                    Alignment.BottomCenter
                )
            )
        }
    }
}

@Composable
private fun ScreenPlaceholder(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
