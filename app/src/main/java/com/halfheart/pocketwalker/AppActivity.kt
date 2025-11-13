package com.halfheart.pocketwalker

import AudioEngine
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.halfheart.pocketwalkerlib.BUTTON_CENTER
import com.halfheart.pocketwalkerlib.BUTTON_LEFT
import com.halfheart.pocketwalkerlib.BUTTON_RIGHT
import com.halfheart.pocketwalkerlib.PocketWalkerNative
import com.yourpackage.TcpSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.function.Function
import kotlin.concurrent.thread
import kotlin.experimental.xor


class AppActivity : ComponentActivity()  {
    private var canvasBitmap by mutableStateOf<Bitmap?>(null)

    private lateinit var pokeWalker: PocketWalkerNative

    private val palette = intArrayOf(
        0xCCCCCC,
        0x999999,
        0x666666,
        0x333333
    )

    private var didInitialize: Boolean = false

    fun initializePokeWalker() {
        val romBytes = resources.openRawResource(R.raw.rom).readAllBytes()

        if (!applicationContext.fileList().contains("eeprom.bin")) {
            val outputStream = applicationContext.openFileOutput("eeprom.bin", MODE_PRIVATE)
            outputStream.write(resources.openRawResource(R.raw.eeprom).readAllBytes())
            outputStream.close()
        }

        val eepromBytes = applicationContext.openFileInput("eeprom.bin").readAllBytes()

        pokeWalker = PocketWalkerNative()
        pokeWalker.create(romBytes, eepromBytes)

        pokeWalker.onDraw { bytes ->
            canvasBitmap = createBitmap(bytes)
        }

        val audioEngine = AudioEngine()
        pokeWalker.onAudio { freq: Float, isFullVolume: Boolean ->
            audioEngine.render(freq, if (isFullVolume) 0.5f else 0.25f, 1.0f)
        }

        thread(priority = Thread.MAX_PRIORITY) {
            pokeWalker.start()
        }
    }

