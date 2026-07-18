package com.example.impulse.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size as CameraSize
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.impulse.util.LogManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.abs

/**
 * QR-code scanning screen used for TOFU: the server displays a QR encoding its
 * certificate hash; scanning it stores the hash in [com.example.impulse.security.TrustedCertManager]
 * so the WebTransport layer can pin it.
 *
 * Expected QR payload format: `impulse-cert:<hex-sha256-hash>`
 *
 * Uses the framework Camera2 API for the camera preview (no CameraX, so the APK
 * does not bundle CameraX's unaligned native library) together with the
 * standalone ML Kit Barcode library for decoding.
 *
 * UI: a Fluent / Material 3 layout with a gradient hero header, a centered
 * square scanner with an animated reticle, a status line, and a bottom control
 * bar (flashlight + manual entry with clipboard paste and live validation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    serverId: String,
    onHashScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var scanned by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualHash by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    // Live validation state for the manual entry field.
    val manualValid = remember(manualHash) { parseCertHash(manualHash) != null }
    // Holds the active camera controller so the flashlight button can reach it.
    var activeController by remember { mutableStateOf<Camera2Controller?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение к серверу") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---- Top hero (≈30%): gradient background, logo, instruction ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "🔒",
                                fontSize = 32.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Отсканируйте QR-код\nс экрана сервера",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Формат: impulse-cert:<64 hex>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Bottom scanning area (≈70%) ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hasCameraPermission) {
                    val textureView = remember { TextureView(context) }
                    val controller = remember {
                        Camera2Controller(context, textureView) { raw ->
                            if (scanned == null) {
                                LogManager.d("QrScan", "raw detected: '$raw'")
                                val hash = parseCertHash(raw)
                                if (hash != null) {
                                    scanned = hash
                                    LogManager.i("QrScan", "hash scanned (short=${LogManager.shortHash(hash)})")
                                    onHashScanned(hash)
                                } else {
                                    LogManager.w("QrScan", "parseCertHash rejected raw value")
                                }
                            }
                        }
                    }
                    DisposableEffect(Unit) {
                        try {
                            controller.start()
                        } catch (e: Exception) {
                            LogManager.e("QrScan", "camera start failed", e)
                            hasCameraPermission = false
                        }
                        onDispose {
                            // Run teardown off the UI thread: controller.stop()
                            // closes the camera/session/scanner and must not block
                            // (or join) the main thread when the screen is removed
                            // (e.g. navigating QR -> Settings would otherwise crash).
                            val ctrl = controller
                            CoroutineScope(Dispatchers.Default).launch {
                                try { ctrl.stop() } catch (_: Exception) {}
                            }
                        }
                    }
                    // Expose the controller to the flashlight button below.
                    LaunchedEffect(controller) { activeController = controller }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { textureView },
                            modifier = Modifier.fillMaxSize()
                        )
                        ScanOverlay(
                            modifier = Modifier.fillMaxSize(),
                            scanned = scanned != null
                        )
                    }
                } else {
                    // Camera unavailable / denied — friendly fallback.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.FlashlightOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Нет доступа к камере",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Разрешить")
                            }
                        }
                    }
                }

                // ---- Status line ----
                val status = when {
                    scanned != null -> "Код найден!"
                    !hasCameraPermission -> "Ошибка сканирования"
                    else -> "Поиск кода..."
                }
                val statusColor = when {
                    scanned != null -> MaterialTheme.colorScheme.primary
                    !hasCameraPermission -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (scanned != null) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }

                // ---- Bottom control bar ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            torchOn = !torchOn
                            activeController?.setTorch(torchOn)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hasCameraPermission
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (torchOn) "Фонарик вкл" else "Фонарик")
                    }
                    Button(
                        onClick = { showManualEntry = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Ввести вручную")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Manual hash entry dialog (fallback when the QR cannot be read).
    if (showManualEntry) {
        ManualEntryDialog(
            value = manualHash,
            onValueChange = {
                manualHash = it
                manualError = null
            },
            isValid = manualValid,
            error = manualError,
            onPaste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.primaryClip?.getItemAt(0)?.text?.toString()?.let { pasted ->
                    manualHash = pasted
                    manualError = null
                }
            },
            onDismiss = { showManualEntry = false },
            onConfirm = {
                val hash = parseCertHash(manualHash)
                if (hash == null) {
                    manualError = "Ожидается формат impulse-cert:<64 hex>"
                } else {
                    showManualEntry = false
                    if (scanned == null) {
                        scanned = hash
                        LogManager.i("QrScan", "hash entered manually (short=${LogManager.shortHash(hash)})")
                        onHashScanned(hash)
                    }
                }
            }
        )
    }
}

@Composable
private fun ManualEntryDialog(
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    error: String?,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ввод хеша сертификата") },
        text = {
            Column {
                Text(
                    "Введите хеш в формате impulse-cert:<64 hex-символа>.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Хеш сертификата") },
                    isError = error != null || (value.isNotEmpty() && !isValid),
                    supportingText = {
                        when {
                            error != null -> Text(error)
                            value.isNotEmpty() && !isValid -> Text("Ожидается impulse-cert:<64 hex>")
                            isValid -> Text("Формат корректен")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Вставить из буфера")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isValid
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/**
 * Fluent scanning overlay drawn on top of the camera preview: a dark scrim with
 * a rounded-rect "window" cut out, a glowing animated scan line sweeping through
 * the window, corner brackets, and a centered instruction label.
 */
