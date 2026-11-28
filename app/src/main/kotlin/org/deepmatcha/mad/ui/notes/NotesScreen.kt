package org.deepmatcha.mad.ui.notes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
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
import org.deepmatcha.mad.contract.NoteCourseSummary
import org.deepmatcha.mad.contract.NoteItem
import org.deepmatcha.mad.contract.NoteLinkedKind
import org.deepmatcha.mad.ui.components.TaskCard
import org.deepmatcha.mad.ui.components.TaskUiModel
import org.deepmatcha.mad.ui.theme.MoaRed
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
private val NotesText = Color(0xFF171923)
private val NotesGray = Color(0xFF9297A2)
private val FolderBlue = Color(0xFF26BED8)

enum class NotesTab {
    COURSES,
    ALL_NOTES
}

@Composable
fun NotesScreen(
    selectedTab: NotesTab,
    onTabChange: (NotesTab) -> Unit,
    onFolderClick: (String) -> Unit,
    onAssignmentClick: (String) -> Unit,
    onNoteClick: (NoteItem) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dependencies = remember(context.applicationContext) {
        (context.applicationContext as MadApplication).dependencies
    }

    val folders by remember(dependencies) {
        dependencies.notes.observeNoteCourseSummaries()
    }.collectAsState(initial = emptyList())

    val notes by remember(dependencies) {
        dependencies.notes.observeNotes()
    }.collectAsState(initial = emptyList())

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Text(
            text = "Notes",
            modifier = Modifier.padding(
                start = 28.dp,
                top = 30.dp,
                bottom = 12.dp
            ),
            color = NotesText,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 28.dp,
                    end = 20.dp,
                    bottom = 18.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NotesTabSelector(
                selectedTab = selectedTab,
                onTabChange = onTabChange
            )

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "노트 추가",
                    modifier = Modifier.size(27.dp),
                    tint = NotesText
                )
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage.orEmpty(),
                color = MoaRed,
                fontSize = 13.sp,
                modifier = Modifier.padding(
                    horizontal = 28.dp,
                    vertical = 8.dp
                )
            )
        }

        when (selectedTab) {
            NotesTab.COURSES -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 22.dp,
                        end = 22.dp,
                        top = 10.dp,
                        bottom = 140.dp
                    ),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(13.dp)
                ) {
                    gridItems(
                        items = folders,
                        key = { it.folderId }
                    ) { folder ->
                        NotesFolderCard(
                            folder = folder,
                            onClick = {
                                onFolderClick(folder.folderId)
                            }
                        )
                    }
                }
            }

            NotesTab.ALL_NOTES -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        top = 8.dp,
                        bottom = 140.dp
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(16.dp)
                ) {
                    if (notes.isEmpty()) {
                        item {
                            Text(
                                text = "아직 등록된 노트가 없어요.",
                                color = NotesGray,
                                modifier = Modifier.padding(top = 30.dp)
                            )
                        }
                    }

                    listItems(
                        items = notes,
                        key = { it.id }
                    ) { note ->

                        val folderName = folders
                            .firstOrNull {
                                it.folderId == note.folderId
                            }
                            ?.displayName
                            ?: "Added Notes"

                        TaskCard(
                            date = note.dateAt?.date,
                            headerTitle = folderName,
                            showCount = false,
                            tasks = listOf(note.toTaskUiModel()),
                            onTaskClick = openNote,
                            onToggleCompleted = toggleNote
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun NotesTabSelector(
    selectedTab: NotesTab,
    onTabChange: (NotesTab) -> Unit
) {
    val shape = RoundedCornerShape(100.dp)

    BoxWithConstraints(
        modifier = Modifier
            .width(212.dp)
            .height(38.dp)
            .clip(shape)
            .background(Color(0xFFD9D9D9))
    ) {
        val innerPadding = 3.dp
        val tabWidth = (maxWidth - innerPadding * 2) / 2

        val targetX = when (selectedTab) {
            NotesTab.COURSES -> innerPadding
            NotesTab.ALL_NOTES -> innerPadding + tabWidth
        }

        // 선택 캡슐 슬라이딩 애니메이션
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "NotesTabSlide"
        )

        // 움직이는 흰색 Glass 캡슐
        Box(
            modifier = Modifier
                .offset(
                    x = indicatorX,
                    y = innerPadding
                )
                .width(tabWidth)
                .height(maxHeight - innerPadding * 2)
                .shadow(
                    elevation = 5.dp,
                    shape = shape
                )
                .clip(shape)
                .background(
                    Color.White.copy(alpha = 0.92f)
                )
                .border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = 0.95f),
                    shape = shape
                )
        )

        // 탭 텍스트
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                NotesTab.COURSES to "Courses",
                NotesTab.ALL_NOTES to "All Notes"
            ).forEach { (tab, label) ->

                val isSelected = selectedTab == tab

                val textColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        Color(0xFF171923)
                    } else {
                        Color(0xFF9297A2)
                    },
                    label = "NotesTabTextColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onTabChange(tab)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun NotesFolderCard(
    folder: NoteCourseSummary,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(17.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(94.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Color(0xFFF9FAFB))
            .clickable(onClick = onClick)
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = FolderBlue,
                modifier = Modifier.size(29.dp)
            )

            Text(
                text = folder.displayName,
                color = Color(0xFF868686),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "${folder.noteCount} notes",
            modifier = Modifier.align(Alignment.End),
            color = NotesText,
            fontSize = 10.sp
        )
    }
}

// NoteItem -> 공통 TaskCard용 UI 모델
internal fun NoteItem.toTaskUiModel(): TaskUiModel {
    return TaskUiModel(
        id = id,
        title = title,
        subtitle = body
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "내용 없음",
        completed = completed,
        dueDate = dateAt?.date,
        dueTime = dateAt?.time,
        showDeadline = dateAt != null,
        canComplete = true,
        canOpen = true
    )
}
