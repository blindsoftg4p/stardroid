package com.star.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.common.MimeTypes
import java.io.File
import java.io.FileOutputStream

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
                onPreview = { voice, text -> playTts(voice, text) },
                onSelect = { voice -> starManager.setSelectedVoice(voice) }
            )
        }
    }

    private fun playTts(voice: StarVoice, text: String) {
        starManager.requestTtsFull(voice, text) { audioData ->
            runOnUiThread {
                val tempFile = File(cacheDir, "preview.wav")
                FileOutputStream(tempFile).use { it.write(audioData) }
                
                val mediaItem = MediaItem.fromUri(tempFile.absolutePath)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
                exoPlayer?.play()
            }
        }
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
    onPreview: (StarVoice, String) -> Unit,
    onSelect: (StarVoice) -> Unit
) {
    val voices by starManager.voices.collectAsState()
    val servers by starManager.connectedServers.collectAsState()
    val selectedVoice by starManager.selectedVoice.collectAsState()
    
    var serverUrl by remember { mutableStateOf("") }
    var testText by remember { mutableStateOf("Hello, this is a STAR test.") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("STAR Client", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (e.g., example.com:7774)") },
            supportingText = { Text("Supports ws://, wss://, http://, and https://. Defaults to ws:// protocol and port 7774.") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Button(
            onClick = { 
                starManager.addServer(serverUrl)
                serverUrl = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Server")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Connected Servers: ${servers.size}/5")
        servers.forEach { url ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(url, modifier = Modifier.weight(1f))
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

        Spacer(modifier = Modifier.height(16.dp))

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
                                            Button(onClick = { onPreview(voice, testText) }) {
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
    }
}
