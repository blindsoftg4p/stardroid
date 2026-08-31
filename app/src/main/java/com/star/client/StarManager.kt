package com.star.client

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StarVoice(val name: String, val serverUrl: String)

class StarManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("star_prefs", Context.MODE_PRIVATE)
    private val clients = mutableMapOf<String, StarClient>()
    
    private val _voices = MutableStateFlow<List<StarVoice>>(emptyList())
    val voices = _voices.asStateFlow()

    private val _connectedServers = MutableStateFlow<Set<String>>(emptySet())
    val connectedServers = _connectedServers.asStateFlow()

    private val _selectedVoice = MutableStateFlow<StarVoice?>(null)
    val selectedVoice = _selectedVoice.asStateFlow()

    private val audioCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Int, ByteArray) -> Unit>()
    private val statusCallbacks = java.util.concurrent.ConcurrentHashMap<String, (StatusMessage) -> Unit>()

    init {
        loadServers()
        loadSelectedVoice()
    }

    private fun loadServers() {
        val servers = prefs.getStringSet("servers", emptySet()) ?: emptySet()
        servers.forEach { addServer(it, persist = false) }
    }

    private fun loadSelectedVoice() {
        val name = prefs.getString("selected_voice_name", null)
        val url = prefs.getString("selected_voice_url", null)
        if (name != null && url != null) {
            _selectedVoice.value = StarVoice(name, url)
        }
    }

    fun normalizeUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) return ""
        
        // Handle protocol replacement
        if (url.startsWith("http://")) {
            url = "ws://" + url.substring(7)
        } else if (url.startsWith("https://")) {
            url = "wss://" + url.substring(8)
        } else if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            // Default to ws://
            url = "ws://$url"
        }
        
        // Now check for port. Find host part.
        val protocolEnd = url.indexOf("://")
        val protocol = url.substring(0, protocolEnd + 3)
        val rest = url.substring(protocolEnd + 3)
        
        val pathStart = rest.indexOf('/')
        val hostPart = if (pathStart != -1) rest.substring(0, pathStart) else rest
        val pathPart = if (pathStart != -1) rest.substring(pathStart) else ""
        
        val lastColon = hostPart.lastIndexOf(':')
        val hasPort = if (lastColon != -1) {
            val portCandidate = hostPart.substring(lastColon + 1)
            portCandidate.all { it.isDigit() } && portCandidate.isNotEmpty()
        } else {
            false
        }
        
        return if (!hasPort) {
            "$protocol$hostPart:7774$pathPart"
        } else {
            url
        }
    }

    fun addServer(rawUrl: String, persist: Boolean = true) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) return
        if (clients.size >= 5 || clients.containsKey(url)) return
        
        val client = StarClient(url)
        client.setOnVoicesReceived { newVoices ->
            updateVoices(url, newVoices)
        }
        client.setOnAudioReceived { id, frag, data ->
            audioCallbacks[id]?.invoke(frag, data)
        }
        client.setOnStatusReceived { statusMsg ->
            val id = statusMsg.id
            if (id != null) {
                statusCallbacks[id]?.invoke(statusMsg)
            }
        }
        client.connect()
        clients[url] = client
        _connectedServers.value = clients.keys.toSet()
        
        if (persist) {
            val servers = prefs.getStringSet("servers", emptySet())?.toMutableSet() ?: mutableSetOf()
            servers.add(url)
            prefs.edit().putStringSet("servers", servers).apply()
        }
    }

    fun removeServer(url: String) {
        clients[url]?.disconnect()
        clients.remove(url)
        _connectedServers.value = clients.keys.toSet()
        _voices.value = _voices.value.filter { it.serverUrl != url }
        
        val servers = prefs.getStringSet("servers", emptySet())?.toMutableSet() ?: mutableSetOf()
        servers.remove(url)
        prefs.edit().putStringSet("servers", servers).apply()
    }

    fun setSelectedVoice(voice: StarVoice) {
        _selectedVoice.value = voice
        prefs.edit()
            .putString("selected_voice_name", voice.name)
            .putString("selected_voice_url", voice.serverUrl)
            .apply()
    }

    private fun updateVoices(url: String, newVoices: List<String>) {
        val current = _voices.value.filter { it.serverUrl != url }
        val mapped = newVoices.map { StarVoice(it, url) }
        _voices.value = current + mapped
    }

    fun requestTts(
        voice: StarVoice,
        text: String,
        onAudioReceived: (Int, ByteArray) -> Unit,
        onStatusReceived: (StatusMessage) -> Unit
    ) {
        val requestId = "${System.currentTimeMillis() % 1000000}" // Simple ID
        audioCallbacks[requestId] = onAudioReceived
        statusCallbacks[requestId] = onStatusReceived
        clients[voice.serverUrl]?.requestTts(voice.name, text, requestId)
    }

    fun requestTtsFull(voice: StarVoice, text: String, onComplete: (ByteArray) -> Unit) {
        val buffer = java.io.ByteArrayOutputStream()
        var expectedTotalSize = -1
        
        requestTts(voice, text,
            onAudioReceived = { frag, data ->
                buffer.write(data)
                val currentBytes = buffer.toByteArray()
                if (currentBytes.size >= 8 && expectedTotalSize == -1) {
                    if (currentBytes[0] == 'R'.code.toByte() &&
                        currentBytes[1] == 'I'.code.toByte() &&
                        currentBytes[2] == 'F'.code.toByte() &&
                        currentBytes[3] == 'F'.code.toByte()) {
                        val riffSize = (currentBytes[4].toInt() and 0xFF) or
                                       ((currentBytes[5].toInt() and 0xFF) shl 8) or
                                       ((currentBytes[6].toInt() and 0xFF) shl 16) or
                                       ((currentBytes[7].toInt() and 0xFF) shl 24)
                        expectedTotalSize = riffSize + 8
                    }
                }
                if (expectedTotalSize > 0 && currentBytes.size >= expectedTotalSize) {
                    onComplete(currentBytes)
                }
            },
            onStatusReceived = { statusMsg ->
                if (statusMsg.abort == true || 
                    statusMsg.status?.lowercase(java.util.Locale.US)?.contains("error") == true || 
                    statusMsg.status?.lowercase(java.util.Locale.US)?.contains("completed") == true ||
                    statusMsg.status?.lowercase(java.util.Locale.US)?.contains("done") == true) {
                    onComplete(buffer.toByteArray())
                }
            }
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: StarManager? = null

        fun getInstance(context: Context): StarManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StarManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
