package com.example.bluetoothfootball

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Frame-independent game simulation. Coordinates are pixels in the current view.
 */
class GameState {
    companion object {
        const val MATCH_LENGTH_SECONDS = 120f
        private const val PLAYER_RADIUS = 24f
        private const val BALL_RADIUS = 12f
    }

    data class Snapshot(
        val playerX: Float,
        val playerY: Float,
        val opponentX: Float,
        val opponentY: Float,
        val ballX: Float,
        val ballY: Float,
        val blueScore: Int,
        val redScore: Int,
        val secondsRemaining: Float,
        val goalMessageSeconds: Float,
        val playerRadius: Float,
        val ballRadius: Float,
        val matchOver: Boolean
    )

    private var width = 1f
    private var height = 1f
    private var margin = 28f
    private var top = 68f
    private var bottom = 1f
    private var goalHalfHeight = 62f

    private var playerX = 0f
    private var playerY = 0f
    private var opponentX = 0f
    private var opponentY = 0f
    private var ballX = 0f
    private var ballY = 0f
    private var ballVelocityX = 0f
    private var ballVelocityY = 0f

    private var blueScore = 0
    private var redScore = 0
    private var secondsRemaining = MATCH_LENGTH_SECONDS
    private var goalMessageSeconds = 0f
    private var configured = false

    @Synchronized
    fun configure(viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        width = viewWidth
        height = viewHeight
        margin = min(width, height) * 0.055f
        top = height * 0.17f
        bottom = height - margin
        goalHalfHeight = min(height * 0.19f, 78f)
        if (!configured) {
            resetPositions()
            configured = true
        } else {
            clampPlayers()
        }
    }

    @Synchronized
    fun update(
        deltaSeconds: Float,
        joystickX: Float,
        joystickY: Float,
        kick: Boolean,
        opponentJoystickX: Float,
        opponentJoystickY: Float,
        opponentKick: Boolean
    ) {
        if (!configured || deltaSeconds <= 0f || secondsRemaining <= 0f) return
        val dt = min(deltaSeconds, 0.05f)

        secondsRemaining = max(0f, secondsRemaining - dt)
        goalMessageSeconds = max(0f, goalMessageSeconds - dt)

        val playerSpeed = height * 0.65f
        playerX += joystickX.coerceIn(-1f, 1f) * playerSpeed * dt
        playerY += joystickY.coerceIn(-1f, 1f) * playerSpeed * dt
        clampPlayers()

        val opponentSpeed = height * 0.65f
        opponentX += opponentJoystickX.coerceIn(-1f, 1f) * opponentSpeed * dt
        opponentY += opponentJoystickY.coerceIn(-1f, 1f) * opponentSpeed * dt
        clampPlayers()
        pushBallFromPlayer(playerX, playerY)
        pushBallFromPlayer(opponentX, opponentY)

        if (kick) kickBall(playerX, playerY)
        if (opponentKick) kickBall(opponentX, opponentY)

        ballX += ballVelocityX * dt
        ballY += ballVelocityY * dt
        val friction = 0.985f.coerceAtLeast(1f - 1.2f * dt)
        ballVelocityX *= friction
        ballVelocityY *= friction
        if (hypot(ballVelocityX, ballVelocityY) < 5f) {
            ballVelocityX = 0f
            ballVelocityY = 0f
        }

        collideWithWalls()
        checkGoal()
    }

    @Synchronized
    fun snapshot() = Snapshot(
        playerX = playerX,
        playerY = playerY,
        opponentX = opponentX,
        opponentY = opponentY,
        ballX = ballX,
        ballY = ballY,
        blueScore = blueScore,
        redScore = redScore,
        secondsRemaining = secondsRemaining,
        goalMessageSeconds = goalMessageSeconds,
        playerRadius = PLAYER_RADIUS,
        ballRadius = BALL_RADIUS,
        matchOver = secondsRemaining <= 0f
    )

