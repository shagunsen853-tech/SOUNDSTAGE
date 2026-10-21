package com.example.soundstage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    // Audio transfer
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    @Volatile
    private var streaming = false

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
        handler.post(statsUpdater)
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
        currentScreen = when (title) {
            "SoundStage" -> "home"
            "Bluetooth Connection" -> "bluetooth"
            else -> "audio"
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(22, 24, 38))
        }

        val scroll = ScrollView(this)
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

        setStatus(
            if (ready) "Hearing aid ready"
            else "Not connected"
        )
    }

    // ---------------- SCREEN 2: BLUETOOTH ----------------

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

    // ---------------- SCREEN 3: AUDIO TRANSFER ----------------

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
    }

    // ---------------- PERMISSIONS ----------------

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

        if (
            requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        } else {
            setStatus("Bluetooth permission denied")
        }
    }

    // ---------------- BLE SCAN ----------------

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
    }

    // ---------------- BLE CONNECTION ----------------

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
    }

    // ---------------- GATT CALLBACKS ----------------

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
    }

    // ---------------- AUDIO RECEIVE ----------------

    private fun handleAudioPacket(data: ByteArray) {
        if (!streaming || data.isEmpty()) return

        packetCount++
        bytesThisSecond += data.size

        audioQueue.offer(data)
    }

    privat