package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.ReceiveData
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Metadata for E2 multihead model loaded from tcn_multihead_metadata.json
 */
data class TcnMultiheadMetadata(
    val version: String,
    @SerializedName("model_type") val modelType: String,
    val description: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("input_shape") val inputShape: List<Int>,
    @SerializedName("seq_len") val seqLen: Int,
    @SerializedName("n_channels") val nChannels: Int,
    @SerializedName("feature_names") val featureNames: List<String>,
    @SerializedName("prediction_horizons") val predictionHorizons: List<Int>,
    @SerializedName("output_names") val outputNames: List<String>,
    @SerializedName("bin_config") val binConfig: Map<String, BinConfig>,
    @SerializedName("trend_thresholds") val trendThresholds: TrendThresholds,
    val temperatures: Map<String, Float>? = null
)

/**
 * Service for E2 Multi-head TCN model predictions using TensorFlow Lite.
 * 
 * Unlike TcnPredictionService which loads 6 separate models, this service loads
 * a single multi-output model that predicts all horizons in one forward pass.
 */
object TcnMultiheadPredictionService {
    private const val LOG_ID = "GDH.TcnMultihead"
    private const val MODELS_DIR = "tcn_models"
    private const val MODEL_FILE = "tcn_multihead.tflite"
    private const val METADATA_FILE = "tcn_multihead_metadata.json"
    
    // Model configuration
    private const val SEQ_LEN = 8  // 40 minutes / 5 min per sample
    private const val N_CHANNELS = 2  // relative glucose + velocity
    
    private val PREDICTION_HORIZONS = listOf(5, 10, 15, 20, 25, 30)
    
    // Map from output size (n_bins) to horizon for identifying TFLite outputs
    private val BINS_TO_HORIZON = mapOf(
        131 to 5,
        171 to 10,
        221 to 15,
        271 to 20,
        311 to 25,
        341 to 30
    )
    
    private var interpreter: Interpreter? = null
    private var metadata: TcnMultiheadMetadata? = null
    private var initialized = false
    private var modelAvailable = false
    
    // Map from horizon to output tensor index
    private var outputIndexMap: MutableMap<Int, Int> = mutableMapOf()
    
    // Cache for trend probabilities
    private var cachedProbabilities: MutableMap<Int, TrendProbabilities> = mutableMapOf()
    private var cachedTime: Long = 0L
    
    // Cache for raw PMF to ensure consistency across getTrendProbabilities, getZoneProbabilities, and getQuantilePrediction
    private var cachedPmf: MutableMap<Int, FloatArray> = mutableMapOf()
    
    /**
     * Initialize the E2 multihead prediction service.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing TcnMultiheadPredictionService")
        
        try {
            // Load metadata
            loadMetadata(context)
            
            // Load model
            loadModel(context)
            
            initialized = true
            modelAvailable = interpreter != null
            
            Log.i(LOG_ID, "Initialization complete. Model available: $modelAvailable")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to initialize: ${e.message}", e)
            initialized = true
            modelAvailable = false
        }
    }
    
    private fun loadMetadata(context: Context) {
        try {
            val metadataJson = context.assets.open("$MODELS_DIR/$METADATA_FILE")
                .bufferedReader().use { it.readText() }
            metadata = Gson().fromJson(metadataJson, TcnMultiheadMetadata::class.java)
            Log.i(LOG_ID, "Loaded metadata: version=${metadata?.version}, model_type=${metadata?.modelType}")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to load metadata: ${e.message}", e)
        }
    }
    
    private fun loadModel(context: Context) {
        try {
            val modelBuffer = loadModelFile(context, "$MODELS_DIR/$MODEL_FILE")
            interpreter = Interpreter(modelBuffer)
            
            // Build output index map by matching output sizes to horizons
            buildOutputIndexMap()
            
            Log.d(LOG_ID, "Loaded model: $MODEL_FILE")
        } catch (e: Exception) {
            Log.w(LOG_ID, "Model not found: $MODEL_FILE - ${e.message}")
        }
    }
    
    /**
     * Build a map from horizon to TFLite output tensor index.
     * TFLite doesn't preserve output names, so we identify outputs by their size.
     */
    private fun buildOutputIndexMap() {
        val interp = interpreter ?: return
        
        val outputCount = interp.outputTensorCount
        Log.d(LOG_ID, "Model has $outputCount outputs")
        
        for (i in 0 until outputCount) {
            val shape = interp.getOutputTensor(i).shape()
            val nBins = if (shape.size > 1) shape[1] else shape[0]
            
            val horizon = BINS_TO_HORIZON[nBins]
            if (horizon != null) {
                outputIndexMap[horizon] = i
                Log.d(LOG_ID, "Output $i (${nBins} bins) -> horizon $horizon")
            } else {
                Log.w(LOG_ID, "Unknown output $i with $nBins bins")
            }
        }
        
        Log.i(LOG_ID, "Output index map: $outputIndexMap")
    }
    