    fun initializeTcp() {
        val socket = TcpSocket()

        socket.setOnConnect {
            println("[TCP] Connected")
        }

        socket.setOnClose {
            println("[TCP] Disconnected")
        }

        socket.setOnData { data ->
            data.forEach { byte ->
                println("RX: %02X".format(byte xor 0xAA.toByte()))
                pokeWalker.receiveSci3(byte)
            }
        }

        pokeWalker.onTransmitSci3 { byte ->
            println("TX: %02X".format(byte xor 0xAA.toByte()))
            socket.send(byteArrayOf(byte))
        }

        socket.connect("10.0.0.123", 8081)

        thread {
            Thread.sleep(3000)

            while (true) {
                if (!socket.isConnected() && !socket.isConnecting()) {
                    println("Attempting reconnection...")
                    socket.reconnect()
                    Thread.sleep(5000)
                } else {
                    Thread.sleep(1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            PWApp(pokeWalker, canvasBitmap)
        }

        if (!didInitialize)
        {
            didInitialize = true

            sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager.registerListener(sensorListener, it, SENSOR_INTERVAL_US)
            }

            initializePokeWalker()
            initializeTcp()
        }
    }

    override fun onStop() {
        super.onStop()

        val outputStream = applicationContext.openFileOutput("eeprom.bin", MODE_PRIVATE)
        outputStream.write(pokeWalker.getEepromBuffer())
        outputStream.close()
    }

    override fun onPause() {
        super.onPause()

        pokeWalker.pause()
    }

    override fun onResume() {
        super.onResume()

        pokeWalker.resume()
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val SENSOR_TARGET_HZ = 50
    private val SENSOR_INTERVAL_NS = 1_000_000_000L / SENSOR_TARGET_HZ
    private val SENSOR_INTERVAL_US = 1_000_000 / SENSOR_TARGET_HZ
    private var lastSensorTimestamp = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!didInitialize) return
            if (event.timestamp - lastSensorTimestamp < SENSOR_INTERVAL_NS) return

            lastSensorTimestamp = event.timestamp

            val x = event.values[0] / SensorManager.GRAVITY_EARTH
            val y = event.values[1] / SensorManager.GRAVITY_EARTH
            val z = event.values[2] / SensorManager.GRAVITY_EARTH

            pokeWalker.setAccelerationData(x, y, z)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }


    private fun createBitmap(paletteIndices: ByteArray): Bitmap {
        val width = 96
        val height = 64

        val contrast = pokeWalker.getContrast().toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in pixels.indices) {
            var paletteIndex = paletteIndices[i].toInt() and 0xFF
            if (paletteIndex >= palette.size) {
                paletteIndex = 0
            }

            val color = palette[paletteIndex]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val adjustedR = applyContrast(r, contrast)
            val adjustedG = applyContrast(g, contrast)
            val adjustedB = applyContrast(b, contrast)

            pixels[i] = (0xFF shl 24) or (adjustedR shl 16) or (adjustedG shl 8) or adjustedB
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun applyContrast(colorValue: Int, contrast: Int): Int {
        val clampedContrast = contrast.coerceIn(0, 9)

        if (colorValue == 0xCC) {
            return colorValue
        }

        val contrastMultiplier = when (clampedContrast) {
            0 -> 0.2f
            1 -> 0.4f
            2 -> 0.6f
            3 -> 0.8f
            4 -> 1.0f
            5 -> 1.2f
            6 -> 1.4f
            7 -> 1.6f
            8 -> 1.8f
            9 -> 2.0f
            else -> 1.0f
        }

        val referenceValues = mapOf(
            0x99 to 153,
            0x66 to 102,
            0x33 to 51
        )

        val referenceValue = referenceValues[colorValue]
        if (referenceValue != null) {
            val distanceFromMid = referenceValue - 128

            val adjustedDistance = (distanceFromMid * contrastMultiplier).toInt()
            val newValue = 128 + adjustedDistance

            return newValue.coerceIn(0, 255)
        }

        val distanceFromMid = colorValue - 128
        val adjustedDistance = (distanceFromMid * contrastMultiplier).toInt()
        val newValue = 128 + adjustedDistance

        return newValue.coerceIn(0, 255)
    }

}

@Composable
fun PWApp(pokeWalker: PocketWalkerNative, canvasBitmap: Bitmap?) {
    Box(Modifier
        .fillMaxSize()
        .background(Color.Transparent)) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.TopCenter)
                .background(Color(0xffd32329))
        )

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.BottomCenter)
                .background(Color(0xffdadade))
        )

        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            contentScale = ContentScale.Crop,
        )

        Box(
            Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size((96 * 2.5 + 32 + 16).dp, (64 * 2.5 + 32 + 16).dp)
                    .background(Color(0xFFCCCCCC), shape = RoundedCornerShape(16.dp))
                    .align(Alignment.Center)
                    .border(
                        width = 8.dp,
                        color = Color(0xFF1B1B1B),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                canvasBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Pokewalker Display",
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None

                    )
                }
            }
        }

        PWButton(
            pokeWalker, BUTTON_CENTER, size = 46, top = 260,
            modifier = Modifier.align(Alignment.Center)
        )
        PWButton(
            pokeWalker, BUTTON_LEFT, size = 36, top = 240, end = 128,
            modifier = Modifier.align(Alignment.Center)
        )
        PWButton(
            pokeWalker, BUTTON_RIGHT, size = 36, top = 240, start = 128,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun PWButton(
    pokeWalker: PocketWalkerNative,
    button: Int,
    size: Int = 32,
    top: Int = 0,
    bottom: Int = 0,
    start: Int = 0,
    end: Int = 0,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val buttonOutlineColor = Color(0xFFCFCFCF)
    val buttonColor = Color(0xFFF3F3F3)
    val pressedButtonColor = Color(0xFFDFDFDF)

    val hapticFeedback = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .padding(top = top.dp, bottom = bottom.dp, start = start.dp, end = end.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isPressed = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        pokeWalker.press(button)
                        tryAwaitRelease()
                        isPressed = false
                        delay(100) // allow time for read to catch up, while still having button down time
                        pokeWalker.release(button)
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .background(if (isPressed) pressedButtonColor else buttonColor, CircleShape)
                .border(BorderStroke(2.dp, buttonOutlineColor), CircleShape)
                .align(Alignment.Center)

        )
    }
}