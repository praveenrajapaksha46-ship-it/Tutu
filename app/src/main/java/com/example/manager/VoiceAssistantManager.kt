package com.example.manager

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.repository.ChatRepository
import com.example.services.TutuAccessibilityService
import com.example.services.TutuNotificationService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

class VoiceAssistantManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceAssistantManager"
        @Volatile
        private var instance: VoiceAssistantManager? = null

        fun getInstance(context: Context): VoiceAssistantManager {
            return instance ?: synchronized(this) {
                instance ?: VoiceAssistantManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val chatMessageDao = AppDatabase.getDatabase(context).chatMessageDao()
    private val repository = ChatRepository(chatMessageDao)

    // Reactive State Flows
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

    private val _continuousMode = MutableStateFlow(false)
    val continuousMode: StateFlow<Boolean> = _continuousMode.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isWakingUpListening = false

    init {
        initTtsAndRecognizer()
    }

    private fun initTtsAndRecognizer() {
        tts = TextToSpeech(context, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Attempt to support Sinhala or fallback gracefully to System locale
            var result = tts?.setLanguage(Locale("si", "LK"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default
                result = tts?.setLanguage(Locale.US)
            }
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _assistantState.value = AssistantState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        scope.launch {
                            delay(40)
                            if (_assistantState.value == AssistantState.SPEAKING) {
                                _assistantState.value = AssistantState.IDLE
                                if (_continuousMode.value) {
                                    // Resume continuous wake monitoring after finished speaking
                                    startContinuousWakeListening()
                                }
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        _assistantState.value = AssistantState.IDLE
                        if (_continuousMode.value) {
                            startContinuousWakeListening()
                        }
                    }
                })
            }
        }
    }

    fun setContinuousMode(enabled: Boolean) {
        _continuousMode.value = enabled
        if (enabled) {
            startContinuousWakeListening()
        } else {
            stopListening()
        }
    }

    // Wake-word listening cycle
    fun startContinuousWakeListening() {
        if (_assistantState.value == AssistantState.SPEAKING) return
        scope.launch(Dispatchers.Main) {
            try {
                isWakingUpListening = true
                _assistantState.value = AssistantState.LISTENING
                _partialSpeechText.value = "Monitoring for wake word 'Tutu' or 'Hey Tutu'"
                _finalSpeechText.value = ""
                
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                        Log.d(TAG, "Wake detection listener error: $error")
                        // If error is speech timeout, restart immediately in continuous mode
                        if (isWakingUpListening && _continuousMode.value) {
                            scope.launch {
                                delay(1000)
                                if (_continuousMode.value) startContinuousWakeListening()
                            }
                        } else {
                            _assistantState.value = AssistantState.IDLE
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "Wake recognition result: $text")

                        val phrase = text.lowercase()
                        if (phrase.contains("tutu") || phrase.contains("hey tutu") || phrase.contains("ටුටු") || phrase.contains("ටූටූ") || phrase.contains("හලෝ ටුටු") || phrase.contains("tuto") || phrase.contains("ටු ටු")) {
                            // Wake word triggered! Speak acknowledgement first
                            isWakingUpListening = false
                            // Vibrate if permitted
                            triggerWakeAcknowledgement()
                        } else {
                            // Restart wake word monitoring
                            if (_continuousMode.value) {
                                scope.launch {
                                    delay(500)
                                    startContinuousWakeListening()
                                }
                            } else {
                                _assistantState.value = AssistantState.IDLE
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        val phrase = text.lowercase()
                        if (phrase.contains("tutu") || phrase.contains("hey tutu") || phrase.contains("ටුටු") || phrase.contains("ටූටූ") || phrase.contains("හලෝ ටුටු") || phrase.contains("tuto") || phrase.contains("ටු ටු")) {
                            isWakingUpListening = false
                            triggerWakeAcknowledgement()
                        }
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
                Log.e(TAG, "Error: ${e.message}")
            }
        }
    }

    private fun triggerWakeAcknowledgement() {
        speechRecognizer?.stopListening()
        // Determine wake phrase based on default language
        val localizedReply = if (Locale.getDefault().language == "si") {
            "ඔව්, මම අහගෙන ඉන්නේ." // "Yes, I am listening"
        } else {
            "Yes, I am here. Tell me."
        }
        _assistantState.value = AssistantState.PROCESSING
        speakResponseDirectly(localizedReply)

        // Wait brief delay for speaking to end, then trigger actual command recording
        scope.launch {
            delay(1500)
            startCommandListening()
        }
    }

    fun startCommandListening() {
        scope.launch(Dispatchers.Main) {
            try {
                if (tts?.isSpeaking == true) {
                    tts?.stop()
                }
                _assistantState.value = AssistantState.LISTENING
                _partialSpeechText.value = ""
                _finalSpeechText.value = ""
                _errorState.value = null

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                            SpeechRecognizer.ERROR_CLIENT -> "Speech service client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No instruction matched. Please try again."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout. Say something!"
                            else -> "Speech issue: $error"
                        }
                        _errorState.value = errorMessage
                        if (_continuousMode.value) {
                            scope.launch {
                                delay(2000)
                                if (_continuousMode.value) startContinuousWakeListening()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _finalSpeechText.value = text
                        if (text.isNotEmpty()) {
                            processInput(text)
                        } else {
                            _assistantState.value = AssistantState.IDLE
                            if (_continuousMode.value) startContinuousWakeListening()
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
                _errorState.value = "Recognizer error: ${e.message}"
            }
        }
    }

    fun stopListening() {
        scope.launch(Dispatchers.Main) {
            try {
                isWakingUpListening = false
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

        scope.launch {
            // Save user query
            repository.insertMessage(ChatMessage(sender = "user", text = input))

            // 1. Try local physical command processing
            val isLocalHandled = handleLocalPhysicalCommands(input)
            if (!isLocalHandled) {
                // 2. Query Gemini API
                val response = repository.generateAiResponse(repository.allMessages.first(), input)
                repository.insertMessage(ChatMessage(sender = "assistant", text = response))
                speakResponseDirectly(response)
            }
        }
    }

    private suspend fun handleLocalPhysicalCommands(text: String): Boolean {
        val clean = text.lowercase().trim()

        // Support both english and sinhala instructions
        val isFlashOn = clean.contains("flashlight on") || clean.contains("flare on") || clean.contains("turn on flash") || clean.contains("ෆ්ලෑෂ් ලයිට් එක දාන්න") || clean.contains("ෆ්ලෑෂ් ඔන්")
        val isFlashOff = clean.contains("flashlight off") || clean.contains("turn off flash") || clean.contains("ෆ්ලෑෂ් ලයිට් එක නිවන්න") || clean.contains("ෆ්ලෑෂ් ඕෆ්")

        if (isFlashOn) {
            setFlashlightState(true)
            return true
        }
        if (isFlashOff) {
            setFlashlightState(false)
            return true
        }

        // WiFi control
        if (clean.contains("wifi on") || clean.contains("turn on wifi") || clean.contains("වයිෆයි ඔන්") || clean.contains("වයිෆයි ඩේටා දාන්න")) {
            setWifiState(true)
            return true
        }
        if (clean.contains("wifi off") || clean.contains("turn off wifi") || clean.contains("වයිෆයි ඕෆ්") || clean.contains("වයිෆයි නිවන්න")) {
            setWifiState(false)
            return true
        }

        // Bluetooth control
        if (clean.contains("bluetooth on") || clean.contains("turn on bluetooth") || clean.contains("බ්ලූටූත් ඔන්")) {
            setBluetoothState(true)
            return true
        }
        if (clean.contains("bluetooth off") || clean.contains("turn off bluetooth") || clean.contains("බ්ලූටූත් ඕෆ්")) {
            setBluetoothState(false)
            return true
        }

        // Volume control
        if (clean.contains("volume up") || clean.contains("increase volume") || clean.contains("කඩහඬ වැඩි කරන්න") || clean.contains("වොලියම් වැඩි")) {
            changeVolume(true)
            return true
        }
        if (clean.contains("volume down") || clean.contains("decrease volume") || clean.contains("කඩහඬ අඩු කරන්න") || clean.contains("වොලියම් අඩු")) {
            changeVolume(false)
            return true
        }

        // Alarms
        if (clean.startsWith("set alarm for ") || clean.contains("अलार्म") || clean.contains("ඇලාරම් එකක්") || clean.contains("ඇලර්ම් එකක්")) {
            val time = clean.replace("set alarm for", "").replace("ඇලර්ම් එකක් තියන්න", "").trim()
            scheduleAlarm(time)
            return true
        }

        // Reminders
        if (clean.startsWith("remind me ") || clean.contains("reminder") || clean.contains("මතක් කරන්න")) {
            val note = clean.replace("remind me to", "").replace("remind me", "").trim()
            createReminder(note)
            return true
        }

        // Call contact
        if (clean.startsWith("call ") || clean.contains("කෝල් එකක් ගන්න") || clean.contains("අමතන්න")) {
            val target = clean.replace("call", "").replace("ට කෝල් එකක් ගන්න", "").replace("අමතන්න", "").trim()
            makePhoneCall(target)
            return true
        }

        // Send SMS
        if (clean.startsWith("send sms ") || clean.startsWith("sms ") || clean.contains("කෙටි පණිවිඩයක්")) {
            handleSmsVoiceProcessing(text)
            return true
        }

        // Launch Apps
        if (clean.startsWith("open ") || clean.contains("අරින්න") || clean.contains("ලෝන්ච් කරන්න")) {
            val appLabel = clean.replace("open", "").replace("අරින්න", "").replace("ලෝන්ච් කරන්න", "").trim()
            openSystemApp(appLabel)
            return true
        }

        // Specific actions like lock screen / close app
        if (clean.contains("close app") || clean.contains("close current app") || clean.contains("මෙම ඇප් එක වහන්න") || clean.contains("වහන්න")) {
            closeCurrentAppAction()
            return true
        }

        // Read notifications or read inbox messages
         if (clean.contains("read notifications") || clean.contains("නොටිෆිකේෂන් කියවන්න") || clean.contains("show notifications")) {
            readSystemNotifications()
            return true
         }

        if (clean.contains("open contacts") || clean.contains("කොන්ටැක්ට්ස් අරින්න")) {
            openSystemContacts()
            return true
        }

        if (clean.contains("open camera") || clean.contains("කැමරාව අරින්න")) {
            openSystemCamera()
            return true
        }

        if (clean.contains("open gallery") || clean.contains("ගැලරිය අරින්න")) {
            openSystemGallery()
            return true
        }

        return false
    }

    // --- On-Device Executions ---

    private fun setFlashlightState(on: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.getOrNull(0)
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                val status = if (on) "ඔන් කලා" else "ඕෆ් කලා"
                val replyText = if (Locale.getDefault().language == "si") {
                    "මම ෆ්ලෑෂ් ලයිට් එක $status."
                } else {
                    "Flashlight turned ${if (on) "on" else "off"}."
                }
                saveAndSpeakReply(replyText, "FLASHLIGHT")
            } else {
                saveAndSpeakError("Camera flash hardware undetected.")
            }
        } catch (e: Exception) {
            saveAndSpeakError("Error manipulating flashlight: ${e.localizedMessage}")
        }
    }

    private fun setWifiState(on: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                val reply = if (on) "WiFi enabled." else "WiFi disabled."
                saveAndSpeakReply(reply, "WIFI")
            } else {
                // Settings panel for modern Android
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val reply = "Opening WiFi system configuration panel for adjustment."
                saveAndSpeakReply(reply, "WIFI")
            }
        } catch (e: Exception) {
            saveAndSpeakError("Could not toggle WiFi: ${e.localizedMessage}")
        }
    }

    private fun setBluetoothState(on: Boolean) {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null) {
                saveAndSpeakError("Bluetooth hardware undetected.")
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    if (on) adapter.enable() else adapter.disable()
                    val reply = if (on) "Bluetooth turned on." else "Bluetooth turned off."
                    saveAndSpeakReply(reply, "BLUETOOTH")
                } else {
                    promptBluetoothSettings()
                }
            } else {
                promptBluetoothSettings()
            }
        } catch (e: Exception) {
            saveAndSpeakError("Could not toggle Bluetooth: ${e.localizedMessage}")
        }
    }

    private fun promptBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        saveAndSpeakReply("Launching system bluetooth settings dashboard.", "BLUETOOTH")
    }

    private fun changeVolume(increase: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val delta = if (increase) 2 else -2
            val target = (current + delta).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            
            val reply = if (Locale.getDefault().language == "si") {
                "වොලියම් වෙනස් කලා."
            } else {
                "Volume level adjusted successfully."
            }
            saveAndSpeakReply(reply, "VOLUME")
        } catch (e: Exception) {
            saveAndSpeakError("Failed to alter volume level.")
        }
    }

    private fun openSystemContacts() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Opening contacts panel.", "OPEN_APP")
        } catch (e: Exception) {
            saveAndSpeakError("Couldn't open Contacts.")
        }
    }

    private fun openSystemCamera() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                component = ComponentName("com.android.camera", "com.android.camera.Camera")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Opening device camera.", "OPEN_APP")
        } catch (e: Exception) {
            // General capture intent fallback
            val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                saveAndSpeakReply("Directing to Camera dashboard.", "OPEN_APP")
            } catch (ex: Exception) {
                saveAndSpeakError("Failed to load Camera app.")
            }
        }
    }

    private fun openSystemGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Showing system pictures gallery.", "OPEN_APP")
        } catch (e: Exception) {
            saveAndSpeakError("Could not launch Photo Gallery app.")
        }
    }

    private fun readSystemNotifications() {
        val notifications = TutuNotificationService.getRecentNotifications()
        if (notifications.isEmpty()) {
            val reply = if (Locale.getDefault().language == "si") {
                "ඔබට අලුත් නොටිෆිකේෂන් කිසිවක් නැත."
            } else {
                "You do not have any new notification messages right now."
            }
            saveAndSpeakReply(reply, "NOTIFICATIONS")
        } else {
            val summary = java.lang.StringBuilder()
            if (Locale.getDefault().language == "si") {
                summary.append("ඔබට ලැබී ඇති පණිවිඩ: ")
            } else {
                summary.append("Here are your latest incoming notification texts: ")
            }
            
            notifications.take(3).forEachIndexed { i, n ->
                val appLabel = getAppNameFromPackage(n.packageName)
                summary.append("Notification ${i + 1} from $appLabel. Title: ${n.title}. Text: ${n.text}. ")
            }
            
            saveAndSpeakReply(summary.toString(), "NOTIFICATIONS")
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun closeCurrentAppAction() {
        // Accessibility global trigger to go Home
        val intent = Intent(TutuAccessibilityService.ACTION_ACCESSIBILITY_TRIGGER).apply {
            putExtra(TutuAccessibilityService.EXTRA_GESTURE, TutuAccessibilityService.GESTURE_HOME)
        }
        context.sendBroadcast(intent)
        val replyText = if (Locale.getDefault().language == "si") {
            "ඇප් එක වසා මම හෝම් ස්ක්‍රීන් එකට යනවා."
        } else {
            "Closing app and returning Home."
        }
        saveAndSpeakReply(replyText, "CLOSE_APP")
    }

    private fun makePhoneCall(query: String) {
        val number = if (query.any { it.isDigit() }) query else {
            getPhoneNumberForContactName(query)
        }

        if (!number.isNullOrEmpty()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    saveAndSpeakReply("Calling $query now.", "CALL")
                } catch (e: Exception) {
                    openDialerFallback(number, query)
                }
            } else {
                openDialerFallback(number, query)
            }
        } else {
            saveAndSpeakError("Contact number not located for '$query'.")
        }
    }

    private fun openDialerFallback(number: String, contactLabel: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Launching dial pad with number for $contactLabel.", "CALL")
        } catch (e: Exception) {
            saveAndSpeakError("Dialer action failed.")
        }
    }

    private fun handleSmsVoiceProcessing(text: String) {
        val remainder = text.replace("send sms to", "", true).replace("sms", "", true).trim()
        val indexSaying = remainder.lowercase().indexOf(" saying ")
        if (indexSaying != -1) {
            val contactName = remainder.substring(0, indexSaying).trim()
            val messageText = remainder.substring(indexSaying + 8).trim()
            sendDirectSms(contactName, messageText)
        } else {
            val parts = remainder.split(" ", limit = 2)
            if (parts.size == 2) {
                sendDirectSms(parts[0], parts[1])
            } else {
                saveAndSpeakError("Could not fully resolve your SMS target. Say e.g., 'send SMS to sam saying I am running late'.")
            }
        }
    }

    private fun sendDirectSms(contact: String, messageText: String) {
        val number = if (contact.any { it.isDigit() }) contact else getPhoneNumberForContactName(contact)
        if (!number.isNullOrEmpty()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                    smsManager.sendTextMessage(number, null, messageText, null, null)
                    saveAndSpeakReply("Text message successfully dispatched directly to $contact.", "SMS")
                } catch (e: Exception) {
                    launchSystemSmsFallback(number, messageText, contact)
                }
            } else {
                launchSystemSmsFallback(number, messageText, contact)
            }
        } else {
            saveAndSpeakError("No suitable phone contact found for '$contact'.")
        }
    }

    private fun launchSystemSmsFallback(number: String, body: String, contactName: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Launching messages screen prefilled for $contactName.", "SMS")
        } catch (e: Exception) {
            saveAndSpeakError("Unable to open device SMS composer.")
        }
    }

    private fun openSystemApp(appLabel: String) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var targetPackage: String? = null
        var matchedLabel = appLabel

        // Match localized custom shortcuts
        val cleanLabel = appLabel.lowercase(Locale.ROOT)
        val packageMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "youtube" to "com.google.android.youtube",
            "tiktok" to "com.zhiliaoapp.musically",
            "settings" to "com.android.settings",
            "preferences" to "com.android.settings",
            "chrome" to "com.android.chrome"
        )
        for ((key, value) in packageMap) {
            if (cleanLabel.contains(key)) {
                targetPackage = value
                matchedLabel = key.replaceFirstChar { it.uppercase() }
                break
            }
        }

        if (targetPackage == null) {
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.lowercase().contains(cleanLabel) || cleanLabel.contains(label.lowercase())) {
                    targetPackage = app.packageName
                    matchedLabel = label
                    break
                }
            }
        }

        if (targetPackage != null) {
            try {
                val intent = pm.getLaunchIntentForPackage(targetPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    saveAndSpeakReply("Launching $matchedLabel app.", "OPEN_APP")
                } else {
                    saveAndSpeakError("Found $matchedLabel, but custom configuration blocks direct launch.")
                }
            } catch (e: Exception) {
                saveAndSpeakError("Failure starting application: ${e.localizedMessage}")
            }
        } else {
            saveAndSpeakError("I couldn't locate any app matching the voice description '$appLabel' on this device.")
        }
    }

    private fun scheduleAlarm(timeText: String) {
        val time = parseTimeSpec(timeText)
        if (time != null) {
            val (hour, minute) = time
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Tutu Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val reply = String.format("Alarm scheduled successfully for %02d:%02d.", hour, minute)
                saveAndSpeakReply(reply, "ALARM")
            } catch (e: Exception) {
                saveAndSpeakError("Cannot set alarms through direct interface.")
            }
        } else {
            saveAndSpeakError("Time pattern unrecognized. Try saying 'Set alarm for 6 AM' or similar.")
        }
    }

    private fun createReminder(note: String) {
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, "Tutu Voice Reminder: $note")
                putExtra(CalendarContract.Events.DESCRIPTION, "Added automatically via Tutu AI Voice Assistant.")
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            saveAndSpeakReply("Launching calendar with details populated for reminder: $note.", "ALARM")
        } catch (e: Exception) {
            saveAndSpeakError("Unable to compile calendar agenda.")
        }
    }

    // --- Helpers ---

    private fun saveAndSpeakReply(text: String, tag: String) {
        scope.launch {
            repository.insertMessage(ChatMessage(sender = "assistant", text = text, isCommand = true, commandType = tag))
            speakResponseDirectly(text)
        }
    }

    private fun saveAndSpeakError(text: String) {
        scope.launch {
            repository.insertMessage(ChatMessage(sender = "assistant", text = text))
            speakResponseDirectly(text)
        }
    }

    private fun speakResponseDirectly(text: String) {
        if (isTtsInitialized) {
            _assistantState.value = AssistantState.SPEAKING
            // Queue flush to start instantly
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TUTU_SPEECH_UTTERANCE")
        } else {
            _assistantState.value = AssistantState.IDLE
        }
    }

    private fun getPhoneNumberForContactName(name: String): String? {
        val cr = context.contentResolver
        var number: String? = null
        try {
            val cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    number = it.getString(index)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return number
    }

    private fun parseTimeSpec(text: String): Pair<Int, Int>? {
        // Standard formats: "6:30 am", "18:00", "7 pm"
        val clean = text.lowercase(Locale.ROOT)
        val timePattern = """(\d{1,2})(:?)(\d{0,2})\s*(am|pm)?""".toRegex()
        val match = timePattern.find(clean)
        if (match != null) {
            var hour = match.groupValues[1].toInt()
            val separator = match.groupValues[2]
            var minute = 0
            if (separator.isNotEmpty() && match.groupValues[3].isNotEmpty()) {
                minute = match.groupValues[3].toInt()
            }
            val ampm = match.groupValues[4]
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return Pair(hour, minute)
        }
        return null
    }

    fun clearChatHistory() {
        scope.launch {
            repository.clearHistory()
        }
    }

    fun deleteChatMessage(id: Long) {
        scope.launch {
            repository.deleteMessage(id)
        }
    }

    fun cleanUp() {
        try {
            speechRecognizer?.destroy()
            tts?.stop()
            tts?.shutdown()
            job.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
