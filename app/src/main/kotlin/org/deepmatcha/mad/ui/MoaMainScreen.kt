
package org.deepmatcha.mad.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.deepmatcha.mad.contract.NoteItem
import org.deepmatcha.mad.ui.components.BottomBar
import org.deepmatcha.mad.ui.components.MoaTab
import org.deepmatcha.mad.ui.home.HomeScreen
import org.deepmatcha.mad.ui.notes.FolderNotesScreen
import org.deepmatcha.mad.ui.notes.NotesScreen
import org.deepmatcha.mad.ui.notes.NotesTab
import org.deepmatcha.mad.ui.tasks.TaskDetailScreen

@Composable
fun MoaMainScreen() {
    var selectedTab by rememberSaveable {
        mutableStateOf(MoaTab.HOME)
    }

    var selectedNotesTab by rememberSaveable {
        mutableStateOf(NotesTab.COURSES)
    }

    var selectedFolderId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var selectedAssignmentId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var previewNote by remember {
        mutableStateOf<NoteItem?>(null)
    }

    var showAddNotice by remember {
        mutableStateOf(false)
    }

    BackHandler(
        enabled = selectedAssignmentId != null ||
                selectedFolderId != null
    ) {
        when {
            selectedAssignmentId != null -> {
                selectedAssignmentId = null
            }

            selectedFolderId != null -> {
                selectedFolderId = null
            }
        }
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
            when (selectedTab) {
                MoaTab.HOME -> {
                    HomeScreen(
                        onTaskClick = { assignmentId ->
                            selectedAssignmentId = assignmentId
                        }
                    )
                }

                MoaTab.WEEKLY -> {
                    ScreenPlaceholder(
                        title = "Weekly",
                        description = "시간표 화면 구현 예정"
                    )
                }

                MoaTab.NOTES -> {
                    val folderId = selectedFolderId

                    if (folderId == null) {
                        NotesScreen(
                            selectedTab = selectedNotesTab,
                            onTabChange = {
                                selectedNotesTab = it
                            },
                            onFolderClick = {
                                selectedNotesTab = NotesTab.COURSES
                                selectedFolderId = it
                            },
                            onAssignmentClick = {
                                selectedAssignmentId = it
                            },
                            onNoteClick = {
                                previewNote = it
                            },
                            onAddClick = {
                                showAddNotice = true
                            }
                        )
                    } else {
                        FolderNotesScreen(
                            folderId = folderId,
                            onBackClick = {
                                selectedFolderId = null
                            },
                            onAddClick = {
                                showAddNotice = true
                            },
                            onAssignmentClick = {
                                selectedAssignmentId = it
                            },
                            onNoteClick = {
                                previewNote = it
                            }
                        )
                    }
                }
            }

            // 과제 상세 화면을 현재 화면 위에 표시
            selectedAssignmentId?.let { assignmentId ->
                TaskDetailScreen(
                    assignmentId = assignmentId,
                    onBackClick = {
                        selectedAssignmentId = null
                    }
                )
            }
        }

        // 과제 상세 화면에서는 하단 바 숨김
        if (selectedAssignmentId == null) {
            BottomBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab

                    if (tab != MoaTab.NOTES) {
                        selectedFolderId = null
                    }
                },
                modifier = Modifier.align(
                    Alignment.BottomCenter
                )
            )
        }
    }

    // 일반 메모 / 공지는 우선 읽기 전용으로 표시
    val note = previewNote

    if (note != null) {
        AlertDialog(
            onDismissRequest = {
                previewNote = null
            },
            title = {
                Text(note.title)
            },
            text = {
                Text(
                    note.body
                        ?.takeIf { it.isNotBlank() }
                        ?: "작성된 내용이 없어요."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        previewNote = null
                    }
                ) {
                    Text("닫기")
                }
            }
        )
    }

    // 노트 추가 화면은 다음 단계에서 연결
    if (showAddNotice) {
        AlertDialog(
            onDismissRequest = {
                showAddNotice = false
            },
            title = {
                Text("노트 추가")
            },
            text = {
                Text("노트 추가 화면은 다음 단계에서 구현할 예정이에요.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAddNotice = false
                    }
                ) {
                    Text("확인")
                }
            }
        )
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
