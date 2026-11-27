// 과제 카드
package org.deepmatcha.mad.ui.components
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deepmatcha.mad.ui.theme.MoaBlue
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TaskUiModel(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val completed: Boolean = false,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val showDeadline: Boolean = false,
    val canComplete: Boolean = true,
    val canOpen: Boolean = true
)

@Composable
fun TaskCard(
    date: LocalDate?,
    tasks: List<TaskUiModel>,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onTaskClick: (String) -> Unit = {},
    onToggleCompleted: (String, Boolean) -> Unit = { _, _ -> }
) {
    val shape = RoundedCornerShape(25.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Color(0xFFF9FAFD))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (highlighted) {
                        Color(0xFFE8F0FF)
                    } else {
                        Color(0xFFF9FAFD)
                    }
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 14.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = date?.format(
                    DateTimeFormatter.ofPattern(
                        "EEE, MMM d",
                        Locale.ENGLISH
                    )
                ) ?: "No Dates",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF171923)
            )

            Text(
                text = "${tasks.size} tasks",
                fontSize = 11.sp,
                color = Color(0xFF9297A2)
            )
        }

        tasks.forEach { task ->
            TaskCardRow(
                task = task,
                onTaskClick = {
                    onTaskClick(task.id)
                },
                onToggleCompleted = { checked ->
                    onToggleCompleted(task.id, checked)
                }
            )
        }

        Spacer(Modifier.height(9.dp))
    }
}

@Composable
private fun TaskCardRow(
    task: TaskUiModel,
    onTaskClick: () -> Unit,
    onToggleCompleted: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 14.dp,
                vertical = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .toggleable(
                    value = task.completed,
                    enabled = task.canComplete,
                    role = Role.Checkbox,
                    onValueChange = onToggleCompleted
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.completed) MoaBlue
                        else Color.Transparent
                    )
                    .then(
                        if (!task.completed) {
                            Modifier.background(
                                Color.Transparent,
                                CircleShape
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (task.completed) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(25.dp)
                            .background(
                                Color(0xFFB4B8BE),
                                CircleShape
                            )
                            .padding(2.dp)
                            .background(
                                Color(0xFFF9FAFD),
                                CircleShape
                            )
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = task.canOpen) {
                    onTaskClick()
                },
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = task.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF171923),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.completed) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                }
            )

            if (task.subtitle.isNotBlank()) {
                Text(
                    text = task.subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF9297A2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (task.showDeadline && task.dueDate != null) {
            DeadlineBadge(
                dueDate = task.dueDate,
                dueTime = task.dueTime
            )
        }
    }
}
