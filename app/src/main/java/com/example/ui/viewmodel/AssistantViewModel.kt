package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.repository.ChatRepository
import com.example.manager.AssistantState
import com.example.manager.VoiceAssistantManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val chatMessageDao = AppDatabase.getDatabase(application).chatMessageDao()
    private val repository = ChatRepository(chatMessageDao)

    private val assistantManager = VoiceAssistantManager.getInstance(application)

    // Expose chat log reactively from repository
    val chatMessages: StateFlow<List<ChatMessage>> = repository.allMessages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Bridge all states to VoiceAssistantManager
    val assistantState: StateFlow<AssistantState> = assistantManager.assistantState
    val partialSpeechText: StateFlow<String> = assistantManager.partialSpeechText
    val finalSpeechText: StateFlow<String> = assistantManager.finalSpeechText
    val errorState: StateFlow<String?> = assistantManager.errorState
    val rmsValue: StateFlow<Float> = assistantManager.rmsValue
    val continuousMode: StateFlow<Boolean> = assistantManager.continuousMode

    fun startListening() {
        assistantManager.startCommandListening()
    }

    fun stopListening() {
        assistantManager.stopListening()
    }

    fun processInput(input: String) {
        assistantManager.processInput(input)
    }

    fun toggleContinuousMode() {
        val nextMode = !continuousMode.value
        assistantManager.setContinuousMode(nextMode)
    }

    fun clearChatHistory() {
        assistantManager.clearChatHistory()
    }

    fun deleteChatMessage(id: Long) {
        assistantManager.deleteChatMessage(id)
    }

    override fun onCleared() {
        super.onCleared()
        // We do not call assistantManager.cleanUp() here because AssistantViewModel
        // is cleared on Activity rotation/destruction, but the continuous manager singleton
        // should persist for continuous service monitoring.
    }
}
