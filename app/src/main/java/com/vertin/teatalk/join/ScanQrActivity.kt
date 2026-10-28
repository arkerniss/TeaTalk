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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.vertin.teatalk.ui.DarkTeaPalette
import com.vertin.teatalk.ui.TeaPrimaryButton
import com.vertin.teatalk.ui.TeaTalkTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// The scanner sits on a full-bleed black camera preview, so it always uses the
// dark palette's clay (readable on video) instead of the ambient theme's.
private val ScanClay = DarkTeaPalette.clay

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
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "扫描茶室二维码",
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "把主机屏幕上的二维码放进取景框",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                ViewFinder(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(240.dp),
                )
                cameraError?.let { message ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        TeaPrimaryButton(
                            text = "返回手动加入",
                            onClick = { finish() },
                        )
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
                        text = "需要相机权限",
                        fontFamily = FontFamily.Serif,
                        fontSize = 24.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "也可以返回后手动输入主机地址。",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    TeaPrimaryButton(
                        text = "重新授权",
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                    Spacer(Modifier.height(10.dp))
                    ScanGhostButton(text = "返回", onClick = { finish() })
                }
            }
        }
    }

    /** White-outline variant of the ghost button: the only place it appears is on black. */
    @Composable
    private fun ScanGhostButton(text: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }

    /** Four rounded L-shaped corner brackets — no full rectangle. */
    @Composable
    private fun ViewFinder(modifier: Modifier = Modifier) {
        Canvas(modifier) {
            val arm = 34.dp.toPx()
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val left = inset
            val top = inset
            val right = size.width - inset
            val bottom = size.height - inset
            drawBracket(
                first = Offset(left, top + arm),
                corner = Offset(left, top),
                second = Offset(left + arm, top),
                stroke = stroke,
            )
            drawBracket(
                first = Offset(right - arm, top),
                corner = Offset(right, top),
                second = Offset(right, top + arm),
                stroke = stroke,
            )
            drawBracket(
                first = Offset(left, bottom - arm),
                corner = Offset(left, bottom),
                second = Offset(left + arm, bottom),
                stroke = stroke,
            )
            drawBracket(
                first = Offset(right - arm, bottom),
                corner = Offset(right, bottom),
                second = Offset(right, bottom - arm),
                stroke = stroke,
            )
        }
    }

    private fun DrawScope.drawBracket(
        first: Offset,
        corner: Offset,
        second: Offset,
        stroke: Float,
    ) {
        drawLine(
            color = ScanClay,
            start = first,
            end = corner,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ScanClay,
            start = corner,
            end = second,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
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
