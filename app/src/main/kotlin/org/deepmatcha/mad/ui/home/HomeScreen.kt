package org.deepmatcha.mad.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.deepmatcha.mad.MadApplication
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.CalendarItemPatch
import org.deepmatcha.mad.contract.CalendarKind
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.Origin
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.ui.components.Calendar
import org.deepmatcha.mad.ui.components.TaskCard
import org.deepmatcha.mad.ui.components.TaskUiModel
import org.deepmatcha.mad.ui.theme.MoaRed
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(
    userName: String = "길동",
    onSettingsClick: () -> Unit = {},
    onTaskClick: (String) -> Unit = {}
) {
    val context = LocalContext.current

    val dependencies = remember {
        (context.applicationContext as MadApplication).dependencies
    }

    val today = remember {
        LocalDate.now(ZoneId.of("Asia/Seoul"))
    }

    var selectedEpochDay by rememberSaveable {
        mutableLongStateOf(today.toEpochDay())
    }

    var isMonthExpanded by rememberSaveable {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    val selectedDate = LocalDate.ofEpochDay(
        selectedEpochDay
    )

    val range = remember(selectedDate) {
        val first = selectedDate.withDayOfMonth(1)

        DateRange(
            from = first.minusDays(14),
            to = first.plusMonths(1).plusDays(14)
        )
    }

    val courses by remember(dependencies) {
        dependencies.school.observeCourses()
    }.collectAsState(initial = emptyList())

    val calendarItems by remember(dependencies, range) {
        dependencies.calendar.observeCalendar(
            range = range,
            includeCompleted = true
        )
    }.collectAsState(initial = emptyList())

    val courseNames = courses.associate {
        it.id to it.name
    }

    val homeItems = calendarItems.filter {
        it.kind != CalendarKind.CLASS && !it.hidden
    }

    val markedDates = homeItems.map {
        it.startAt.date
    }.toSet()

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FB)),
        contentPadding = PaddingValues(
            start = 22.dp,
            end = 22.dp,
            top = 28.dp,
            bottom = 130.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${userName}님\n오늘도 좋은 하루에요!!",
                    color = Color(0xFF171923),
                    fontSize = 25.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "설정"
                    )
                }
            }
        }

        item {
            Calendar(
                selectedDate = selectedDate,
                markedDates = markedDates,
                expanded = isMonthExpanded,
                onDateSelected = { date ->
                    selectedEpochDay = date.toEpochDay()
                },
                onExpandedChange = {
                    isMonthExpanded = !isMonthExpanded
                },
                onPrevious = {
                    val newDate = if (isMonthExpanded) {
                        selectedDate.minusMonths(1)
                    } else {
                        selectedDate.minusWeeks(1)
                    }
                    selectedEpochDay = newDate.toEpochDay()
                },
                onNext = {
                    val newDate = if (isMonthExpanded) {
                        selectedDate.plusMonths(1)
                    } else {
                        selectedDate.plusWeeks(1)
                    }
                    selectedEpochDay = newDate.toEpochDay()
                }
            )
        }

        if (errorMessage != null) {
            item {
                Text(
                    text = errorMessage.orEmpty(),
                    color = MoaRed,
                    fontSize = 13.sp
                )
            }
        }

        val dates = (0L..2L).map {
            selectedDate.plusDays(it)
        }

        val hasAnyTask = dates.any { date ->
            homeItems.any { it.startAt.date == date }
        }

        if (!hasAnyTask) {
            item {
                Text(
                    text = "선택한 날짜에 예정된 할 일이 없어요.",
                    color = Color(0xFF9297A2),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 28.dp)
                )
            }
        }

        dates.forEach { date ->
            val originalItems = homeItems.filter {
                it.startAt.date == date
            }

            if (originalItems.isNotEmpty()) {
                val tasks = originalItems.map { item ->
                    TaskUiModel(
                        id = item.id,
                        title = item.title,
                        subtitle = courseNames[item.courseId]
                            ?: when (item.kind) {
                                CalendarKind.EVENT -> "개인 일정"
                                CalendarKind.LECTURE -> "강의"
                                else -> "과제"
                            },
                        completed = item.completed == true,
                        dueDate = item.startAt.date,
                        dueTime = item.startAt.time,
                        showDeadline =
                            item.kind == CalendarKind.ASSIGNMENT,
                        canComplete =
                            item.assignmentId != null ||
                                    item.origin == Origin.USER,
                        canOpen = item.assignmentId != null
                    )
                }

                item(key = "tasks-$date") {
                    TaskCard(
                        date = date,
                        tasks = tasks,
                        highlighted = date == selectedDate,
                        onTaskClick = { itemId ->
                            originalItems
                                .firstOrNull { it.id == itemId }
                                ?.assignmentId
                                ?.let(onTaskClick)
                        },
                        onToggleCompleted = { itemId, checked ->
                            val original = originalItems.firstOrNull {
                                it.id == itemId
                            }

                            if (original != null) {
                                scope.launch {
                                    val failed = when {
                                        original.assignmentId != null -> {
                                            dependencies.school
                                                .setUserCompleted(
                                                    original.assignmentId!!,
                                                    checked
                                                ) is ApiResult.Failure
                                        }

                                        original.origin == Origin.USER -> {
                                            dependencies.calendar
                                                .updateCalendarItem(
                                                    original.id,
                                                    CalendarItemPatch(
                                                        completed =
                                                            PatchField.Set(checked)
                                                    )
                                                ) is ApiResult.Failure
                                        }

                                        else -> false
                                    }

                                    errorMessage = if (failed) {
                                        "완료 상태를 변경하지 못했어요."
                                    } else {
                                        null
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
