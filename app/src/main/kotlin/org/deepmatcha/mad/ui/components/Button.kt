// 메인 버튼 컴포넌트
package org.deepmatcha.mad.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import org.deepmatcha.mad.ui.theme.MoaBlue
import org.deepmatcha.mad.ui.theme.MoaTheme

@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val shape = RoundedCornerShape(16.dp)
    val canClick = enabled && !isLoading

    val interactionSource = remember {
        MutableInteractionSource()
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && canClick) 0.97f else 1f,
        animationSpec = spring(),
        label = "ButtonPressAnimation"
    )

    val glassGradient = if (canClick) {
        Brush.verticalGradient(
            colors = listOf(
                MoaBlue.copy(alpha = 0.78f),
                Color(0xFF8FACD7).copy(alpha = 0.65f)
            )
        )
    } else if (isLoading) {
        Brush.verticalGradient(
            colors = listOf(
                MoaBlue.copy(alpha = 0.78f),
                Color(0xFF8FACD7).copy(alpha = 0.65f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFB8C8DE).copy(alpha = 0.55f),
                Color(0xFFD2DDED).copy(alpha = 0.40f)
            )
        )
    }

    Box(
        modifier = modifier
            .height(54.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = MoaBlue.copy(alpha = 0.12f),
                spotColor = MoaBlue.copy(alpha = 0.15f)
            )
            .clip(shape)
            .background(glassGradient)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        Color.White.copy(alpha = 0.25f)
                    )
                ),
                shape = shape
            )
            .clickable(
                enabled = canClick,
                interactionSource = interactionSource
            ) {
                onClick()
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                color = if (enabled) {
                    Color.White
                } else {
                    Color(0xFF8091A8)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ButtonPreview() {
    MoaTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF0F3F9))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                text = "Continue",
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                text = "Save Note",
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                text = "Continue",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
