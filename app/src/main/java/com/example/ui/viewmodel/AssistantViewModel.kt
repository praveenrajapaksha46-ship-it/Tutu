package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

sealed class CommandAction {
    data class Flashlight(val on: Boolean) : CommandAction()
    data class Call(val query: String) : CommandAction()
    data class Sms(val query: String, val body: String) : CommandAction()
    data class OpenApp(val appLabel: String) : CommandAction()
    data class SetAlarm(val timeString: String) : CommandAction()
    data class Search(val query: String) : CommandAction()
    data class OpenUrl(val url: String) : CommandAction()
}

class AssistantViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val chatMessageDao = AppDatabase.getDatabase(application).chatMessageDao()
    private val repository = ChatRepository(chatMessageDao)

    // Chat history collected reactively
    val chatMessages: StateFlow<List<ChatMessage>> = repository.allMessages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _partialSpeechText = MutableStateFlow("")
    val partialSpeechText: StateFlow<String> = _partialSpeechText.asStateFlow()

    private val _finalSpeechText = MutableStateFlow("")
    val finalSpeechText: StateFlow<String> = _finalSpeechText.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _rmsValue = MutableStateFlow(0f)
    val rmsValue: StateFlow<Float> = _rmsValue.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(getApplication(), this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
            }
        }
    }

    fun startListening() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                if (tts?.isSpeaking == true) {
                    tts?.stop()
                }
                
                _assistantState.value = AssistantState.LISTENING
                _partialSpeechText.value = ""
                _finalSpeechText.value = ""
                _errorState.value = null

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
                }
                
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    
                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsValue.value = rmsdB
                    }
                    
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    
                    override fun onError(error: Int) {
                        _assistantState.value = AssistantState.IDLE
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side connection error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO Permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Please try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition engine busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server-side feedback error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Say something!"
                            else -> "Speech recognition issue. Code: $error"
                        }
                        _errorState.value = errorMessage
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _finalSpeechText.value = text
                        if (text.isNotEmpty()) {
                            processInput(text)
                        } else {
                            _assistantState.value = AssistantState.IDLE
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        _partialSpeechText.value = matches?.firstOrNull() ?: ""
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _assistantState.value = AssistantState.IDLE
                _errorState.value = "Fallback SpeechRecognizer creation issue: ${e.localizedMessage}"
            }
        }
    }

    fun stopListening() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
                _assistantState.value = AssistantState.IDLE
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun processInput(input: String) {
        if (input.trim().isEmpty()) return

        _assistantState.value = AssistantState.PROCESSING
        _errorState.value = null

        viewModelScope.launch {
            // Save User request to database
            repository.insertMessage(ChatMessage(sender = "user", text = input))

            // Check if input is a physical device action (Local Processing first)
            val action = parseLocalCommand(input)
            if (action != null) {
                executeCommand(action, input)
            } else {
                // Not local, fallback to Gemini API
                val response = repository.generateAiResponse(chatMessages.value, input)
                repository.insertMessage(ChatMessage(sender = "assistant", text = response))
                speakResponse(response)
            }
        }
    }

    private fun parseLocalCommand(text: String): CommandAction? {
        val clean = text.lowercase().trim()
        
        // 1. Flashlight on/off
        if (clean.contains("flashlight on") || clean.contains("turn on flashlight") || clean.contains("turn on flash")) {
            return CommandAction.Flashlight(true)
        }
        if (clean.contains("flashlight off") || clean.contains("turn off flashlight") || clean.contains("turn off flash")) {
            return CommandAction.Flashlight(false)
        }
        
        // 2. Alarms
        if (clean.startsWith("set alarm for ")) {
            val time = text.substring(14).trim()
            return CommandAction.SetAlarm(time)
        }
        if (clean.startsWith("set alarm at ")) {
            val time = text.substring(13).trim()
            return CommandAction.SetAlarm(time)
        }
        
        // 3. Make Phone Calls
        if (clean.startsWith("call ")) {
            val contact = text.substring(5).trim()
            return CommandAction.Call(contact)
        }
        
        // 4. Open website URLs
        if (clean.startsWith("open website ")) {
            val url = text.substring(13).trim()
            return CommandAction.OpenUrl(url)
        }
        if (clean.startsWith("open url ")) {
            val url = text.substring(9).trim()
            return CommandAction.OpenUrl(url)
        }
        if (clean.startsWith("go to ")) {
            val url = text.substring(6).trim()
            return CommandAction.OpenUrl(url)
        }
        // Specific checks for web domains
        if (clean.startsWith("open ") && (clean.endsWith(".com") || clean.endsWith(".org") || clean.endsWith(".net") || clean.contains("www."))) {
            val url = text.substring(5).trim()
            return CommandAction.OpenUrl(url)
        }

        // 5. Open Apps
        if (clean.startsWith("open ")) {
            val appLabel = text.substring(5).trim()
            return CommandAction.OpenApp(appLabel)
        }
        
        // 6. Search google
        if (clean.startsWith("search ")) {
            val q = text.substring(7).trim()
            if (q.startsWith("for ")) {
                return CommandAction.Search(q.substring(4).trim())
            }
            return CommandAction.Search(q)
        }
        if (clean.startsWith("google ")) {
            return CommandAction.Search(text.substring(7).trim())
        }
        
        // 7. Send SMS directly
        // Matches "send sms to [XYZ] saying [ABC]"
        if (clean.startsWith("send sms to ")) {
            val remainder = text.substring(12).trim()
            val indexSaying = remainder.lowercase().indexOf(" saying ")
            if (indexSaying != -1) {
                val contactName = remainder.substring(0, indexSaying).trim()
                val messageText = remainder.substring(indexSaying + 8).trim()
                return CommandAction.Sms(contactName, messageText)
            }
        }
        if (clean.startsWith("sms ")) {
            val remainder = text.substring(4).trim()
            val indexSaying = remainder.lowercase().indexOf(" saying ")
            if (indexSaying != -1) {
                val contactName = remainder.substring(0, indexSaying).trim()
                val messageText = remainder.substring(indexSaying + 8).trim()
                return CommandAction.Sms(contactName, messageText)
            } else {
                val spaceIndex = remainder.indexOf(" ")
                if (spaceIndex != -1) {
                    val contactName = remainder.substring(0, spaceIndex).trim()
                    val messageText = remainder.substring(spaceIndex + 1).trim()
                    return CommandAction.Sms(contactName, messageText)
                }
            }
        }

        return null
    }

    private suspend fun executeCommand(action: CommandAction, rawInput: String) {
        viewModelScope.launch(Dispatchers.Main) {
            when (action) {
                is CommandAction.Flashlight -> setFlashlightState(action.on)
                is CommandAction.Call -> makePhoneCall(action.query)
                is CommandAction.Sms -> sendSms(action.query, action.body)
                is CommandAction.OpenApp -> openInstalledApp(action.appLabel)
                is CommandAction.SetAlarm -> scheduleAlarm(action.timeString)
                is CommandAction.Search -> searchGoogle(action.query)
                is CommandAction.OpenUrl -> openWebsiteUrl(action.url)
            }
        }
    }

    // --- On-Device Actions ---

    private fun setFlashlightState(on: Boolean) {
        try {
            val context = getApplication<Application>()
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.getOrNull(0)
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                val text = "Flashlight ${if (on) "turned on" else "turned off"}."
                saveAssistantResponse(text, "FLASHLIGHT")
                speakResponse(text)
            } else {
                val text = "Flashlight not found on this device."
                saveAssistantResponse(text, "FLASHLIGHT")
                speakResponse(text)
            }
        } catch (e: Exception) {
            val text = "I couldn't control the flashlight: ${e.localizedMessage}"
            saveAssistantResponse(text, "FLASHLIGHT")
            speakResponse(text)
        }
    }

    private fun makePhoneCall(query: String) {
        val contactNumber = if (query.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            query
        } else {
            getPhoneNumberForContact(query)
        }

        if (!contactNumber.isNullOrEmpty()) {
            val context = getApplication<Application>()
            val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                try {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$contactNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    val reply = "Calling $query now."
                    saveAssistantResponse(reply, "CALL")
                    speakResponse(reply)
                } catch (e: Exception) {
                    openDialerFallback(contactNumber, query)
                }
            } else {
                openDialerFallback(contactNumber, query)
            }
        } else {
            val reply = "I couldn't find a phone number for $query in your contacts list."
            saveAssistantResponse(reply, "CALL")
            speakResponse(reply)
        }
    }

    private fun openDialerFallback(number: String, label: String) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val reply = "Opening the phone dialer for $label."
            saveAssistantResponse(reply, "CALL")
            speakResponse(reply)
        } catch (e: Exception) {
            val reply = "Could not open dialer dial pad."
            saveAssistantResponse(reply, "CALL")
            speakResponse(reply)
        }
    }

    private fun sendSms(contactQuery: String, body: String) {
        val phoneNumber = if (contactQuery.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            contactQuery
        } else {
            getPhoneNumberForContact(contactQuery)
        }

        if (!phoneNumber.isNullOrEmpty()) {
            val context = getApplication<Application>()
            val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                try {
                    val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                    smsManager.sendTextMessage(phoneNumber, null, body, null, null)
                    val reply = "Direct SMS sent to $contactQuery."
                    saveAssistantResponse(reply, "SMS")
                    speakResponse(reply)
                } catch (e: Exception) {
                    openSmsAppFallback(phoneNumber, body, contactQuery)
                }
            } else {
                openSmsAppFallback(phoneNumber, body, contactQuery)
            }
        } else {
            val reply = "Contact $contactQuery not found to send SMS."
            saveAssistantResponse(reply, "SMS")
            speakResponse(reply)
        }
    }

    private fun openSmsAppFallback(number: String, body: String, label: String) {
        try {
            val context = getApplication<Application>()
            val uri = Uri.parse("smsto:$number")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val reply = "Launching Messages screen pre-filled for $label."
            saveAssistantResponse(reply, "SMS")
            speakResponse(reply)
        } catch (e: Exception) {
            val reply = "Failed to launch device messaging app."
            saveAssistantResponse(reply, "SMS")
            speakResponse(reply)
        }
    }

    private fun openInstalledApp(appLabel: String) {
        try {
            val context = getApplication<Application>()
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            var targetPackage: String? = null
            var finalLabel = appLabel

            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.lowercase().contains(appLabel.lowercase()) || appLabel.lowercase().contains(label.lowercase())) {
                    targetPackage = app.packageName
                    finalLabel = label
                    break
                }
            }

            if (targetPackage != null) {
                val intent = pm.getLaunchIntentForPackage(targetPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    val reply = "Opening $finalLabel."
                    saveAssistantResponse(reply, "OPEN_APP")
                    speakResponse(reply)
                } else {
                    val reply = "Found $finalLabel, but cannot launch it directly."
                    saveAssistantResponse(reply, "OPEN_APP")
                    speakResponse(reply)
                }
            } else {
                // Hardcoded fallback popular apps
                val fallbackPkg = when (appLabel.lowercase()) {
                    "whatsapp" -> "com.whatsapp"
                    "youtube" -> "com.google.android.youtube"
                    "gmail" -> "com.google.android.gm"
                    "maps" -> "com.google.android.apps.maps"
                    "facebook" -> "com.facebook.katana"
                    "chrome" -> "com.android.chrome"
                    else -> null
                }
                if (fallbackPkg != null) {
                    val intent = pm.getLaunchIntentForPackage(fallbackPkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        val reply = "Opening $appLabel."
                        saveAssistantResponse(reply, "OPEN_APP")
                        speakResponse(reply)
                        return
                    }
                }
                val reply = "I couldn't find an app named $appLabel on this phone."
                saveAssistantResponse(reply, "OPEN_APP")
                speakResponse(reply)
            }
        } catch (e: Exception) {
            val reply = "Error opening app: ${e.localizedMessage}"
            saveAssistantResponse(reply, "OPEN_APP")
            speakResponse(reply)
        }
    }

    private fun scheduleAlarm(timeString: String) {
        val parsedTime = parseHourAndMinutes(timeString)
        if (parsedTime != null) {
            val (hour, minutes) = parsedTime
            try {
                val context = getApplication<Application>()
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "SmartDroid AI Assistant Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val formattedTime = String.format("%02d:%02d", hour, minutes)
                val reply = "I've configured an alarm for $formattedTime."
                saveAssistantResponse(reply, "ALARM")
                speakResponse(reply)
            } catch (e: Exception) {
                openAlarmFallback()
            }
        } else {
            val reply = "I didn't recognize any standard time format from '$timeString'. Say e.g., '6 AM' or '7:30 PM'."
            saveAssistantResponse(reply, "ALARM")
            speakResponse(reply)
        }
    }

    private fun openAlarmFallback() {
        try {
            val context = getApplication<Application>()
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val reply = "Opening the system alarms manager now."
            saveAssistantResponse(reply, "ALARM")
            speakResponse(reply)
        } catch (ex: Exception) {
            val reply = "Failed to launch alarms application interface."
            saveAssistantResponse(reply, "ALARM")
            speakResponse(reply)
        }
    }

    private fun searchGoogle(query: String) {
        try {
            val context = getApplication<Application>()
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?q=$encodedQuery"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val reply = "Searching Google for: $query."
            saveAssistantResponse(reply, "SEARCH")
            speakResponse(reply)
        } catch (e: Exception) {
            val reply = "Failed to open internet browser to run google search."
            saveAssistantResponse(reply, "SEARCH")
            speakResponse(reply)
        }
    }

    private fun openWebsiteUrl(urlSelected: String) {
        var finalUrl = urlSelected.trim()
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://$finalUrl"
        }
        try {
            val context = getApplication<Application>()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val reply = "Navigating to: $urlSelected"
            saveAssistantResponse(reply, "OPEN_URL")
            speakResponse(reply)
        } catch (e: Exception) {
            val reply = "Failed to open browser page for $urlSelected"
            saveAssistantResponse(reply, "OPEN_URL")
            speakResponse(reply)
        }
    }

    // --- Helper Utilities ---

    private fun saveAssistantResponse(text: String, tag: String?) {
        viewModelScope.launch {
            repository.insertMessage(
                ChatMessage(
                    sender = "assistant",
                    text = text,
                    isCommand = true,
                    commandType = tag
                )
            )
        }
    }

    private fun speakResponse(text: String) {
        if (isTtsInitialized) {
            _assistantState.value = AssistantState.SPEAKING
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SMARTDROID_UTTERANCE")
            
            // Check speaker periodically to return to IDLE state
            viewModelScope.launch {
                // Delay to allow speech to begin
                kotlinx.coroutines.delay(500)
                while (tts?.isSpeaking == true) {
                    kotlinx.coroutines.delay(200)
                }
                if (_assistantState.value == AssistantState.SPEAKING) {
                    _assistantState.value = AssistantState.IDLE
                }
            }
        } else {
            _assistantState.value = AssistantState.IDLE
        }
    }

    private fun getPhoneNumberForContact(name: String): String? {
        val context = getApplication<Application>()
        val contentResolver = context.contentResolver
        var phoneNumber: String? = null
        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    phoneNumber = it.getString(index)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return phoneNumber
    }

    private fun parseHourAndMinutes(text: String): Pair<Int, Int>? {
        // Parsing "HH:MM" format
        val hmRegex = """(\d{1,2}):(\d{2})""".toRegex()
        val hmMatch = hmRegex.find(text)
        if (hmMatch != null) {
            var hour = hmMatch.groupValues[1].toInt()
            val min = hmMatch.groupValues[2].toInt()
            if (text.lowercase().contains("pm") && hour < 12) hour += 12
            if (text.lowercase().contains("am") && hour == 12) hour = 0
            return Pair(hour, min)
        }

        // Parsing "X am" / "Y pm"
        val singleRegex = """(\d{1,2})\s*(am|pm)""".toRegex(RegexOption.IGNORE_CASE)
        val singleMatch = singleRegex.find(text)
        if (singleMatch != null) {
            var hour = singleMatch.groupValues[1].toInt()
            val meridiem = singleMatch.groupValues[2].lowercase()
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return Pair(hour, 0)
        }

        // Raw numbers
        val rawNum = text.filter { it.isDigit() }
        if (rawNum.isNotEmpty()) {
            val hour = rawNum.toInt()
            if (hour in 0..23) return Pair(hour, 0)
        }

        return null
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun deleteChatMessage(id: Long) {
        viewModelScope.launch {
            repository.deleteMessage(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            speechRecognizer?.destroy()
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
