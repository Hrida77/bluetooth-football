package com.example.bluetoothfootball

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WireGameState(
    val playerX: Float,
    val playerY: Float,
    val opponentX: Float,
    val opponentY: Float,
    val ballX: Float,
    val ballY: Float,
    val blueScore: Int,
    val redScore: Int,
    val secondsRemaining: Float,
    val goalMessageSeconds: Float
)

interface NearbyGameListener {
    fun onStatus(message: String)
    fun onConnected()
    fun onDisconnected(message: String)
    fun onRemoteInput(x: Float, y: Float, kick: Boolean)
    fun onRemoteState(state: WireGameState)
}

class NearbyGameSession(
    context: Context,
    private val isHost: Boolean,
    private val listener: NearbyGameListener
) {
    companion object {
        private const val SERVICE_ID = "com.example.bluetoothfootball.game"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
        private const val INPUT_PACKET_SIZE = 10
        private const val STATE_PACKET_SIZE = 41
        private const val INPUT_PACKET_TYPE: Byte = 1
        private const val STATE_PACKET_TYPE: Byte = 2
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    @Volatile private var endpointId: String? = null
    @Volatile private var stopped = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId != this@NearbyGameSession.endpointId) return
            val bytes = payload.asBytes() ?: return
            when (bytes.firstOrNull()) {
                INPUT_PACKET_TYPE -> decodeInput(bytes)?.let {
                    listener.onRemoteInput(it.first, it.second, it.third)
                } ?: listener.onDisconnected("Received invalid player input.")
                STATE_PACKET_TYPE -> decodeState(bytes)?.let(listener::onRemoteState)
                    ?: listener.onDisconnected("Received invalid game state.")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (stopped || (this@NearbyGameSession.endpointId != null &&
                    this@NearbyGameSession.endpointId != endpointId)
            ) {
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { reportFailure("Could not accept the connection", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (stopped) return
            if (resolution.status.isSuccess) {
                this@NearbyGameSession.endpointId = endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onConnected()
            } else {
                listener.onDisconnected(
                    "Connection failed (code ${resolution.status.statusCode})."
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (this@NearbyGameSession.endpointId == endpointId) {
                this@NearbyGameSession.endpointId = null
                if (!stopped) listener.onDisconnected("The other player disconnected.")
            }
        }
    }

    fun start() {
        stopped = false
        if (isHost) {
            listener.onStatus("Waiting for a player to join...")
            client.startAdvertising(
                android.os.Build.MODEL,
                SERVICE_ID,
                lifecycleCallback,
                com.google.android.gms.nearby.connection.AdvertisingOptions.Builder()
                    .setStrategy(STRATEGY)
                    .build()
            ).addOnFailureListener { reportFailure("Could not start hosting", it) }
        } else {
            listener.onStatus("Searching for a host...")
            client.startDiscovery(
                SERVICE_ID,
                object : com.google.android.gms.nearby.connection.EndpointDiscoveryCallback() {
                    override fun onEndpointFound(
                        endpointId: String,
                        info: DiscoveredEndpointInfo
                    ) {
                        if (stopped || this@NearbyGameSession.endpointId != null) return
                        listener.onStatus("Found ${info.endpointName}. Connecting...")
                        client.requestConnection(
                            android.os.Build.MODEL,
                            endpointId,
                            lifecycleCallback
                        ).addOnFailureListener {
                            reportFailure("Could not connect to the host", it)
                        }
                    }

                    override fun onEndpointLost(endpointId: String) {
                        if (this@NearbyGameSession.endpointId == null && !stopped) {
                            listener.onStatus("Host is no longer available. Searching...")
                        }
                    }
                },
                com.google.android.gms.nearby.connection.DiscoveryOptions.Builder()
                    .setStrategy(STRATEGY)
                    .build()
            ).addOnFailureListener { reportFailure("Could not search for a host", it) }
        }
    }

    fun sendInput(x: Float, y: Float, kick: Boolean) {
        if (isHost) return
        val endpoint = endpointId ?: return
        val bytes = ByteBuffer.allocate(INPUT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(INPUT_PACKET_TYPE)
            .putFloat(x.coerceIn(-1f, 1f))
            .putFloat(y.coerceIn(-1f, 1f))
            .put(if (kick) 1.toByte() else 0.toByte())
            .array()
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { reportFailure("Could not send player input", it) }
    }

    fun sendState(state: GameState.Snapshot, width: Float, height: Float) {
        if (!isHost) return
        val endpoint = endpointId ?: return
        if (width <= 0f || height <= 0f) return
        val bytes = ByteBuffer.allocate(STATE_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(STATE_PACKET_TYPE)
            .putFloat(state.playerX / width)
            .putFloat(state.playerY / height)
            .putFloat(state.opponentX / width)
            .putFloat(state.opponentY / height)
            .putFloat(state.ballX / width)
            .putFloat(state.ballY / height)
            .putInt(state.blueScore)
            .putInt(state.redScore)
            .putFloat(state.secondsRemaining)
            .putFloat(state.goalMessageSeconds)
            .array()
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { reportFailure("Could not send the game state", it) }
    }

    fun stop() {
        stopped = true
        endpointId?.let(client::disconnectFromEndpoint)
        endpointId = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private fun reportFailure(action: String, error: Exception) {
        if (stopped) return
        val detail = error.localizedMessage ?: error.javaClass.simpleName
        listener.onDisconnected("$action: $detail")
    }

    private fun decodeInput(bytes: ByteArray): Triple<Float, Float, Boolean>? {
        if (bytes.size != INPUT_PACKET_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != INPUT_PACKET_TYPE) return null
        val x = buffer.float
        val y = buffer.float
        val kick = buffer.get().toInt() != 0
        if (!x.isFinite() || !y.isFinite()) return null
        return Triple(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), kick)
    }

    private fun decodeState(bytes: ByteArray): WireGameState? {
        if (bytes.size != STATE_PACKET_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != STATE_PACKET_TYPE) return null
        val state = WireGameState(
            playerX = buffer.float,
            playerY = buffer.float,
            opponentX = buffer.float,
            opponentY = buffer.float,
            ballX = buffer.float,
            ballY = buffer.float,
            blueScore = buffer.int,
            redScore = buffer.int,
            secondsRemaining = buffer.float,
            goalMessageSeconds = buffer.float
        )
        val values = listOf(
            state.playerX, state.playerY, state.opponentX, state.opponentY,
            state.ballX, state.ballY, state.secondsRemaining, state.goalMessageSeconds
        )
        if (values.any { !it.isFinite() }) return null
        if (state.blueScore < 0 || state.redScore < 0) return null
        return state
    }
}
