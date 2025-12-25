package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import de.michelinside.glucodatahandler.common.database.GlucoseValue
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import java.nio.FloatBuffer

/**
 * Represents a glucose prediction for a specific time horizon.
 */
data class GlucosePrediction(
    val horizon: Int,       // Minutes ahead (5, 10, 15, 20, 25, 30)
    val q10: Float,         // 10th percentile (lower bound) - absolute glucose value
    val q50: Float,         // 50th percentile (median) - absolute glucose value
    val q90: Float,         // 90th percentile (upper bound) - absolute glucose value
    val q10Delta: Float,    // Delta for Q10
    val q50Delta: Float,    // Delta for Q50
    val q90Delta: Float     // Delta for Q90
) {
    val intervalWidth: Float get() = q90 - q10
}

/**
 * Model metadata loaded from model_metadata.json
 */
data class ModelMetadata(
    val version: String,
    val created_at: String,
    val training_start: String,
    val training_end: String,
    val training_samples: Int,
    val training_window_months: Int,
    val feature_names: List<String>,
    val num_features: Int,
    val prediction_horizons: List<Int>,
    val quantiles: Map<String, Float>,
    val models: List<ModelInfo>
)

data class ModelInfo(
    val name: String,
    val horizon: Int,
    val quantile: Float,
    val quantile_name: String
)

/**
 * Service for on-device glucose predictions using ONNX Runtime.
 * 
 * Uses trained GBM quantile regression models to predict future glucose values
 * with confidence intervals (Q10, Q50, Q90).
 */
object GlucosePredictionService {
    private const val LOG_ID = "GDH.PredictionService"
    private const val MODELS_DIR = "models"
    private const val METADATA_FILE = "model_metadata.json"
    
    // Lookback windows in minutes (must match training)
    private val LOOKBACK_WINDOWS = listOf(5, 10, 15, 20, 25, 30, 35, 40)
    private val PREDICTION_HORIZONS = listOf(5, 10, 15, 20, 25, 30)
    private val QUANTILES = listOf("q10", "q50", "q90")
    
    private var ortEnvironment: OrtEnvironment? = null
    private var sessions: MutableMap<String, OrtSession> = mutableMapOf()
    private var metadata: ModelMetadata? = null
    private var initialized = false
    private var modelsAvailable = false
    
    // Cache for predictions
    private var cachedPredictions: List<GlucosePrediction> = emptyList()
    private var cachedTime: Long = 0L
    
    /**
     * Initialize the prediction service by loading models from assets.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing GlucosePredictionService")
        
        try {
            // Create ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // Load metadata
            loadMetadata(context)
            
            // Load all models
            loadModels(context)
            
            initialized = true
            modelsAvailable = sessions.isNotEmpty()
            
            Log.i(LOG_ID, "Initialization complete. Models available: $modelsAvailable (${sessions.size} loaded)")
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
            metadata = Gson().fromJson(metadataJson, ModelMetadata::class.java)
            Log.i(LOG_ID, "Loaded metadata: version=${metadata?.version}, " +
                    "training=${metadata?.training_start} to ${metadata?.training_end}")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to load metadata: ${e.message}", e)
        }
    }
    
    private fun loadModels(context: Context) {
        val env = ortEnvironment ?: return
        
        for (horizon in PREDICTION_HORIZONS) {
            for (quantile in QUANTILES) {
                val modelName = "model_${quantile}_${horizon}min"
                try {
                    val modelBytes = context.assets.open("$MODELS_DIR/$modelName.onnx")
                        .readBytes()
                    val session = env.createSession(modelBytes)
                    sessions[modelName] = session
                    Log.d(LOG_ID, "Loaded model: $modelName")
                } catch (e: Exception) {
                    Log.w(LOG_ID, "Model not found: $modelName.onnx - ${e.message}")
                }
            }
        }
    }
    
    /**
     * Check if prediction service is available (models loaded successfully).
     */
    fun isAvailable(): Boolean = initialized && modelsAvailable
    