    private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(assetPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Check if E2 multihead model is available.
     */
    fun isAvailable(): Boolean = initialized && modelAvailable
    
    /**
     * Get trend probabilities for a specific horizon using E2 model.
     */
    fun getTrendProbabilities(context: Context, horizon: Int): TrendProbabilities? {
        init(context)
        
        if (!modelAvailable) {
            Log.w(LOG_ID, "E2 multihead model not available")
            return null
        }
        
        if (horizon !in PREDICTION_HORIZONS) {
            Log.w(LOG_ID, "Invalid horizon: $horizon")
            return null
        }
        
        // Check cache
        val currentTime = ReceiveData.time
        if (currentTime != cachedTime) {
            Log.d(LOG_ID, "Time changed, invalidating cache")
            cachedProbabilities.clear()
            cachedPmf.clear()
            cachedTime = currentTime
        }
        
        if (currentTime > 0 && cachedProbabilities.containsKey(horizon)) {
            return cachedProbabilities[horizon]
        }
        
        try {
            // Use cached PMF if available, otherwise run inference
            val pmf = cachedPmf[horizon] ?: run {
                val input = buildInputTensor() ?: return null
                val result = runInference(horizon, input) ?: return null
                cachedPmf[horizon] = result  // Cache the PMF for getZoneProbabilities/getQuantilePrediction
                result
            }
            
            // Convert PMF to trend probabilities
            val trendProbs = computeTrendProbabilities(pmf, horizon)
            
            // Cache result
            cachedProbabilities[horizon] = trendProbs
            
            return trendProbs
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error getting trend probabilities: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get all trend probabilities.
     */
    fun getAllTrendProbabilities(context: Context): Map<Int, TrendProbabilities> {
        init(context)
        
        val results = mutableMapOf<Int, TrendProbabilities>()
        for (horizon in PREDICTION_HORIZONS) {
            getTrendProbabilities(context, horizon)?.let {
                results[horizon] = it
            }
        }
        return results
    }
    
    /**
     * Get zone probabilities for a specific horizon using E2 model.
     * 
     * Zone thresholds (standard):
     * - Very Low: < 54 mg/dL
     * - Low: 54-69 mg/dL
     * - In Range: 70-180 mg/dL
     * - High: 181-250 mg/dL
     * - Very High: > 250 mg/dL
     */
    fun getZoneProbabilities(context: Context, horizon: Int, currentGlucose: Int): ZoneProbabilities? {
        init(context)
        
        if (!modelAvailable || horizon !in PREDICTION_HORIZONS) {
            return null
        }
        
        if (currentGlucose <= 0) {
            return null
        }
        
        // Check cache - invalidate if time changed
        val currentTime = ReceiveData.time
        if (currentTime != cachedTime) {
            Log.d(LOG_ID, "Time changed in getZoneProbabilities, invalidating cache")
            cachedProbabilities.clear()
            cachedPmf.clear()
            cachedTime = currentTime
        }
        
        try {
            // Use cached PMF if available, otherwise run inference
            val pmf = cachedPmf[horizon] ?: run {
                val input = buildInputTensor() ?: return null
                val result = runInference(horizon, input) ?: return null
                cachedPmf[horizon] = result  // Cache for other methods
                result
            }
            
            // Get bin centers from metadata
            val binConfig = metadata?.binConfig?.get(horizon.toString())
            val binCenters = binConfig?.binCenters
            val nBins = pmf.size
            val useBinCenters = binCenters != null && binCenters.size == nBins
            
            // Fallback parameters if bin centers not available
            val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
            val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
            val step = if (nBins > 1) (maxDelta - minDelta) / (nBins - 1) else 1f
            
            // Initialize zone probabilities
            var probVeryLow = 0f
            var probLow = 0f
            var probInRange = 0f
            var probHigh = 0f
            var probVeryHigh = 0f
            
            // Sum probabilities for each zone
            for (i in pmf.indices) {
                val delta = if (useBinCenters) {
                    binCenters!![i].toFloat()
                } else {
                    minDelta + i * step
                }
                
                val predictedGlucose = currentGlucose + delta
                
                when {
                    predictedGlucose < 54 -> probVeryLow += pmf[i]
                    predictedGlucose < 70 -> probLow += pmf[i]
                    predictedGlucose <= 180 -> probInRange += pmf[i]
                    predictedGlucose <= 250 -> probHigh += pmf[i]
                    else -> probVeryHigh += pmf[i]
                }
            }
            
            val zoneProbs = mapOf(
                GlucoseZone.VERY_LOW to probVeryLow,
                GlucoseZone.LOW to probLow,
                GlucoseZone.IN_RANGE to probInRange,
                GlucoseZone.HIGH to probHigh,
                GlucoseZone.VERY_HIGH to probVeryHigh
            )
            
            Log.d(LOG_ID, "E2 Zone probabilities for ${horizon}min (glucose=$currentGlucose): " +
                    "VeryLow=${String.format("%.1f%%", probVeryLow * 100)}, " +
                    "Low=${String.format("%.1f%%", probLow * 100)}, " +
                    "InRange=${String.format("%.1f%%", probInRange * 100)}, " +
                    "High=${String.format("%.1f%%", probHigh * 100)}, " +
                    "VeryHigh=${String.format("%.1f%%", probVeryHigh * 100)}")
            
            return ZoneProbabilities(horizon, currentGlucose, zoneProbs)
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error computing zone probabilities: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Build input tensor from recent glucose values.
     * Same logic as TcnPredictionService.
     */
    private fun buildInputTensor(): FloatArray? {
        if (!dbAccess.active) {
            Log.w(LOG_ID, "Database not active")
            return null
        }
        
        val currentTime = ReceiveData.time
        val currentValue = ReceiveData.rawValue.toFloat()
        
        if (currentTime == 0L || currentValue <= 0) {
            Log.w(LOG_ID, "No current glucose data")
            return null
        }
        
        // Get historical values
        val minTime = currentTime - (60 * 60 * 1000L)
        val historicalValues = dbAccess.getGlucoseValuesInRange(minTime, currentTime)
            .sortedBy { it.timestamp }
        
        if (historicalValues.size < SEQ_LEN + 1) {
            Log.w(LOG_ID, "Insufficient data: ${historicalValues.size} values, need ${SEQ_LEN + 1}")
            return null
        }
        
        val recentValues = historicalValues.takeLast(SEQ_LEN + 1)
        val pastValues = FloatArray(SEQ_LEN + 1)
        
        for (i in 0..SEQ_LEN) {
            pastValues[i] = recentValues[i].value.toFloat()
        }
        
        // Build input tensor [SEQ_LEN * N_CHANNELS]
        val input = FloatArray(SEQ_LEN * N_CHANNELS)
        
        for (i in 0 until SEQ_LEN) {
            // Channel 0: normalized relative glucose
            val relativeGlucose = (pastValues[i + 1] - currentValue) / 50f
            input[i * N_CHANNELS] = relativeGlucose
            
            // Channel 1: velocity
            val velocity = (pastValues[i + 1] - pastValues[i]) / 10f
            input[i * N_CHANNELS + 1] = velocity
        }
        
        return input
    }
    
    /**
     * Run TFLite inference for all horizons and return PMF for specific horizon.
     */
    private fun runInference(horizon: Int, input: FloatArray): FloatArray? {
        val interp = interpreter ?: return null
        val outputIndex = outputIndexMap[horizon] ?: return null
        
        try {
            // Prepare input [1, 8, 2]
            val inputArray = Array(1) { Array(SEQ_LEN) { FloatArray(N_CHANNELS) } }
            for (i in 0 until SEQ_LEN) {
                inputArray[0][i][0] = input[i * N_CHANNELS]
                inputArray[0][i][1] = input[i * N_CHANNELS + 1]
            }
            
            // Prepare outputs for all horizons
            val outputs = mutableMapOf<Int, Any>()
            for (h in PREDICTION_HORIZONS) {
                val idx = outputIndexMap[h] ?: continue
                val binConfig = metadata?.binConfig?.get(h.toString())
                val nBins = binConfig?.nBins ?: BINS_TO_HORIZON.entries.find { it.value == h }?.key ?: continue
                outputs[idx] = Array(1) { FloatArray(nBins) }
            }
            
            // Run inference
            interp.runForMultipleInputsOutputs(arrayOf(inputArray), outputs)
            
            // Extract PMF for requested horizon
            @Suppress("UNCHECKED_CAST")
            val outputArray = outputs[outputIndex] as? Array<FloatArray> ?: return null
            val logits = outputArray[0]
            
            // Apply temperature scaling
            val temperature = metadata?.temperatures?.get(horizon.toString()) ?: 1.0f
            Log.d(LOG_ID, "Applying temperature T=$temperature for ${horizon}min")
            
            return softmax(logits, temperature)
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Inference error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Apply softmax with temperature scaling.
     */
    private fun softmax(logits: FloatArray, temperature: Float = 1.0f): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expValues = logits.map { exp(((it - maxLogit) / temperature).toDouble()).toFloat() }
        val sumExp = expValues.sum()
        return expValues.map { it / sumExp }.toFloatArray()
    }
    
    /**
     * Compute trend probabilities from PMF.
     */
    private fun computeTrendProbabilities(pmf: FloatArray, horizon: Int): TrendProbabilities {
        val doubleDownThreshold = -3f * horizon
        val downThreshold = -2f * horizon
        val slightlyDownThreshold = -1f * horizon
        val slightlyUpThreshold = 1f * horizon
        val upThreshold = 2f * horizon
        val doubleUpThreshold = 3f * horizon
        
        val binConfig = metadata?.binConfig?.get(horizon.toString())
        val binCenters = binConfig?.binCenters
        
        val nBins = pmf.size
        val useBinCenters = binCenters != null && binCenters.size == nBins
        
        val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
        val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
        val step = if (nBins > 1) (maxDelta - minDelta) / (nBins - 1) else 1f
        
        var probDoubleDown = 0f
        var probDown = 0f
        var probSlightlyDown = 0f
        var probFlat = 0f
        var probSlightlyUp = 0f
        var probUp = 0f
        var probDoubleUp = 0f
        
        for (i in pmf.indices) {
            val delta = if (useBinCenters) {
                binCenters!![i].toFloat()
            } else {
                minDelta + i * step
            }
            val prob = pmf[i]
            
            when {
                delta < doubleDownThreshold -> probDoubleDown += prob
                delta < downThreshold -> probDown += prob
                delta < slightlyDownThreshold -> probSlightlyDown += prob
                delta <= slightlyUpThreshold -> probFlat += prob
                delta <= upThreshold -> probSlightlyUp += prob
                delta <= doubleUpThreshold -> probUp += prob
                else -> probDoubleUp += prob
            }
        }
        
        return TrendProbabilities(
            horizon = horizon,
            probabilities = mapOf(
                TrendCategory.DOUBLE_DOWN to probDoubleDown,
                TrendCategory.DOWN to probDown,
                TrendCategory.SLIGHTLY_DOWN to probSlightlyDown,
                TrendCategory.FLAT to probFlat,
                TrendCategory.SLIGHTLY_UP to probSlightlyUp,
                TrendCategory.UP to probUp,
                TrendCategory.DOUBLE_UP to probDoubleUp
            )
        )
    }
    
    /**
     * Get quantile prediction for a specific horizon.
     */
    fun getQuantilePrediction(context: Context, horizon: Int): TcnPredictionService.QuantilePrediction? {
        init(context)
        
        if (!modelAvailable || horizon !in PREDICTION_HORIZONS) {
            return null
        }
        
        val currentGlucose = ReceiveData.rawValue.toFloat()
        if (currentGlucose <= 0) {
            return null
        }
        
        // Check cache - invalidate if time changed
        val currentTime = ReceiveData.time
        if (currentTime != cachedTime) {
            Log.d(LOG_ID, "Time changed in getQuantilePrediction, invalidating cache")
            cachedProbabilities.clear()
            cachedPmf.clear()
            cachedTime = currentTime
        }
        
        try {
            // Use cached PMF if available, otherwise run inference
            val pmf = cachedPmf[horizon] ?: run {
                val input = buildInputTensor() ?: return null
                val result = runInference(horizon, input) ?: return null
                cachedPmf[horizon] = result  // Cache for other methods
                result
            }
            
            val binConfig = metadata?.binConfig?.get(horizon.toString())
            val binCenters = binConfig?.binCenters
            
            if (binCenters == null || binCenters.size != pmf.size) {
                Log.w(LOG_ID, "Bin centers mismatch, using fallback")
                val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
                val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
                return computeQuantilesWithRange(pmf, currentGlucose, horizon, minDelta, maxDelta)
            }
            
            // Compute quantiles from CDF
            var cumSum = 0f
            var q10Delta: Float? = null
            var q50Delta: Float? = null
            var q90Delta: Float? = null
            
            for (i in pmf.indices) {
                cumSum += pmf[i]
                val delta = binCenters[i].toFloat()
                
                if (q10Delta == null && cumSum >= 0.10f) q10Delta = delta
                if (q50Delta == null && cumSum >= 0.50f) q50Delta = delta
                if (q90Delta == null && cumSum >= 0.90f) q90Delta = delta
                
                if (q10Delta != null && q50Delta != null && q90Delta != null) break
            }
            
            q10Delta = q10Delta ?: binCenters.first().toFloat()
            q50Delta = q50Delta ?: 0f
            q90Delta = q90Delta ?: binCenters.last().toFloat()
            
            Log.d(LOG_ID, "E2 Quantiles for ${horizon}min: Q10=$q10Delta, Q50=$q50Delta, Q90=$q90Delta")
            
            return TcnPredictionService.QuantilePrediction(
                horizon = horizon,
                q10Delta = q10Delta,
                q50Delta = q50Delta,
                q90Delta = q90Delta,
                q10 = currentGlucose + q10Delta,
                q50 = currentGlucose + q50Delta,
                q90 = currentGlucose + q90Delta
            )
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error computing quantiles: ${e.message}", e)
            return null
        }
    }
    
    private fun computeQuantilesWithRange(
        pmf: FloatArray,
        currentGlucose: Float,
        horizon: Int,
        minDelta: Float,
        maxDelta: Float
    ): TcnPredictionService.QuantilePrediction {
        val nBins = pmf.size
        val step = (maxDelta - minDelta) / (nBins - 1)
        
        var cumSum = 0f
        var q10Delta: Float? = null
        var q50Delta: Float? = null
        var q90Delta: Float? = null
        
        for (i in pmf.indices) {
            cumSum += pmf[i]
            val delta = minDelta + i * step
            
            if (q10Delta == null && cumSum >= 0.10f) q10Delta = delta
            if (q50Delta == null && cumSum >= 0.50f) q50Delta = delta
            if (q90Delta == null && cumSum >= 0.90f) q90Delta = delta
            
            if (q10Delta != null && q50Delta != null && q90Delta != null) break
        }
        
        q10Delta = q10Delta ?: minDelta
        q50Delta = q50Delta ?: 0f
        q90Delta = q90Delta ?: maxDelta
        
        return TcnPredictionService.QuantilePrediction(
            horizon = horizon,
            q10Delta = q10Delta,
            q50Delta = q50Delta,
            q90Delta = q90Delta,
            q10 = currentGlucose + q10Delta,
            q50 = currentGlucose + q50Delta,
            q90 = currentGlucose + q90Delta
        )
    }
    
    /**
     * Get quantile predictions for all horizons.
     */
    fun getAllQuantilePredictions(context: Context): List<TcnPredictionService.QuantilePrediction> {
        init(context)
        
        val results = mutableListOf<TcnPredictionService.QuantilePrediction>()
        for (horizon in PREDICTION_HORIZONS) {
            getQuantilePrediction(context, horizon)?.let {
                results.add(it)
            }
        }
        return results
    }
    
    /**
     * Clear cached probabilities and PMF.
     */
    fun clearCache() {
        cachedProbabilities.clear()
        cachedPmf.clear()
        cachedTime = 0L
    }
    
    /**
     * Close interpreter and release resources.
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            initialized = false
            modelAvailable = false
            clearCache()
            Log.i(LOG_ID, "TcnMultiheadPredictionService closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing: ${e.message}", e)
        }
    }
    
    /**
     * Get available prediction horizons.
     */
    fun getAvailableHorizons(): List<Int> = if (modelAvailable) PREDICTION_HORIZONS else emptyList()
}


