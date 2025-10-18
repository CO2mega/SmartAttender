package com.example.greetingcard.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.lang.reflect.Method
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Lightweight TFLite wrapper to compute face embeddings.
 * Expects a TFLite model in assets with input [1,H,W,3] float and output [1,embeddingDim].
 * This implementation uses NNAPI when available and numThreads=2 by default which targets
 * Snapdragon 845+ performance.
 */
object FaceEmbedder {
    // Interpreter will be loaded via reflection so the project can compile even before Gradle sync
    private var interpreter: Any? = null
    private var interpreterRunMethod: Method? = null
    private var interpreterOptionsClass: Class<*>? = null
    private var optionsSetNumThreads: Method? = null
    private var optionsSetUseNNAPI: Method? = null
    private var inputWidth = 112
    private var inputHeight = 112
    // runtime-detected input channels (default 3) and expected input bytes
    private var inputChannels = 3
    private var inputBatch = 1
    private var expectedInputBytes: Int? = null
    private var inputDataType: String? = null
    private var embeddingDim = 128
    private var initialized = false

    // 匹配阈值，已设为 0.75
    private const val MATCH_THRESHOLD = 0.75f

    fun initialize(context: Context, modelAssetName: String = "mobile_face_net.tflite", numThreads: Int = 2): Boolean {
        if (initialized) return true
        Log.d("FaceEmbedder", "initialize called, modelAssetName=$modelAssetName")
        try {
            // check if asset exists (helpful debug)
            try {
                val assets = context.assets.list("") ?: emptyArray()
                Log.d("FaceEmbedder", "assets root contains: ${assets.take(20).joinToString(",")}")
            } catch (e: Exception) {
                Log.w("FaceEmbedder", "Failed to list assets", e)
            }

            val mapped = loadModelFile(context, modelAssetName) ?: run {
                Log.w("FaceEmbedder", "Model file not found in assets: $modelAssetName")
                return false
            }
            // MappedByteBuffer doesn't have size(); use capacity() to get buffer size
            Log.d("FaceEmbedder", "Model file mapped: ${mapped.capacity()} bytes (mapped)")
            // Use reflection to construct org.tensorflow.lite.Interpreter and its Options
            try {
                val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
                Log.d("FaceEmbedder", "Interpreter class found: $interpreterClass")
                // Try to locate the nested Options class robustly: prefer declaredClasses, then fallback to binary name
                var optionsClass: Class<*>? = null
                try {
                    optionsClass = interpreterClass.declaredClasses.firstOrNull { it.simpleName == "Options" }
                } catch (_: Exception) { /* ignore */ }
                if (optionsClass == null) {
                    try {
                        optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
                        Log.d("FaceEmbedder", "Interpreter.Options class via Class.forName")
                    } catch (_: Exception) {
                        optionsClass = null
                    }
                }
                if (optionsClass == null) throw ClassNotFoundException("Interpreter.Options class not found")
                interpreterOptionsClass = optionsClass
                Log.d("FaceEmbedder", "Options class resolved: ${interpreterOptionsClass?.name}")
                val optionsCtor = interpreterOptionsClass!!.getConstructor()
                val options = optionsCtor.newInstance()
                // setNumThreads(int)
                optionsSetNumThreads = interpreterOptionsClass!!.getMethod("setNumThreads", Int::class.javaPrimitiveType)
                optionsSetNumThreads?.invoke(options, numThreads)
                // setUseNNAPI(boolean)
                optionsSetUseNNAPI = interpreterOptionsClass!!.getMethod("setUseNNAPI", Boolean::class.javaPrimitiveType)
                try { optionsSetUseNNAPI?.invoke(options, true) } catch (_: Exception) {}

                // Robust constructor selection: iterate all declared constructors and pick one
                // whose parameter types are assignable from our `mapped` buffer (or ByteBuffer)
                // and optionally from the Options class. This accommodates different TFLite
                // versions that declare constructors with ByteBuffer vs MappedByteBuffer, and
                // constructors that accept an Options instance.
                try {
                    var lastEx: Exception? = null
                    val ctors = interpreterClass.declaredConstructors
                    for (ctor in ctors) {
                        try {
                            val params = ctor.parameterTypes
                            when (params.size) {
                                1 -> {
                                    val p0 = params[0]
                                    if (p0.isAssignableFrom(mapped.javaClass)) {
                                        ctor.isAccessible = true
                                        interpreter = ctor.newInstance(mapped)
                                        break
                                    } else if (p0.isAssignableFrom(ByteBuffer::class.java)) {
                                        ctor.isAccessible = true
                                        interpreter = ctor.newInstance(mapped as ByteBuffer)
                                        break
                                    }
                                }
                                2 -> {
                                    val p0 = params[0]
                                    val p1 = params[1]
                                    val acceptsBuffer = p0.isAssignableFrom(mapped.javaClass) || p0.isAssignableFrom(ByteBuffer::class.java)
                                    val acceptsOptions = interpreterOptionsClass?.let { p1.isAssignableFrom(it) } ?: false
                                    if (acceptsBuffer && acceptsOptions) {
                                        val arg0 = if (p0.isAssignableFrom(mapped.javaClass)) mapped else mapped as ByteBuffer
                                        ctor.isAccessible = true
                                        interpreter = ctor.newInstance(arg0, options)
                                        break
                                    }
                                }
                                else -> {
                                    // ignore other arities
                                }
                            }
                        } catch (e: Exception) {
                            lastEx = e
                        }
                    }
                    if (interpreter == null) {
                        // Attempt a fallback: write the mapped buffer to a temp file and try File-based ctors
                        try {
                            val tmpFile = File.createTempFile("tflite_model", ".tflite", context.cacheDir)
                            try {
                                val dup = mapped.duplicate()
                                dup.position(0)
                                val bytes = ByteArray(dup.capacity())
                                dup.get(bytes)
                                FileOutputStream(tmpFile).use { it.write(bytes) }
                                // Try File-based constructors
                                try {
                                    val ctorFileOpt = interpreterClass.getConstructor(File::class.java, interpreterOptionsClass)
                                    ctorFileOpt.isAccessible = true
                                    interpreter = ctorFileOpt.newInstance(tmpFile, options)
                                } catch (_: NoSuchMethodException) { /* try next */ }
                                if (interpreter == null) {
                                    try {
                                        val ctorFile = interpreterClass.getConstructor(File::class.java)
                                        ctorFile.isAccessible = true
                                        interpreter = ctorFile.newInstance(tmpFile)
                                    } catch (_: NoSuchMethodException) { /* try next */ }
                                }
                                if (interpreter == null) {
                                    try {
                                        val ctorPathOpt = interpreterClass.getConstructor(String::class.java, interpreterOptionsClass)
                                        ctorPathOpt.isAccessible = true
                                        interpreter = ctorPathOpt.newInstance(tmpFile.absolutePath, options)
                                    } catch (_: NoSuchMethodException) { /* try next */ }
                                }
                                if (interpreter == null) {
                                    try {
                                        val ctorPath = interpreterClass.getConstructor(String::class.java)
                                        ctorPath.isAccessible = true
                                        interpreter = ctorPath.newInstance(tmpFile.absolutePath)
                                    } catch (_: NoSuchMethodException) { /* try next */ }
                                }
                            } catch (e: Exception) {
                                // writing or file-based ctor failed; fall through to logging
                                Log.w("FaceEmbedder", "File-based fallback failed: ${e.message}")
                            } finally {
                                // we intentionally keep the temp file for interpreter construction; if interpreter still null, delete it
                                if (interpreter == null) tmpFile.delete()
                            }
                        } catch (e: Exception) {
                            // ignore file create failures
                        }

                        // If still not created, log available ctors for debugging and rethrow
                        if (interpreter == null) {
                            try {
                                val ctorSigs = interpreterClass.declaredConstructors.joinToString(";") { c ->
                                    c.parameterTypes.joinToString(",") { it.simpleName }
                                }
                                Log.w("FaceEmbedder", "No suitable Interpreter constructor found. Available ctors: $ctorSigs")
                            } catch (_: Exception) { /* ignore logging failure */ }
                            throw lastEx ?: NoSuchMethodException("No suitable Interpreter constructor found")
                        }
                     }
                 } catch (e2: Exception) {
                     // none matched or constructor invocation failed
                     throw e2
                 }
                // cache run method
                interpreterRunMethod = interpreterClass.getMethod("run", Any::class.java, Any::class.java)

                // Inspect input tensor to determine expected buffer size / channels / data type
                try {
                    try {
                        val getInputTensor = interpreterClass.getMethod("getInputTensor", Int::class.javaPrimitiveType)
                        val tensor0 = getInputTensor.invoke(interpreter, 0)
                        val tensorClass = tensor0.javaClass
                        val shapeMethod = tensorClass.getMethod("shape")
                        val numBytesMethod = tensorClass.getMethod("numBytes")
                        val dataTypeMethod = tensorClass.getMethod("dataType")
                        val shapeAny = shapeMethod.invoke(tensor0)
                        val shape = when (shapeAny) {
                            is IntArray -> shapeAny
                            is Array<*> -> (shapeAny as Array<Int>).toIntArray()
                            else -> null
                        }
                        val numBytesAny = numBytesMethod.invoke(tensor0)
                        val numBytes = (numBytesAny as? Number)?.toInt()
                        val dt = dataTypeMethod.invoke(tensor0)
                        val dtName = dt?.toString()
                        if (shape != null && shape.size >= 3) {
                            // handle shapes like [B,H,W,C] or [H,W,C]
                            if (shape.size >= 4) {
                                inputBatch = shape[0]
                                inputHeight = shape[shape.size - 3]
                                inputWidth = shape[shape.size - 2]
                                inputChannels = shape[shape.size - 1]
                            } else {
                                inputBatch = 1
                                inputHeight = shape[shape.size - 3]
                                inputWidth = shape[shape.size - 2]
                                inputChannels = shape[shape.size - 1]
                            }
                        }
                        if (numBytes != null) expectedInputBytes = numBytes
                        inputDataType = dtName
                        Log.d("FaceEmbedder", "Detected input tensor: shape=${shape?.joinToString(",")}, dataType=$dtName, numBytes=$numBytes")
                    } catch (e: Exception) {
                        Log.w("FaceEmbedder", "Failed to inspect input tensor via reflection", e)
                    }
                    // Inspect output tensor to detect embeddingDim and batch
                    try {
                        val getOutputTensor = interpreterClass.getMethod("getOutputTensor", Int::class.javaPrimitiveType)
                        val outTensor0 = getOutputTensor.invoke(interpreter, 0)
                        val outTensorClass = outTensor0.javaClass
                        val outShapeMethod = outTensorClass.getMethod("shape")
                        val outShapeAny = outShapeMethod.invoke(outTensor0)
                        val outShape = when (outShapeAny) {
                            is IntArray -> outShapeAny
                            is Array<*> -> (outShapeAny as Array<Int>).toIntArray()
                            else -> null
                        }
                        if (outShape != null && outShape.size >= 2) {
                            // [batch, embeddingDim]
                            inputBatch = outShape[0]
                            embeddingDim = outShape[1]
                        } else if (outShape != null && outShape.size == 1) {
                            embeddingDim = outShape[0]
                        }
                        Log.d("FaceEmbedder", "Detected output tensor: shape=${outShape?.joinToString(",")}, embeddingDim=$embeddingDim, batch=$inputBatch")
                    } catch (e: Exception) {
                        Log.w("FaceEmbedder", "Failed to inspect output tensor via reflection", e)
                    }
                } catch (_: Exception) { /* ignore */ }

                // attempt a dummy run to validate (use detected sizes if available)
                try {
                    val dummyBuf = expectedInputBytes?.let { ByteBuffer.allocateDirect(it) } ?: ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * inputChannels)
                    dummyBuf.order(ByteOrder.nativeOrder())
                    val out = Array(1) { FloatArray(embeddingDim) }
                    interpreterRunMethod?.invoke(interpreter, dummyBuf, out)
                    Log.d("FaceEmbedder", "Dummy run succeeded")
                } catch (e: Exception) {
                    Log.w("FaceEmbedder", "Dummy run failed (non-fatal) - interpreter may still work", e)
                }
            } catch (e: ClassNotFoundException) {
                Log.w("FaceEmbedder", "TFLite classes not found on classpath: ${e.message}")
                interpreter = null
                initialized = false
                return false
            }
            // if succeeded, keep initialized
            initialized = true
            Log.i("FaceEmbedder", "Interpreter initialized")
            return true
        } catch (e: Exception) {
            Log.e("FaceEmbedder", "Failed to initialize interpreter", e)
            interpreter = null
            initialized = false
            return false
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer? {
        return try {
            val afd = context.assets.openFd(assetName)
            val inputStream = FileInputStream(afd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: IOException) {
            Log.w("FaceEmbedder", "Could not load model from assets: $assetName", e)
            null
        }
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val input = convertBitmapToByteBuffer(resized)
            val output = Array(inputBatch) { FloatArray(embeddingDim) }
            // invoke run via reflection
            interpreterRunMethod?.invoke(interp, input, output)
            val emb = l2Normalize(output[0]) // 只返回第一个 embedding
            return emb
        } catch (e: Exception) {
            Log.e("FaceEmbedder", "Failed to compute embedding", e)
            return null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Build a ByteBuffer sized and formatted to match the runtime-detected input tensor.
        // Supported data types: FLOAT32, UINT8. If detection failed, fall back to FLOAT32 with 3 channels.
        val detectedChannels = inputChannels
        val dtype = inputDataType ?: "FLOAT32"
        val bytesPerElement = when (dtype) {
            "FLOAT32" -> 4
            "UINT8" -> 1
            "FLOAT16" -> 2
            else -> 4
        }
        val expectedBytes = expectedInputBytes ?: (bytesPerElement * inputWidth * inputHeight * detectedChannels)
        val byteBuffer = ByteBuffer.allocateDirect(expectedBytes)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // number of elements (per-channel) expected for one image
        val expectedPerPixel = detectedChannels

        when (dtype) {
            "UINT8" -> {
                // for UINT8, put raw byte values [0..255]
                for (bIndex in 0 until inputBatch) {
                    for (i in 0 until inputWidth * inputHeight) {
                        val v = intValues[i]
                        val r = ((v shr 16) and 0xFF).toByte()
                        val g = ((v shr 8) and 0xFF).toByte()
                        val b = (v and 0xFF).toByte()
                        // fill channels: [R,G,B,0,0,...] or repeat as needed
                        byteBuffer.put(r)
                        if (expectedPerPixel >= 2) byteBuffer.put(g)
                        if (expectedPerPixel >= 3) byteBuffer.put(b)
                        for (c in 3 until expectedPerPixel) byteBuffer.put(0)
                    }
                }
            }
            "FLOAT16" -> {
                // Best-effort: convert float32 normalized to half (not highly optimized)
                // If FLOAT16 isn't supported well here, fall back to zeros to avoid crashes.
                try {
                    val shortBuf = java.nio.ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (bIndex in 0 until inputBatch) {
                        for (i in 0 until inputWidth * inputHeight) {
                            val v = intValues[i]
                            val r = (((v shr 16) and 0xFF) / 127.5f) - 1f
                            val g = (((v shr 8) and 0xFF) / 127.5f) - 1f
                            val b = ((v and 0xFF) / 127.5f) - 1f
                            val vals = floatArrayOf(r, g, b)
                            for (c in 0 until expectedPerPixel) {
                                val f = if (c < 3) vals[c] else 0f
                                // convert float to IEEE 754 half (approx)
                                val shortVal = floatToHalf(f)
                                shortBuf.put(shortVal)
                            }
                        }
                    }
                     // copy shorts into byteBuffer
                     byteBuffer.rewind()
                     val shortArr = ShortArray(shortBuf.position())
                     shortBuf.rewind()
                     shortBuf.get(shortArr)
                     val tmp = ByteBuffer.allocate(shortArr.size * 2).order(ByteOrder.nativeOrder())
                     for (s in shortArr) tmp.putShort(s)
                     tmp.rewind()
                     byteBuffer.put(tmp)
                } catch (e: Exception) {
                    Log.w("FaceEmbedder", "FLOAT16 conversion failed, filling zeros", e)
                    while (byteBuffer.hasRemaining()) byteBuffer.put(0)
                }
            }
            else -> {
                // default: FLOAT32
                for (bIndex in 0 until inputBatch) {
                    for (i in 0 until inputWidth * inputHeight) {
                        val v = intValues[i]
                        val r = ((v shr 16) and 0xFF).toFloat()
                        val g = ((v shr 8) and 0xFF).toFloat()
                        val b = (v and 0xFF).toFloat()
                        // normalize to [-1,1]
                        val rf = (r / 127.5f) - 1f
                        val gf = (g / 127.5f) - 1f
                        val bf = (b / 127.5f) - 1f
                        // fill channels
                        byteBuffer.putFloat(rf)
                        if (expectedPerPixel >= 2) byteBuffer.putFloat(gf)
                        if (expectedPerPixel >= 3) byteBuffer.putFloat(bf)
                        for (c in 3 until expectedPerPixel) byteBuffer.putFloat(0f)
                    }
                }
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    // helper: approximate float->half conversion (IEEE 754) returning Short
    private fun floatToHalf(f: Float): Short {
        // simple (not perfectly accurate) conversion using java.lang.Float methods
        val intBits = java.lang.Float.floatToIntBits(f)
        val sign = (intBits ushr 16) and 0x8000
        var valBits = (intBits and 0x7fffffff)
        if (valBits > 0x47ffefff) {
            // overflow to infinity
            return (sign or 0x7c00).toShort()
        }
        if (valBits < 0x38800000) {
            // too small becomes zero
            return sign.toShort()
        }
        val exp = ((valBits ushr 23) - 127 + 15) and 0xff
        val mant = (valBits ushr 13) and 0x3ff
        return (sign or (exp shl 10) or mant).toShort()
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        val norm = sqrt(sum.toDouble()).toFloat().coerceAtLeast(1e-10f)
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    fun floatArrayToBase64(arr: FloatArray): String {
        val bb = ByteBuffer.allocate(4 * arr.size).order(ByteOrder.LITTLE_ENDIAN)
        for (f in arr) bb.putFloat(f)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    fun base64ToFloatArray(base64: String): FloatArray? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val fa = FloatArray(bytes.size / 4)
            bb.asFloatBuffer().get(fa)
            fa
        } catch (e: Exception) {
            Log.e("FaceEmbedder", "Failed to decode embedding", e)
            null
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na.toDouble()).toFloat() * sqrt(nb.toDouble()).toFloat()
        return if (denom == 0f) 0f else dot / denom
    }

    fun isMatch(a: FloatArray, b: FloatArray, threshold: Float = MATCH_THRESHOLD): Boolean {
        return cosineSimilarity(a, b) > threshold
    }
    fun getDefaultMatchThreshold(): Float = MATCH_THRESHOLD
}
