package org.deepmatcha.mad.ui.notes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.deepmatcha.mad.MadApplication
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.NoteItem
import org.deepmatcha.mad.contract.NoteLinkedKind
import org.deepmatcha.mad.ui.components.TaskCard
import org.deepmatcha.mad.ui.theme.MoaRed
import java.time.LocalDate

@Composable
fun FolderNotesScreen(
    folderId: String,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onAssignmentClick: (String) -> Unit,
    onNoteClick: (NoteItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dependencies = remember(context.applicationContext) {
        (context.applicationContext as MadApplication).dependencies
    }

    val folders by remember(dependencies) {
        dependencies.notes.observeNoteCourseSummaries()
    }.collectAsState(initial = emptyList())

    val notes by remember(dependencies, folderId) {
        dependencies.notes.observeNotes(folderId = folderId)
    }.collectAsState(initial = emptyList())

    val folderName = folders.firstOrNull {
        it.folderId == folderId
    }?.displayName ?: "폴더"

    var showRenameDialog by remember {
        mutableStateOf(false)
    }

    var newFolderName by remember {
        mutableStateOf("")
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    val scope = rememberCoroutineScope()

    val openNote: (String) -> Unit = { noteId ->
        val note = notes.firstOrNull { it.id == noteId }

        if (note != null) {
            val assignmentId = note.link
                ?.takeIf {
                    it.entityKind == NoteLinkedKind.ASSIGNMENT
                }
                ?.entityId

            if (assignmentId != null) {
                onAssignmentClick(assignmentId)
            } else {
                onNoteClick(note)
            }
        }
    }

    val toggleNote: (String, Boolean) -> Unit = {
            noteId, checked ->

        scope.launch {
            try {
                val result = dependencies.notes.setNoteCompleted(
                    noteId,
                    checked
                )

                errorMessage = if (result is ApiResult.Failure) {
                    "완료 상태를 변경하지 못했어요."
                } else {
                    null
                }
            } catch (e: Exception) {
                errorMessage = "완료 상태를 변경하지 못했어요."
            }
        }
    }

    val dates = notes
        .mapNotNull { it.dateAt?.date }
        .distinct()
        .sorted()
        .toMutableList<LocalDate?>()

    if (notes.any { it.dateAt == null }) {
        dates.add(null)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 28.dp,
                    end = 20.dp,
                    top = 30.dp,
                    bottom = 12.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notes",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF171923)
            )

            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "노트 추가"
                )
            }
        }

        // 현재 폴더 정보
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 19.dp,
                    end = 22.dp,
                    top = 4.dp,
                    bottom = 20.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "폴더 목록으로 돌아가기"
                )
            }

            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = Color(0xFF26BED8),
                modifier = Modifier.size(30.dp)
            )

            Spacer(Modifier.width(10.dp))

            Text(
                text = folderName,
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF292929)
            )

            IconButton(
                onClick = {
                    newFolderName = folderName
                    showRenameDialog = true
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "폴더 이름 변경",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage.orEmpty(),
                color = MoaRed,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 25.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 4.dp,
                bottom = 140.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(15.dp)
        ) {
            if (notes.isEmpty()) {
                item {
                    Text(
                        text = "이 폴더에는 아직 노트가 없어요.",
                        color = Color(0xFF9297A2),
                        modifier = Modifier.padding(top = 26.dp)
                    )
                }
            }

            items(
                items = dates,
                key = { it?.toString() ?: "no-date" }
            ) { date ->

                val groupedNotes = notes.filter {
                    it.dateAt?.date == date
                }

                TaskCard(
                    date = date,
                    tasks = groupedNotes.map {
                        it.toTaskUiModel()
                    },
                    showCount = false,
                    onTaskClick = openNote,
                    onToggleCompleted = toggleNote
                )
            }
        }
    }

    // 폴더 이름 변경
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
            },
            title = {
                Text("폴더 이름 변경")
            },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = {
                        newFolderName = it
                    },
                    label = {
                        Text("폴더 이름")
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName.trim()

                        if (name.isEmpty()) {
                            errorMessage = "폴더 이름을 입력해 주세요."
                        } else {
                            scope.launch {
                                try {
                                    when (
                                        dependencies.notes.renameNoteFolder(
                                            folderId,
                                            name
                                        )
                                    ) {
                                        is ApiResult.Success -> {
                                            showRenameDialog = false
                                            errorMessage = null
                                        }

                                        is ApiResult.Failure -> {
                                            errorMessage =
                                                "폴더 이름을 변경하지 못했어요."
                                        }
                                    }
                                } catch (e: Exception) {
                                    errorMessage =
                                        "폴더 이름을 변경하지 못했어요."
                                }
                            }
                        }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                    }
                ) {
                    Text("취소")
                }
            }
        )
    }
}
