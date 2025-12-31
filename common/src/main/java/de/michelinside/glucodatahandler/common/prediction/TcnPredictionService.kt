package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import de.michelinside.glucodatahandler.common.database.GlucoseValue
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.ReceiveData
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Trend category with associated probability.
 */
enum class TrendCategory(val symbol: String, val label: String) {
    DOUBLE_DOWN("⬇⬇", "Falling Rapidly"),
    DOWN("↓", "Falling"),
    SLIGHTLY_DOWN("↘", "Falling Slowly"),
    FLAT("→", "Steady"),
    SLIGHTLY_UP("↗", "Rising Slowly"),
    UP("↑", "Rising"),
    DOUBLE_UP("⬆⬆", "Rising Rapidly")
}

/**
 * Trend probabilities for a specific horizon.
 */
data class TrendProbabilities(
    val horizon: Int,
    val probabilities: Map<TrendCategory, Float>
) {
    fun getMostLikely(): TrendCategory {
        return probabilities.maxByOrNull { it.value }?.key ?: TrendCategory.FLAT
    }
    
    fun getProbability(category: TrendCategory): Float {
        return probabilities[category] ?: 0f
    }
}

/**
 * TCN metadata loaded from tcn_metadata.json
 */
data class TcnMetadata(
    val version: String,
    @SerializedName("model_type") val modelType: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("input_shape") val inputShape: List<Int>,
    @SerializedName("seq_len") val seqLen: Int,
    @SerializedName("n_channels") val nChannels: Int,
    @SerializedName("feature_names") val featureNames: List<String>,
    @SerializedName("prediction_horizons") val predictionHorizons: List<Int>,
    @SerializedName("bin_config") val binConfig: Map<String, BinConfig>,
    @SerializedName("trend_thresholds") val trendThresholds: TrendThresholds,
    val temperatures: Map<String, Float>? = null  // Per-horizon temperature scaling factors
)

data class BinConfig(
    val min: Int,
    val max: Int,
    @SerializedName("n_bins") val nBins: Int,
    @SerializedName("bin_centers") val binCenters: List<Int>
)

data class TrendThresholds(
    @SerializedName("double_down") val doubleDown: Float,
    val down: Float,
    @SerializedName("slightly_down") val slightlyDown: Float,
    @SerializedName("flat_low") val flatLow: Float,
    @SerializedName("flat_high") val flatHigh: Float,
    @SerializedName("slightly_up") val slightlyUp: Float,
    val up: Float,
    @SerializedName("double_up") val doubleUp: Float
)

/**
 * Service for TCN distribution model predictions using TensorFlow Lite.
 * 
 * Runs TCN models that output a probability mass function (PMF) over
 * glucose delta bins, then aggregates to compute trend category probabilities.
 */
object TcnPredictionService {
    private const val LOG_ID = "GDH.TcnPrediction"
    private const val MODELS_DIR = "tcn_models"
    private const val METADATA_FILE = "tcn_metadata.json"
    
    // Model configuration
    private const val SEQ_LEN = 8  // 40 minutes / 5 min per sample
    private const val N_CHANNELS = 2  // relative glucose + velocity
    private const val SAMPLE_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
    
    private val PREDICTION_HORIZONS = listOf(5, 10, 15, 20, 25, 30)
    
    private var interpreters: MutableMap<Int, Interpreter> = mutableMapOf()
    private var metadata: TcnMetadata? = null
    private var initialized = false
    private var modelsAvailable = false
    
    // Cache
    private var cachedProbabilities: MutableMap<Int, TrendProbabilities> = mutableMapOf()
    private var cachedTime: Long = 0L
    
