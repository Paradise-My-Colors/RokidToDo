package com.paradisemc.nexus.plugin.todo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface AiDecision {
    data class Add(val items: List<String>) : AiDecision
    data class ListRemaining(val filter: ToDoFilter) : AiDecision
    data class Error(val message: String) : AiDecision
}

class GeminiAudioInterpreter(private val store: ToDoStore) {
    fun interpret(wav: ByteArray, callback: (AiDecision) -> Unit) {
        Thread {
            val result = runCatching { request(wav) }
                .getOrElse { AiDecision.Error(it.message?.take(140) ?: "AI request failed") }
            callback(result)
        }.start()
    }

    private fun request(wav: ByteArray): AiDecision {
        val apiKey = store.apiKey()
        if (apiKey.isBlank()) return AiDecision.Error("Set your Gemini API key in the To Do phone settings")
        if (wav.size < 1_000) return AiDecision.Error("Recording was too short")

        val model = URLEncoder.encode(store.model(), Charsets.UTF_8.name())
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))))))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            return AiDecision.Error(detail?.take(140) ?: "AI request failed ($code)")
        }

        val response = JSONObject(text)
        val returned = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text").orEmpty()
        if (returned.isBlank()) return AiDecision.Error("AI returned no result")
        return parseDecision(returned)
    }

    private fun parseDecision(raw: String): AiDecision {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = runCatching { JSONObject(clean) }.getOrElse {
            val start = clean.indexOf('{'); val end = clean.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(clean.substring(start, end + 1)) else throw it
        }
        return when (json.optString("action").lowercase()) {
            "list" -> AiDecision.ListRemaining(
                when (json.optString("filter").lowercase()) {
                    "today" -> ToDoFilter.TODAY
                    "older", "old" -> ToDoFilter.OLDER
                    else -> ToDoFilter.ALL
                },
            )
            "add" -> {
                val array = json.optJSONArray("items") ?: JSONArray()
                val items = (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
                if (items.isEmpty()) AiDecision.Error("I could not find a to-do item in that recording")
                else AiDecision.Add(items)
            }
            else -> AiDecision.Error("I could not understand that to-do request")
        }
    }

    private companion object {
        const val PROMPT = """You are the intent parser for a private wearable To Do list. Listen to the supplied audio and infer the user's intended list action directly from the audio. Do not return a transcript. Return ONLY JSON. If the user asks to hear, repeat, show, recall, or tell them tasks still to do, return {\"action\":\"list\",\"filter\":\"all\"}. Use filter \"today\" if they explicitly ask for today's tasks, or \"older\" if they explicitly ask for older tasks. Otherwise treat the speech as one or more tasks to add and return {\"action\":\"add\",\"items\":[\"task 1\",\"task 2\"]}. Preserve the language used by the speaker, make each task concise and actionable, split genuinely separate tasks, and do not invent details."""
    }
}
