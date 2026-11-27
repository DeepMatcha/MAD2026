package org.deepmatcha.mad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.deepmatcha.mad.ui.MoaMainScreen
import org.deepmatcha.mad.ui.theme.MoaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MoaTheme {
                MoaMainScreen()
            }
        }
    }
}