    /**
     * Initialize the TCN prediction service by loading models from assets.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing TcnPredictionService")
        
        try {
            // Load metadata
            loadMetadata(context)
            
            // Load all models
            loadModels(context)
            
            initialized = true
            modelsAvailable = interpreters.isNotEmpty()
            
            Log.i(LOG_ID, "Initialization complete. Models available: $modelsAvailable (${interpreters.size} loaded)")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to initialize: ${e.message}", e)
            initialized = true
            modelsAvailable = false
        }
    }
    
    private fun loadMetadata(context: Context) {
        try {
            val metadataJson = context.assets.open("$MODELS_DIR/$METADATA_FILE")
                .bufferedReader().use { it.readText() }
            metadata = Gson().fromJson(metadataJson, TcnMetadata::class.java)
            Log.i(LOG_ID, "Loaded metadata: version=${metadata?.version}")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to load metadata: ${e.message}", e)
        }
    }
    
    private fun loadModels(context: Context) {
        for (horizon in PREDICTION_HORIZONS) {
            val modelName = "tcn_${horizon}min.tflite"
            try {
                val modelBuffer = loadModelFile(context, "$MODELS_DIR/$modelName")
                val interpreter = Interpreter(modelBuffer)
                interpreters[horizon] = interpreter
                Log.d(LOG_ID, "Loaded model: $modelName")
            } catch (e: Exception) {
                Log.w(LOG_ID, "Model not found: $modelName - ${e.message}")
            }
        }
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
     * Check if TCN prediction service is available.
     */
    fun isAvailable(): Boolean = initialized && modelsAvailable
    
    /**
     * Get sample trend probabilities for testing the chart display.
     * Returns a typical "flat" prediction for debugging.
     */
    fun getTestProbabilities(horizon: Int): TrendProbabilities {
        return TrendProbabilities(
            horizon = horizon,
            probabilities = mapOf(
                TrendCategory.DOUBLE_DOWN to 0.01f,
                TrendCategory.DOWN to 0.02f,
                TrendCategory.SLIGHTLY_DOWN to 0.05f,
                TrendCategory.FLAT to 0.84f,
                TrendCategory.SLIGHTLY_UP to 0.05f,
                TrendCategory.UP to 0.02f,
                TrendCategory.DOUBLE_UP to 0.01f
            )
        )
    }
    
