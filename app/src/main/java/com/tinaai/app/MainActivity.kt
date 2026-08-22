package com.tinaai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tina.webrtc.CallScreen

/**
 * Entry point. Handles camera/mic permissions, then a minimal room-entry
 * screen before dropping into the call. Replace the room-entry screen with
 * your real matchmaking flow when that's ready — this is just enough to
 * test the pipeline end to end on two devices.
 */
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted = hasRequiredPermissions()
        if (!permissionsGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }

        setContent {
            MaterialTheme {
                if (permissionsGranted) {
                    TinaApp()
                } else {
                    PermissionRationale { permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    ) }
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return camera == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun PermissionRationale(onRequestAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("TINA needs camera and microphone access to make video calls.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestAgain) { Text("Grant permissions") }
    }
}

/** Minimal room-entry UI → CallScreen. Swap for real matchmaking later. */
@Composable
private fun TinaApp() {
    var roomId by remember { mutableStateOf("") }
    var isCaller by remember { mutableStateOf(true) }
    var inCall by remember { mutableStateOf(false) }

    if (inCall) {
        CallScreen(
            roomId = roomId,
            isCaller = isCaller,
            myLanguageTag = "en-IN",     // TODO: pull from user's language setting
            myBaseLanguage = "en",       // TODO: pull from user's language setting
            peerBaseLanguage = "hi",     // TODO: exchange via RTDB room metadata
            onCallEnded = { inCall = false }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("TINA AI — test call", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = roomId,
                onValueChange = { roomId = it },
                label = { Text("Room ID (same on both devices)") }
            )
            Spacer(Modifier.height(16.dp))
            Row {
                FilterChip(selected = isCaller, onClick = { isCaller = true }, label = { Text("Caller") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !isCaller, onClick = { isCaller = false }, label = { Text("Callee") })
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { inCall = true }, enabled = roomId.isNotBlank()) {
                Text("Start call")
            }
        }
    }
}
