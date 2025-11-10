// MainActivity.kt
package com.example.canhbaongugat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.canhbaongugat.ui.theme.CanhBaoNguGatTheme
import java.util.concurrent.Executors

// Biến theo dõi quyền camera
private var hasCameraPermission by mutableStateOf(false)

class MainActivity : ComponentActivity() {

    // ✅ THÊM MỚI: Yêu cầu quyền đọc ảnh (cho Android cũ)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                hasCameraPermission = true
            } else {
                Log.w("MainActivity", "❌ Quyền Camera hoặc Ảnh bị từ chối")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ THÊM MỚI: Kiểm tra cả 2 quyền
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val storagePermission =
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Chỉ cần cho Android 9 trở xuống
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                PackageManager.PERMISSION_GRANTED
            }

        if (cameraPermission == PackageManager.PERMISSION_GRANTED && storagePermission == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            // Xin quyền camera
            if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            // Xin quyền đọc ảnh (nếu cần)
            if (storagePermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        setContent {
            CanhBaoNguGatTheme {
                if (hasCameraPermission) {
                    CameraPreviewScreen()
                } else {
                    PermissionDeniedMessage()
                }
            }
        }
    }
}

// 🧩 Khi quyền camera bị từ chối
@Composable
fun PermissionDeniedMessage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "⚠️ Vui lòng cấp quyền Camera (và Thư viện) để bật hệ thống.",
            color = Color.Gray
        )
    }
}

// 📷 Màn hình chính của camera + xử lý phát hiện khuôn mặt
@Composable
fun CameraPreviewScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    // Bộ phân tích khuôn mặt
    val faceAnalyzer = remember { FaceAnalyzer(context) }
    val currentMouthRatio by faceAnalyzer.currentMouthRatio.collectAsState() // ✅ THÊM DÒNG NÀY
    val alertLevel by faceAnalyzer.alertLevel.collectAsState()
    val currentOpenness by faceAnalyzer.currentOpenness.collectAsState()

    // --- ✅ THÊM MỚI (Logic chọn ảnh) ---
    // 1. Trạng thái để giữ kết quả phân tích ảnh tĩnh
    var staticAnalysisResult by remember { mutableStateOf<String?>(null) }

    // 2. Trình khởi chạy Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                Log.i("PhotoPicker", "Ảnh đã chọn: $uri")
                try {
                    // 3. Chuyển Uri sang Bitmap
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                context.contentResolver,
                                uri
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }

                    // 4. Gọi hàm analyzeBitmap (mình sẽ tạo ở bước 2)
                    // Cần chạy trên 1 luồng khác để không block UI
                    val probabilityOpen = faceAnalyzer.analyzeBitmap(bitmap)

                    // 5. Hiển thị kết quả
                    val resultText = if (probabilityOpen > 0.5) {
                        "Open_Eyes (Mắt Mở) - Score: ${"%.2f".format(probabilityOpen)}"
                    } else {
                        "Closed_Eyes (Mắt Nhắm) - Score: ${"%.2f".format(probabilityOpen)}"
                    }
                    staticAnalysisResult = resultText

                } catch (e: Exception) {
                    Log.e("PhotoPicker", "Lỗi xử lý ảnh: ${e.message}", e)
                    staticAnalysisResult = "Lỗi: Không thể phân tích ảnh."
                }
            } else {
                Log.i("PhotoPicker", "Không chọn ảnh nào.")
            }
        }
    )
    // --- KẾT THÚC PHẦN THÊM MỚI ---


    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply {
                            setAnalyzer(cameraExecutor, faceAnalyzer)
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "❌ Liên kết camera thất bại", e)
                    }
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 🟡 Hiển thị thông tin nhận dạng (debug)
        Text(
            text = "👁 Mắt: ${"%.2f".format(currentOpenness)} | 👄 Miệng: ${"%.2f".format(currentMouthRatio)} | Trạng thái: $alertLevel",
            color = Color.Yellow,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )

        // 🚨 Hiển thị cảnh báo
        if (alertLevel == AlertLevel.CRITICAL) {
            AlertOverlay()
        }


        // --- ✅ THÊM MỚI: Nút bấm chọn ảnh ---
        IconButton(
            onClick = {
                // Mở Photo Picker
                photoPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Chọn ảnh từ thư viện",
                tint = Color.White
            )
        }

        // --- ✅ THÊM MỚI: Hộp thoại hiển thị kết quả ---
        if (staticAnalysisResult != null) {
            AlertDialog(
                onDismissRequest = { staticAnalysisResult = null },
                title = { Text("Kết quả phân tích ảnh") },
                text = { Text(staticAnalysisResult!!) },
                confirmButton = {
                    Button(onClick = { staticAnalysisResult = null }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

// 🚨 Cảnh báo khi phát hiện ngủ gật (KHÔNG THAY ĐỔI)
@Composable
fun AlertOverlay() {
    // ... (Toàn bộ code của hàm AlertOverlay giữ nguyên)
    val context = LocalContext.current

    DisposableEffect(Unit) {
        // 1️⃣ Rung cảnh báo
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }

        // 2️⃣ Âm thanh cảnh báo
        val mediaPlayer: MediaPlayer? = try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            MediaPlayer.create(context, uri).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            Log.e("ALERT", "Lỗi phát âm thanh: ${e.message}")
            null
        }

        onDispose {
            try {
                vibrator.cancel()
            } catch (e: Exception) {
                Log.e("ALERT", "Lỗi dừng rung: ${e.message}")
            }
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
    }

    // 3️⃣ Hiển thị cảnh báo trực quan
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Red.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🚨 NGỦ GẬT! TỈNH DẬY NGAY!!!",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
    }
}