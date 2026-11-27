// 데드라인 뱃지
package org.deepmatcha.mad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deepmatcha.mad.ui.theme.MoaRed
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun DeadlineBadge(
    dueDate: LocalDate,
    modifier: Modifier = Modifier,
    dueTime: LocalTime? = null
) {
    val label = getDeadlineLabel(dueDate, dueTime)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MoaRed.copy(alpha = 0.15f))
            .padding(
                horizontal = 9.dp,
                vertical = 6.dp
            )
    ) {
        Text(
            text = label,
            color = MoaRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun getDeadlineLabel(
    dueDate: LocalDate,
    dueTime: LocalTime?
): String {
    val zone = ZoneId.of("Asia/Seoul")
    val today = LocalDate.now(zone)

    val days = ChronoUnit.DAYS.between(today, dueDate)

    return when {
        days > 0 -> "D-$days"

        days < 0 -> "D+${-days}"

        dueTime == null -> "오늘"

        else -> {
            val minutes = Duration.between(
                LocalTime.now(zone),
                dueTime
            ).toMinutes()

            when {
                minutes <= 0 -> "마감"
                minutes < 60 -> "-${minutes}m"
                else -> "-${(minutes + 59) / 60}h"
            }
        }
    }
}
