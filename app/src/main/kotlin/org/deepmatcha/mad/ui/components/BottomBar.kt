// 하단 바
package org.deepmatcha.mad.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class MoaTab(
    val label: String,
    val icon: ImageVector
) {
    HOME("Home", Icons.Outlined.Home),
    WEEKLY("Weekly", Icons.Outlined.DateRange),
    NOTES("Notes", Icons.Outlined.Inventory2)
}

@Composable
fun BottomBar(
    selectedTab: MoaTab,
    onTabSelected: (MoaTab) -> Unit,
    modifier: Modifier = Modifier
) {

    val capsuleShape = RoundedCornerShape(100.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = 26.dp,
                vertical = 12.dp
            ),
        contentAlignment = Alignment.Center
    ) {

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)

                // Floating shadow
                .shadow(
                    elevation = 18.dp,
                    shape = capsuleShape,
                    clip = false,
                    ambientColor = Color(0x22000000),
                    spotColor = Color(0x18000000)
                )

                .clip(capsuleShape)

                //메인 투명 글래스
                .background(
                    Brush.verticalGradient(
                        // 투명도조절
                        colors = listOf(
                            Color.White.copy(alpha = 0.65f),
                            Color.White.copy(alpha = 0.35f)
                        )
                    )
                )

                // Glass outline
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.98f),
                            Color(0xFFD8DEE9).copy(alpha = 0.65f)
                        )
                    ),
                    shape = capsuleShape
                )

                .padding(
                    horizontal = 9.dp,
                    vertical = 8.dp
                )
        ) {

            val tabs = MoaTab.entries
            val tabWidth = maxWidth / tabs.size

            val selectedIndex = tabs.indexOf(selectedTab)

            // Sliding animation
            val indicatorX by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "GlassIndicatorSlide"
            )

            // 블루 캡슐
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .padding(
                        horizontal = 9.dp,  //양옆
                        vertical = 2.dp  // 상하 여백 추가
                    )
                    .shadow(
                        elevation = 5.dp,
                        shape = capsuleShape
                    )

                    .clip(capsuleShape)

                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFB9C6DE).copy(alpha = 0.78f),
                                Color(0xFFB9C6DE).copy(alpha = 0.64f)
                            )
                        )
                    )

                    .border(
                        width = 0.3.dp,
                        color = Color.White.copy(alpha = 0.75f),
                        shape = capsuleShape
                    )
            )

            // Icons
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                tabs.forEach { tab ->

                    val isSelected = selectedTab == tab

                    // Selected icon becomes white
                    val iconColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            Color.White
                        } else {
                            Color(0xFF242424)
                        },
                        animationSpec = tween(
                            durationMillis = 200
                        ),
                        label = "IconColorAnimation"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = {
                                    onTabSelected(tab)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {

                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = iconColor,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
        }
    }
}
