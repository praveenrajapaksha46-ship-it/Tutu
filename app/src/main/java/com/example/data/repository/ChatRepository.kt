package com.example.data.repository

import com.example.BuildConfig
import com.example.data.local.ChatMessageDao
import com.example.data.model.ChatMessage
import com.example.data.api.RetrofitClient
import com.example.data.api.GeminiRequest
import com.example.data.api.Content
import com.example.data.api.Part
import com.example.data.api.GenerationConfig
import kotlinx.coroutines.flow.Flow
import java.lang.Exception

class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    val allMessages: Flow<List<ChatMessage>> = chatMessageDao.getAllMessages()

    suspend fun insertMessage(message: ChatMessage): Long {
        return chatMessageDao.insertMessage(message)
    }

    suspend fun deleteMessage(id: Long) {
        chatMessageDao.deleteMessage(id)
    }

    suspend fun clearHistory() {
        chatMessageDao.clearHistory()
    }

    suspend fun generateAiResponse(recentMessages: List<ChatMessage>, currentPrompt: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Please configure your GEMINI_API_KEY in the AI Studio Secrets panel."
        }

        // Build list of contents
        val contentsList = mutableListOf<Content>()

        // Map recent history to Gemini API format (limit to last 10 messages to avoid context blowup)
        recentMessages.takeLast(10).forEach { msg ->
            val role = if (msg.sender == "user") "user" else "model"
            contentsList.add(
                Content(
                    role = role,
                    parts = listOf(Part(text = msg.text))
                )
            )
        }

        // Add the current prompt if not already in the list
        if (contentsList.isEmpty() || contentsList.last().parts.firstOrNull()?.text != currentPrompt) {
            contentsList.add(
                Content(
                    role = "user",
                    parts = listOf(Part(text = currentPrompt))
                )
            )
        }

        val systemInstructionText = """
            You are "Tutu", a highly advanced, friendly, and capable AI Voice & Text Android Assistant.
            You FULLY support both Sinhala (සිංහල) and English. When a user converses, commands, or speaks to you in Sinhala, respond back in fluent, natural, grammatically correct Sinhala. When they speak in English or mixed Singlish, reply accordingly in matching language.
            When replying, stay extremely warm, polite, and highly concise (optimal for immediate Text-To-Speech playback, maximum 1 or 2 elegant sentences).
            Avoid all bullet points, complex symbols, lists, markdown, or long paragraphs, because your responses are read out loud directly to the user.
            
            Device activities supported on this device:
            - Launching any app/game: "Open [app name]" (WhatsApp, Facebook, YouTube, TikTok, Camera, Gallery, Settings, etc.)
            - Closing apps / going home: "Close current app", "Close app"
            - Placing direct phone calls: "Call [name or number]"
            - Sending direct SMS: "Send SMS to [name] saying [message]"
            - Hardware setting toggles: "Turn on/off flashlight", "WiFi on/off", "Bluetooth on/off"
            - Sound adjustment: "Volume up", "Volume down"
            - Time management: "Set alarm for [time]", "Remind me to [task]"
            - Notification readouts: "Read notifications", "Read my messages"
            - General Siri-like conversational intelligence.
            
            Always keep your words comforting, compact, and optimized for speech! E.g., "ෆ්ලෑෂ් ලයිට් එක ක්‍රියාත්මක කලා." or "Calling Sam now." or "මම ඔයාගේ අලුත් නොටිෆිකේෂන් කියවන්නම්."
        """.trimIndent()

        val systemInstruction = Content(
            parts = listOf(Part(text = systemInstructionText))
        )

        val request = GeminiRequest(
            contents = contentsList,
            systemInstruction = systemInstruction,
            generationConfig = GenerationConfig(temperature = 0.7f)
        )

        return try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "I couldn't generate a response. Please try again."
        } catch (e: Exception) {
            e.printStackTrace()
            "Network connection error: ${e.localizedMessage ?: "Please check internet availability."}"
        }
    }
}
