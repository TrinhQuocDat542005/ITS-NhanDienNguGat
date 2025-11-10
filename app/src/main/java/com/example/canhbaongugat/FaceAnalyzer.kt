// FaceAnalyzer.kt
package com.example.canhbaongugat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF // ✅ THÊM LẠI
import android.graphics.Rect
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark // ✅ THÊM LẠI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow // ✅ THÊM LẠI
import kotlin.math.sqrt // ✅ THÊM LẠI


// 🔔 Các cấp độ cảnh báo (Không đổi)
enum class AlertLevel {
    NONE,
    LOW,
    CRITICAL
}

// ✅ PHIÊN BẢN KẾT HỢP (ML Kit + TFLite + Ngáp)
class FaceAnalyzer(context: Context) : ImageAnalysis.Analyzer {

    // 🌡 Trạng thái (Không đổi)
    private val _alertLevel = MutableStateFlow(AlertLevel.NONE)
    val alertLevel: StateFlow<AlertLevel> = _alertLevel
    private val _currentOpenness = MutableStateFlow(1.0)
    val currentOpenness: StateFlow<Double> = _currentOpenness
    // ✅ THÊM LẠI: Trạng thái miệng
    private val _currentMouthRatio = MutableStateFlow(0.0)
    val currentMouthRatio: StateFlow<Double> = _currentMouthRatio

    // 🚦 Ngưỡng cảnh báo (Cập nhật)
    private val LOW_DROWSY_THRESHOLD = 0.50
    private val CRITICAL_DROWSY_THRESHOLD = 0.30
    private val YAWN_THRESHOLD = 0.60 // ✅ THÊM LẠI: Ngưỡng ngáp
    private val LOW_DROWSY_FRAMES = 10
    private val CRITICAL_DROWSY_FRAMES = 30
    private var frameCounter = 0
    private var lastAnalyzedTimestamp = 0L

