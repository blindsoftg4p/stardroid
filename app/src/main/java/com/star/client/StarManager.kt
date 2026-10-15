package com.star.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StarVoice(
    val name: String,
    val serverUrl: String,
    val provider: String = "",
    val engine: String = ""
)

enum class ConnState { CONNECTING, CONNECTED, DISCONNECTED }

class StarManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("star_prefs", Context.MODE_PRIVATE)
    private val clients = mutableMapOf<String, StarClient>()

    private val _voices = MutableStateFlow<List<StarVoice>>(emptyList())
    val voices = _voices.asStateFlow()

    private val _connectedServers = MutableStateFlow<Set<String>>(emptySet())
    val connectedServers = _connectedServers.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connectionStates = _connectionStates.asStateFlow()

    private val _selectedVoice = MutableStateFlow<StarVoice?>(null)
    val selectedVoice = _selectedVoice.asStateFlow()

    // Provider role state
    private val _providerEnabled = MutableStateFlow(false)
    val providerEnabled = _providerEnabled.asStateFlow()

    private val _providerUrl = MutableStateFlow("")
    val providerUrl = _providerUrl.asStateFlow()

    private val _providerName = MutableStateFlow("android")
    val providerName = _providerName.asStateFlow()

    private val _providerStatus = MutableStateFlow("")
    val providerStatus = _providerStatus.asStateFlow()

    private var starProvider: StarProvider? = null

    private val audioCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Int, ByteArray) -> Unit>()
    private val statusCallbacks = java.util.concurrent.ConcurrentHashMap<String, (StatusMessage) -> Unit>()

    private var requestCounter = 0L

    init {
        loadServers()
        loadSelectedVoice()
        loadProviderConfig()
        updateProvider()
    }

    private fun loadServers() {
        val savedServers = prefs.getStringSet("servers", emptySet()) ?: emptySet()
        val servers = savedServers.map(::normalizeUrl).filter { it.isNotEmpty() }.toSet()
        if (servers != savedServers) {
            prefs.edit().putStringSet("servers", servers).apply()
        }
        servers.forEach { addServer(it, persist = false) }
    }

    private fun loadSelectedVoice() {
        val name = prefs.getString("selected_voice_name", null)
        val url = prefs.getString("selected_voice_url", null)
        if (name != null && url != null) {
            val normalizedUrl = normalizeUrl(url)
            _selectedVoice.value = StarVoice(name, normalizedUrl)
            if (normalizedUrl != url) {
                prefs.edit().putString("selected_voice_url", normalizedUrl).apply()
            }
        }
    }

    private fun loadProviderConfig() {
        _providerEnabled.value = prefs.getBoolean("provider_enabled", false)
        _providerUrl.value = prefs.getString("provider_url", "") ?: ""
        _providerName.value = prefs.getString("provider_name", "android") ?: "android"
    }

    fun normalizeUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) return ""

        // Resolve scheme
        when {
            url.startsWith("http://")  -> url = "ws://"  + url.substring(7)
            url.startsWith("https://") -> url = "wss://" + url.substring(8)
            !url.startsWith("ws://") && !url.startsWith("wss://") ->
                url = "ws://$url"   // bare host → assume local ws
        }

        val protocolEnd = url.indexOf("://")
        val protocol = url.substring(0, protocolEnd)   // "ws" or "wss"
        val rest = url.substring(protocolEnd + 3)

        val pathStart = rest.indexOf('/')
        val hostPart = if (pathStart != -1) rest.substring(0, pathStart) else rest
        val pathPart = if (pathStart != -1) rest.substring(pathStart) else ""

        // Check if a port is already specified
        val lastColon = hostPart.lastIndexOf(':')
        val hasPort = lastColon != -1 &&
            hostPart.substring(lastColon + 1).let { it.isNotEmpty() && it.all(Char::isDigit) }

        if (!hasPort && protocol == "ws") {
            // Local ws:// coagulators default to port 7774
            return "$protocol://$hostPart:7774$pathPart"
        }

        if (protocol == "wss" && hasPort && hostPart.substring(lastColon + 1) == "443") {
            return "$protocol://${hostPart.substring(0, lastColon)}$pathPart"
        }

        return url
    }

    fun addServer(rawUrl: String, persist: Boolean = true) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) return
        if (clients.size >= 10 || clients.containsKey(url)) return

        setState(url, ConnState.CONNECTING)
        val client = StarClient(url)
        client.setOnVoicesReceived { newVoices ->
            updateVoices(url, newVoices)
        }
        client.setOnAudioReceived { id, frag, data ->
            val callback = audioCallbacks[id]
            if (callback != null) callback.invoke(frag, data)
            else Log.w("StarManager", "Discarded audio for unknown STAR request id $id")
        }
        client.setOnStatusReceived { statusMsg ->
            val id = statusMsg.id
            if (id != null) {
                val callback = statusCallbacks[id]
                if (callback != null) callback.invoke(statusMsg)
                else Log.w("StarManager", "Discarded status for unknown STAR request id $id")
            } else {
                // Coagulator warnings such as "failed to find provider" may not
                // include an id, so deliver them to outstanding requests.
                statusCallbacks.values.toSet().forEach { it(statusMsg) }
            }
        }
        client.setOnConnected {
            setState(url, ConnState.CONNECTED)
        }
        client.setOnDisconnected {
            setState(url, ConnState.DISCONNECTED)
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

    private fun setState(url: String, state: ConnState) {
        _connectionStates.value = _connectionStates.value + (url to state)
    }

    fun removeServer(url: String) {
        clients[url]?.disconnect()
        clients.remove(url)
        _connectedServers.value = clients.keys.toSet()
        _connectionStates.value = _connectionStates.value - url
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

    private fun updateVoices(url: String, newVoices: List<VoiceEntry>) {
        val current = _voices.value.filter { it.serverUrl != url }
        val mapped = newVoices.map { StarVoice(it.name, url, it.provider, it.engine) }
        _voices.value = current + mapped
    }

    fun requestTts(
        voice: StarVoice,
        text: String,
        rate: Int = 0,
        pitch: Int = 0,
        onAudioReceived: (Int, ByteArray) -> Unit,
        onStatusReceived: (StatusMessage) -> Unit
    ): String? {
        val requestId = nextRequestId()
        audioCallbacks[requestId] = onAudioReceived
        statusCallbacks[requestId] = onStatusReceived
        val sent = clients[normalizeUrl(voice.serverUrl)]?.requestTts(voice.name, text, requestId, rate, pitch) == true
        if (!sent) {
            audioCallbacks.remove(requestId)
            statusCallbacks.remove(requestId)
        }
        return if (sent) requestId else null
    }

    fun clearTtsRequest(requestId: String) {
        audioCallbacks.remove(requestId)
        statusCallbacks.remove(requestId)
    }

    private fun nextRequestId(): String {
        // Must stay free of underscores: the coagulator composes
        // <clientId>_<ourId>_<fragment> and we route back on the middle part.
        return "${System.currentTimeMillis() % 1000000}${requestCounter++}"
    }

    fun requestTtsFull(voice: StarVoice, text: String, onComplete: (ByteArray) -> Unit, onError: (String) -> Unit = {}) {
        val buffer = java.io.ByteArrayOutputStream()
        var expectedTotalSize = -1
        var completed = false

        fun finish(data: ByteArray) {
            if (!completed) {
                completed = true
                onComplete(data)
            }
        }

        val sent = requestTts(voice, text,
            onAudioReceived = { _, data ->
                if (data.isNotEmpty()) {
                    buffer.write(data)
                }
                val currentBytes = buffer.toByteArray()
                // Detect WAV header to know the full expected size
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
                    finish(currentBytes)
                }
            },
            onStatusReceived = { statusMsg ->
                val s = statusMsg.status?.lowercase(java.util.Locale.US) ?: ""
                when {
                    statusMsg.abort == true -> {
                        onError("Request aborted by server.")
                        finish(buffer.toByteArray())
                    }
                    s.contains("error") -> {
                        onError("Server error: ${statusMsg.status}")
                        finish(buffer.toByteArray())
                    }
                    s.contains("done") || s.contains("completed") -> {
                        finish(buffer.toByteArray())
                    }
                }
            }
        )
        if (sent == null) {
            completed = true
            onError("The selected voice's server is disconnected.")
        }
    }

    // ---- Provider role ----

    fun setProviderConfig(enabled: Boolean, url: String, name: String) {
        _providerEnabled.value = enabled
        _providerUrl.value = url.trim()
        _providerName.value = name.trim().ifEmpty { "android" }
        prefs.edit()
            .putBoolean("provider_enabled", _providerEnabled.value)
            .putString("provider_url", _providerUrl.value)
            .putString("provider_name", _providerName.value)
            .apply()
        updateProvider()
    }

    private fun updateProvider() {
        starProvider?.stop()
        starProvider = null
        _providerStatus.value = ""
        if (_providerEnabled.value) {
            val url = normalizeUrl(_providerUrl.value)
            if (url.isNotEmpty()) {
                val provider = StarProvider(
                    context = context,
                    providerName = _providerName.value,
                    coagulatorUrl = url
                ) { msg -> _providerStatus.value = msg }
                starProvider = provider
                provider.start()
            }
        }
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
