package com.example.bluetoothfootball

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), NearbyGameListener {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private var gameView: FootballView? = null
    private var gameSession: NearbyGameSession? = null
    private var statusText: TextView? = null
    private var disconnectPanel: LinearLayout? = null
    private var pendingHost = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showStartScreen()
    }

    override fun onResume() {
        super.onResume()
        gameView?.resume()
    }

    override fun onPause() {
        gameView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        gameSession?.stop()
        super.onDestroy()
    }

    override fun onStatus(message: String) {
        runOnUiThread {
            statusText?.text = message
        }
    }

    override fun onConnected() {
        runOnUiThread {
            gameView?.setConnected(true)
            statusText?.text = "Connected — match in progress"
        }
    }

    override fun onDisconnected(message: String) {
        runOnUiThread {
            gameView?.setConnected(false)
            statusText?.text = message
            disconnectPanel?.visibility = View.VISIBLE
        }
    }

    override fun onRemoteInput(x: Float, y: Float, kick: Boolean) {
        gameView?.setRemoteInput(x, y, kick)
    }

    override fun onRemoteState(state: WireGameState) {
        gameView?.receiveRemoteState(state)
    }

    private fun showStartScreen() {
        gameSession?.stop()
        gameSession = null
        gameView?.pause()
        gameView = null
        statusText = null
        disconnectPanel = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF102B1C.toInt())
        }
        content.addView(TextView(this).apply {
            text = "Bluetooth Football"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 30 })
        content.addView(startButton("Host Game") { requestGameStart(host = true) })
        content.addView(startButton("Join Game") { requestGameStart(host = false) })
        setContentView(content)
    }

    private fun startButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.42f).toInt().coerceAtLeast(220),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 }
    }

    private fun requestGameStart(host: Boolean) {
        pendingHost = host
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startGame(host)
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    @Deprecated("Deprecated in Android API; permissions are handled by requestPermissions.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (requiredPermissions().all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }) {
            startGame(pendingHost)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permissions required")
                .setMessage("Bluetooth, nearby Wi-Fi, and location permissions are needed to find and connect to the other phone.")
                .setPositiveButton("Try again") { _, _ -> requestGameStart(pendingHost) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startGame(host: Boolean) {
        val view = FootballView(
            this,
            host,
            onInput = { x, y, kick -> gameSession?.sendInput(x, y, kick) },
            onState = { state, width, height ->
                gameSession?.sendState(state, width, height)
            }
        )
        gameView = view
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xAA102B1C.toInt())
        }
        val returnButton = Button(this).apply {
            text = "Return to Start"
            setOnClickListener { showStartScreen() }
        }
        disconnectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xEE102B1C.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Connection lost"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            })
            addView(returnButton)
            visibility = View.GONE
        }

        val gameFrame = FrameLayout(this)
        gameFrame.addView(view, FrameLayout.LayoutParams(-1, -1))
        gameFrame.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))
        gameFrame.addView(disconnectPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        setContentView(gameFrame)

        gameSession = NearbyGameSession(this, host, this).also { it.start() }
    }
}