    // --- ✅ 1. BỘ PHÁT HIỆN KHUÔN MẶT (ML Kit) ---
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL) // ✅ Cần Landmark cho miệng
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    // --- ✅ 2. BỘ PHÂN LOẠI ẢNH (TFLite) ---
    private val tfliteInterpreter: Interpreter
    private val labels: List<String>
    private val imageProcessor: ImageProcessor
    private val outputBuffer: TensorBuffer
    private val MODEL_INPUT_WIDTH = 160
    private val MODEL_INPUT_HEIGHT = 160

    init {
        // --- Cấu hình TFLite (Không đổi) ---
        val modelByteBuffer: ByteBuffer =
            FileUtil.loadMappedFile(context, "model.tflite")
        tfliteInterpreter = Interpreter(modelByteBuffer, Interpreter.Options())
        labels = FileUtil.loadLabels(context, "labels.txt")

        val outputShape = tfliteInterpreter.getOutputTensor(0).shape()
        val outputDataType = tfliteInterpreter.getOutputTensor(0).dataType()
        outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType)

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f)) // Chuyển pixel về [-1, 1]
            .build()

        Log.i("FaceAnalyzer", "Đã khởi tạo TFLite và ML Kit Face Detector (có Landmark)")
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzedTimestamp < TimeUnit.MILLISECONDS.toMillis(50)) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimestamp = currentTimestamp

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            // 2. CHẠY ML KIT ĐỂ TÌM KHUÔN MẶT
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces.first()

                        // ✅ THÊM LẠI: Tính toán Mouth Aspect Ratio (MAR)
                        val mouthRatio = calculateMouthRatio(face)
                        _currentMouthRatio.value = mouthRatio // Cập nhật UI

                        // 3. CHUẨN BỊ ẢNH ĐỂ CẮT (CROP)
                        val fullBitmap = imageProxy.toBitmap()

                        // 4. CẮT (CROP) ẢNH
                        val croppedBitmap = cropBitmap(fullBitmap, face.boundingBox)

                        // 5. CHẠY TFLite (Mô hình của anh)
                        // ✅ Sửa: Truyền cả mouthRatio vào
                        runTFLite(croppedBitmap, mouthRatio)

                    } else {
                        // Không tìm thấy khuôn mặt
                        processResult(1.0, 0.0) // Mắt mở, miệng đóng
                        _currentMouthRatio.value = 0.0
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FaceAnalyzer", "ML Kit Face Detection thất bại: ${e.message}")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * Cắt Bitmap theo khung viền (Rect)
     */
    private fun cropBitmap(source: Bitmap, box: Rect): Bitmap {
        val left = max(0, box.left)
        val top = max(0, box.top)
        val width = min(source.width - left, box.width())
        val height = min(source.height - top, box.height())

        if (width <= 0 || height <= 0) {
            Log.w("FaceAnalyzer", "Khung cắt không hợp lệ (width/height <= 0)")
            return source
        }
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    /**
     * ✅ THÊM LẠI: Tính toán Mouth Aspect Ratio (MAR)
     */
    private fun calculateMouthRatio(face: Face): Double {
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position

        var mouthRatio = 0.0
        if (mouthLeft != null && mouthRight != null && mouthBottom != null && noseBase != null) {
            // Ước lượng MOUTH_TOP (giống code cũ của anh)
            val mouthTopX = (noseBase.x + mouthBottom.x) / 2
            val mouthTopY = (noseBase.y + mouthBottom.y) / 2
            val mouthTop = PointF(mouthTopX, mouthTopY)

            val vertical = distance(mouthTop, mouthBottom)
            val horizontal = distance(mouthLeft, mouthRight)
            if (horizontal > 0) {
                mouthRatio = vertical / horizontal
            }
        }
        return mouthRatio
    }

    /**
     * ✅ THÊM LẠI: Hàm tính khoảng cách
     */
    private fun distance(p1: PointF, p2: PointF): Double {
        return sqrt(
            (p2.x - p1.x).toDouble().pow(2.0) +
                    (p2.y - p1.y).toDouble().pow(2.0)
        )
    }


    /**
     * Chạy mô hình TFLite trên Bitmap đã cắt
     */
    // ✅ Sửa: Thêm tham số mouthRatio
    private fun runTFLite(bitmap: Bitmap, mouthRatio: Double) {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val processedImage = imageProcessor.process(tensorImage)

        try {
            tfliteInterpreter.run(processedImage.buffer, outputBuffer.buffer)
        } catch (e: Exception) {
            Log.e("FaceAnalyzer", "Lỗi khi chạy mô hình TFLite: ${e.message}", e)
            return
        }

        val probabilityOpen = outputBuffer.floatArray[0].toDouble()

        // ✅ Sửa: Truyền cả 2 kết quả
        processResult(probabilityOpen, mouthRatio)
    }

    /**
     * ✅ SỬA: HÀM LOGIC CẢNH BÁO (Dùng cả Mắt và Miệng)
     */
    private fun processResult(openness: Double, mouthRatio: Double) {
        _currentOpenness.value = openness
        // _currentMouthRatio đã được cập nhật trong hàm analyze

        when {
            // 🚨 CẢNH BÁO NGỦ GẬT (CRITICAL - Mắt nhắm sâu)
            openness < CRITICAL_DROWSY_THRESHOLD -> {
                frameCounter++
                if (frameCounter >= CRITICAL_DROWSY_FRAMES) {
                    _alertLevel.value = AlertLevel.CRITICAL
                    Log.w("DrowsyAnalyzer", "🚨 (Hybrid) PHÁT HIỆN NGỦ GẬT!")
                } else if (frameCounter >= LOW_DROWSY_FRAMES) {
                    _alertLevel.value = AlertLevel.LOW
                }
            }

            // ⚠️ CẢNH BÁO SỚM (LOW - Mắt nhắm nhẹ HOẶC Ngáp)
            // ✅ THÊM LẠI LOGIC NGÁP
            openness < LOW_DROWSY_THRESHOLD || mouthRatio > YAWN_THRESHOLD -> {
                frameCounter++
                if (frameCounter >= LOW_DROWSY_FRAMES && _alertLevel.value == AlertLevel.NONE) {
                    _alertLevel.value = AlertLevel.LOW
                    Log.i("DrowsyAnalyzer", "⚠️ (Hybrid) CẢNH BÁO: Mệt mỏi hoặc Ngáp")
                }
            }

            // 👁 TRẠNG THÁI TỈNH TÁO (RESET)
            // ✅ THÊM LẠI LOGIC NGÁP
            openness > (LOW_DROWSY_THRESHOLD + 0.10) && mouthRatio < 0.5 -> {
                frameCounter = 0
                if (_alertLevel.value != AlertLevel.NONE) {
                    Log.i("DrowsyAnalyzer", "👁 (Hybrid) Tỉnh táo trở lại")
                }
                _alertLevel.value = AlertLevel.NONE
            }

            else -> {
                if (_alertLevel.value == AlertLevel.NONE) frameCounter = 0
            }
        }
    }

    // ✅ HÀM PHÂN TÍCH ẢNH TĨNH (Không thay đổi)
    fun analyzeBitmap(bitmap: Bitmap): Double {
        Log.i("FaceAnalyzer", "Bắt đầu phân tích ảnh tĩnh...")

        // 1. CHẠY ML KIT ĐỂ TÌM KHUÔN MẶT
        // (Bắt buộc phải chạy để cắt ảnh)
        val image = InputImage.fromBitmap(bitmap, 0)
        val tasks = faceDetector.process(image)

        // Phải chờ cho ML Kit chạy xong
        // Đây là cách chạy đồng bộ (blocking), chỉ dùng cho demo ảnh tĩnh
        try {
            val faces = com.google.android.gms.tasks.Tasks.await(tasks)
            if (faces.isNotEmpty()) {
                val face = faces.first()
                // Cắt ảnh
                val croppedBitmap = cropBitmap(bitmap, face.boundingBox)

                // Chạy TFLite
                val tensorImage = TensorImage.fromBitmap(croppedBitmap)
                val processedImage = imageProcessor.process(tensorImage)

                val localOutputBuffer = TensorBuffer.createFixedSize(
                    outputBuffer.shape,
                    outputBuffer.dataType
                )
                tfliteInterpreter.run(processedImage.buffer, localOutputBuffer.buffer)
                val probabilityOpen = localOutputBuffer.floatArray[0].toDouble()
                Log.i("FaceAnalyzer", "Phân tích ảnh tĩnh xong. Xác suất Mắt Mở: $probabilityOpen")
                return probabilityOpen
            } else {
                Log.w("FaceAnalyzer", "Ảnh tĩnh không tìm thấy khuôn mặt.")
                return 1.0 // Không thấy mặt, coi như tỉnh
            }
        } catch (e: Exception) {
            Log.e("FaceAnalyzer", "Lỗi khi phân tích ảnh tĩnh: ${e.message}", e)
            return 1.0 // Lỗi, coi như tỉnh
        }
    }
}