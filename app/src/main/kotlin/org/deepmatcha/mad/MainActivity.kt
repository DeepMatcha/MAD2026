package org.deepmatcha.mad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply { setText(R.string.setup_preview) })
            addView(Button(context).apply {
                setText(R.string.summary_title)
                setOnClickListener { startActivity(Intent(this@MainActivity, SummaryActivity::class.java)) }
            })
        })
    }
}
