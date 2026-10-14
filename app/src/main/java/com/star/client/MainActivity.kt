package com.star.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var starManager: StarManager
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        starManager = StarManager.getInstance(this)
        exoPlayer = ExoPlayer.Builder(this).build()

        setContent {
            StarApp(
                starManager = starManager,
                onPreview = { voice, text, onError -> playTts(voice, text, onError) },
                onSelect = { voice -> starManager.setSelectedVoice(voice) }
            )
        }
    }

    private fun playTts(voice: StarVoice, text: String, onError: (String) -> Unit) {
        if (text.isBlank()) {
            onError("Test text is empty. Please enter some text first.")
            return
        }
        starManager.requestTtsFull(voice, text,
            onComplete = { audioData ->
                if (audioData.isEmpty()) {
                    runOnUiThread { onError("Server returned empty audio. Check that the voice name is valid.") }
                    return@requestTtsFull
                }
                runOnUiThread {
                    try {
                        val tempFile = File(cacheDir, "preview.wav")
                        FileOutputStream(tempFile).use { it.write(audioData) }
                        val mediaItem = MediaItem.fromUri(tempFile.absolutePath)
                        exoPlayer?.setMediaItem(mediaItem)
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    } catch (e: Exception) {
                        onError("Playback error: ${e.message}")
                    }
                }
            },
            onError = { msg -> runOnUiThread { onError(msg) } }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@Composable
fun StarApp(
    starManager: StarManager,
    onPreview: (StarVoice, String, (String) -> Unit) -> Unit,
    onSelect: (StarVoice) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val voices by starManager.voices.collectAsState()
    val servers by starManager.connectedServers.collectAsState()
    val connectionStates by starManager.connectionStates.collectAsState()
    val selectedVoice by starManager.selectedVoice.collectAsState()

    val providerEnabled by starManager.providerEnabled.collectAsState()
    val providerUrl by starManager.providerUrl.collectAsState()
    val providerName by starManager.providerName.collectAsState()
    val providerStatus by starManager.providerStatus.collectAsState()

    var serverUrl by remember { mutableStateOf("") }
    var testText by remember { mutableStateOf("Hello, this is a STAR test.") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(innerPadding)
            .padding(16.dp)
    ) {
        Text("STAR Client", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text("Coagulators", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Coagulator URL") },
            supportingText = { Text("Bare hosts default to wss:// (port 443). Use http://, https://, ws:// or wss:// and add an explicit port for LAN coagulators.") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                starManager.addServer(serverUrl)
                serverUrl = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Coagulator")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Connected Coagulators: ${servers.size}/10")
        servers.forEach { url ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(url)
                    val state = connectionStates[url] ?: ConnState.CONNECTING
                    val label = when (state) {
                        ConnState.CONNECTED -> "connected"
                        ConnState.CONNECTING -> "connecting"
                        ConnState.DISCONNECTED -> "disconnected"
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { starManager.removeServer(url) }) {
                    Text("Remove")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Selected Voice: ${selectedVoice?.name ?: "None"}")

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Test Text") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        val currentVoice = selectedVoice
        Button(
            onClick = {
                if (currentVoice == null) {
                    scope.launch { snackbarHostState.showSnackbar("No voice selected. Tap 'Select Voice' first.") }
                } else {
                    onPreview(currentVoice, testText) { err ->
                        scope.launch { snackbarHostState.showSnackbar(err) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (currentVoice != null) "Preview: ${currentVoice.name}" else "Preview (select a voice first)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        var showVoiceSelector by remember { mutableStateOf(false) }

        Button(onClick = { showVoiceSelector = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Select Voice")  
        }

        if (showVoiceSelector) {
            Dialog(onDismissRequest = { showVoiceSelector = false }) {
                Surface(
                    modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    var searchQuery by remember { mutableStateOf("") }
                    val filteredVoices = voices.filter { it.name.contains(searchQuery, ignoreCase = true) }

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Select a Voice", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search voices (e.g. tom)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(filteredVoices) { voice ->
                                val isSelected = voice == selectedVoice
                                ListItem(
                                    headlineContent = { Text(if (isSelected) "${voice.name} (Selected)" else voice.name) },
                                    supportingContent = { Text(voice.serverUrl) },
                                    trailingContent = {
                                        Row {
                                            TextButton(onClick = {
                                                onSelect(voice)
                                                showVoiceSelector = false
                                            }) {
                                                Text("Select")
                                            }
                                            Button(onClick = {
                                                onPreview(voice, testText) { err ->
                                                    scope.launch { snackbarHostState.showSnackbar(err) }
                                                }
                                            }) {
                                                Text("Preview")
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showVoiceSelector = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        Text("Provider (share this device's TTS voices)", style = MaterialTheme.typography.titleLarge)

        var providerEnabledLocal by remember { mutableStateOf(providerEnabled) }
        var providerUrlLocal by remember { mutableStateOf(providerUrl) }
        var providerNameLocal by remember { mutableStateOf(providerName) }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Enable provider", modifier = Modifier.weight(1f))
            Switch(
                checked = providerEnabledLocal,
                onCheckedChange = { providerEnabledLocal = it }
            )
        }

        OutlinedTextField(
            value = providerUrlLocal,
            onValueChange = { providerUrlLocal = it },
            label = { Text("Coagulator URL (defaults to wss://)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = providerNameLocal,
            onValueChange = { providerNameLocal = it },
            label = { Text("Provider name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { starManager.setProviderConfig(providerEnabledLocal, providerUrlLocal, providerNameLocal) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply Provider Settings")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (providerStatus.isNotEmpty()) {
            Text(providerStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
    } // end Scaffold
}