package com.tina.webrtc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tina.character.TinaCharacterView
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    roomId: String,
    isCaller: Boolean,
    myLanguageTag: String,   // e.g. "en-IN" — for speech recognition
    myBaseLanguage: String,  // e.g. "en" — ML Kit translation source
    peerBaseLanguage: String, // e.g. "ru" — ML Kit translation target
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = viewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val cameraEnabled by viewModel.cameraEnabled.collectAsState()
    val myLiveCaption by viewModel.myLiveCaption.collectAsState()
    val peerCaption by viewModel.peerCaption.collectAsState()
    val tinaExpression by viewModel.tinaExpression.collectAsState()

    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(localRenderer, remoteRenderer) {
        val local = localRenderer
        val remote = remoteRenderer
        if (local != null && remote != null) {
            viewModel.startCall(
                roomId, isCaller, myLanguageTag, myBaseLanguage, peerBaseLanguage, local, remote
            )
        }
    }

    LaunchedEffect(callState) {
        if (callState == CallState.ENDED) onCallEnded()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Remote video fills the screen
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceViewRenderer(context).also { remoteRenderer = it }
            }
        )

        // Local preview, small, top-right corner
        AndroidView(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(width = 120.dp, height = 160.dp),
            factory = { context ->
                SurfaceViewRenderer(context).also { localRenderer = it }
            }
        )

        // Connection status
        if (callState == CallState.CONNECTING) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(text = "Connecting...", color = Color.White)
                }
            }
        }

        // TINA — sits centered, above the peer caption she's "speaking"
        if (callState == CallState.IN_CALL) {
            TinaCharacterView(
                expression = tinaExpression,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 210.dp)
            )
        }

        // Peer's translated speech — the main caption, larger and prominent
        if (peerCaption.isNotBlank() && callState == CallState.IN_CALL) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp, start = 16.dp, end = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(text = peerCaption, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // My own recognized speech — small, just for my own confirmation
        if (myLiveCaption.isNotBlank() && callState == CallState.IN_CALL) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp, end = 160.dp)
                    .background(Color.Black.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(text = myLiveCaption, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Call controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            FilledIconButton(onClick = { viewModel.toggleMic() }) {
                Icon(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = "Toggle mic")
            }
            FilledIconButton(onClick = { viewModel.toggleCamera() }) {
                Icon(if (cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = "Toggle camera")
            }
            FilledIconButton(onClick = { viewModel.switchCamera() }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
            }
            FilledIconButton(
                onClick = { viewModel.endCall() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Red)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End call")
            }
        }
    }
}
