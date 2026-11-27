// 캘린더
package org.deepmatcha.mad.ui.components
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deepmatcha.mad.ui.theme.MoaBlue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Calendar Colors
private val CalendarBackground = Color(0xFFF2F3F5)
private val TodayBackground = Color(0xFFDDE0E5)
private val CalendarText = Color(0xFF171923)
private val CalendarGray = Color(0xFF9297A2)

@Composable
fun Calendar(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    markedDates: Set<LocalDate> = emptySet(),
    expanded: Boolean = false,
    onExpandedChange: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    showExpandControl: Boolean = true,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(26.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 7.dp,
                shape = shape
            )
            .clip(shape)
            .background(CalendarBackground)
            .animateContentSize()
            .padding(
                horizontal = 14.dp,
                vertical = 12.dp
            )
    ) {

        // Calendar Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${selectedDate.monthValue}월 " +
                        "${selectedDate.dayOfMonth}일",
                fontWeight = FontWeight.Bold,
                color = CalendarText,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = "이전",
                    tint = CalendarText
                )
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "다음",
                    tint = CalendarText
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Weekday Labels
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                "Su", "Mo", "Tu", "We",
                "Th", "Fr", "Sa"
            ).forEach { day ->

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = day,
                        color = CalendarGray,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Monthly Calendar
        if (expanded) {

            val firstDay = selectedDate.withDayOfMonth(1)

            val gridStart = firstDay.with(
                TemporalAdjusters.previousOrSame(
                    DayOfWeek.SUNDAY
                )
            )

            val gridEnd = firstDay
                .plusMonths(1)
                .minusDays(1)
                .with(
                    TemporalAdjusters.nextOrSame(
                        DayOfWeek.SATURDAY
                    )
                )

            val dayCount = ChronoUnit.DAYS.between(
                gridStart,
                gridEnd
            ).toInt() + 1

            repeat(dayCount / 7) { week ->

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(7) { day ->

                        val date = gridStart.plusDays(
                            (week * 7 + day).toLong()
                        )

                        if (date.month == selectedDate.month &&
                            date.year == selectedDate.year
                        ) {
                            CalendarDateCell(
                                date = date,
                                selected = date == selectedDate,
                                marked = date in markedDates,
                                onClick = {
                                    onDateSelected(date)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            )
                        }
                    }
                }
            }
        } else {

            // Weekly Calendar
            val weekStart = selectedDate.with(
                TemporalAdjusters.previousOrSame(
                    DayOfWeek.SUNDAY
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(7) { index ->

                    val date = weekStart.plusDays(
                        index.toLong()
                    )

                    CalendarDateCell(
                        date = date,
                        selected = date == selectedDate,
                        marked = date in markedDates,
                        onClick = {
                            onDateSelected(date)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Expand / Collapse Button
        if (showExpandControl) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clickable {
                        onExpandedChange()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) {
                        "달력 접기"
                    } else {
                        "달력 펼치기"
                    },
                    tint = CalendarGray,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

// Individual Date Cell
@Composable
private fun CalendarDateCell(
    date: LocalDate,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now(
        ZoneId.of("Asia/Seoul")
    )

    val isToday = date == today

    // Selected: Blue
    // Today (not selected): Gray
    // Other dates: Transparent
    val backgroundColor = when {
        selected -> MoaBlue
        isToday -> TodayBackground
        else -> Color.Transparent
    }

    val textColor = if (selected) {
        Color.White
    } else {
        CalendarText
    }

    Column(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        // Date Circle
        Box(
            modifier = Modifier
                .size(31.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (selected) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                }
            )
        }

        Spacer(
            modifier = Modifier.height(3.dp)
        )

        // Task Indicator Dot
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    if (marked) {
                        MoaBlue.copy(alpha = 0.7f)
                    } else {
                        Color.Transparent
                    }
                )
        )
    }
}