    /**
     * Get trend probabilities for a specific horizon.
     */
    fun getTrendProbabilities(context: Context, horizon: Int): TrendProbabilities? {
        init(context)
        
        if (!modelsAvailable) {
            Log.w(LOG_ID, "TCN models not available (interpreters: ${interpreters.size})")
            return null
        }
        
        if (horizon !in PREDICTION_HORIZONS) {
            Log.w(LOG_ID, "Invalid horizon: $horizon (valid: $PREDICTION_HORIZONS)")
            return null
        }
        
        // Check cache (only use cache if we have valid time data)
        val currentTime = ReceiveData.time
        
        // Invalidate entire cache when time changes to prevent stale data
        if (currentTime != cachedTime) {
            Log.d(LOG_ID, "Time changed ($cachedTime -> $currentTime), invalidating trend cache")
            cachedProbabilities.clear()
            cachedTime = currentTime
        }
        
        if (currentTime > 0 && cachedProbabilities.containsKey(horizon)) {
            Log.d(LOG_ID, "Returning cached result for horizon $horizon")
            return cachedProbabilities[horizon]
        }
        
        try {
            // Log current state
            Log.d(LOG_ID, "getTrendProbabilities: time=${ReceiveData.time}, glucose=${ReceiveData.rawValue}, dbActive=${dbAccess.active}")
            
            // Build input features
            val input = buildInputTensor()
            if (input == null) {
                Log.w(LOG_ID, "Could not build input tensor - check earlier logs for reason")
                return null
            }
            
            Log.d(LOG_ID, "Input tensor built: size=${input.size}, values=[${input.take(4).joinToString { "%.3f".format(it) }}...]")
            
            // Run inference
            val pmf = runInference(horizon, input)
            if (pmf == null) {
                Log.w(LOG_ID, "Inference failed for horizon $horizon")
                return null
            }
            
            val pmfSum = pmf.sum()
            val pmfMax = pmf.maxOrNull() ?: 0f
            val pmfPeakIdx = pmf.indices.maxByOrNull { pmf[it] } ?: -1
            Log.d(LOG_ID, "PMF received: size=${pmf.size}, sum=$pmfSum, max=$pmfMax at idx=$pmfPeakIdx")
            
            if (pmfSum < 0.01f || pmfSum.isNaN()) {
                Log.e(LOG_ID, "Invalid PMF: sum=$pmfSum - model may not be loaded correctly")
                return null
            }
            
            // Convert PMF to trend probabilities
            val trendProbs = computeTrendProbabilities(pmf, horizon)
            
            // Log final result
            val probSum = trendProbs.probabilities.values.sum()
            Log.i(LOG_ID, "Final trend probs for ${horizon}min: sum=$probSum, mostLikely=${trendProbs.getMostLikely()}")
            
            // Cache result
            cachedProbabilities[horizon] = trendProbs
            
            return trendProbs
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error getting trend probabilities: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get trend probabilities for all horizons.
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
     * Build input tensor from recent glucose values.
     * 
     * Input shape: [1, SEQ_LEN, N_CHANNELS]
     * Channel 0: (G[t-k] - G[t]) / 50 (normalized relative glucose)
     * Channel 1: (G[t-k] - G[t-k-1]) / 10 (velocity)
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
        
        // Get historical values - we need SEQ_LEN + 1 = 9 readings
        // Look back further to ensure we have enough data
        val minTime = currentTime - (60 * 60 * 1000L)  // 60 minutes back
        val historicalValues = dbAccess.getGlucoseValuesInRange(minTime, currentTime)
            .sortedBy { it.timestamp }  // Ensure sorted oldest to newest
        
        Log.d(LOG_ID, "Historical data: ${historicalValues.size} values")
        
        if (historicalValues.size < SEQ_LEN + 1) {
            Log.w(LOG_ID, "Insufficient data: ${historicalValues.size} values, need ${SEQ_LEN + 1}")
            return null
        }
        
        // Use the most recent SEQ_LEN + 1 readings (last 9 readings)
        val recentValues = historicalValues.takeLast(SEQ_LEN + 1)
        val pastValues = FloatArray(SEQ_LEN + 1)
        
        for (i in 0..SEQ_LEN) {
            pastValues[i] = recentValues[i].value.toFloat()
        }
        
        // Log the time span of the data we're using
        val oldestTime = recentValues.first().timestamp
        val newestTime = recentValues.last().timestamp
        val spanMinutes = (newestTime - oldestTime) / 60000
        Log.d(LOG_ID, "Using ${recentValues.size} readings spanning $spanMinutes min: [${pastValues.joinToString { "%.0f".format(it) }}]")
        
        // Build input tensor [SEQ_LEN * N_CHANNELS]
        val input = FloatArray(SEQ_LEN * N_CHANNELS)
        
        for (i in 0 until SEQ_LEN) {
            // Channel 0: normalized relative glucose (G[t-k] - G[t]) / 50
            val relativeGlucose = (pastValues[i + 1] - currentValue) / 50f
            input[i * N_CHANNELS] = relativeGlucose
            
            // Channel 1: velocity (G[t-k] - G[t-k-1]) / 10
            val velocity = (pastValues[i + 1] - pastValues[i]) / 10f
            input[i * N_CHANNELS + 1] = velocity
        }
        
        return input
    }
    
    private fun findClosestValue(values: List<GlucoseValue>, targetTime: Long): Float? {
        val tolerance = 4 * 60 * 1000L  // 4 minutes tolerance (readings are every 5 min)
        
        var closest: GlucoseValue? = null
        var minDiff = Long.MAX_VALUE
        
        for (value in values) {
            val diff = kotlin.math.abs(value.timestamp - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                closest = value
            }
        }
        
        return if (closest != null && minDiff <= tolerance) {
            closest.value.toFloat()
        } else {
            null
        }
    }
    
    /**
     * Run TFLite inference and return PMF (softmax of logits).
     */
    private fun runInference(horizon: Int, input: FloatArray): FloatArray? {
        val interpreter = interpreters[horizon] ?: run {
            Log.e(LOG_ID, "No interpreter for horizon $horizon")
            return null
        }
        
        try {
            // The model expects input shape [1, 8, 2] - a 3D array
            // We'll use a 3D float array for cleaner TFLite interface
            val inputArray = Array(1) { Array(SEQ_LEN) { FloatArray(N_CHANNELS) } }
            for (i in 0 until SEQ_LEN) {
                inputArray[0][i][0] = input[i * N_CHANNELS]      // relative glucose
                inputArray[0][i][1] = input[i * N_CHANNELS + 1]  // velocity
            }
            
            // Get output shape from interpreter
            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputSize = if (outputShape.size > 1) outputShape[1] else outputShape[0]
            Log.d(LOG_ID, "Model output shape: ${outputShape.contentToString()}, outputSize=$outputSize")
            
            // Prepare output array [1, n_bins]
            val outputArray = Array(1) { FloatArray(outputSize) }
            
            // Run inference
            interpreter.run(inputArray, outputArray)
            
            val logits = outputArray[0]
            Log.d(LOG_ID, "Inference complete for ${horizon}min: logits[0]=${logits[0]}, logits[100]=${logits.getOrNull(100)}, logits.size=${logits.size}")
            
            // Get temperature for this horizon (default to 1.0 if not available)
            val temperature = metadata?.temperatures?.get(horizon.toString()) ?: 1.0f
            Log.d(LOG_ID, "Applying temperature scaling: T=$temperature for ${horizon}min horizon")
            
            // Apply temperature-scaled softmax to get calibrated PMF
            val pmf = softmax(logits, temperature)
            Log.d(LOG_ID, "PMF sum=${pmf.sum()}, peak at ${pmf.indices.maxByOrNull { pmf[it] }}")
            
            return pmf
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Inference error for horizon $horizon: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Apply softmax to logits to get probabilities.
     * 
     * @param logits Raw model output logits
     * @param temperature Temperature scaling factor (T < 1 sharpens, T > 1 softens)
     */
    private fun softmax(logits: FloatArray, temperature: Float = 1.0f): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expValues = logits.map { exp(((it - maxLogit) / temperature).toDouble()).toFloat() }
        val sumExp = expValues.sum()
        return expValues.map { it / sumExp }.toFloatArray()
    }
    
    /**
     * Compute trend probabilities from PMF by summing bins within each category.
     * Uses actual bin centers from metadata for accurate delta computation.
     */
    private fun computeTrendProbabilities(pmf: FloatArray, horizon: Int): TrendProbabilities {
        // Trend thresholds in mg/dL (scaled by horizon)
        // Rate thresholds are per minute, so delta = rate * horizon
        val doubleDownThreshold = -3f * horizon  // e.g., -45 for 15 min
        val downThreshold = -2f * horizon        // e.g., -30 for 15 min
        val slightlyDownThreshold = -1f * horizon // e.g., -15 for 15 min
        val slightlyUpThreshold = 1f * horizon   // e.g., +15 for 15 min
        val upThreshold = 2f * horizon           // e.g., +30 for 15 min
        val doubleUpThreshold = 3f * horizon     // e.g., +45 for 15 min
        
        // Get bin centers from metadata for accurate delta computation
        val binConfig = metadata?.binConfig?.get(horizon.toString())
        val binCenters = binConfig?.binCenters
        
        val nBins = pmf.size
        val useBinCenters = binCenters != null && binCenters.size == nBins
        
        Log.d(LOG_ID, "computeTrendProbabilities: horizon=$horizon, nBins=$nBins, binCenters.size=${binCenters?.size}, useBinCenters=$useBinCenters")
        
        // Fallback parameters if bin centers not available
        val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
        val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
        val step = if (nBins > 1) (maxDelta - minDelta) / (nBins - 1) else 1f
        
        if (!useBinCenters) {
            Log.w(LOG_ID, "FALLBACK bin centers: min=$minDelta, max=$maxDelta, step=$step")
        } else {
            Log.d(LOG_ID, "Using metadata bin_centers: [${binCenters!![0]}, ..., ${binCenters.last()}]")
        }
        
        Log.d(LOG_ID, "Computing trend probabilities for horizon $horizon with $nBins bins")
        
        // Initialize probabilities
        var probDoubleDown = 0f
        var probDown = 0f
        var probSlightlyDown = 0f
        var probFlat = 0f
        var probSlightlyUp = 0f
        var probUp = 0f
        var probDoubleUp = 0f
        
        // Sum PMF values for each category
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
        
        Log.d(LOG_ID, "Trend probs for ${horizon}min: ⬇⬇=${String.format("%.1f%%", probDoubleDown*100)}, " +
                "↓=${String.format("%.1f%%", probDown*100)}, ↘=${String.format("%.1f%%", probSlightlyDown*100)}, " +
                "→=${String.format("%.1f%%", probFlat*100)}, ↗=${String.format("%.1f%%", probSlightlyUp*100)}, " +
                "↑=${String.format("%.1f%%", probUp*100)}, ⬆⬆=${String.format("%.1f%%", probDoubleUp*100)}")
        
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
     * Generate default bin centers if metadata is not available.
     */
    private fun generateDefaultBinCenters(nBins: Int, horizon: Int): List<Float> {
        // Estimate range based on typical glucose deltas
        val min = -3f * horizon - 25f
        val max = 3f * horizon + 25f
        val step = (max - min) / (nBins - 1)
        return (0 until nBins).map { min + it * step }
    }
    
    /**
     * Clear cached probabilities.
     */
    fun clearCache() {
        cachedProbabilities.clear()
        cachedTime = 0L
    }
    
    /**
     * Close interpreters and release resources.
     */
    fun close() {
        try {
            interpreters.values.forEach { it.close() }
            interpreters.clear()
            initialized = false
            modelsAvailable = false
            clearCache()
            Log.i(LOG_ID, "TcnPredictionService closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing service: ${e.message}", e)
        }
    }
    
    /**
     * Get available prediction horizons.
     */
    fun getAvailableHorizons(): List<Int> = PREDICTION_HORIZONS.filter { interpreters.containsKey(it) }
    
    /**
     * Get the probability of going below a threshold (e.g., 70 for hypoglycemia).
     * Returns the probability that glucose + delta < threshold.
     * Uses actual bin centers from metadata for accurate computation.
     */
    fun getLowProbability(context: Context, horizon: Int, currentGlucose: Int, threshold: Int = 70): Float? {
        init(context)
        
        if (!modelsAvailable || horizon !in PREDICTION_HORIZONS) {
            return null
        }
        
        try {
            val input = buildInputTensor() ?: return null
            val pmf = runInference(horizon, input) ?: return null
            
            // Get bin centers from metadata
            val binConfig = metadata?.binConfig?.get(horizon.toString())
            val binCenters = binConfig?.binCenters
            val nBins = pmf.size
            val useBinCenters = binCenters != null && binCenters.size == nBins
            
            Log.d(LOG_ID, "getLowProbability: horizon=$horizon, nBins=$nBins, binCenters.size=${binCenters?.size}, useBinCenters=$useBinCenters")
            if (binCenters != null && binCenters.isNotEmpty()) {
                Log.d(LOG_ID, "  binCenters[0]=${binCenters[0]}, binCenters[last]=${binCenters.last()}")
            }
            
            // Fallback parameters if bin centers not available
            val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
            val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
            val step = if (nBins > 1) (maxDelta - minDelta) / (nBins - 1) else 1f
            
            if (!useBinCenters) {
                Log.w(LOG_ID, "  FALLBACK: minDelta=$minDelta, maxDelta=$maxDelta, step=$step")
            }
            
            // Sum probabilities where current + delta < threshold
            val targetDelta = threshold - currentGlucose
            var lowProb = 0f
            var binsIncluded = 0
            for (i in pmf.indices) {
                val delta = if (useBinCenters) {
                    binCenters!![i].toFloat()
                } else {
                    minDelta + i * step
                }
                if (currentGlucose + delta < threshold) {
                    lowProb += pmf[i]
                    binsIncluded++
                }
            }
            
            Log.d(LOG_ID, "Low probability (<$threshold, delta<$targetDelta) for ${horizon}min: ${String.format("%.1f%%", lowProb * 100)}, bins=$binsIncluded")
            return lowProb
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error computing low probability: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get test low probability for UI testing.
     */
    fun getTestLowProbability(currentGlucose: Int, threshold: Int = 70): Float {
        // Return a reasonable test value based on current glucose
        return when {
            currentGlucose < 80 -> 0.35f   // 35% chance if already close to low
            currentGlucose < 100 -> 0.15f  // 15% chance if moderately low
            currentGlucose < 120 -> 0.05f  // 5% chance if normal
            else -> 0.02f                   // 2% chance if high
        }
    }
    
    /**
     * Quantile predictions (Q10, Q50, Q90) computed from the PMF.
     */
    data class QuantilePrediction(
        val horizon: Int,
        val q10Delta: Float,   // 10th percentile delta
        val q50Delta: Float,   // 50th percentile delta (median)
        val q90Delta: Float,   // 90th percentile delta
        val q10: Float,        // Absolute glucose value for Q10
        val q50: Float,        // Absolute glucose value for Q50
        val q90: Float         // Absolute glucose value for Q90
    )
    
    /**
     * Compute Q10, Q50, Q90 from the PMF for a specific horizon.
     * 
     * These quantiles are computed by finding the delta values where the
     * cumulative probability reaches 10%, 50%, and 90%.
     * Uses actual bin centers from metadata for accurate quantile computation.
     */
    fun getQuantilePrediction(context: Context, horizon: Int): QuantilePrediction? {
        init(context)
        
        if (!modelsAvailable || horizon !in PREDICTION_HORIZONS) {
            return null
        }
        
        val currentGlucose = ReceiveData.rawValue.toFloat()
        if (currentGlucose <= 0) {
            return null
        }
        
        try {
            val input = buildInputTensor() ?: return null
            val pmf = runInference(horizon, input) ?: return null
            
            // Get bin centers from metadata for accurate quantile computation
            val binConfig = metadata?.binConfig?.get(horizon.toString())
            val binCenters = binConfig?.binCenters
            
            Log.d(LOG_ID, "getQuantilePrediction: horizon=$horizon, pmf.size=${pmf.size}, binCenters.size=${binCenters?.size}")
            if (binCenters != null && binCenters.isNotEmpty()) {
                Log.d(LOG_ID, "  binCenters[0]=${binCenters[0]}, binCenters[last]=${binCenters.last()}")
            }
            
            if (binCenters == null || binCenters.size != pmf.size) {
                Log.w(LOG_ID, "Bin centers not available or size mismatch (${binCenters?.size} vs ${pmf.size}), using fallback")
                // Fallback: use metadata min/max if available, otherwise estimate
                val minDelta = binConfig?.min?.toFloat() ?: (-3f * horizon - 25f)
                val maxDelta = binConfig?.max?.toFloat() ?: (3f * horizon + 25f)
                return computeQuantilesWithRange(pmf, currentGlucose, horizon, minDelta, maxDelta)
            }
            
            // Log PMF statistics for debugging
            val pmfSum = pmf.sum()
            val pmfMax = pmf.maxOrNull() ?: 0f
            val pmfPeakIdx = pmf.indices.maxByOrNull { pmf[it] } ?: -1
            val pmfPeakDelta = if (pmfPeakIdx >= 0) binCenters[pmfPeakIdx] else 0
            Log.d(LOG_ID, "PMF stats: sum=$pmfSum, max=$pmfMax, peakIdx=$pmfPeakIdx, peakDelta=$pmfPeakDelta")
            
            // Log cumulative probability at key thresholds for debugging
            var cumAt45 = 0f  // double-down threshold
            var cumAt30 = 0f  // down threshold
            var cumAt15 = 0f  // slightly-down threshold
            var cumAt0 = 0f   // flat center
            for (i in pmf.indices) {
                val delta = binCenters[i]
                if (delta <= -3 * horizon) cumAt45 = pmf.take(i + 1).sum()
                if (delta <= -2 * horizon) cumAt30 = pmf.take(i + 1).sum()
                if (delta <= -1 * horizon) cumAt15 = pmf.take(i + 1).sum()
                if (delta <= 0) cumAt0 = pmf.take(i + 1).sum()
            }
            Log.d(LOG_ID, "Cumulative: at_doubleDown=${String.format("%.1f%%", cumAt45*100)}, at_down=${String.format("%.1f%%", cumAt30*100)}, at_slightlyDown=${String.format("%.1f%%", cumAt15*100)}, at_0=${String.format("%.1f%%", cumAt0*100)}")
            
            // Compute cumulative distribution function using actual bin centers
            var cumSum = 0f
            var q10Delta: Float? = null
            var q50Delta: Float? = null
            var q90Delta: Float? = null
            var q10Idx: Int? = null
            var q50Idx: Int? = null
            var q90Idx: Int? = null
            
            for (i in pmf.indices) {
                cumSum += pmf[i]
                val delta = binCenters[i].toFloat()
                
                // Find first delta where cumulative prob >= target
                if (q10Delta == null && cumSum >= 0.10f) {
                    q10Delta = delta
                    q10Idx = i
                }
                if (q50Delta == null && cumSum >= 0.50f) {
                    q50Delta = delta
                    q50Idx = i
                }
                if (q90Delta == null && cumSum >= 0.90f) {
                    q90Delta = delta
                    q90Idx = i
                }
                
                if (q10Delta != null && q50Delta != null && q90Delta != null) {
                    break
                }
            }
            
            // Default to first/last bin centers if we didn't reach the threshold
            q10Delta = q10Delta ?: binCenters.first().toFloat()
            q50Delta = q50Delta ?: 0f
            q90Delta = q90Delta ?: binCenters.last().toFloat()
            
            Log.d(LOG_ID, "Quantiles for ${horizon}min: Q10=$q10Delta (idx=$q10Idx), Q50=$q50Delta (idx=$q50Idx), Q90=$q90Delta (idx=$q90Idx)")
            
            return QuantilePrediction(
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
    
    /**
     * Fallback quantile computation when bin centers are not available.
     * Assumes bins are evenly distributed between minDelta and maxDelta.
     */
    private fun computeQuantilesWithRange(
        pmf: FloatArray, 
        currentGlucose: Float, 
        horizon: Int,
        minDelta: Float,
        maxDelta: Float
    ): QuantilePrediction {
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
        
        Log.d(LOG_ID, "Quantiles for ${horizon}min: Q10=$q10Delta, Q50=$q50Delta, Q90=$q90Delta (fallback)")
        
        return QuantilePrediction(
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
    fun getAllQuantilePredictions(context: Context): List<QuantilePrediction> {
        init(context)
        
        val results = mutableListOf<QuantilePrediction>()
        for (horizon in PREDICTION_HORIZONS) {
            getQuantilePrediction(context, horizon)?.let {
                results.add(it)
            }
        }
        return results
    }
}


