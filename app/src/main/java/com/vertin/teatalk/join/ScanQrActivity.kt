package com.vertin.teatalk.join

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vertin.teatalk.ui.TeaTalkTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanQrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BackHandler { finish() }
            TeaTalkTheme { ScanScreen() }
        }
    }

    @Composable
    private fun ScanScreen() {
        val context = LocalContext.current
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        var askedOnce by remember { mutableStateOf(false) }
        var cameraError by remember { mutableStateOf<String?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { allowed -> granted = allowed }
        LaunchedEffect(Unit) {
            if (!granted && !askedOnce) {
                askedOnce = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                CameraPreview(
                    onDecoded = { text ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
                        finish()
                    },
                    onCameraError = { message -> cameraError = message },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "扫描茶室二维码",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "把主机屏幕上的二维码放进取景框",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.72f)
                        .padding(vertical = 80.dp)
                        .background(Color.Transparent)
                        .then(Modifier),
                )
                cameraError?.let { message ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(message, color = Color.White, textAlign = TextAlign.Center)
                        Button(onClick = { finish() }) { Text("返回手动加入") }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "需要相机权限才能扫码",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "也可以返回后手动输入主机地址。",
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.padding(top = 18.dp),
                    ) { Text("重新授权") }
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("返回") }
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(
        onDecoded: (String) -> Unit,
        onCameraError: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
        val executor = remember { Executors.newSingleThreadExecutor() }
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
        val decoded = remember { AtomicBoolean(false) }
        val reader = remember {
            MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        DisposableEffect(Unit) {
            var provider: ProcessCameraProvider? = null
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (decoded.get()) return@setAnalyzer
                            val text = decodeQr(reader, image)
                            if (text != null && decoded.compareAndSet(false, true)) {
                                mainExecutor.execute { onDecoded(text) }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure { error ->
                    mainExecutor.execute { onCameraError(error.message ?: "相机启动失败") }
                }
            }, mainExecutor)

            onDispose {
                runCatching { provider?.unbindAll() }
                executor.shutdownNow()
            }
        }
    }

    private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        // Y-plane row stride often includes padding; copying only width*height
        // bytes directly would shift every row and break QR decoding on many phones.
        val luma = ByteArray(width * height)
        runCatching {
            val base = buffer.position()
            if (plane.pixelStride == 1 && plane.rowStride == width) {
                buffer.get(luma, 0, luma.size)
            } else {
                for (row in 0 until height) {
                    val rowStart = base + row * plane.rowStride
                    for (column in 0 until width) {
                        luma[row * width + column] = buffer.get(rowStart + column * plane.pixelStride)
                    }
                }
            }
        }.getOrElse { return null }
        val rotation = image.imageInfo.rotationDegrees
        val (rotated, rotatedWidth, rotatedHeight) = rotateLuma(luma, width, height, rotation)
        val source = PlanarYUVLuminanceSource(
            rotated,
            rotatedWidth,
            rotatedHeight,
            0,
            0,
            rotatedWidth,
            rotatedHeight,
            false,
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotateLuma(data: ByteArray, width: Int, height: Int, rotation: Int): Triple<ByteArray, Int, Int> {
        return when (((rotation % 360) + 360) % 360) {
            90 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = data[y * width + x]
                    }
                }
                Triple(out, height, width)
            }

            180 -> {
                val out = ByteArray(data.size)
                for (index in data.indices) out[index] = data[data.size - 1 - index]
                Triple(out, width, height)
            }

            270 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = data[y * width + x]
                    }
                }
                Triple(out, height, width)
            }

            else -> Triple(data, width, height)
        }
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
    }
}
