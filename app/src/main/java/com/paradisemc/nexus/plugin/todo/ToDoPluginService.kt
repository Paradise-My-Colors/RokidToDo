package com.paradisemc.nexus.plugin.todo

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.io.ByteArrayOutputStream

class ToDoPluginService : NexusPluginService() {
    private enum class Mode { BROWSE, ACTIONS, LISTENING, PROCESSING, NOTICE }

    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { ToDoStore(this) }
    private val ai by lazy { GeminiAudioInterpreter(store) }
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var tts: NexusTtsSession? = null
    private var mode = Mode.BROWSE
    private var filter = ToDoFilter.ALL
    private var selection = 0
    private var actionSelection = 0
    private var actionTaskId: String? = null
    private var notice = ""
    private var pcm = ByteArrayOutputStream()
    private var audioFormat = NexusAudioFormat(16_000, 1, "pcm_s16le")
    private var processAfterStop = false
    private var cancelAfterStart = false

    private val recordingTimeout = Runnable {
        if (mode == Mode.LISTENING) finishRecording()
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        mode = Mode.BROWSE
        filter = ToDoFilter.ALL
        selection = 0
        render(show = true)
    }

    override fun onNexusClose() {
        main.removeCallbacks(recordingTimeout)
        processAfterStop = false
        cancelAfterStart = true
        audio?.stop()
        audio = null
        tts?.close()
        tts = null
        surface?.hide()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (mode) {
            Mode.BROWSE -> browseInput(event.keyCode)
            Mode.ACTIONS -> actionInput(event.keyCode)
            Mode.LISTENING -> listeningInput(event.keyCode)
            Mode.PROCESSING -> if (event.keyCode == KeyEvent.KEYCODE_BACK) showNotice("AI request is already being processed")
            Mode.NOTICE -> noticeInput(event.keyCode)
        }
    }

    private fun browseInput(keyCode: Int) {
        val tasks = store.pending(filter)
        val rowCount = 2 + tasks.size
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> selection = (selection + 1).coerceAtMost((rowCount - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> selection = (selection - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (selection) {
                0 -> startRecording()
                1 -> { filter = filter.next(); selection = 1; render(false) }
                else -> tasks.getOrNull(selection - 2)?.let { openActions(it) }
            }
            KeyEvent.KEYCODE_BACK -> { surface?.hide(); return }
            else -> return
        }
        if (mode == Mode.BROWSE) render(false)
    }

    private fun actionInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> actionSelection = (actionSelection + 1).coerceAtMost(2)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> actionSelection = (actionSelection - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_BACK -> { mode = Mode.BROWSE; render(false); return }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val id = actionTaskId
                when (actionSelection) {
                    0 -> if (id != null) store.markDone(id)
                    1 -> if (id != null) store.delete(id)
                    else -> Unit
                }
                actionTaskId = null
                mode = Mode.BROWSE
                clampSelection()
            }
            else -> return
        }
        render(false)
    }

