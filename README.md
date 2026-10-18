# Bluetooth Football

A small native Android two-phone football game built with Kotlin and a custom `SurfaceView`. Two nearby phones connect using Google's Nearby Connections API with the point-to-point strategy. The host runs the simulation and sends compact game-state updates; the joining phone sends joystick and kick input.

## Open and run

1. Open Android Studio and choose **Open**.
2. Select this project folder (the one containing `settings.gradle.kts`).
3. Use JDK 17 when prompted, allow Gradle sync, and install the Android SDK platform 35 if Android Studio requests it.
4. Select an Android 8.0 (API 26) or newer device/emulator, then press **Run**.

The app launches in landscape orientation. Choose **Host Game** on one phone and **Join Game** on the other, then grant the nearby-device permissions when prompted. The host's joystick controls blue and the joining player's joystick controls red. Tap **KICK** when near the ball. A match lasts two minutes.
