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
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size as CameraSize
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.impulse.ui.theme.*
import com.example.impulse.util.LogManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    serverId: String,
    onCertScanned: (String) -> Unit,
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
    var scanError by remember { mutableStateOf<String?>(null) }
    var manualError by remember { mutableStateOf<String?>(null) }
    val manualValid = remember(manualHash) { parseCertHash(manualHash) != null }
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
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))

                // ── Header ──────────────────────────────────────────────
                Text(
                    "Подключение к серверу",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Отсканируйте QR-код с экрана сервера",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                // ── Camera card ─────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Camera preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center,
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
                                                scanError = null
                                                LogManager.i("QrScan", "cert scanned (short=${LogManager.shortHash(hash)})")
                                                onCertScanned(hash)
                                            } else {
                                                scanError = "Невалидный QR-код"
                                                LogManager.w("QrScan", "parseCertHash rejected: '$raw'")
                                            }
                                        }
                                    }
                                }
                                DisposableEffect(Unit) {
                                    try { controller.start() } catch (e: Exception) {
                                        LogManager.e("QrScan", "camera start failed", e)
                                        hasCameraPermission = false
                                    }
                                    onDispose { try { controller.stop() } catch (_: Exception) {} }
                                }
                                LaunchedEffect(controller) { activeController = controller }

                                AndroidView(
                                    factory = { textureView },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                )
                                ScanOverlay(
                                    modifier = Modifier.fillMaxSize(),
                                    scanned = scanned != null,
                                )
                            } else {
                                // No camera permission
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.FlashlightOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Нет доступа к камере",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                        Text("Разрешить")
                                    }
                                }
                            }
                        }

                        // ── Status ──────────────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            val statusText = when {
                                scanned != null -> "Код найден"
                                !hasCameraPermission -> "Камера недоступна"
                                else -> "Поиск кода..."
                            }
                            val statusColor = when {
                                scanned != null -> MaterialTheme.colorScheme.primary
                                !hasCameraPermission -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            if (scanned != null) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = statusColor,
                            )
                        }

                        // ── Error banner ────────────────────────────────
                        if (scanError != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = scanError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { scanError = null }) {
                                        Text("OK")
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // ── Action buttons ──────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    torchOn = !torchOn
                                    activeController?.setTorch(torchOn)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = hasCameraPermission,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (torchOn) "Вкл" else "Фонарик")
                            }
                            Button(
                                onClick = { showManualEntry = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Вручную")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Format hint ─────────────────────────────────────────
                Text(
                    "impulse-cert:<64 hex>",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }

    // Manual hash entry dialog
    if (showManualEntry) {
        ManualEntryDialog(
            value = manualHash,
            onValueChange = { manualHash = it; manualError = null },
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
                    manualError = "Ожидается impulse-cert:<64 hex>"
                } else {
                    showManualEntry = false
                    if (scanned == null) {
                        scanned = hash
                        LogManager.i("QrScan", "cert entered manually (short=${LogManager.shortHash(hash)})")
                        onCertScanned(hash)
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
                    "Введите хеш в формате impulse-cert:<64 hex>.",
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
            TextButton(onClick = onConfirm, enabled = isValid) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun ScanOverlay(modifier: Modifier = Modifier, scanned: Boolean) {
    val density = LocalDensity.current
    val windowSize = 220.dp
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
        targetValue = 0.65f,
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
            val radius = 20.dp.toPx()

            // Dimmed scrim around the window
            val scrimColor = Color.Black.copy(alpha = 0.50f)
            drawRect(scrimColor, topLeft = Offset(0f, 0f), size = Size(w, top))
            drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(w, h - bottom))
            drawRect(scrimColor, topLeft = Offset(0f, top), size = Size(left, windowPx))
            drawRect(scrimColor, topLeft = Offset(right, top), size = Size(w - right, windowPx))

            val accent = if (scanned) Color(0xFF3DDC84) else Color(0xFF4F8CFF)
            val stroke = 2.dp.toPx()
            val cornerLen = 24.dp.toPx()

            // Corner brackets
            fun drawCorner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(
                    color = accent,
                    start = Offset(cx, cy + dy * cornerLen),
                    end = Offset(cx, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accent,
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

            // Subtle border glow
            drawRoundRect(
                color = accent.copy(alpha = glow),
                topLeft = Offset(left, top),
                size = Size(windowPx, windowPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = Stroke(width = stroke)
            )

            // Scan line
            if (!scanned) {
                val lineY = top + scanProgress * windowPx
                drawLine(
                    color = accent.copy(alpha = 0.8f),
                    start = Offset(left + 8.dp.toPx(), lineY),
                    end = Offset(right - 8.dp.toPx(), lineY),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = accent.copy(alpha = 0.15f),
                    start = Offset(left + 8.dp.toPx(), lineY - 8.dp.toPx()),
                    end = Offset(right - 8.dp.toPx(), lineY - 8.dp.toPx()),
                    strokeWidth = 8.dp.toPx()
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

    @Volatile private var isActive = false
    @Volatile private var stopped = true
    private var retryCount = 0

    private var previewSize: CameraSize? = null

    fun start() {
        if (isActive) return
        isActive = true
        stopped = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            isActive = false
            return
        }
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    if (isActive && !stopped) openCamera()
                }

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
        if (!isActive) return
        val cameraId = chooseBackCamera() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return
        val handler = backgroundHandler ?: return

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
                if (!isActive || stopped) {
                    imageLock.release()
                    return@setOnImageAvailableListener
                }
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
                        if (!isActive || stopped) return@addOnSuccessListener
                        for (barcode in barcodes) {
                            if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                barcode.rawValue?.let { onQrDetected(it) }
                            }
                        }
                    }
                    .addOnFailureListener { if (!stopped) LogManager.w("QrScan", "scan failed", it) }
                    .addOnCompleteListener {
                        try { image.close() } catch (_: Exception) {} finally { imageLock.release() }
                    }
            }, handler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!isActive) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                createSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                scheduleRetry()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                LogManager.e("QrScan", "camera onError error=$error")
                camera.close()
                cameraDevice = null
                scheduleRetry()
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        if (!isActive) return
        val device = cameraDevice ?: return
        val handler = backgroundHandler ?: return
        val surfaceTexture = textureView.surfaceTexture ?: run {
            LogManager.w("QrScan", "createSession skipped: surfaceTexture is null")
            return
        }
        val surface = Surface(surfaceTexture)
        val readerSurface = imageReader?.surface ?: return
        try {
            val outputs = listOf<OutputConfiguration>(
                OutputConfiguration(surface),
                OutputConfiguration(readerSurface)
            )
            device.createCaptureSessionByOutputConfigurations(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isActive) {
                            session.close()
                            return
                        }
                        captureSession = session
                        retryCount = 0
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            addTarget(readerSurface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        session.setRepeatingRequest(request.build(), null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        LogManager.e("QrScan", "camera capture session configure failed")
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            LogManager.e("QrScan", "createSession failed: ${e.message}", e)
        } catch (e: IllegalStateException) {
            LogManager.e("QrScan", "createSession failed (camera closed?): ${e.message}", e)
        }
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
        isActive = false
        stopped = true
        // scanner.close() MUST happen on the background thread AFTER the
        // imageReader is closed and the capture session is torn down — otherwise
        // an in-flight scanner.process() call (on pool-7-thread) touches freed
        // native memory in libbarhopper_v3.so → SIGSEGV.
        val handler = backgroundHandler
        if (handler != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try {
                    captureSession?.close()
                    captureSession = null
                    cameraDevice?.close()
                    cameraDevice = null
                    imageReader?.close()
                    imageReader = null
                    scanner.close()
                } catch (e: Exception) {
                    LogManager.e("QrScan", "stop camera cleanup failed", e)
                } finally {
                    backgroundThread?.quitSafely()
                    backgroundThread = null
                    backgroundHandler = null
                    latch.countDown()
                }
            }
            try { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { }
        } else {
            try {
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
                imageReader?.close()
                imageReader = null
                scanner.close()
            } catch (e: Exception) {
                LogManager.e("QrScan", "stop failed", e)
            }
            stopBackgroundThread()
        }
        textureView.post { textureView.surfaceTextureListener = null }
    }

    private fun getRotationCompensation(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = context.display.rotation
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

    private fun scheduleRetry() {
        if (!isActive || stopped || retryCount >= 3) return
        retryCount++
        val delayMs = retryCount * 1000L
        LogManager.i("QrScan", "camera retry $retryCount in ${delayMs}ms")
        backgroundHandler?.postDelayed({
            if (!isActive || stopped) return@postDelayed
            try {
                captureSession?.close()
                captureSession = null
                imageReader?.close()
                imageReader = null
            } catch (_: Exception) {}
            openCamera()
        }, delayMs)
    }

    fun setTorch(enabled: Boolean) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val st = textureView.surfaceTexture ?: return
        val handler = backgroundHandler ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(st))
                addTarget(imageReader?.surface ?: return)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
            session.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            LogManager.w("QrScan", "setTorch failed", e)
        }
    }
}

/**
 * Extracts the hex cert hash from a TOFU QR payload.
 *
 * STRICT validation (per security audit): the payload MUST carry a recognized
 * TOFU prefix and a 64-hex SHA-256 fingerprint.
 *
 * Accepted forms:
 *   - `impulse-cert:<64-hex-sha256>`          (current server QR format)
 *   - `impulse-tofu|<64-hex>|<issued_at>`     (legacy server QR format)
 *
 * Returns the normalized lowercase hash, or null for unrecognised/malformed payloads.
 */
internal fun parseCertHash(raw: String): String? {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()

    val certPrefix = "impulse-cert:"
    if (lower.startsWith(certPrefix)) {
        val hash = trimmed.substring(certPrefix.length).trim()
        if (!hash.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
        return hash.lowercase()
    }

    val tofuPrefix = "impulse-tofu|"
    if (lower.startsWith(tofuPrefix)) {
        val rest = trimmed.substring(tofuPrefix.length).trim()
        val fp = rest.substringBefore('|').trim()
        if (fp.matches(Regex("^[0-9a-fA-F]{64}$"))) return fp.lowercase()
        return null
    }

    return null
}

internal fun isValidCertHash(raw: String): Boolean = parseCertHash(raw) != null
