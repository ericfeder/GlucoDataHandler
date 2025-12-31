package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils

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
 * Model metadata loaded from tcn_metadata.json
 */
data class ModelMetadata(
    val version: String,
    val created_at: String,
    val model_type: String = "TCN"
)

/**
 * Service for on-device glucose predictions using TCN models.
 * 
 * Uses trained TCN distribution models that output a probability mass function (PMF),
 * from which Q10, Q50, Q90 quantile predictions are computed.
 */
object GlucosePredictionService {
    private const val LOG_ID = "GDH.PredictionService"
    
    private val PREDICTION_HORIZONS = listOf(5, 10, 15, 20, 25, 30)
    
    private var metadata: ModelMetadata? = null
    private var initialized = false
    
    // Cache for predictions
    private var cachedPredictions: List<GlucosePrediction> = emptyList()
    private var cachedTime: Long = 0L
    
    /**
     * Initialize the prediction service.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing GlucosePredictionService (TCN-based)")
        
        try {
            // Initialize TCN prediction service
            TcnPredictionService.init(context)
            
            // Create metadata from TCN service
            metadata = ModelMetadata(
                version = "2.0-TCN",
                created_at = "2025-01-01",
                model_type = "TCN Distribution"
            )
            
            initialized = true
            Log.i(LOG_ID, "Initialization complete. Using TCN models: ${TcnPredictionService.isAvailable()}")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to initialize: ${e.message}", e)
            initialized = true
        }
    }
    
    /**
     * Check if prediction service is available (models loaded successfully).
     */
    fun isAvailable(): Boolean = initialized && TcnPredictionService.isAvailable()
    
    /**
     * Get predictions for all horizons based on current glucose data.
     * Returns cached predictions if data hasn't changed.
     */
    fun getPredictions(context: Context): List<GlucosePrediction> {
        init(context)
        
        if (!TcnPredictionService.isAvailable()) {
            Log.w(LOG_ID, "TCN models not available, returning empty predictions")
            return emptyList()
        }
        
        // Check cache
        val currentTime = ReceiveData.time
        if (currentTime == cachedTime && cachedPredictions.isNotEmpty()) {
            return cachedPredictions
        }
        
        try {
            // Get quantile predictions from TCN for all horizons
            val tcnPredictions = TcnPredictionService.getAllQuantilePredictions(context)
            
            // Convert to GlucosePrediction format
            val predictions = tcnPredictions.map { tcn ->
                GlucosePrediction(
                    horizon = tcn.horizon,
                    q10 = tcn.q10,
                    q50 = tcn.q50,
                    q90 = tcn.q90,
                    q10Delta = tcn.q10Delta,
                    q50Delta = tcn.q50Delta,
                    q90Delta = tcn.q90Delta
                )
            }
            
            // Update cache
            cachedPredictions = predictions
            cachedTime = currentTime
            
            Log.d(LOG_ID, "Generated ${predictions.size} predictions from TCN")
            return predictions
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error generating predictions: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Get the predicted trend rate based on Q50 15-minute prediction.
     * Returns rate in mg/dL per minute (same scale as Dexcom rate).
     * 
     * Dexcom thresholds (per minute):
     * - Steady (→): < 1 mg/dL/min (< 15 mg/dL per 15 min)
     * - Slowly (↗↘): 1-2 mg/dL/min (15-30 mg/dL per 15 min)
     * - Rising/Falling (↑↓): 2-3 mg/dL/min (30-45 mg/dL per 15 min)
     * - Rapidly (⇈⇊): > 3 mg/dL/min (> 45 mg/dL per 15 min)
     */
    fun getModelTrendRate(context: Context): Float {
        val predictions = getPredictions(context)
        val pred15 = predictions.find { it.horizon == 15 } ?: return Float.NaN
        
        // Convert 15-min delta to rate per minute (matching Dexcom's rate scale)
        val rate = pred15.q50Delta / 15f
        
        Log.d(LOG_ID, "getModelTrendRate: currentGlucose=${ReceiveData.rawValue}, " +
                "q50=${pred15.q50}, q50Delta=${pred15.q50Delta}, rate=$rate (per min)")
        
        return rate
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
        TcnPredictionService.clearCache()
    }
    
    /**
     * Close and release resources.
     */
    fun close() {
        try {
            TcnPredictionService.close()
            initialized = false
            clearCache()
            Log.i(LOG_ID, "GlucosePredictionService closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing service: ${e.message}", e)
        }
    }
}
