package com.example.soundstage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        // IMPORTANT: Replace these placeholders with actual TYTO_APP_SPEC values.
        val AUDIO_SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0")

        val AUDIO_DATA_CHAR_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

        val CONTROL_CHAR_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef2")

        val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Supports the two names discussed for your test device.
        // Change this list if your hearing aid advertises another name.
        val DEVICE_NAME_PREFIXES = listOf("TYTO", "ULCONNECTMAX")

        val START_STREAM_CMD = byteArrayOf(0x01)
        val STOP_STREAM_CMD = byteArrayOf(0x00)

        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16

        const val MAX_RETRIES = 3
        const val PERMISSION_REQUEST = 1
        const val MIC_PERMISSION_REQUEST = 2
        const val SPEECH_PERMISSION_REQUEST = 3

        // Headphone hearing mode settings
        const val HEARING_SAMPLE_RATE = 16000
        const val HEARING_GAIN = 2.0f // 1.0 = normal, 2.0 = twice as loud
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var userDisconnected = false
    private var retryCount = 0
    private var scanning = false
    private var ready = false

    private var currentScreen = "home"

    // Soundstage screen
    private var radarView: RadarView? = null
    private val soundstageRefreshers = mutableListOf<() -> Unit>()

    // Live transcript (phone speech-to-text)
    private var speech: SpeechRecognizer? = null
    private var listening = false
    private val transcriptLines = mutableListOf<String>()
    private var transcriptView: TextView? = null
    private var transcriptStatus: TextView? = null
    private var transcriptButton: Button? = null

    // ---------------- PEOPLE (demo data, spec 5.4) ----------------
    // Later this list will come from the hearing aid. For now it is fake data.
    data class Person(
        val id: Int,
        val name: String,
        val where: String,
        val known: Boolean,
        var kept: Boolean,
        val bearing: Float = 0f, // degrees: 0 = front, 90 = right, -90 = left, 180 = behind
        val range: Float = 0.6f  // 0 = close, 1 = far (approximate)
    )

    private val people = mutableListOf(
        Person(1, "Mar\u00eda", "", known = true, kept = true, bearing = -35f, range = 0.5f),
        Person(2, "Leah", "", known = false, kept = true, bearing = 30f, range = 0.5f),
        Person(4, "Speaker 4", "table on your left", known = false, kept = false, bearing = -95f, range = 0.85f),
        Person(5, "Speaker 5", "table on your right", known = false, kept = false, bearing = 100f, range = 0.85f),
        Person(6, "Speaker 6", "behind you", known = false, kept = false, bearing = 180f, range = 0.85f)
    )

    // Audio transfer
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    @Volatile
    private var streaming = false

    // Headphone hearing mode (phone mic -> Bluetooth headphones)
    private var hearingRecord: AudioRecord? = null
    private var hearingTrack: AudioTrack? = null
    private var hearingThread: Thread? = null

    @Volatile
    private var hearingOn = false

    private var wavFile: File? = null
    private var wavOut: FileOutputStream? = null
    private var wavDataBytes = 0L

    @Volatile
    private var packetCount = 0L

    @Volatile
    private var bytesThisSecond = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHomeScreen()
        handler.post(statusUpdater)
    }

    // ---------------- UI HELPERS ----------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeText(
        value: String,
        size: Float = 16f
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun beginScreen(title: String) {
        if (listening) stopTranscript()
        radarView = null
        soundstageRefreshers.clear()
        transcriptView = null
        transcriptStatus = null
        transcriptButton = null

        currentScreen = when (title) {
            "SoundStage" -> "home"
            "Bluetooth Connection" -> "bluetooth"
            "People in range" -> "people"
            "Soundstage" -> "soundstage"
            else -> "audio"
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(22, 24, 38))
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Color.rgb(22, 24, 38))
        scroll.addView(root)

        setContentView(scroll)

        val heading = makeText(title, 27f)
        heading.gravity = Gravity.CENTER_VERTICAL
        root.addView(heading)

        root.addView(makeText("SoundStage Hearing Aid App", 14f))
        root.addView(makeText("Status: ", 16f).also {
            statusText = it
        })

        statsText = makeText("", 13f)
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {
        root.addView(
            makeButton(label, action),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            if (::statusText.isInitialized) {
                statusText.text = "Status: $message"
            }
        }
    }

    // ---------------- SCREEN 1: SOUNDSTAGE ----------------

    private fun showHomeScreen() {
        beginScreen("SoundStage")

        root.addView(
            makeText(
                "Connect your hearing aid and transfer audio.",
                17f
            )
        )

        addButton("Connect Hearing Aid") {
            showBluetoothScreen()
            checkPermissionsAndScan()
        }

        addButton("Soundstage (Radar + Transcript)") {
            showSoundstageScreen()
        }

        addButton("People (Keep / Mute)") {
            showPeopleScreen()
        }

        addButton("Open Audio Transfer") {
            if (ready && gatt != null) {
                showAudioScreen()
            } else {
                setStatus("Connect the hearing aid first")
            }
        }

        addButton("Disconnect") {
            disconnect()
            showHomeScreen()
            setStatus("Disconnected")
        }

        root.addView(
            makeText(
                "Or use normal Bluetooth headphones: pair them in " +
                        "phone Bluetooth settings, then tap Start.",
                15f
            )
        )

        addButton("Start Headphone Hearing") {
            if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    MIC_PERMISSION_REQUEST
                )
            } else {
                startHearing()
            }
        }

        addButton("Stop Headphone Hearing") {
            stopHearing()
        }

        setStatus(
            if (ready) "Hearing aid ready"
            else "Not connected"
        )
    }

    // ---------------- SCREEN: SOUNDSTAGE (spec 5.3) ----------------

    private fun directionName(bearing: Float): String {
        var b = bearing % 360f
        if (b > 180f) b -= 360f
        if (b < -180f) b += 360f
        val a = abs(b)
        val side = if (b > 0) "right" else "left"
        return when {
            a <= 22.5f -> "Front"
            a <= 67.5f -> "Front-$side"
            a <= 112.5f -> side.replaceFirstChar { it.uppercase() }
            a <= 157.5f -> "Back-$side"
            else -> "Behind"
        }
    }

    private fun showSoundstageScreen() {
        beginScreen("Soundstage")

        root.addView(makeText("Your conversation \u2013 tap a person to keep or mute.", 15f))

        val radar = RadarView(this, people) { person ->
            person.kept = !person.kept
            sendKeepMute(person)
        }
        radarView = radar
        root.addView(
            radar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        root.addView(
            makeText("Demo positions \u2022 Bearing exact, distance approximate", 12f)
                .also {
                    it.setTextColor(Color.rgb(150, 160, 180))
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
        )

        // Sound source list
        for (person in people) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    person.kept = !person.kept
                    sendKeepMute(person)
                }
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(12) })

            val name = makeText(if (person.known) "${person.name}  \u2605" else person.name, 17f)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val info = makeText("", 15f)
            row.addView(info)

            val refresh = {
                (dot.background as GradientDrawable).setColor(
                    if (person.kept) RadarView.TEAL else RadarView.GREY
                )
                name.setTextColor(if (person.kept) Color.WHITE else Color.rgb(140, 145, 160))
                info.text = directionName(person.bearing) +
                        if (person.kept) "" else " \u00b7 muted"
                info.setTextColor(Color.rgb(170, 178, 195))
            }
            refresh()
            soundstageRefreshers.add(refresh)
            root.addView(row)
        }

        // Transcript card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(30, 36, 52))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            makeText("Transcript", 20f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(makeButton("Clear") {
            transcriptLines.clear()
            updateTranscriptText(null)
        })
        val startBtn = makeButton(if (listening) "Stop" else "Start") {
            if (listening) stopTranscript() else requestTranscript()
        }
        transcriptButton = startBtn
        header.addView(startBtn)
        card.addView(header)

        val status = makeText("Tap Start and speak. Live transcript, may contain errors.", 13f)
            .also { it.setTextColor(Color.rgb(150, 160, 180)) }
        transcriptStatus = status
        card.addView(status)

        val body = makeText("", 17f)
        transcriptView = body
        card.addView(body)
        updateTranscriptText(null)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )

        addButton("Back to SoundStage") {
            showHomeScreen()
        }

        val keptCount = people.count { it.kept }
        setStatus("$keptCount people in your conversation")
    }

    // ---------------- LIVE TRANSCRIPT ----------------

    private fun updateTranscriptText(partial: String?) {
        val view = transcriptView ?: return
        val lines = transcriptLines.takeLast(12).toMutableList()
        if (!partial.isNullOrBlank()) lines.add("\u2026 $partial")
        view.text =
            if (lines.isEmpty()) "Start speaking. The words will appear here."
            else lines.joinToString("\n")
        view.setTextColor(if (lines.isEmpty()) Color.rgb(150, 160, 180) else Color.WHITE)
    }

    private fun requestTranscript() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                SPEECH_PERMISSION_REQUEST
            )
        } else {
            startTranscript()
        }
    }

    private fun startTranscript() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            transcriptStatus?.text = "Speech-to-text not available on this phone"
            return
        }

        // Only one thing can use the mic at a time.
        if (hearingOn) stopHearing()

        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(speechListener)
            }
        }

        listening = true
        transcriptButton?.text = "Stop"
        transcriptStatus?.text = "Listening continuously\u2026"
        listenOnce()
    }

    private fun listenOnce() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speech?.startListening(intent)
        } catch (e: Exception) {
            transcriptStatus?.text = "Could not start listening: ${e.message}"
        }
    }

    private fun stopTranscript() {
        listening = false
        try {
            speech?.cancel()
        } catch (_: Exception) {
        }
        transcriptButton?.text = "Start"
        transcriptStatus?.text = "Stopped"
        updateTranscriptText(null)
    }

    private val speechListener: RecognitionListener git= object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            updateTranscriptText(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                transcriptLines.add(text)
                if (transcriptLines.size > 50) transcriptLines.removeAt(0)
            }
            updateTranscriptText(null)
            // Keep listening: start the next round.
            handler.postDelayed({ listenOnce() }, 250)
        }

        override fun onError(error: Int) {
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopTranscript()
                    transcriptStatus?.text = "Microphone permission needed"
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Recreate the recognizer and try again.
                    speech?.destroy()
                    speech = SpeechRecognizer.createSpeechRecognizer(this@MainActivity).apply {
                        setRecognitionListener(this@MainActivity.speechListener)
                    }
                    handler.postDelayed({ listenOnce() }, 500)
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    transcriptStatus?.text = "No internet for speech-to-text. Retrying\u2026"
                    handler.postDelayed({ listenOnce() }, 1500)
                }
                else -> {
                    // No speech / timeout: just listen again.
                    handler.postDelayed({ listenOnce() }, 250)
                }
            }
        }
    }

    // ---------------- SCREEN: PEOPLE (KEEP / MUTE) ----------------

    private fun showPeopleScreen() {
        beginScreen("People in range")

        root.addView(makeText("Keep them in your conversation, or mute.", 16f))
        root.addView(
            makeText("Demo data \u2013 not connected to a real hearing aid yet.", 13f)
                .also { it.setTextColor(Color.rgb(150, 160, 180)) }
        )

        for (person in people) {
            root.addView(makePersonRow(person))
        }

        addButton("\u2606  Add a known person by voice") {
            Toast.makeText(this, "Coming soon (VoiceID)", Toast.LENGTH_SHORT).show()
        }

        addButton("Back to SoundStage") {
            showHomeScreen()
        }

        val keptCount = people.count { it.kept }
        setStatus("$keptCount people in your conversation")
    }

    private fun makePersonRow(person: Person): LinearLayout {
        val teal = Color.rgb(94, 186, 170)
        val grey = Color.rgb(60, 66, 80)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        // Round avatar
        val avatar = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (person.kept) teal else grey)
            }
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(14)
        })

        // Name + subtitle
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameText = makeText(
            if (person.known) "${person.name}  \u2605" else person.name,
            18f
        ).apply { setPadding(0, 0, 0, 0) }
        val subText = makeText("", 13f).apply { setPadding(0, dp(2), 0, 0) }
        texts.addView(nameText)
        texts.addView(subText)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun refresh() {
            (avatar.background as GradientDrawable).setColor(if (person.kept) teal else grey)
            nameText.setTextColor(if (person.kept) Color.WHITE else Color.rgb(140, 145, 160))
            val parts = mutableListOf(if (person.kept) "In your conversation" else "Muted")
            if (person.known) parts.add("known person")
            if (person.where.isNotEmpty()) parts.add(person.where)
            subText.text = parts.joinToString(" \u00b7 ")
            subText.setTextColor(if (person.kept) teal else Color.rgb(120, 125, 140))
        }

        // Keep / Mute switch
        val toggle = Switch(this).apply {
            isChecked = person.kept
            contentDescription = "Keep ${person.name} in conversation"
            setOnCheckedChangeListener { _, checked ->
                person.kept = checked
                refresh()
                sendKeepMute(person)
            }
        }
        row.addView(toggle)

        refresh()
        return row
    }

    // Later: send this to the hearing aid (spec "App -> Aid downlink").
    // For now it only updates the status line.
    private fun sendKeepMute(person: Person) {
        radarView?.invalidate()
        soundstageRefreshers.forEach { it() }
        val keptCount = people.count { it.kept }
        setStatus(
            (if (person.kept) "${person.name} kept" else "${person.name} muted") +
                    " \u2013 $keptCount in your conversation"
        )
    }// ---------------- SCREEN 3: AUDIO TRANSFER ----------------
    private fun showBluetoothScreen() {
        beginScreen("Bluetooth Connection")

        root.addView(
            makeText(
                "Scan for your hearing aid. Keep it powered on and nearby."
            )
        )

        addButton("Scan / Connect") {
            checkPermissionsAndScan()
        }

        addButton("Stop Scan") {
            stopScan()
            setStatus("Scan stopped")
        }

        addButton("Disconnect") {
            disconnect()
        }

        addButton("Back to SoundStage") {
            showHomeScreen()
        }

        if (ready) {
            addButton("Continue to Audio Transfer") {
                showAudioScreen()
            }
        }

        setStatus(
            if (ready) "Connected and ready"
            else if (scanning) "Scanning..."
            else "Ready to scan"
        )
    }
    private fun showAudioScreen() {
        beginScreen("Audio Transfer")

        root.addView(
            makeText(
                "Audio is received through BLE notifications when " +
                        "the hearing aid supports the configured audio service."
            )
        )

        root.addView(statsText)

        addButton("Start Audio") {
            startStreaming()
        }

        addButton("Stop Audio") {
            stopStreaming()
        }

        addButton("Back to Bluetooth Connection") {
            showBluetoothScreen()
        }

        addButton("Disconnect") {
            disconnect()
            showBluetoothScreen()
        }

        setStatus(
            if (ready) "Connected"
            else "Not connected"
        )
    }// ---------------- PERMISSIONS ----------------

    private val requiredPermissions: Array<String>
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

    private fun checkPermissionsAndScan() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startScan()
        } else {
            requestPermissions(
                missing.toTypedArray(),
                PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == SPEECH_PERMISSION_REQUEST) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startTranscript()
            } else {
                transcriptStatus?.text = "Microphone permission denied"
            }
            return
        }

        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startHearing()
            } else {
                setStatus("Microphone permission denied")
            }
            return
        }

        if (
            requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        } else {
            setStatus("Bluetooth permission denied")
        }
    }// ---------------- BLE SCAN ----------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val deviceName =
                try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                } ?: return

            val matches = DEVICE_NAME_PREFIXES.any {
                deviceName.startsWith(it, ignoreCase = true)
            }

            if (!matches) return

            stopScan()

            setStatus("Found $deviceName. Connecting...")
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("Scan failed: $errorCode")
        }
    }

    private fun startScan() {
        val adapter = btAdapter
            ?: return setStatus("Bluetooth not supported")

        if (!adapter.isEnabled) {
            return setStatus("Turn Bluetooth on")
        }

        if (scanning) return

        userDisconnected = false
        ready = false
        retryCount = 0
        scanning = true

        setStatus("Scanning for hearing aid...")

        try {
            adapter.bluetoothLeScanner?.startScan(scanCallback)
                ?: run {
                    scanning = false
                    setStatus("BLE scanner unavailable")
                    return
                }
        } catch (e: SecurityException) {
            scanning = false
            setStatus("Bluetooth permission error")
            return
        } catch (e: Exception) {
            scanning = false
            setStatus("Unable to start scan: ${e.message}")
            return
        }

        handler.postDelayed({
            if (scanning) {
                stopScan()
                setStatus("Device not found. Check device name and range.")
            }
        }, 15000)
    }

    private fun stopScan() {
        if (!scanning) return

        scanning = false

        try {
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Scanner may already be stopped.
        }
    }// ---------------- BLE CONNECTION ----------------

    private fun connect(device: BluetoothDevice) {
        lastDevice = device
        ready = false

        try {
            gatt?.close()
            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            setStatus("Connection failed: ${e.message}")
        }
    }

    private fun disconnect() {
        userDisconnected = true
        ready = false
        stopScan()
        stopStreaming()

        try {
            gatt?.disconnect()
        } catch (_: Exception) {
            gatt?.close()
            gatt = null
        }

        setStatus("Disconnecting...")
    }// ---------------- GATT CALLBACKS ----------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                retryCount = 0
                setStatus("Connected. Setting up BLE...")
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                stopStreaming()

                try {
                    g.close()
                } catch (_: Exception) {
                }

                if (gatt == g) gatt = null

                if (
                    !userDisconnected &&
                    retryCount < MAX_RETRIES
                ) {
                    retryCount++
                    setStatus(
                        "Disconnected ($status). Retry " +
                                "$retryCount/$MAX_RETRIES..."
                    )

                    handler.postDelayed({
                        if (!userDisconnected) {
                            lastDevice?.let { connect(it) }
                        }
                    }, 1500L * retryCount)
                } else {
                    setStatus("Disconnected")
                }
            }
        }

        override fun onMtuChanged(
            g: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            setStatus("MTU $mtu. Discovering services...")

            try {
                g.requestConnectionPriority(
                    BluetoothGatt.CONNECTION_PRIORITY_HIGH
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    g.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                }
            } catch (_: Exception) {
                // Connection can still continue without these optimizations.
            }

            g.discoverServices()
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Service discovery failed: $status")
                return
            }

            val service = g.getService(AUDIO_SERVICE_UUID)
            val characteristic =
                service?.getCharacteristic(AUDIO_DATA_CHAR_UUID)

            if (characteristic == null) {
                ready = false
                setStatus(
                    "Audio characteristic not found. " +
                            "Verify service and characteristic UUIDs."
                )
                return
            }

            enableNotifications(g, characteristic)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (
                descriptor.uuid == CCCD_UUID &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                ready = true
                setStatus("BLE ready. Open Audio Transfer.")

                runOnUiThread {
                    if (currentScreen == "bluetooth") {
                        showBluetoothScreen()
                    }
                }
            } else {
                ready = false
                setStatus("Notification setup failed: $status")
            }
        }

        // Android 13 and newer
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == AUDIO_DATA_CHAR_UUID) {
                handleAudioPacket(value)
            }
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == AUDIO_DATA_CHAR_UUID
            ) {
                @Suppress("DEPRECATION")
                handleAudioPacket(characteristic.value.copyOf())
            }
        }
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val enabled = g.setCharacteristicNotification(
            characteristic,
            true
        )

        if (!enabled) {
            setStatus("Could not enable notifications")
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)

        if (descriptor == null) {
            setStatus("CCCD descriptor missing")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )

            if (result != BluetoothStatusCodes.SUCCESS) {
                setStatus("Could not write notification descriptor")
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            @Suppress("DEPRECATION")
            val started = g.writeDescriptor(descriptor)

            if (!started) {
                setStatus("Could not write notification descriptor")
            }
        }
    }

    private fun writeControl(command: ByteArray) {
        val currentGatt = gatt ?: return

        val characteristic = currentGatt
            .getService(AUDIO_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHAR_UUID)
            ?: run {
                setStatus("Control characteristic not found")
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = currentGatt.writeCharacteristic(
                    characteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    setStatus("Control command failed: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command

                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                @Suppress("DEPRECATION")
                val started =
                    currentGatt.writeCharacteristic(characteristic)

                if (!started) {
                    setStatus("Control command could not start")
                }
            }
        } catch (e: Exception) {
            setStatus("Control write error: ${e.message}")
        }
    }// ---------------- AUDIO RECEIVE ----------------

    private fun handleAudioPacket(data: ByteArray) {
        if (!streaming || data.isEmpty()) return

        packetCount++
        bytesThisSecond += data.size

        audioQueue.offer(data)
    }

    // ---------------- AUDIO START / STOP ----------------

    private fun startStreaming() {
        if (streaming) {
            setStatus("Audio already running")
            return
        }

        if (!ready || gatt == null) {
            setStatus("Connect the hearing aid first")
            return
        }

        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            channelMask,
            encoding
        )

        if (minBuf <= 0) {
            setStatus("Audio output not supported on this phone")
            return
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            setStatus("Could not create audio player: ${e.message}")
            return
        }

        audioTrack = track
        audioQueue.clear()
        packetCount = 0L
        bytesThisSecond = 0L

        openWavFile()

        streaming = true
        track.play()

        audioThread = Thread {
            // A 16-bit sample is 2 bytes. If a packet ends in the middle
            // of a sample, keep the last byte and join it with the next packet.
            var leftover: Byte? = null

            while (streaming) {
                val packet = try {
                    audioQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                val bytes = if (leftover != null) {
                    byteArrayOf(leftover!!) + packet
                } else {
                    packet
                }

                val usable = bytes.size - (bytes.size % 2)
                leftover = if (usable < bytes.size) bytes[bytes.size - 1] else null

                if (usable > 0) {
                    try {
                        track.write(bytes, 0, usable)
                    } catch (_: Exception) {
                    }
                    writeWav(bytes, usable)
                }
            }
        }.also { it.start() }

        writeControl(START_STREAM_CMD)
        setStatus("Audio started. Waiting for packets...")
    }

    private fun stopStreaming() {
        if (!streaming) return

        streaming = false

        // Ask the hearing aid to stop sending, only if still connected.
        if (ready && gatt != null) {
            writeControl(STOP_STREAM_CMD)
        }

        try {
            audioThread?.join(500)
        } catch (_: InterruptedException) {
        }
        audioThread = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        audioTrack?.release()
        audioTrack = null

        audioQueue.clear()

        val saved = closeWavFile()
        setStatus(
            if (saved != null) "Audio stopped. Saved: ${saved.name}"
            else "Audio stopped"
        )
    }// ---------------- WAV RECORDING ----------------

    private fun openWavFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "soundstage_${System.currentTimeMillis()}.wav")
            val out = FileOutputStream(file)
            out.write(ByteArray(44)) // placeholder header, filled in on close
            wavFile = file
            wavOut = out
            wavDataBytes = 0L
        } catch (_: Exception) {
            wavFile = null
            wavOut = null
        }
    }

    private fun writeWav(data: ByteArray, length: Int) {
        val out = wavOut ?: return
        try {
            out.write(data, 0, length)
            wavDataBytes += length
        } catch (_: Exception) {
        }
    }

    private fun closeWavFile(): File? {
        val out = wavOut ?: return null
        val file = wavFile
        wavOut = null
        wavFile = null

        try {
            out.flush()
            out.close()
        } catch (_: Exception) {
        }

        if (file == null || wavDataBytes == 0L) {
            file?.delete()
            return null
        }

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(wavHeader(wavDataBytes))
            }
        } catch (_: Exception) {
            return null
        }

        return file
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteArray(44)

        fun putInt(pos: Int, v: Long) {
            header[pos] = (v and 0xFF).toByte()
            header[pos + 1] = ((v shr 8) and 0xFF).toByte()
            header[pos + 2] = ((v shr 16) and 0xFF).toByte()
            header[pos + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putShort(pos: Int, v: Int) {
            header[pos] = (v and 0xFF).toByte()
            header[pos + 1] = ((v shr 8) and 0xFF).toByte()
        }

        "RIFF".toByteArray().copyInto(header, 0)
        putInt(4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, CHANNELS)
        putInt(24, SAMPLE_RATE.toLong())
        putInt(28, byteRate.toLong())
        putShort(32, blockAlign)
        putShort(34, BITS_PER_SAMPLE)
        "data".toByteArray().copyInto(header, 36)
        putInt(40, dataSize)

        return header
    }// ---------------- LIVE STATS (every second) ----------------

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStats() {
        val kbps = bytesThisSecond * 8 / 1000
        bytesThisSecond = 0L

        val needed = SAMPLE_RATE * BITS_PER_SAMPLE * CHANNELS / 1000

        if (::statsText.isInitialized) {
            statsText.text =
                (if (streaming) "Streaming" else "Idle") + "\n" +
                        "Packets: $packetCount\n" +
                        "Rate: $kbps kbps (raw PCM needs $needed kbps)\n" +
                        "Buffered: ${audioQueue.size} packets"
        }
    }

    // ---------------- HEADPHONE HEARING MODE ----------------
    // Phone microphone -> normal Bluetooth (or wired) headphones.

    private fun findHeadphones(): AudioDeviceInfo? {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val wanted = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            26, // AudioDeviceInfo.TYPE_BLE_HEADSET (Android 12+)
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (type in wanted) {
            outputs.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    private fun startHearing() {
        if (hearingOn) {
            setStatus("Headphone hearing already on")
            return
        }

        // Without headphones the phone speaker would whistle (feedback).
        val headphones = findHeadphones()
        if (headphones == null) {
            setStatus("No headphones found. Pair them in phone Bluetooth settings first.")
            return
        }

        val inBuf = AudioRecord.getMinBufferSize(
            HEARING_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val outBuf = AudioTrack.getMinBufferSize(
            HEARING_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (inBuf <= 0 || outBuf <= 0) {
            setStatus("Audio not supported on this phone")
            return
        }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                HEARING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                inBuf * 2
            )
        } catch (e: Exception) {
            setStatus("Microphone error: ${e.message}")
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            setStatus("Microphone busy or not available")
            return
        }

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(HEARING_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = try {
            builder.build()
        } catch (e: Exception) {
            rec.release()
            setStatus("Audio output error: ${e.message}")
            return
        }

        // Sound goes to the headphones, mic stays the phone's own mic.
        track.setPreferredDevice(headphones)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { rec.setPreferredDevice(it) }

        hearingRecord = rec
        hearingTrack = track
        hearingOn = true

        rec.startRecording()
        track.play()

        hearingThread = Thread {
            val buf = ShortArray(320) // 20 ms of audio
            while (hearingOn) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    for (i in 0 until n) {
                        val v = (buf[i] * HEARING_GAIN).toInt()
                        buf[i] = v.coerceIn(-32768, 32767).toShort()
                    }
                    track.write(buf, 0, n)
                }
            }
        }.also { it.start() }

        val name = headphones.productName?.toString() ?: "headphones"
        setStatus("Headphone hearing ON ($name). Speak near the phone.")
    }

    private fun stopHearing() {
        if (!hearingOn) {
            setStatus("Headphone hearing is off")
            return
        }

        hearingOn = false

        try {
            hearingThread?.join(500)
        } catch (_: InterruptedException) {
        }
        hearingThread = null

        try {
            hearingRecord?.stop()
        } catch (_: Exception) {
        }
        hearingRecord?.release()
        hearingRecord = null

        try {
            hearingTrack?.stop()
        } catch (_: Exception) {
        }
        hearingTrack?.release()
        hearingTrack = null

        setStatus("Headphone hearing stopped")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (hearingOn) stopHearing()
        listening = false
        try {
            speech?.destroy()
        } catch (_: Exception) {
        }
        speech = null
        disconnect()
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        super.onDestroy()
    }
}// ---------------- RADAR VIEW (Soundstage circle) ----------------

class RadarView(
    context: Context,
    private val people: List<MainActivity.Person>,
    private val onPersonTap: (MainActivity.Person) -> Unit
) : View(context) {

    companion object {
        val TEAL = Color.rgb(94, 186, 170)
        val GREY = Color.rgb(85, 92, 108)
        val ORANGE = Color.rgb(232, 160, 60)
    }

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(60, 70, 92)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ORANGE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 160, 180)
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val dotRadius get() = 13f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w) // square
    }

    private fun maxRadius(): Float = min(width, height) / 2f - 34f * density

    private fun positionOf(p: MainActivity.Person): Pair<Float, Float> {
        val cx = width / 2f
        val cy = height / 2f
        val r = maxRadius() * p.range
        val rad = Math.toRadians(p.bearing.toDouble())
        val x = cx + (r * sin(rad)).toFloat()
        val y = cy - (r * cos(rad)).toFloat()
        return Pair(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = maxRadius()

        // Rings + cross lines
        for (i in 1..3) canvas.drawCircle(cx, cy, maxR * i / 3f, ringPaint)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, ringPaint)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, ringPaint)

        canvas.drawText("FRONT", cx, cy - maxR - 12f * density, labelPaint)

        // Lines from you to people in your conversation (the island)
        for (p in people) {
            if (!p.kept) continue
            val (x, y) = positionOf(p)
            canvas.drawLine(cx, cy, x, y, linePaint)
        }

        // People
        for (p in people) {
            val (x, y) = positionOf(p)
            dotPaint.color = if (p.kept) TEAL else GREY
            canvas.drawCircle(x, y, dotRadius, dotPaint)
            textPaint.color = if (p.kept) Color.WHITE else Color.rgb(140, 145, 160)
            val label = if (p.known) "${p.name} \u2605" else p.name
            canvas.drawText(label, x, y + dotRadius + 16f * density, textPaint)
        }

        // You (center)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 16f * density, dotPaint)
        dotPaint.color = Color.rgb(22, 24, 38)
        canvas.drawCircle(cx, cy, 7f * density, dotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val touchRadius = 30f * density
            var best: MainActivity.Person? = null
            var bestDist = Float.MAX_VALUE
            for (p in people) {
                val (x, y) = positionOf(p)
                val d = (x - event.x) * (x - event.x) + (y - event.y) * (y - event.y)
                if (d < bestDist) {
                    bestDist = d
                    best = p
                }
            }
            if (best != null && bestDist <= touchRadius * touchRadius) {
                onPersonTap(best)
                performClick()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}