package com.example.bluetoothfootball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot
import kotlin.math.min

class FootballView(
    context: Context,
    private val isHost: Boolean,
    private val onInput: (Float, Float, Boolean) -> Unit,
    private val onState: (GameState.Snapshot, Float, Float) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val gameState = GameState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val threadLock = Object()
    @Volatile private var running = false
    private var gameThread: Thread? = null
    @Volatile private var joystickX = 0f
    @Volatile private var joystickY = 0f
    @Volatile private var kickRequested = false
    @Volatile private var connected = false
    @Volatile private var remoteJoystickX = 0f
    @Volatile private var remoteJoystickY = 0f
    @Volatile private var remoteKickRequested = false
    private var joystickPointerId = MotionEvent.INVALID_POINTER_ID
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickRadius = 0f
    private var kickBounds = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun resume() {
        synchronized(threadLock) {
            if (running) return
            running = true
            gameThread = Thread(this, "FootballGameLoop").also { it.start() }
        }
    }

    fun pause() {
        val thread = synchronized(threadLock) {
            running = false
            gameThread
        }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(500L)
        }
        synchronized(threadLock) {
            if (gameThread === thread) gameThread = null
        }
        joystickX = 0f
        joystickY = 0f
        kickRequested = false
    }

    fun setConnected(value: Boolean) {
        connected = value
    }

    fun setRemoteInput(x: Float, y: Float, kick: Boolean) {
        remoteJoystickX = x.coerceIn(-1f, 1f)
        remoteJoystickY = y.coerceIn(-1f, 1f)
        if (kick) remoteKickRequested = true
    }

    fun receiveRemoteState(state: WireGameState) {
        gameState.applyRemoteState(
            state.playerX,
            state.playerY,
            state.opponentX,
            state.opponentY,
            state.ballX,
            state.ballY,
            state.blueScore,
            state.redScore,
            state.secondsRemaining,
            state.goalMessageSeconds
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) = resume()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        gameState.configure(width.toFloat(), height.toFloat())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = pause()

    override fun run() {
        var previousTime = System.nanoTime()
        var lastInputSent = 0L
        var lastStateSent = 0L
        var pendingClientKick = false
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - previousTime) / 1_000_000_000f).coerceAtMost(0.05f)
            previousTime = now
            if (!isHost && kickRequested) pendingClientKick = true
            if (isHost && connected) {
                gameState.update(
                    dt,
                    joystickX,
                    joystickY,
                    kickRequested,
                    remoteJoystickX,
                    remoteJoystickY,
                    remoteKickRequested
                )
                remoteKickRequested = false
                val snapshot = gameState.snapshot()
                if (now - lastStateSent >= 33_000_000L) {
                    onState(snapshot, width.toFloat(), height.toFloat())
                    lastStateSent = now
                }
            } else if (!isHost && connected && now - lastInputSent >= 33_000_000L) {
                onInput(joystickX, joystickY, pendingClientKick)
                pendingClientKick = false
                lastInputSent = now
            }
            kickRequested = false

            val canvas = if (holder.surface.isValid) holder.lockCanvas() else null
            if (canvas != null) {
                try {
                    drawGame(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            try {
                Thread.sleep(16L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (kickBounds.contains(x, y)) {
                    kickRequested = true
                } else if (joystickPointerId == MotionEvent.INVALID_POINTER_ID &&
                    hypot(x - joystickCenterX, y - joystickCenterY) <= joystickRadius * 1.6f
                ) {
                    joystickPointerId = event.getPointerId(event.actionIndex)
                    updateJoystick(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(joystickPointerId)
                if (pointerIndex >= 0) {
                    updateJoystick(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.getPointerId(event.actionIndex) == joystickPointerId) {
                    joystickPointerId = MotionEvent.INVALID_POINTER_ID
                    joystickX = 0f
                    joystickY = 0f
                }
            }
        }
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joystickCenterX
        val dy = y - joystickCenterY
        val distance = hypot(dx, dy).coerceAtLeast(1f)
        val magnitude = (distance / joystickRadius).coerceAtMost(1f)
        joystickX = dx / distance * magnitude
        joystickY = dy / distance * magnitude
    }

    private fun drawGame(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        gameState.configure(width, height)
        val game = gameState.snapshot()
        val margin = min(width, height) * 0.055f
        val top = height * 0.17f
        val bottom = height - margin
        val centerY = height / 2f
        val goalHalfHeight = min(height * 0.19f, 78f)

        canvas.drawColor(0xFF102B1C.toInt())
        paint.color = 0xFF2E9B50.toInt()
        canvas.drawRect(margin, top, width - margin, bottom, paint)
        paint.color = 0xFFB8E8C2.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawLine(margin, top, width - margin, top, paint)
        canvas.drawLine(margin, bottom, width - margin, bottom, paint)
        canvas.drawLine(margin, top, margin, centerY - goalHalfHeight, paint)
        canvas.drawLine(margin, centerY + goalHalfHeight, margin, bottom, paint)
        canvas.drawLine(width - margin, top, width - margin, centerY - goalHalfHeight, paint)
        canvas.drawLine(width - margin, centerY + goalHalfHeight, width - margin, bottom, paint)
        canvas.drawCircle(width / 2f, centerY, min(height * 0.12f, 70f), paint)
        canvas.drawCircle(width / 2f, centerY, 4f, fillPaint(paint.color))

        val boxDepth = min(width * 0.12f, 105f)
        val boxHalfHeight = min(height * 0.29f, 112f)
        canvas.drawRect(margin, centerY - boxHalfHeight, margin + boxDepth, centerY + boxHalfHeight, paint)
        canvas.drawRect(width - margin - boxDepth, centerY - boxHalfHeight, width - margin, centerY + boxHalfHeight, paint)

        paint.strokeWidth = 7f
        canvas.drawLine(margin - 9f, centerY - goalHalfHeight, margin - 9f, centerY + goalHalfHeight, paint)
        canvas.drawLine(width - margin + 9f, centerY - goalHalfHeight, width - margin + 9f, centerY + goalHalfHeight, paint)
        paint.style = Paint.Style.FILL

        paint.color = 0xFF1565E8.toInt()
        canvas.drawCircle(game.playerX, game.playerY, game.playerRadius, paint)
        paint.color = 0xFFE53935.toInt()
        canvas.drawCircle(game.opponentX, game.opponentY, game.playerRadius, paint)
        paint.color = 0xFFFFF3D6.toInt()
        canvas.drawCircle(game.ballX, game.ballY, game.ballRadius, paint)
        paint.color = 0xFF303030.toInt()
        canvas.drawCircle(game.ballX, game.ballY, 3f, paint)

        drawScoreAndTimer(canvas, width, game)
        if (game.goalMessageSeconds > 0f || game.matchOver) {
            drawCenterMessage(canvas, width, height, if (game.matchOver) "Full time!" else "Goal!")
        }
        drawControls(canvas, width, height)
    }

    private fun drawScoreAndTimer(canvas: Canvas, width: Float, game: GameState.Snapshot) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(heightTextSize(canvas.height), 30f)
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        val minutes = (game.secondsRemaining / 60f).toInt()
        val seconds = (game.secondsRemaining % 60f).toInt()
        val clock = "%d:%02d".format(minutes, seconds)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText("${game.blueScore}  -  ${game.redScore}", width / 2f, canvas.height * 0.085f, paint)
        paint.textSize = 18f
        paint.color = 0xFFD8E8DC.toInt()
        canvas.drawText(clock, width / 2f, canvas.height * 0.135f, paint)
    }

    private fun drawCenterMessage(canvas: Canvas, width: Float, height: Float, message: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0xCC102B1C.toInt()
        canvas.drawRoundRect(width * 0.37f, height * 0.40f, width * 0.63f, height * 0.60f, 18f, 18f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 36f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(message, width / 2f, height * 0.515f, paint)
    }

    private fun drawControls(canvas: Canvas, width: Float, height: Float) {
        joystickCenterX = width * 0.13f
        joystickCenterY = height * 0.79f
        joystickRadius = min(width, height) * 0.085f
        val knobX = joystickCenterX + joystickX * joystickRadius
        val knobY = joystickCenterY + joystickY * joystickRadius

        paint.style = Paint.Style.FILL
        paint.color = 0x55333333
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, paint)
        paint.color = 0x99FFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xAAFFFFFF.toInt()
        canvas.drawCircle(knobX, knobY, joystickRadius * 0.43f, paint)

        val buttonRadius = min(width, height) * 0.075f
        val buttonX = width * 0.87f
        val buttonY = height * 0.79f
        kickBounds.set(buttonX - buttonRadius, buttonY - buttonRadius, buttonX + buttonRadius, buttonY + buttonRadius)
        paint.color = 0xCCDF3B32.toInt()
        canvas.drawCircle(buttonX, buttonY, buttonRadius, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(20f, buttonRadius * 0.46f)
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        canvas.drawText("KICK", buttonX, buttonY + paint.textSize * 0.35f, paint)
    }

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun heightTextSize(height: Int) = height * 0.055f
}