    private fun listeningInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finishRecording()
            KeyEvent.KEYCODE_BACK -> cancelRecording()
            else -> Unit
        }
    }

    private fun noticeInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                mode = Mode.BROWSE
                render(false)
            }
            else -> Unit
        }
    }

    private fun openActions(item: ToDoItem) {
        actionTaskId = item.id
        actionSelection = 0
        mode = Mode.ACTIONS
        render(false)
    }

    private fun startRecording() {
        if (store.apiKey().isBlank()) {
            showNotice("Open To Do on the phone and add a Gemini API key first")
            return
        }
        pcm = ByteArrayOutputStream()
        processAfterStop = false
        cancelAfterStart = false
        val session = nexusAudioSession(audioCallbacks) ?: run {
            showNotice("Glasses microphone is unavailable")
            return
        }
        audio = session
        when (session.start()) {
            NexusSdkResult.SENT -> {
                mode = Mode.LISTENING
                main.removeCallbacks(recordingTimeout)
                main.postDelayed(recordingTimeout, MAX_RECORDING_MS)
                render(false)
            }
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> { audio = null; showNotice("Approve microphone access for To Do in Nexus Plugin access") }
            NexusSdkResult.NOT_REGISTERED -> { audio = null; showNotice("Nexus is not connected yet") }
            else -> { audio = null; showNotice("Could not start the glasses microphone") }
        }
    }

    private fun finishRecording() {
        if (mode != Mode.LISTENING) return
        main.removeCallbacks(recordingTimeout)
        processAfterStop = true
        mode = Mode.PROCESSING
        render(false)
        val session = audio
        if (session?.isActive == true) session.stop()
    }

    private fun cancelRecording() {
        main.removeCallbacks(recordingTimeout)
        processAfterStop = false
        cancelAfterStart = true
        val session = audio
        if (session?.isActive == true) session.stop()
        audio = null
        mode = Mode.BROWSE
        render(false)
    }

    private val audioCallbacks = object : NexusAudioCallbacks {
        override fun onAudioStarted(format: NexusAudioFormat) {
            main.post {
                audioFormat = format
                if (cancelAfterStart || processAfterStop) audio?.stop()
            }
        }

        override fun onAudioFrame(pcmBytes: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
            synchronized(pcm) {
                if (pcm.size() < MAX_PCM_BYTES) {
                    val count = minOf(pcmBytes.size, MAX_PCM_BYTES - pcm.size())
                    pcm.write(pcmBytes, 0, count)
                }
            }
        }

        override fun onAudioStopped(reason: NexusAudioStopReason) {
            main.post {
                audio = null
                main.removeCallbacks(recordingTimeout)
                if (cancelAfterStart) {
                    cancelAfterStart = false
                    processAfterStop = false
                    return@post
                }
                if (processAfterStop) {
                    processAfterStop = false
                    submitAudio()
                } else if (mode == Mode.LISTENING) {
                    showNotice(audioStopText(reason))
                }
            }
        }
    }

    private fun submitAudio() {
        mode = Mode.PROCESSING
        render(false)
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        val wav = WavEncoder.pcm16Le(bytes, audioFormat.sampleRate, audioFormat.channels)
        ai.interpret(wav) { decision -> main.post { applyAiDecision(decision) } }
    }

    private fun applyAiDecision(decision: AiDecision) {
        when (decision) {
            is AiDecision.Add -> {
                val added = store.addAll(decision.items)
                filter = ToDoFilter.ALL
                selection = if (added.isEmpty()) 0 else 2
                showNotice(if (added.size == 1) "Added 1 task" else "Added ${added.size} tasks")
            }
            is AiDecision.ListRemaining -> readRemaining(decision.filter)
            is AiDecision.Error -> showNotice(decision.message)
        }
    }

    private fun readRemaining(requested: ToDoFilter) {
        filter = requested
        val tasks = store.pending(requested)
        val label = requested.displayName.lowercase()
        val text = if (tasks.isEmpty()) {
            if (requested == ToDoFilter.ALL) "Your To Do list is clear." else "You have no $label tasks."
        } else {
            val spoken = tasks.take(12).joinToString(". ") { it.label }
            val more = if (tasks.size > 12) ". And ${tasks.size - 12} more." else "."
            "You have ${tasks.size} ${if (tasks.size == 1) "task" else "tasks"}. $spoken$more"
        }.take(1000)
        val session = tts ?: nexusTtsSession(ttsCallbacks)?.also { tts = it }
        val result = session?.speak(text)
        notice = if (result == NexusSdkResult.SENT) "Reading ${tasks.size} ${requested.displayName.lowercase()} task${if (tasks.size == 1) "" else "s"}…" else "Could not read the list aloud"
        mode = Mode.NOTICE
        render(false)
    }

    private val ttsCallbacks = object : NexusTtsCallbacks {
        override fun onTtsStarted(utteranceId: String) = Unit
        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) = Unit
    }

    private fun showNotice(message: String) {
        notice = message.take(180)
        mode = Mode.NOTICE
        render(false)
    }

    private fun render(show: Boolean) {
        val card = when (mode) {
            Mode.BROWSE -> browseCard()
            Mode.ACTIONS -> actionCard()
            Mode.LISTENING -> NexusCard(
                title = "To Do · Listening",
                lines = listOf("Speak tasks to add, or ask what is still left."),
                footer = "tap when finished · back to cancel",
                contentKey = "listening",
                handlesBack = true,
            )
            Mode.PROCESSING -> NexusCard(
                title = "To Do · AI",
                lines = listOf("Understanding your request…"),
                footer = "audio is processed on the phone",
                contentKey = "processing",
                handlesBack = true,
            )
            Mode.NOTICE -> NexusCard(
                title = "To Do",
                lines = listOf(notice),
                footer = "tap or back",
                contentKey = "notice-${Integer.toHexString(notice.hashCode())}",
                handlesBack = true,
            )
        }
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private fun browseCard(): NexusCard {
        val tasks = store.pending(filter)
        val maxSelection = 1 + tasks.size
        selection = selection.coerceIn(0, maxSelection)
        val lines = mutableListOf<String>()
        lines += row(selection == 0, "+ Voice add / ask")
        lines += row(selection == 1, "View: ${filter.displayName}  (${tasks.size})")

        val taskIndex = (selection - 2).coerceAtLeast(0)
        val windowStart = if (tasks.size <= TASK_WINDOW) 0 else (taskIndex - TASK_WINDOW / 2).coerceIn(0, tasks.size - TASK_WINDOW)
        tasks.drop(windowStart).take(TASK_WINDOW).forEachIndexed { offset, item ->
            val absolute = windowStart + offset
            lines += row(selection == absolute + 2, item.label.take(92))
        }
        if (tasks.isEmpty()) lines += "  No unfinished tasks in ${filter.displayName.lowercase()}"

        return NexusCard(
            title = "To Do",
            lines = lines,
            footer = "swipe to move · tap to select · back",
            contentKey = "browse-${filter.name}-${selection}-${tasks.joinToString("|") { it.id.take(6) }}".let { Integer.toHexString(it.hashCode()) },
            handlesBack = true,
        )
    }

    private fun actionCard(): NexusCard {
        val item = store.load().firstOrNull { it.id == actionTaskId }
        val label = item?.label ?: "Task unavailable"
        val options = listOf("Mark as done", "Delete", "Cancel")
        return NexusCard(
            title = label.take(80),
            lines = options.mapIndexed { index, text -> row(actionSelection == index, text) },
            footer = "swipe · tap to confirm · back",
            contentKey = "actions-${actionTaskId.orEmpty().take(8)}-$actionSelection",
            handlesBack = true,
        )
    }

    private fun row(selected: Boolean, text: String): String = if (selected) "> $text" else "  $text"

    private fun clampSelection() {
        val max = 1 + store.pending(filter).size
        selection = selection.coerceIn(0, max)
    }

    private fun audioStopText(reason: NexusAudioStopReason): String = when (reason) {
        NexusAudioStopReason.DENIED_BUSY -> "The glasses microphone is busy"
        NexusAudioStopReason.DENIED_NO_LINK -> "No connection to the glasses"
        NexusAudioStopReason.DENIED_NOT_GRANTED, NexusAudioStopReason.REVOKED -> "Microphone access is not granted"
        else -> "Recording stopped before it could be processed"
    }

    private companion object {
        const val SURFACE_ID = "todo-main"
        const val TASK_WINDOW = 5
        const val MAX_RECORDING_MS = 15_000L
        const val MAX_PCM_BYTES = 1_000_000
    }
}