@Composable
private fun ScanOverlay(modifier: Modifier = Modifier, scanned: Boolean) {
    val density = LocalDensity.current
    val windowSize = 260.dp
    val windowPx = with(density) { windowSize.toPx() }

    val infinite = rememberInfiniteTransition()
    val scanProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val glow by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val left = (w - windowPx) / 2f
            val top = (h - windowPx) / 2f
            val right = left + windowPx
            val bottom = top + windowPx
            val radius = 28.dp.toPx()

            val scrimColor = Color.Black.copy(alpha = 0.55f)
            drawRect(scrimColor, topLeft = Offset(0f, 0f), size = Size(w, top))
            drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(w, h - bottom))
            drawRect(scrimColor, topLeft = Offset(0f, top), size = Size(left, windowPx))
            drawRect(scrimColor, topLeft = Offset(right, top), size = Size(w - right, windowPx))

            val accent = Color(0xFF4F8CFF)
            val stroke = 3.dp.toPx()

            val cornerLen = 28.dp.toPx()
            val bracketColor = if (scanned) Color(0xFF3DDC84) else accent
            fun drawCorner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(
                    color = bracketColor,
                    start = Offset(cx, cy + dy * cornerLen),
                    end = Offset(cx, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = bracketColor,
                    start = Offset(cx, cy),
                    end = Offset(cx + dx * cornerLen, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            drawCorner(left, top, 1f, 1f)
            drawCorner(right, top, -1f, 1f)
            drawCorner(left, bottom, 1f, -1f)
            drawCorner(right, bottom, -1f, -1f)

            drawRoundRect(
                color = bracketColor.copy(alpha = glow),
                topLeft = Offset(left, top),
                size = Size(windowPx, windowPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = Stroke(width = stroke)
            )

            if (!scanned) {
                val lineY = top + scanProgress * windowPx
                drawLine(
                    color = accent.copy(alpha = 0.9f),
                    start = Offset(left + 8.dp.toPx(), lineY),
                    end = Offset(right - 8.dp.toPx(), lineY),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = accent.copy(alpha = 0.25f),
                    start = Offset(left + 8.dp.toPx(), lineY - 10.dp.toPx()),
                    end = Offset(right - 8.dp.toPx(), lineY - 10.dp.toPx()),
                    strokeWidth = 10.dp.toPx()
                )
                drawLine(
                    color = accent.copy(alpha = 0.25f),
                    start = Offset(left + 8.dp.toPx(), lineY + 10.dp.toPx()),
                    end = Offset(right - 8.dp.toPx(), lineY + 10.dp.toPx()),
                    strokeWidth = 10.dp.toPx()
                )
            }
        }
    }
}

/**
 * Minimal Camera2 wrapper: opens the back camera, streams preview frames to a
 * [TextureView] (with correct aspect-ratio transform so the image is not
 * stretched) and feeds YUV_420_888 frames from an [ImageReader] to ML Kit.
 */
@SuppressLint("MissingPermission")
private class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onQrDetected: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor = Executors.newSingleThreadExecutor()
    private val imageLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    private var previewSize: CameraSize? = null

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture, width: Int, height: Int
                ) = openCamera()

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture, width: Int, height: Int
                ) = configureTransform(width, height)

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        // NOTE: must NOT call join() here. This is invoked from the UI thread
        // (DisposableEffect.onDispose), and blocking the UI thread on a thread
        // join can deadlock / throw when the screen is torn down (e.g. navigating
        // QR -> Settings). quitSafely() lets the looper drain and exit on its own.
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun chooseBackCamera(): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return cameraId
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun openCamera() {
        val cameraId = chooseBackCamera() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return

        val viewWidth = textureView.width.takeIf { it > 0 } ?: 1080
        val viewHeight = textureView.height.takeIf { it > 0 } ?: 1080
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        previewSize = chooseSize(sizes, viewRatio)
        val ps = previewSize ?: return

        textureView.surfaceTexture?.setDefaultBufferSize(ps.width, ps.height)
        configureTransform(viewWidth, viewHeight)

        imageReader = ImageReader.newInstance(
            ps.width, ps.height, ImageFormat.YUV_420_888, 3
        ).apply {
            setOnImageAvailableListener({ reader ->
                if (!imageLock.tryAcquire()) return@setOnImageAvailableListener
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    imageLock.release()
                    return@setOnImageAvailableListener
                }
                if (image == null) {
                    imageLock.release()
                    return@setOnImageAvailableListener
                }
                val rotation = getRotationCompensation(cameraId)
                val inputImage = InputImage.fromMediaImage(image, rotation)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                barcode.rawValue?.let { onQrDetected(it) }
                            }
                        }
                    }
                    .addOnFailureListener { LogManager.w("QrScan", "scan failed", it) }
                    .addOnCompleteListener {
                        try { image.close() } finally { imageLock.release() }
                    }
            }, backgroundHandler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                LogManager.e("QrScan", "camera onError error=$error")
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val surface = Surface(textureView.surfaceTexture!!)
        val readerSurface = imageReader?.surface ?: return
        device.createCaptureSession(
            listOf(surface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        addTarget(readerSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    LogManager.e("QrScan", "camera capture session configure failed")
                }
            },
            backgroundHandler
        )
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val ps = previewSize ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, ps.height.toFloat(), ps.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
        textureView.setTransform(matrix)
    }

    private fun chooseSize(sizes: Array<CameraSize>, targetRatio: Float): CameraSize {
        val matched = sizes.filter {
            val r = it.width.toFloat() / it.height.toFloat()
            abs(r - targetRatio) < 0.05f
        }
        val pool = if (matched.isNotEmpty()) matched else sizes.toList()
        return pool.maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull()
            ?: CameraSize(1280, 1280)
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            LogManager.e("QrScan", "stop failed", e)
        }
        stopBackgroundThread()
        scanner.close()
    }

    private fun getRotationCompensation(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = context.display?.rotation ?: android.view.Surface.ROTATION_0
        val surfaceRotationDegrees = when (displayRotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + surfaceRotationDegrees) % 360
        } else {
            (sensorOrientation - surfaceRotationDegrees + 360) % 360
        }
    }

    fun setTorch(enabled: Boolean) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(textureView.surfaceTexture!!))
                addTarget(imageReader?.surface ?: return)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
            session.setRepeatingRequest(request.build(), null, backgroundHandler)
        } catch (e: Exception) {
            LogManager.w("QrScan", "setTorch failed", e)
        }
    }
}