    @Synchronized
    fun applyRemoteState(
        playerX: Float,
        playerY: Float,
        opponentX: Float,
        opponentY: Float,
        ballX: Float,
        ballY: Float,
        blueScore: Int,
        redScore: Int,
        secondsRemaining: Float,
        goalMessageSeconds: Float
    ) {
        if (!configured) return
        this.playerX = playerX.coerceIn(0f, 1f) * width
        this.playerY = playerY.coerceIn(0f, 1f) * height
        this.opponentX = opponentX.coerceIn(0f, 1f) * width
        this.opponentY = opponentY.coerceIn(0f, 1f) * height
        this.ballX = ballX.coerceIn(0f, 1f) * width
        this.ballY = ballY.coerceIn(0f, 1f) * height
        this.blueScore = blueScore.coerceAtLeast(0)
        this.redScore = redScore.coerceAtLeast(0)
        this.secondsRemaining = secondsRemaining.coerceIn(0f, MATCH_LENGTH_SECONDS)
        this.goalMessageSeconds = goalMessageSeconds.coerceAtLeast(0f)
    }

    private fun pushBallFromPlayer(x: Float, y: Float) {
        val dx = ballX - x
        val dy = ballY - y
        val distance = hypot(dx, dy)
        val minimumDistance = PLAYER_RADIUS + BALL_RADIUS
        if (distance < minimumDistance) {
            val safeDistance = distance.coerceAtLeast(0.001f)
            val nx = dx / safeDistance
            val ny = dy / safeDistance
            ballX = x + nx * minimumDistance
            ballY = y + ny * minimumDistance
            ballVelocityX += nx * 95f
            ballVelocityY += ny * 95f
        }
    }

    private fun kickBall(x: Float, y: Float) {
        val dx = ballX - x
        val dy = ballY - y
        val distance = hypot(dx, dy)
        if (distance <= PLAYER_RADIUS + BALL_RADIUS + 42f) {
            val nx = if (distance > 1f) dx / distance else 1f
            val ny = if (distance > 1f) dy / distance else 0f
            ballVelocityX = nx * 560f
            ballVelocityY = ny * 560f
        }
    }

    private fun collideWithWalls() {
        val left = margin + BALL_RADIUS
        val right = width - margin - BALL_RADIUS
        val upper = top + BALL_RADIUS
        val lower = bottom - BALL_RADIUS
        if (ballY < upper) {
            ballY = upper
            ballVelocityY = kotlin.math.abs(ballVelocityY)
        } else if (ballY > lower) {
            ballY = lower
            ballVelocityY = -kotlin.math.abs(ballVelocityY)
        }

        if (ballY !in (height / 2f - goalHalfHeight)..(height / 2f + goalHalfHeight)) {
            if (ballX < left) {
                ballX = left
                ballVelocityX = kotlin.math.abs(ballVelocityX)
            } else if (ballX > right) {
                ballX = right
                ballVelocityX = -kotlin.math.abs(ballVelocityX)
            }
        }
    }

    private fun checkGoal() {
        if (ballY !in (height / 2f - goalHalfHeight)..(height / 2f + goalHalfHeight)) return
        when {
            ballX < margin -> {
                redScore++
                goalMessageSeconds = 1.5f
                resetPositions()
            }
            ballX > width - margin -> {
                blueScore++
                goalMessageSeconds = 1.5f
                resetPositions()
            }
        }
    }

    private fun clampPlayers() {
        playerX = playerX.coerceIn(margin + PLAYER_RADIUS, width / 2f - PLAYER_RADIUS)
        opponentX = opponentX.coerceIn(width / 2f + PLAYER_RADIUS, width - margin - PLAYER_RADIUS)
        playerY = playerY.coerceIn(top + PLAYER_RADIUS, bottom - PLAYER_RADIUS)
        opponentY = opponentY.coerceIn(top + PLAYER_RADIUS, bottom - PLAYER_RADIUS)
    }

    private fun resetPositions() {
        playerX = width * 0.25f
        playerY = height / 2f
        opponentX = width * 0.75f
        opponentY = height / 2f
        ballX = width / 2f
        ballY = height / 2f
        ballVelocityX = 0f
        ballVelocityY = 0f
    }
}
