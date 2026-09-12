package com.paradisemc.nexus.plugin.todo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ToDoActivity : Activity() {
    private val store by lazy { ToDoStore(this) }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun deferRebuild() = handler.post { rebuild() }

    private fun rebuild() {
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@ToDoActivity, "Add task"), NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(addTaskRow(), NexusUi.block())

            addView(BusTheme.gap(this@ToDoActivity, 24))
            addView(NexusUi.sectionRow(this@ToDoActivity, "Voice AI · Free tier"), NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(aiSettings(), NexusUi.block())

            addTaskSection("All", store.pending(ToDoFilter.ALL), completed = false)
            addTaskSection("Today", store.pending(ToDoFilter.TODAY), completed = false)
            addTaskSection("Older", store.pending(ToDoFilter.OLDER), completed = false)
            addTaskSection("Completed", store.completed(), completed = true)

            addView(BusTheme.gap(this@ToDoActivity, 24))
            addView(NexusUi.sectionRow(this@ToDoActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(NexusUi.uninstallCard(this@ToDoActivity, "To Do") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }, NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@ToDoActivity,
                    NexusPluginIcons.drawableFor("bookmark"),
                    "To Do",
                    "Voice-first Nexus plugin · v${versionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@ToDoActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun LinearLayout.addTaskSection(title: String, items: List<ToDoItem>, completed: Boolean) {
        addView(BusTheme.gap(this@ToDoActivity, 24))
        addView(NexusUi.sectionRow(this@ToDoActivity, title, items.size.toString()), NexusUi.block())
        addView(BusTheme.gap(this@ToDoActivity, 10))
        if (items.isEmpty()) {
            addView(NexusUi.cardBody(this@ToDoActivity, if (completed) "No completed tasks." else "No unfinished tasks here."), NexusUi.block())
        } else {
            items.forEach { item ->
                addView(taskCard(item, completed), NexusUi.block())
                addView(BusTheme.gap(this@ToDoActivity, 8))
            }
        }
    }

    private fun addTaskRow(): LinearLayout {
        val input = NexusUi.field(this, "e.g. Call the dentist")
        val add = NexusUi.pillButton(this, "Add").apply {
            setOnClickListener { if (store.add(input.text.toString()) != null) deferRebuild() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = NexusUi.dp(this@ToDoActivity, 10)
            })
        }
    }

    private fun aiSettings(): LinearLayout {
        val api = NexusUi.field(this, "Paste Gemini API key here").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(store.apiKey())
        }
        val model = NexusUi.field(this, ToDoStore.DEFAULT_MODEL).apply {
            isSingleLine = true
            setText(store.model())
        }
        val save = NexusUi.pillButton(this, "Save Gemini API key").apply {
            setOnClickListener {
                val key = api.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this@ToDoActivity, "Paste your Gemini API key first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val saved = store.saveAiSettings(key, model.text.toString())
                if (saved) {
                    Toast.makeText(this@ToDoActivity, "Gemini API key saved ✓", Toast.LENGTH_SHORT).show()
                    deferRebuild()
                } else {
                    Toast.makeText(this@ToDoActivity, "Could not save the API key. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val getKey = NexusUi.outlinePillButton(this, "Get free Gemini key").apply {
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.cardBody(
                this@ToDoActivity,
                "Uses Google's Gemini API Free Tier with gemini-3.7-flash by default. Paste your key below, then tap the large Save Gemini API key button. The glasses microphone is sent as raw WAV audio directly to Gemini; Nexus/Android speech-to-text is not used.",
            ), NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(NexusUi.cardBody(
                this@ToDoActivity,
                if (store.hasApiKey()) "Gemini API key: Saved ✓" else "Gemini API key: Not saved yet",
            ), NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(api, NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 8))
            addView(model, NexusUi.block())
            addView(BusTheme.gap(this@ToDoActivity, 12))
            addView(
                save,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(BusTheme.gap(this@ToDoActivity, 10))
            addView(
                getKey,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun taskCard(item: ToDoItem, completed: Boolean): LinearLayout = NexusUi.pressableCard(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = false
        addView(NexusUi.rowTitle(this@ToDoActivity, item.label))
        addView(NexusUi.rowSub(this@ToDoActivity, if (completed) "Completed ${formatTime(item.doneAt ?: item.createdAt)}" else "Created ${formatTime(item.createdAt)}"))
        addView(BusTheme.gap(this@ToDoActivity, 8))
        addView(LinearLayout(this@ToDoActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (!completed) {
                addView(NexusUi.pillButton(this@ToDoActivity, "Done").apply {
                    setOnClickListener { store.markDone(item.id); deferRebuild() }
                })
                addView(BusTheme.gap(this@ToDoActivity, 8))
            }
            addView(NexusUi.outlinePillButton(this@ToDoActivity, "Delete").apply {
                setOnClickListener { store.delete(item.id); deferRebuild() }
            })
        })
    }

    private fun formatTime(timestamp: Long): String {
        val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
}
