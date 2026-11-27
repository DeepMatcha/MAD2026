//메인 화면 관리자입니당
package org.deepmatcha.mad.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.deepmatcha.mad.ui.components.BottomBar
import org.deepmatcha.mad.ui.components.MoaTab
import org.deepmatcha.mad.ui.home.HomeScreen

@Composable
fun MoaMainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MoaTab.HOME) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            when (selectedTab) {
                MoaTab.HOME -> HomeScreen() //홈스크린
                MoaTab.WEEKLY -> ScreenPlaceholder("Weekly", "시간표 화면을 여기에 구현할 예정")
                MoaTab.NOTES -> ScreenPlaceholder("Notes", "노트 화면을 여기에 구현할 예정")
            }
        }

        BottomBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ScreenPlaceholder(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}
