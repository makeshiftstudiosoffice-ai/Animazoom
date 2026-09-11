package com.onestudio.animazoom

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import java.util.concurrent.LinkedBlockingQueue
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.onestudio.animazoom.ui.theme.AnimaZoomTheme
import org.tensorflow.lite.Interpreter
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            AnimaZoomTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    var hasPermissions by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    if (hasPermissions) {
        CameraInterface()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Permissões necessárias para gravar vídeos.")
                Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                    Text("Conceder Permissões")
                }
            }
        }
    }
}

@Composable
fun CameraInterface() {
    val context = LocalContext.current
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isRecording by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastRecordedPath by remember { mutableStateOf<String?>(null) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }

    // Especificações Técnicas: Cockpit de Redes
    var selectedPlatform by remember { mutableStateOf("YT") }
    val platforms = listOf(
        PlatformConfig("SHORTS", 59, Color.Red),
        PlatformConfig("TK/KW", 150, Color.Yellow),
        PlatformConfig("YT", 420, Color.Green)
    )

    // Controle de Zoom "Mão Livre"
    var zoomScale by remember { mutableFloatStateOf(1f) }

    var recordingStartTime by remember { mutableLongStateOf(0L) }
    var currentRecordingDuration by remember { mutableLongStateOf(0L) }

    val styleTransformer = remember { StyleTransformer(context) }
    val logoPainter = painterFromAssets("logo_anima_zoom.png")
    val videoRecorder = remember { VideoRecorder(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 1. O "Corte Seco" (Stop Automático)
    LaunchedEffect(isRecording, selectedPlatform) {
        if (isRecording) {
            recordingStartTime = System.currentTimeMillis()
            val limitSecs = platforms.find { it.name == selectedPlatform }?.duration ?: 59
            val limitMs = limitSecs * 1000L

            while (isRecording) {
                currentRecordingDuration = System.currentTimeMillis() - recordingStartTime
                if (currentRecordingDuration >= limitMs) {
                    // "O timer matando a gravação no tempo cravado"
                    isRecording = false
                    isSaving = true
                    Toast.makeText(context, "Limite de $selectedPlatform atingido!", Toast.LENGTH_SHORT).show()
                    videoRecorder.stopRecording(lensFacing) { path, error ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            lastRecordedPath = path
                            lastErrorMessage = error
                            isSaving = false
                            showSuccessDialog = true
                        }
                    }
                    break
                }
                kotlinx.coroutines.delay(100)
            }
        } else {
            currentRecordingDuration = 0L
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text(if (lastRecordedPath?.isNotEmpty() == true) "Missão Concluída" else "Falha na Missão") },
            text = {
                Text(if (lastRecordedPath?.isNotEmpty() == true)
                    "Vídeo Gerado com Sucesso!"
                else "Erro Técnico: ${lastErrorMessage ?: "Falha desconhecida na escrita"}")
            },
            confirmButton = {
                if (lastRecordedPath?.isNotEmpty() == true) {
                    Button(onClick = {
                        showSuccessDialog = false
                        lastRecordedPath?.let { path ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    val file = File(path)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Erro ao abrir vídeo: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("VER VÍDEO")
                    }
                } else {
                    Button(onClick = { showSuccessDialog = false }) {
                        Text("ENTENDIDO")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("FECHAR")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.White)
            .pointerInput(Unit) {
                // Implementação manual de detecção de escala para suavidade "Elite"
                detectTransformGestures { _, _, zoom, _ ->
                    // Travar a velocidade para ser lenta e constante
                    val sensitivity = 0.25f
                    val slowedZoom = 1f + (zoom - 1f) * sensitivity
                    val newScale = zoomScale * slowedZoom
                    // Trava entre 1x (Marco Zero) e 5x (Emaranhado Máximo)
                    zoomScale = newScale.coerceIn(1f, 5f)
                }
            }
        ) {
            CameraProcessor(
                lensFacing = lensFacing,
                onFrameProcessed = { bitmap ->
                    val art = styleTransformer.transform(bitmap, zoomScale)
                    processedBitmap = art
                    if (isRecording) {
                        videoRecorder.recordFrame(art, lensFacing)
                    }
                }
            )

            processedBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Top Identity
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                Text("BRAZI Studios", color = Color.Black, style = MaterialTheme.typography.headlineSmall)
            }

            // GhostLogoView removido daqui pois agora é desenhado diretamente no frame pelo StyleTransformer
            // Isso garante que a logo apareça tanto no preview quanto no vídeo gravado.

            // Overlay for all controls to ensure clickability
            Box(modifier = Modifier.fillMaxSize()) {
                CameraControls(
                    isRecording = isRecording,
                    enabled = !isSaving,
                    selectedPlatform = selectedPlatform,
                    platforms = platforms,
                    onPlatformSelect = { selectedPlatform = it },
                    onToggleRecording = {
                        if (isRecording) {
                            isRecording = false
                            isSaving = true
                            Toast.makeText(context, "Limpando o Ninho... Aguarde", Toast.LENGTH_LONG).show()
                            videoRecorder.stopRecording(lensFacing) { path, error ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    lastRecordedPath = path
                                    lastErrorMessage = error
                                    isSaving = false
                                    showSuccessDialog = true
                                }
                            }
                        } else {
                            isRecording = true
                            lastRecordedPath = null
                            lastErrorMessage = null
                            videoRecorder.startRecording(lensFacing)
                            Toast.makeText(context, "Gravação iniciada ($selectedPlatform)", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFlipCamera = {
                        if (!isRecording && !isSaving) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    }
                )

                // WhatsApp Share Button - Explicitly on top and bright when ready
                lastRecordedPath?.let { path ->
                    if (!isRecording && !isSaving) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { openVault(context) },
                                modifier = Modifier.size(width = 200.dp, height = 60.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.DarkGray,
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                            ) {
                                Text("ABRIR COFRE", style = MaterialTheme.typography.labelLarge)
                            }

                            Button(
                                onClick = {
                                    Toast.makeText(context, "Preparando envio...", Toast.LENGTH_SHORT).show()
                                    shareVideo(context, path)
                                },
                                modifier = Modifier.size(width = 200.dp, height = 60.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF25D366),
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                            ) {
                                Text("ENVIAR PARA O WHATSAPP", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                // Color selector locked to BLACK
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(24.dp)
                        .size(40.dp)
                        .background(Color.Black, CircleShape)
                )
            }
        }
    }
}

fun shareVideo(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar via"))
    } catch (e: Exception) {
        Log.e("Share", "Error sharing video", e)
    }
}

fun openVault(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // Caminho para a pasta específica do AnimaZoom (ajustado para bater com a pasta do celular)
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FAnimaZoom")
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback: Abre a galeria geral de vídeos
        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/video")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(Intent.createChooser(fallbackIntent, "Abrir Galeria"))
        } catch (ex: Exception) {
            Toast.makeText(context, "Não foi possível abrir a galeria", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun CameraProcessor(
    lensFacing: Int,
    onFrameProcessed: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = cameraProviderFuture.get()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            val bitmap = imageProxy.toBitmapCustom()
            onFrameProcessed(bitmap)
            imageProxy.close()
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("CameraProcessor", "Binding failed", e)
        }
    }
}

fun ImageProxy.toBitmapCustom(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    val matrix = Matrix()
    matrix.postRotate(imageInfo.rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
}

@Composable
fun GhostLogoView(painter: androidx.compose.ui.graphics.painter.Painter?) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val targetX by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(animation = tween(12000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "x"
    )
    val targetY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(animation = tween(18000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "y"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val x = (targetX * (maxWidth.value - 100)).dp
        val y = (targetY * (maxHeight.value - 100)).dp
        painter?.let {
            Image(
                painter = it,
                contentDescription = null,
                modifier = Modifier.offset(x, y).size(100.dp).alpha(0.12f) // Logo mais discreta
            )
        }
    }
}

data class PlatformConfig(val name: String, val duration: Int, val color: Color)

@Composable
fun CameraControls(
    isRecording: Boolean,
    enabled: Boolean = true,
    selectedPlatform: String,
    platforms: List<PlatformConfig>,
    onPlatformSelect: (String) -> Unit,
    onToggleRecording: () -> Unit,
    onFlipCamera: () -> Unit
) {
    } Box(modifier = Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Cockpit de Redes (Interface Visual)
            Row(
                modifier = Modifier.padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                platforms.forEach { platform ->
                    Button(
                        onClick = { if (!isRecording) onPlatformSelect(platform.name) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedPlatform == platform.name) platform.color else Color.DarkGray,
                            contentColor = if (platform.color == Color.Yellow) Color.Black else Color.White
                        ),
                        modifier = Modifier.height(40.dp),
                        enabled = !isRecording && enabled
                    ) {
                        Text(platform.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onFlipCamera, enabled = enabled) {
                    Icon(Icons.Default.Cached, contentDescription = "Flip", tint = if (enabled) Color.White else Color.Gray, modifier = Modifier.size(32.dp))
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .alpha(if (enabled) 1f else 0.5f)
                        .background(Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable(enabled = enabled) { onToggleRecording() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = "REC",
                        tint = if (isRecording) Color.Red else if (enabled) Color.White else Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                }
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}

class StyleTransformer(val context: Context) {
    private var interpreter: Interpreter? = null
    private var faceLandmarker: FaceLandmarker? = null

    private val wordsManager = MakeshiftWordsManager()

    private val paperBitmap: Bitmap? by lazy {
        try { context.assets.open("papel_pautado.jpg").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }

    private val logoBitmap: Bitmap? by lazy {
        try { context.assets.open("logo_anima_zoom.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }

    private val inputSize = 256
    private val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    private val typewriterPaint = Paint().apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 5f
        isAntiAlias = true
    }

    private val logoPaint = Paint().apply {
        isAntiAlias = true
        alpha = 127 // Calibrado: 50% de opacidade (Sutil e Profissional)
    }

    private val zone1Chars = arrayOf("M", "W", "B", "@", "#")
    private val zone2Chars = arrayOf("a", "e", "o", "s", "V", "X")
    private val zone3Chars = arrayOf(".", ",", ";", ":", "'", "\"", "-", "/")

    init {
        try {
            val modelFile = loadFile(context, "style_transformer.tflite.gz", "style_transformer.tflite", true)
            interpreter = Interpreter(modelFile)

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("StyleTransformer", "Init error", e)
        }
    }

    private fun loadFile(context: Context, assetName: String, fileName: String, isGzip: Boolean): File {
        val file = File(context.cacheDir, fileName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                val finalInput = if (isGzip) GZIPInputStream(input) else input
                finalInput.use { stream ->
                    FileOutputStream(file).use { output -> stream.copyTo(output) }
                }
            }
        }
        return file
    }

    fun transform(bitmap: Bitmap, zoomScale: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // 1. Camada 0: Papel Vegetal Envelhecido
        paperBitmap?.let {
            canvas.drawBitmap(it, Rect(0, 0, it.width, it.height), Rect(0, 0, width, height), null)
        } ?: canvas.drawColor(android.graphics.Color.WHITE)

        // 2. Motor Olivetti Noir: Perpetual Typing
        // Mantemos o zoomScale aqui para a amostragem do Bitmap, mas as letras ficarão fixas no onDraw.
        drawOlivettiMatrix(canvas, bitmap, width, height, zoomScale)

        // 3. Marca d'água Circulante (Ghost Logo) - Estática
        drawGhostLogo(canvas, width, height)

        // 4. Sequestro de Sinal: Palavras aleatórias (Bicolor) - Estática
        wordsManager.drawSequestro(canvas, width, height)

        // 5. Selo BRAZI Studios (Carimbo de Tinta Fixo) - Estática
        drawBraziStamp(canvas, width, height)

        return outputBitmap
    }

    private fun drawGhostLogo(canvas: Canvas, w: Int, h: Int) {
        logoBitmap?.let { logo ->
            val time = System.currentTimeMillis()

            // Simular a animação do Compose (12s para X, 18s para Y)
            val progressX = (time % 12000) / 12000f
            val progressY = (time % 18000) / 18000f

            // Efeito vai e vem (ping-pong)
            val factorX = if (progressX < 0.5f) progressX * 2 else (1f - progressX) * 2
            val factorY = if (progressY < 0.5f) progressY * 2 else (1f - progressY) * 2

            val logoSize = w * 0.16f // Reduzido em 20% para ar 'Premium'
            val x = factorX * (w - logoSize)
            val y = factorY * (h - logoSize)

            val destRect = RectF(x, y, x + logoSize, y + logoSize)
            canvas.drawBitmap(logo, null, destRect, logoPaint)
        }


        private fun drawOlivettiMatrix(canvas: Canvas, original: Bitmap, w: Int, h: Int, zoomScale: Float) {
        val random = java.util.Random()
        val stepX = 3
        val stepY = 4

        val pixels = IntArray(w * h)
        original.getPixels(pixels, 0, w, 0, 0, w, h)

        // Trava o tamanho original das letras (Mata o "Balde de Petróleo")
        // O valor fixo garante a nitidez e evita o emaranhado excessivo.
        typewriterPaint.textSize = 5f

        for (x in 0 until w step stepX) {
            for (y in 0 until h step stepY) {
                // Zoom apenas no Bitmap (Amostragem seletiva centralizada)
                // A imagem cresce (1x a 5x), mas a "grade" de letras permanece constante.
                val sx = ((x - w / 2f) / zoomScale + w / 2f).toInt().coerceIn(0, w - 1)
                val sy = ((y - h / 2f) / zoomScale + h / 2f).toInt().coerceIn(0, h - 1)

                val pixel = pixels[sy * w + sx]
                val luma = (((pixel shr 16) and 0xFF) * 0.299 + ((pixel shr 8) and 0xFF) * 0.587 + (pixel and 0xFF) * 0.114).toInt()

                val jX = x.toFloat() + (random.nextFloat() * 2 - 1)
                val jY = y.toFloat() + (random.nextFloat() * 2 - 1)

                when {
                    luma <= 51 -> { // Sombras Intensas (Protocolo BRAZI-TYPE)
                        typewriterPaint.color = android.graphics.Color.BLACK
                        typewriterPaint.alpha = 255
                        canvas.drawText(zone1Chars[random.nextInt(zone1Chars.size)], jX, jY, typewriterPaint)
                        canvas.drawText(zone1Chars[random.nextInt(zone1Chars.size)], jX + 0.5f, jY, typewriterPaint)
                    }
                    luma <= 127 -> { // Meios-tons e Volumes
                        typewriterPaint.color = android.graphics.Color.BLACK
                        typewriterPaint.alpha = 200
                        canvas.drawText(zone2Chars[random.nextInt(zone2Chars.size)], jX, jY, typewriterPaint)
                    }
                    luma <= 204 -> { // Contornos e Detalhes Finos
                        typewriterPaint.color = android.graphics.Color.BLACK
                        typewriterPaint.alpha = 160
                        canvas.drawText(zone3Chars[random.nextInt(zone3Chars.size)], jX, jY, typewriterPaint)
                    }
                    else -> { // Efeito Fita Gasta (Zonas de luz)
                        typewriterPaint.color = android.graphics.Color.BLACK
                        typewriterPaint.alpha = 45
                        val ribbonChars = arrayOf(".", ",")
                        canvas.drawText(ribbonChars[random.nextInt(ribbonChars.size)], jX, jY, typewriterPaint)
                    }
                }
            }
        }
    }

    private fun drawBraziStamp(canvas: Canvas, w: Int, h: Int) {
        val stampPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 20f // Reduzido para ar 'Premium'
            alpha = 127 // 50% de opacidade
        }
        canvas.drawText("BRAZI Studios", 40f, h - 40f, stampPaint)
    }
}

class VideoRecorder(val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var isRecording = AtomicBoolean(false)
    private var tempFile: File? = null

    private val videoWidth = 480
    private val videoHeight = 640

    fun startRecording(lensFacing: Int = -1) {
        if (isRecording.get()) return

        // 1. Limpeza de Rastro: Deleta temporários antigos
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach {
                if (it.name.startsWith("temp_recording_") && it.name.endsWith(".mp4")) {
                    it.delete()
                }
            }
        } catch (e: Exception) { Log.e("VideoRecorder", "Erro na limpeza de rastro", e) }

        System.gc()

        try {
            tempFile = File(context.cacheDir, "temp_recording_${System.currentTimeMillis()}.mp4")

            // 2. Plano B: MediaRecorder Simples (Pé de Cabra)
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            mediaRecorder?.apply {
                // Microfone OFF: Removido setAudioSource e setAudioEncoder
                // O vídeo será mudo e a trilha será injetada via FFmpeg
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(tempFile!!.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(videoWidth, videoHeight)
                setVideoEncodingBitRate(1000000) // 1.0 Mbps
                setVideoFrameRate(20)
                setOrientationHint(90)

                prepare()
                start()
                recorderSurface = surface
            }

            isRecording.set(true)
            Log.d("VideoRecorder", "MediaRecorder iniciado em 480p")
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Falha no Plano B (MediaRecorder)", e)
            cleanup()
        }
    }

    fun recordFrame(bitmap: Bitmap, currentLens: Int = -1) {
        if (!isRecording.get() || recorderSurface == null) return

        try {
            val canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                recorderSurface?.lockHardwareCanvas()
            } else {
                recorderSurface?.lockCanvas(null)
            }

            canvas?.let {
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, videoWidth, videoHeight, true)
                it.drawBitmap(scaledBitmap, 0f, 0f, null)
                recorderSurface?.unlockCanvasAndPost(it)
            }
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Erro ao desenhar frame no Surface", e)
        }
    }

    private fun cleanup() {
        isRecording.set(false)
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
        recorderSurface = null
    }

    fun stopRecording(lensFacing: Int = -1, onComplete: (String, String?) -> Unit) {
        if (!isRecording.getAndSet(false)) return

        Thread {
            var errorMessage: String? = null
            try {
                Log.d("VideoRecorder", "Parando MediaRecorder...")

                // Pequeno atraso para o último frame entrar (Pulo do Gato)
                Thread.sleep(500)

                try {
                    mediaRecorder?.stop()
                } catch (e: Exception) {
                    Log.e("VideoRecorder", "Erro no stop do MediaRecorder", e)
                    errorMessage = "Falha no Gravador: ${e.message}"
                }

                mediaRecorder?.release()
                mediaRecorder = null
                recorderSurface = null

                saveToPrivateStorage { path, error ->
                    onComplete(path, error ?: errorMessage)
                }
            } catch (e: Exception) {
                Log.e("VideoRecorder", "Falha crítica no encerramento", e)
                onComplete("", e.message)
            }
        }.start()
    }

    private fun saveToPrivateStorage(onComplete: (String, String?) -> Unit) {
        val file = tempFile ?: return
        if (!file.exists() || file.length() == 0L) {
            onComplete("", "Arquivo não gerado ou vazio")
            return
        }

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "ANIMAZOOM_$timeStamp.mp4"
            val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (privateDir?.exists() == false) privateDir.mkdirs()

            val targetFile = File(privateDir, fileName)
            file.copyTo(tazsrgetFile, overwrite = true)

            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null) { _, _ -> }
            onComplete(targetFile.absolutePath, null)
        } catch (e: Exception) {
            onComplete("", "Erro de Escrita: ${e.message}")
        } finally {
            file.delete()
        }
    }
}

@Composable
fun painterFromAssets(path: String): androidx.compose.ui.graphics.painter.Painter? {
    val context = LocalContext.current
    val bitmap = remember(path) {
        try { context.assets.open(path).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }
    return bitmap?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.asImageBitmap()) }
}

