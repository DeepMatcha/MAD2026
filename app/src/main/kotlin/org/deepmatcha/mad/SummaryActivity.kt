package org.deepmatcha.mad

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.deepmatcha.mad.ondevice.litert.GemmaModel
import org.deepmatcha.mad.ondevice.litert.GemmaModelState

class SummaryActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[SummaryViewModel::class.java] }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private var picking: Job? = null
    private var picker: AlertDialog? = null

    private lateinit var source: EditText
    private lateinit var options: Map<GemmaModel, RadioButton>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)
        source = findViewById(R.id.source_text)
        source.setText(model.draft)
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                model.edit(s.toString())
                render(model.models.value, model.ui.value)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        options = mapOf(
            GemmaModel.E2B to findViewById(R.id.choice_e2b),
            GemmaModel.E4B to findViewById(R.id.choice_e4b),
        )
        model.choice?.let { options.getValue(it).isChecked = true }
        findViewById<RadioGroup>(R.id.model_choices).setOnCheckedChangeListener { _, id ->
            model.choice = options.entries.firstOrNull { it.value.id == id }?.key
            render(model.models.value, model.ui.value)
        }
        findViewById<Button>(R.id.use_model).setOnClickListener { model.selectModel() }
        findViewById<Button>(R.id.cancel_download).setOnClickListener { model.cancelDownload() }
        findViewById<Button>(R.id.summarize).setOnClickListener { model.summarize() }
        findViewById<Button>(R.id.pick_source).setOnClickListener { pickResource() }
    }

    override fun onStart() {
        super.onStart()
        observing = scope.launch {
            model.models.combine(model.ui) { models, ui -> models to ui }
                .collect { (models, ui) -> render(models, ui) }
        }
    }

    private fun render(models: GemmaModelState, ui: SummaryUiState) {
        val busy = ui.downloading || ui.generating || models.download != null
        val choice = model.choice
        for ((item, option) in options) {
            val label = getString(R.string.model_option, item.label, Formatter.formatFileSize(this, item.sizeBytes))
            option.text = if (item in models.installed) getString(R.string.model_installed, label) else label
            option.isEnabled = !busy
        }
        findViewById<Button>(R.id.use_model).apply {
            isEnabled = !busy && choice != null && choice != models.selected
            setText(if (choice in models.installed) R.string.select_installed else R.string.download_model)
        }
        val transfer = models.download
        findViewById<TextView>(R.id.model_status).text = when {
            transfer != null && transfer.bytes >= transfer.model.sizeBytes -> getString(R.string.download_verify, transfer.model.label)
            transfer != null -> getString(R.string.download_status, transfer.model.label,
                Formatter.formatFileSize(this, transfer.bytes), Formatter.formatFileSize(this, transfer.model.sizeBytes))
            models.selected != null -> getString(R.string.selected_model, models.selected?.label)
            else -> getString(R.string.no_model)
        }
        findViewById<ProgressBar>(R.id.download_progress).apply {
            visibility = if (ui.downloading) View.VISIBLE else View.GONE
            isIndeterminate = transfer == null || transfer.bytes >= transfer.model.sizeBytes
            if (transfer != null) progress = (100 * transfer.bytes / transfer.model.sizeBytes).toInt()
        }
        findViewById<Button>(R.id.cancel_download).visibility = if (ui.downloading) View.VISIBLE else View.GONE
        source.isEnabled = !ui.generating
        findViewById<Button>(R.id.pick_source).isEnabled = !ui.generating
        findViewById<Button>(R.id.summarize).isEnabled =
            !busy && choice != null && choice == models.selected && model.draft.isNotBlank()
        findViewById<TextView>(R.id.feedback).text = ui.feedback
        findViewById<TextView>(R.id.answer).text = ui.answer
    }

    private fun pickResource() {
        if (picking?.isActive == true) return
        picking = scope.launch {
            try {
                val school = (application as MadApplication).dependencies.school
                val entries = school.observeCourses().first().flatMap { course ->
                    school.observeResources(course.id).first().mapNotNull { resource ->
                        val body = resource.contentText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        "${course.name} · ${resource.title}" to "${resource.title}\n\n$body"
                    }
                }
                if (entries.isEmpty()) {
                    Toast.makeText(this@SummaryActivity, R.string.no_resources, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                picker = AlertDialog.Builder(this@SummaryActivity)
                    .setTitle(R.string.pick_source)
                    .setItems(entries.map { it.first }.toTypedArray()) { _, which -> source.setText(entries[which].second) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@SummaryActivity, R.string.resources_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStop() {
        observing?.cancel()
        picking?.cancel()
        picker?.dismiss()
        picker = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
