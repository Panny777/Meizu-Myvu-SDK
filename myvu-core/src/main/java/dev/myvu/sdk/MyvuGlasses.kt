package dev.myvu.sdk

import android.content.Context
import dev.myvu.sdk.event.GlassesEvent
import dev.myvu.sdk.protocol.link.DeviceInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine/Flow wrapper around [MyvuClient].
 *
 * This is a thin, optional convenience layer: Java callers can use [MyvuClient]
 * directly and ignore this class. Everything here delegates to the underlying
 * [java] client, exposing its listener callbacks as [state], [deviceInfo] and
 * [events] flows and turning connect/query into suspend functions.
 *
 * Flow emissions are delivered on the SDK's dispatch executor (the main thread
 * by default), matching [MyvuClient.addListener].
 */
class MyvuGlasses @JvmOverloads constructor(
    context: Context,
    config: MyvuConfig = MyvuConfig.builder().build(),
) : AutoCloseable {

    /** The underlying Java client; use it for anything not surfaced here. */
    val java: MyvuClient = MyvuClient(context, config)

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    /**
     * Glasses-initiated events (AI triggers, unparsed inbound objects). Replay
     * is 0 and the buffer drops oldest, so slow collectors never stall the
     * connection thread.
     */
    private val _events = MutableSharedFlow<GlassesEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    /** Raw body of every non-audio inbound relay message. */
    private val _rawInbound = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val rawInbound: SharedFlow<String> = _rawInbound.asSharedFlow()

    private val listener = object : MyvuClient.Listener {
        override fun onConnectionStateChanged(s: ConnectionState) { _state.value = s }
        override fun onDeviceInfo(info: DeviceInfo) { _deviceInfo.value = info }
        override fun onEvent(event: GlassesEvent) { _events.tryEmit(event) }
        override fun onRawInbound(jsonBody: String) { _rawInbound.tryEmit(jsonBody) }
    }

    init {
        java.addListener(listener)
    }

    /** Same executor semantics as [MyvuClient.addListener]. */
    fun addRawListener(listener: MyvuClient.Listener, executor: Executor) =
        java.addListener(listener, executor)

    // -------------------------------------------------------------- connect

    /**
     * Connects and suspends until the session is [ConnectionState.READY], or
     * throws [MyvuConnectionException] on failure / [timeout].
     *
     * @param mac target MAC, or null to auto-search for nearby glasses.
     */
    suspend fun connect(mac: String? = null, timeout: Duration = 60.seconds) {
        withTimeout(timeout) {
            suspendCancellableCoroutine { cont ->
                val gate = object : MyvuClient.Listener {
                    override fun onConnectionStateChanged(s: ConnectionState) {
                        when (s) {
                            ConnectionState.READY -> {
                                java.removeListener(this)
                                if (cont.isActive) cont.resume(Unit)
                            }
                            ConnectionState.FAILED -> {
                                java.removeListener(this)
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        MyvuConnectionException("connection failed"))
                                }
                            }
                            else -> {}
                        }
                    }
                }
                cont.invokeOnCancellation { java.removeListener(gate) }
                java.addListener(gate)
                if (mac != null) java.connect(mac) else java.connectAutoSearch()
            }
        }
    }

    fun disconnect() = java.disconnect()
    override fun close() = java.shutdown()

    // ---------------------------------------------------------- teleprompter

    @JvmOverloads
    fun openTeleprompter(text: String, title: String = "") = java.openTeleprompter(text, title)

    @JvmOverloads
    fun teleprompterHighlight(index: Int, title: String = "") =
        java.teleprompterHighlight(index, title)

    fun showNotification(title: String, body: String) = java.showNotification(title, body)

    // --------------------------------------------------------------- settings

    fun setBrightness(value: Int) = java.setBrightness(value)
    fun setVolume(value: Int) = java.setVolume(value)
    fun toggleWifi(on: Boolean) = java.toggleWifi(on)
    fun setZenMode(on: Boolean) = java.setZenMode(on)
    fun setAirMode(on: Boolean) = java.setAirMode(on)
    fun setWearDetection(on: Boolean) = java.setWearDetection(on)
    fun setMusicTpControl(on: Boolean) = java.setMusicTpControl(on)
    fun setScreenOffTime(seconds: Int) = java.setScreenOffTime(seconds)
    fun setStandbyPosition(position: Int) = java.setStandbyPosition(position)
    fun setDeviceName(name: String) = java.setDeviceName(name)
    fun setLanguage(language: String, country: String) = java.setLanguage(language, country)
    fun syncTime() = java.syncTime()

    // ----------------------------------------------------------------- queries

    /** Fire-and-forget query; observe [events]/[rawInbound] for the reply. */
    fun sendQuery(subAction: String) = java.query(subAction)

    /**
     * Sends a `system` query and best-effort awaits the reply.
     *
     * The glasses interleave query replies with continuous telemetry and do not
     * tag replies with the request, so this correlates by looking for the
     * subject token (e.g. `brightness` from `get_brightness`) in inbound
     * objects. Returns the first matching object within [timeout]; throws
     * [kotlinx.coroutines.TimeoutCancellationException] if none arrives.
     */
    suspend fun query(subAction: String, timeout: Duration = 5.seconds): JSONObject {
        val token = subjectToken(subAction)
        return withTimeout(timeout) {
            java.query(subAction)
            events
                .filter { it is GlassesEvent.QueryReply || it is GlassesEvent.Unknown }
                .first { matchesToken(it, token) }
                .let { toJson(it) }
        }
    }

    private fun subjectToken(subAction: String): String =
        subAction.removePrefix("get_").removePrefix("request_")
            .removeSuffix("_mode").removeSuffix("_list")

    private fun matchesToken(e: GlassesEvent, token: String): Boolean = when (e) {
        is GlassesEvent.QueryReply -> e.subAction.contains(token) || e.data.toString().contains(token)
        is GlassesEvent.Unknown -> e.rawJson.contains(token)
        else -> false
    }

    private fun toJson(e: GlassesEvent): JSONObject = when (e) {
        is GlassesEvent.QueryReply -> e.data
        is GlassesEvent.Unknown -> JSONObject(e.rawJson)
        else -> JSONObject()
    }

    // ---------------------------------------------------------------- trackpad

    /** Grouped trackpad input; each call routes a "phonepad" action to the launcher. */
    val trackpad: TrackpadController = TrackpadController(java)

    class TrackpadController internal constructor(private val client: MyvuClient) {
        fun start() = client.trackpadStart()
        fun stop() = client.trackpadStop()
        fun click() = client.trackpadClick()
        fun doubleClick() = client.trackpadDoubleClick()
        fun longPress() = client.trackpadLongPress()
        fun swipe(
            direction: Int, startX: Float, startY: Float,
            endX: Float, endY: Float, speedX: Float, speedY: Float,
        ) = client.trackpadSwipe(direction, startX, startY, endX, endY, speedX, speedY)
    }

    // ------------------------------------------------------------ escape hatch

    fun sendRaw(actionJson: String) = java.sendRaw(actionJson)
}

/** Thrown when [MyvuGlasses.connect] cannot reach a ready session. */
class MyvuConnectionException(message: String) : Exception(message)
