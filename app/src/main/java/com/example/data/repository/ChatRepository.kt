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
            You are "SmartDroid AI", a highly capable, intelligent voice and text Android assistant running live on the user's phone.
            You can answer questions, chat with the user, and help guide them with on-device actions.
            When replying, stay warm, polite, highly concise (ideal for Text-To-Speech playback), and helpful. 
            Avoid long paragraphs, complex markdown tables, or extensive lists unless requested, since your words will be read out loud.
            
            Device capabilities of this app (processed fallback systems handle these, but you can instruct or confirm):
            - Make phone calls: "Call Amila" or "Call [phone number]"
            - Send SMS messages: "Send SMS to Amila saying [message]"
            - Open installed apps: "Open WhatsApp" or "Open [app name]"
            - Flashlight control: "Turn on/off flashlight"
            - Setting alarms: "Set alarm for [time]"
            - Searching google: "Search [query]"
            - Opening websites: "Open [website url]"
            
            Confirm actions with short friendly words, e.g. "I'll turn on your flashlight right now." or "Calling Mom now."
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