/**
 * Extracts the hex cert hash from a QR payload.
 *
 * STRICT validation (per security audit): the payload MUST carry a recognized
 * TOFU prefix and a 64-hex SHA-256 fingerprint. A bare 64-hex token found
 * elsewhere is NOT accepted — accepting arbitrary 64-hex strings let a
 * malicious or malformed QR silently pin the wrong certificate.
 *
 * Two payload forms are accepted (both case-insensitive prefix, tolerant of
 * surrounding whitespace):
 *   - `impulse-cert:<64-hex-sha256>`            (primary, client-pinned form)
 *   - `impulse-tofu|<64-hex>|<issued_at>`       (server TUI form; the trailing
 *      `|<issued_at>` unix-seconds field is ignored — the client only needs the
 *      fingerprint to pin, and the timestamp is not validated/trusted here)
 *
 * In both cases the returned value is exactly the 64-char lowercase hex
 * fingerprint, so downstream [com.example.impulse.security.TrustedCertManager]
 * storage and comparison stay identical regardless of which form was scanned.
 */
internal fun parseCertHash(raw: String): String? {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()

    // Form 1: impulse-cert:<64-hex>
    val certPrefix = "impulse-cert:"
    if (lower.startsWith(certPrefix)) {
        val hash = trimmed.substring(certPrefix.length).trim()
        if (hash.matches(Regex("^[0-9a-fA-F]{64}$"))) return hash.lowercase()
        return null
    }

    // Form 2: impulse-tofu|<64-hex>|<issued_at>
    val tofuPrefix = "impulse-tofu|"
    if (lower.startsWith(tofuPrefix)) {
        val rest = trimmed.substring(tofuPrefix.length).trim()
        // Split on '|'; the first segment must be exactly 64 hex chars.
        val fp = rest.substringBefore('|').trim()
        if (fp.matches(Regex("^[0-9a-fA-F]{64}$"))) return fp.lowercase()
        return null
    }

    return null
}

/** True only for a recognized TOFU payload (used by manual entry). */
internal fun isValidCertHash(raw: String): Boolean = parseCertHash(raw) != null