    /**
     * Get predictions for all horizons based on current glucose data.
     * Returns cached predictions if data hasn't changed.
     */
    fun getPredictions(context: Context): List<GlucosePrediction> {
        init(context)
        
        if (!modelsAvailable) {
            Log.w(LOG_ID, "Models not available, returning empty predictions")
            return emptyList()
        }
        
        // Check cache
        val currentTime = ReceiveData.time
        if (currentTime == cachedTime && cachedPredictions.isNotEmpty()) {
            return cachedPredictions
        }
        
        try {
            // Build feature vector
            val features = buildFeatureVector()
            if (features == null) {
                Log.w(LOG_ID, "Could not build feature vector - insufficient data")
                return emptyList()
            }
            
            // Run inference for all horizons
            val predictions = mutableListOf<GlucosePrediction>()
            val currentGlucose = ReceiveData.rawValue.toFloat()
            
            for (horizon in PREDICTION_HORIZONS) {
                val q10Delta = runInference("model_q10_${horizon}min", features)
                val q50Delta = runInference("model_q50_${horizon}min", features)
                val q90Delta = runInference("model_q90_${horizon}min", features)
                
                if (q10Delta != null && q50Delta != null && q90Delta != null) {
                    predictions.add(GlucosePrediction(
                        horizon = horizon,
                        q10 = currentGlucose + q10Delta,
                        q50 = currentGlucose + q50Delta,
                        q90 = currentGlucose + q90Delta,
                        q10Delta = q10Delta,
                        q50Delta = q50Delta,
                        q90Delta = q90Delta
                    ))
                }
            }
            
            // Update cache
            cachedPredictions = predictions
            cachedTime = currentTime
            
            Log.d(LOG_ID, "Generated ${predictions.size} predictions")
            return predictions
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error generating predictions: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Build feature vector from recent glucose values.
     * Returns array of 8 floats: [past_delta_5min, past_delta_10min, ..., past_delta_40min]
     */
    private fun buildFeatureVector(): FloatArray? {
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
        
        // Get historical values for the lookback period (need values from 40+ minutes ago)
        val minTime = currentTime - (45 * 60 * 1000) // 45 minutes ago
        val historicalValues = dbAccess.getGlucoseValuesInRange(minTime, currentTime)
        
        if (historicalValues.size < 2) {
            Log.w(LOG_ID, "Insufficient historical data: ${historicalValues.size} values")
            return null
        }
        
        // Build feature array
        val features = FloatArray(LOOKBACK_WINDOWS.size)
        var allFeaturesValid = true
        
        for ((index, lookbackMin) in LOOKBACK_WINDOWS.withIndex()) {
            val targetTime = currentTime - (lookbackMin * 60 * 1000)
            val pastValue = findClosestValue(historicalValues, targetTime)
            
            if (pastValue != null) {
                features[index] = currentValue - pastValue
            } else {
                Log.w(LOG_ID, "Missing value for lookback ${lookbackMin}min")
                allFeaturesValid = false
                break
            }
        }
        
        if (!allFeaturesValid) {
            return null
        }
        
        return features
    }
    
    /**
     * Find the closest glucose value to the target timestamp.
     * Returns null if no value within 3 minutes of target.
     */
    private fun findClosestValue(values: List<GlucoseValue>, targetTime: Long): Float? {
        val tolerance = 3 * 60 * 1000 // 3 minutes tolerance
        
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
     * Run ONNX model inference.
     */
    private fun runInference(modelName: String, features: FloatArray): Float? {
        val session = sessions[modelName] ?: return null
        val env = ortEnvironment ?: return null
        
        try {
            // Create input tensor [1, 8]
            val inputShape = longArrayOf(1, features.size.toLong())
            val inputBuffer = FloatBuffer.wrap(features)
            val inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)
            
            // Run inference
            val inputName = session.inputNames.first()
            val results = session.run(mapOf(inputName to inputTensor))
            
            // Get output
            val output = results[0].value
            val prediction = when (output) {
                is FloatArray -> output[0]
                is Array<*> -> (output[0] as? FloatArray)?.get(0)
                else -> null
            }
            
            // Cleanup
            inputTensor.close()
            results.close()
            
            return prediction
        } catch (e: Exception) {
            Log.e(LOG_ID, "Inference error for $modelName: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get the predicted trend rate based on Q50 30-minute prediction.
     * Returns rate in mg/dL per 5 minutes (same scale as Dexcom rate).
     */
    fun getModelTrendRate(context: Context): Float {
        val predictions = getPredictions(context)
        val pred30 = predictions.find { it.horizon == 30 } ?: return Float.NaN
        
        // Convert 30-min delta to rate per 5 minutes
        return pred30.q50Delta / 6f
    }
    
    /**
     * Get the predicted trend arrow symbol based on model predictions.
     */
    fun getModelTrendArrow(context: Context): Char {
        val rate = getModelTrendRate(context)
        return GlucoDataUtils.getRateSymbol(rate)
    }
    
    /**
     * Get the predicted trend rate as a Dexcom-style label.
     */
    fun getModelTrendLabel(context: Context): String {
        val rate = getModelTrendRate(context)
        return GlucoDataUtils.getDexcomLabel(rate)
    }
    
    /**
     * Get prediction for a specific horizon (5, 10, 15, 20, 25, or 30 minutes).
     */
    fun getPrediction(context: Context, horizon: Int): GlucosePrediction? {
        return getPredictions(context).find { it.horizon == horizon }
    }
    
    /**
     * Get model metadata (training info, version, etc.)
     */
    fun getMetadata(): ModelMetadata? = metadata
    
    /**
     * Clear cached predictions (call when new glucose data arrives).
     */
    fun clearCache() {
        cachedPredictions = emptyList()
        cachedTime = 0L
    }
    
    /**
     * Close ONNX sessions and release resources.
     */
    fun close() {
        try {
            sessions.values.forEach { it.close() }
            sessions.clear()
            ortEnvironment?.close()
            ortEnvironment = null
            initialized = false
            modelsAvailable = false
            clearCache()
            Log.i(LOG_ID, "GlucosePredictionService closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing service: ${e.message}", e)
        }
    }
}


