package com.example.soundstage

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String
)

@Composable
fun ScannerScreen(
    status: String,
    devices: List<FoundDevice>,
    scanning: Boolean,
    receivedText: String,
    gattReady: Boolean,
    audioStreaming: Boolean,
    playIncoming: Boolean,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onConnect: (FoundDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSend: () -> Unit,
    onToggleAudio: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            "SoundStage",
            style = MaterialTheme.typography.headlineMedium
        )

        Text("BLE Hearing Aid Scanner")

        Text("Status: $status")

        Text("Received: $receivedText")

        Button(
            onClick = onSend,
            enabled = gattReady && !audioStreaming,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send test data")
        }

        Button(
            onClick = onToggleAudio,
            enabled = gattReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (audioStreaming) "Stop audio" else "Start audio")
        }

        Button(
            onClick = onTogglePlay,
            enabled = gattReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (playIncoming) "Stop playback" else "Play incoming audio")
        }

        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan BLE Devices")
        }

        if (scanning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Scan")
            }
            CircularProgressIndicator()
        }

        Button(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Disconnect")
        }

        HorizontalDivider()

        Text(
            "Nearby Devices (${devices.size})",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = devices,
                key = { it.address }
            ) { item ->

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(item.name)
                        Text(
                            item.address,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Button(
                            onClick = { onConnect(item) }
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}