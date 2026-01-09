package de.michelinside.glucodatahandler

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import de.michelinside.glucodatahandler.android_auto.CarModeReceiver
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GdhUncaughtExecptionHandler
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.SourceState
import de.michelinside.glucodatahandler.common.SourceStateData
import de.michelinside.glucodatahandler.common.WearPhoneConnection
import de.michelinside.glucodatahandler.common.chart.ChartCreator
import de.michelinside.glucodatahandler.common.chart.GlucoseChart
import de.michelinside.glucodatahandler.common.notification.AlarmHandler
import de.michelinside.glucodatahandler.common.notification.AlarmState
import de.michelinside.glucodatahandler.common.notification.AlarmType
import de.michelinside.glucodatahandler.common.notification.ChannelType
import de.michelinside.glucodatahandler.common.notification.Channels
import de.michelinside.glucodatahandler.common.notifier.DataSource
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.tasks.DexcomShareSourceTask
import de.michelinside.glucodatahandler.common.ui.Dialogs
import de.michelinside.glucodatahandler.common.prediction.GlucosePredictionService
import de.michelinside.glucodatahandler.common.prediction.GlucoseZone
import de.michelinside.glucodatahandler.common.prediction.PredictionData
import de.michelinside.glucodatahandler.common.prediction.TcnPredictionService
import de.michelinside.glucodatahandler.common.prediction.TrendCategory
import de.michelinside.glucodatahandler.common.prediction.ZoneProbabilities
import de.michelinside.glucodatahandler.common.ui.ProbabilityPillView
import de.michelinside.glucodatahandler.common.chart.TrendProbabilityChart
import de.michelinside.glucodatahandler.common.utils.BitmapUtils
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import de.michelinside.glucodatahandler.common.utils.GlucoseStatistics
import de.michelinside.glucodatahandler.common.utils.PackageUtils
import de.michelinside.glucodatahandler.common.utils.Utils
import de.michelinside.glucodatahandler.notification.AlarmNotification
import de.michelinside.glucodatahandler.preferences.AlarmFragment
import de.michelinside.glucodatahandler.preferences.LockscreenSettingsFragment
import de.michelinside.glucodatahandler.watch.WatchDrip
import de.michelinside.glucodatahandler.widget.BatteryLevelWidget
import java.text.DateFormat
import java.text.DecimalFormat
import java.time.Duration
import java.util.Date
import kotlin.math.min
import kotlin.time.Duration.Companion.days
import de.michelinside.glucodatahandler.common.R as CR


class MainActivity : AppCompatActivity(), NotifierInterface {
    private lateinit var txtBgValue: TextView
    private lateinit var viewIcon: ImageView
    private var modelViewIcon: ImageView? = null
    private lateinit var timeText: TextView
    private lateinit var deltaText: TextView
    private lateinit var iobText: TextView
    private lateinit var cobText: TextView
    private lateinit var iobCobLayout: LinearLayout
    private lateinit var txtLastValue: TextView
    private lateinit var tableStatistics: TableLayout
    private lateinit var tableNotes: TableLayout
    private lateinit var btnSources: Button
    private lateinit var btnHelp: Button
    private lateinit var noDataLayout: LinearLayout
    private lateinit var sharedPref: SharedPreferences
    private lateinit var optionsMenu: Menu
    private lateinit var chart: GlucoseChart
    private lateinit var statGroup: RadioGroup
    private lateinit var btnStat1d: RadioButton
    private lateinit var btnStat7d: RadioButton
    private var btnStat14d: RadioButton? = null

    private var layoutWithGraph: LinearLayout? = null
    private var layoutWithoutGraph: LinearLayout? = null
    private var expandCollapseView: ImageView? = null
    private var alarmIcon: MenuItem? = null
    private var snoozeMenu: MenuItem? = null
    private var floatingWidgetItem: MenuItem? = null
    private val LOG_ID = "GDH.Main"
    private var requestNotificationPermission = false
    private var doNotUpdate = false
    private lateinit var chartCreator: ChartCreator
    private var systemBars: Insets? = null
    
    // Trend probability chart
    private var trendProbabilityChart: TrendProbabilityChart? = null
    private var trendProbabilityLayout: LinearLayout? = null
    private var trendDivider: View? = null
    private var probabilityPillsContainer: LinearLayout? = null
    private var selectedHorizon: Int = 15
    private var horizonButtons: Map<Int, Button> = emptyMap()
    
    // Page initialization flags
    private var mainPageInitialized = false
    private var statisticsPageInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            setContentView(R.layout.activity_main)
            Log.v(LOG_ID, "onCreate called")

            GlucoDataServiceMobile.start(this.applicationContext)
            PackageUtils.updatePackages(this.applicationContext)

            PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
            sharedPref = this.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            ReceiveData.initData(this.applicationContext)

            // Check if we're in landscape mode (uses old layout structure)
            expandCollapseView = findViewById(R.id.expandCollapseView)
            val isLandscape = expandCollapseView != null

            if (isLandscape) {
                // Landscape mode - use the old layout structure directly
                initLandscapeViews()
            } else {
                // Portrait mode - single scrollable layout
                initPortraitViews()
            }

            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
                systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val fullscreen = expandCollapseView != null && BitmapUtils.isLandscapeOrientation(this) && sharedPref.getBoolean(Constants.SHARED_PREF_FULLSCREEN_LANDSCAPE, true)
                Log.d(LOG_ID, "System bars: $systemBars - fullscreen: $fullscreen")

                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM && fullscreen)
                    v.setPadding(systemBars!!.left, systemBars!!.top/2, systemBars!!.right, systemBars!!.bottom)
                else
                    v.setPadding(systemBars!!.left, systemBars!!.top, systemBars!!.right, systemBars!!.bottom)
                insets
            }

            Dialogs.updateColorScheme(this)

            if (requestPermission())
                GlucoDataServiceMobile.start(this)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onCreate exception: " + exc.message.toString() )
        }
    }
    
    private fun initPortraitViews() {
        try {
            Log.d(LOG_ID, "Initializing portrait views")
            
            // Initialize main views
            txtBgValue = findViewById(R.id.txtBgValue)
            modelViewIcon = findViewById(R.id.modelViewIcon)
            
            // viewIcon placeholder for compatibility (Dexcom arrow now shown via tooltip on ML arrow)
            viewIcon = ImageView(this)
            timeText = findViewById(R.id.timeText)
            deltaText = findViewById(R.id.deltaText)
            iobText = findViewById(R.id.iobText)
            cobText = findViewById(R.id.cobText)
            iobCobLayout = findViewById(R.id.layout_iob_cob)
            txtLastValue = findViewById(R.id.txtLastValue)
            btnSources = findViewById(R.id.btnSources)
            btnHelp = findViewById(R.id.btnHelp)
            noDataLayout = findViewById(R.id.layout_no_data)
            tableNotes = findViewById(R.id.tableNotes)
            chart = findViewById(R.id.chart)
            
            btnSources.setOnClickListener{
                try {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra(SettingsActivity.FRAGMENT_EXTRA, SettingsFragmentClass.SORUCE_FRAGMENT.value)
                    startActivity(intent)
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "btn source exception: " + exc.message.toString() )
                }
            }

            btnHelp.setOnClickListener{
                try {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(resources.getText(CR.string.help_link).toString())
                    )
                    startActivity(browserIntent)
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "btn help exception: " + exc.message.toString() )
                }
            }
            
            // Initialize trend probability chart
            trendDivider = findViewById(R.id.trendDivider)
            trendProbabilityLayout = findViewById(R.id.layout_trend_probability)
            trendProbabilityChart = findViewById(R.id.trendProbabilityChart)
            probabilityPillsContainer = findViewById(R.id.probabilityPillsContainer)
            
            val mainContent: View = findViewById(R.id.mainContent)
            initTrendProbabilityChart(mainContent)
            
            mainPageInitialized = true
            
            // Initialize statistics views (now in same layout)
            tableStatistics = findViewById(R.id.tableStatistics)
            statGroup = findViewById(R.id.statGroup)
            btnStat1d = findViewById(R.id.btnStat1d)
            btnStat7d = findViewById(R.id.btnStat7d)
            btnStat14d = findViewById(R.id.btnStat14d)
            
            btnStat1d.text = resources.getQuantityString(CR.plurals.duration_days_short, 1, 1)
            btnStat7d.text = resources.getQuantityString(CR.plurals.duration_days_short, 7, 7)
            btnStat14d?.text = resources.getQuantityString(CR.plurals.duration_days_short, 14, 14)

            val savedDays = sharedPref.getInt(Constants.SHARED_PREF_MAIN_STATISTICS_DAYS, 7)
            when (savedDays) {
                1 -> btnStat1d.isChecked = true
                14 -> btnStat14d?.isChecked = true
                else -> btnStat7d.isChecked = true
            }

            statGroup.setOnCheckedChangeListener { _, checkedId ->
                Log.d(LOG_ID, "statGroup changed to $checkedId")
                val days = when (checkedId) {
                    R.id.btnStat1d -> 1
                    R.id.btnStat14d -> 14
                    else -> 7
                }
                with(sharedPref.edit()) {
                    putInt(Constants.SHARED_PREF_MAIN_STATISTICS_DAYS, days)
                    apply()
                }
                updateStatisticsTable()
            }
            
            statisticsPageInitialized = true
            
            // Delay chart creation until view is laid out
            chart.post {
                chartCreator = MainChartCreator(chart, this, Constants.SHARED_PREF_GRAPH_DURATION_PHONE_MAIN, Constants.SHARED_PREF_GRAPH_TRANSPARENCY_PHONE_MAIN)
                chartCreator.onFuturePointSelected = { horizonMinutes ->
                    // Update the distribution chart when a predicted point is clicked
                    runOnUiThread {
                        selectHorizon(horizonMinutes)
                    }
                }
                chartCreator.create()
                chartCreator.resume()
                update()
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "initPortraitViews exception: " + exc.message.toString())
        }
    }
    
    private fun initLandscapeViews() {
        // Original landscape initialization
        txtBgValue = findViewById(R.id.txtBgValue)
        viewIcon = findViewById(R.id.viewIcon)
        modelViewIcon = findViewById(R.id.modelViewIcon)
        timeText = findViewById(R.id.timeText)
        deltaText = findViewById(R.id.deltaText)
        iobText = findViewById(R.id.iobText)
        cobText = findViewById(R.id.cobText)
        iobCobLayout = findViewById(R.id.layout_iob_cob)
        txtLastValue = findViewById(R.id.txtLastValue)
        btnSources = findViewById(R.id.btnSources)
        btnHelp = findViewById(R.id.btnHelp)
        noDataLayout = findViewById(R.id.layout_no_data)
        tableStatistics = findViewById(R.id.tableStatistics)
        tableNotes = findViewById(R.id.tableNotes)
        chart = findViewById(R.id.chart)
        layoutWithGraph = findViewById(R.id.glucose_with_graph)
        layoutWithoutGraph = findViewById(R.id.glucose_without_graph)
        statGroup = findViewById(R.id.statGroup)
        btnStat1d = findViewById(R.id.btnStat1d)
        btnStat7d = findViewById(R.id.btnStat7d)

        btnSources.setOnClickListener{
            try {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra(SettingsActivity.FRAGMENT_EXTRA, SettingsFragmentClass.SORUCE_FRAGMENT.value)
                startActivity(intent)
            } catch (exc: Exception) {
                Log.e(LOG_ID, "btn source exception: " + exc.message.toString() )
            }
        }

        btnHelp.setOnClickListener{
            try {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(resources.getText(CR.string.help_link).toString())
                )
                startActivity(browserIntent)
            } catch (exc: Exception) {
                Log.e(LOG_ID, "btn help exception: " + exc.message.toString() )
            }
        }

        if(expandCollapseView!=null) {
            expandCollapseView!!.setOnClickListener{
                toggleFullscreenLandMode()
            }
            if(sharedPref.getBoolean(Constants.SHARED_PREF_FULLSCREEN_LANDSCAPE, true))
                toggleFullscreenLandMode()
        }

        btnStat1d.text = resources.getQuantityString(CR.plurals.duration_days_short, 1, 1)
        btnStat7d.text = resources.getQuantityString(CR.plurals.duration_days_short, 7, 7)
        
        // btnStat14d may not exist in landscape layout
        val btn14d: RadioButton? = findViewById(R.id.btnStat14d)
        btn14d?.text = resources.getQuantityString(CR.plurals.duration_days_short, 14, 14)

        val savedDays = sharedPref.getInt(Constants.SHARED_PREF_MAIN_STATISTICS_DAYS, 7)
        when (savedDays) {
            1 -> btnStat1d.isChecked = true
            14 -> btn14d?.isChecked = true
            else -> btnStat7d.isChecked = true
        }

        statGroup.setOnCheckedChangeListener { _, checkedId ->
            Log.d(LOG_ID, "statGroup changed to $checkedId")
            val days = when (checkedId) {
                R.id.btnStat1d -> 1
                R.id.btnStat14d -> 14
                else -> 7
            }
            with(sharedPref.edit()) {
                putInt(Constants.SHARED_PREF_MAIN_STATISTICS_DAYS, days)
                apply()
            }
            updateStatisticsTable()
        }
        
        chartCreator = MainChartCreator(chart, this, Constants.SHARED_PREF_GRAPH_DURATION_PHONE_MAIN, Constants.SHARED_PREF_GRAPH_TRANSPARENCY_PHONE_MAIN)
        chartCreator.onFuturePointSelected = { horizonMinutes ->
            // Update the distribution chart when a predicted point is clicked
            runOnUiThread {
                selectHorizon(horizonMinutes)
            }
        }
        chartCreator.create()
        
        mainPageInitialized = true
        statisticsPageInitialized = true
    }

    override fun onPause() {
        try {
            super.onPause()
            InternalNotifier.remNotifier(this, this)
            if (::chartCreator.isInitialized) {
                chartCreator.pause()
            }
            Log.v(LOG_ID, "onPause called")
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onPause exception: " + exc.message.toString() )
        }
    }

    override fun onResume() {
        try {
            super.onResume()
            Log.v(LOG_ID, "onResume called")
            GlucoDataService.checkServices(this.applicationContext)
            doNotUpdate = false
            
            update()
            if (::chartCreator.isInitialized) {
                chartCreator.resume()
            }
            InternalNotifier.addNotifier(this, this, mutableSetOf(
                NotifySource.BROADCAST,
                NotifySource.IOB_COB_CHANGE,
                NotifySource.IOB_COB_TIME,
                NotifySource.MESSAGECLIENT,
                NotifySource.CAPILITY_INFO,
                NotifySource.NODE_BATTERY_LEVEL,
                NotifySource.SETTINGS,
                NotifySource.CAR_CONNECTION,
                NotifySource.TIME_VALUE,
                NotifySource.ALARM_STATE_CHANGED,
                NotifySource.SOURCE_STATE_CHANGE,
                NotifySource.PREDICTION_UPDATE))
            checkUncaughtException()
            checkMissingPermissions()
            checkNewSettings()

            if (requestNotificationPermission && Utils.checkPermission(this, android.Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU)) {
                Log.i(LOG_ID, "Notification permission granted")
                requestNotificationPermission = false
                PermanentNotification.showNotifications()
            }
            GlucoDataService.checkForConnectedNodes(true)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onResume exception: " + exc.message.toString() )
        }
    }

    override fun onDestroy() {
        Log.v(LOG_ID, "onDestroy called")
        super.onDestroy()
        if (::chartCreator.isInitialized) {
            chartCreator.close()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            Log.d(LOG_ID, "Notification permission allowed: $isGranted")
        }


    fun requestPermission() : Boolean {
        requestNotificationPermission = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Utils.checkPermission(this, android.Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU)) {
            Log.i(LOG_ID, "Request notification permission...")
            requestNotificationPermission = true
            if (this.shouldShowRequestPermissionRationale(
                    android.Manifest.permission.POST_NOTIFICATIONS)) {
                Dialogs.showOkDialog(this, CR.string.permission_notification_title, CR.string.permission_notification_message) { _, _ -> requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                this.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
            }
            return false
        }
        requestExactAlarmPermission()
        return true
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Utils.canScheduleExactAlarms(this)) {
            Log.i(LOG_ID, "Request exact alarm permission...")
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder
                .setTitle(CR.string.request_exact_alarm_title)
                .setMessage(CR.string.request_exact_alarm_summary)
                .setPositiveButton(CR.string.button_ok) { dialog, which ->
                    try {
                        startActivity(
                            Intent(
                                ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (exc: Exception) {
                        Log.e(LOG_ID, "requestExactAlarmPermission exception: " + exc.message.toString() )
                    }
                }
                .setNegativeButton(CR.string.button_cancel) { dialog, which ->
                    // Do something else.
                }
            val dialog: AlertDialog = builder.create()
            dialog.show()
        }
    }

    private fun checkMissingPermissions() {
        var permissionRequested = false
        if(sharedPref.contains(Constants.SHARED_PREF_ALARM_FULLSCREEN_NOTIFICATION_ENABLED) && sharedPref.getBoolean(Constants.SHARED_PREF_ALARM_FULLSCREEN_NOTIFICATION_ENABLED, false)) {
            if (!AlarmNotification.hasFullscreenPermission(this)) {
                permissionRequested = true
                Dialogs.showOkCancelDialog(this,
                    resources.getString(CR.string.permission_missing_title),
                    resources.getString(CR.string.setting_permission_missing_message, resources.getString(CR.string.alarm_fullscreen_notification_enabled)),
                    { _, _ ->
                        AlarmFragment.requestFullScreenPermission(this)
                    },
                    { _, _ ->
                        with(sharedPref.edit()) {
                            putBoolean(Constants.SHARED_PREF_ALARM_FULLSCREEN_NOTIFICATION_ENABLED, false)
                            apply()
                        }
                    }
                )
            }
        }
        if(!permissionRequested && sharedPref.contains(Constants.SHARED_PREF_AOD_WP_ENABLED) && sharedPref.getBoolean(Constants.SHARED_PREF_AOD_WP_ENABLED, false)) {
            if (!AODAccessibilityService.isAccessibilitySettingsEnabled(this)) {
                Dialogs.showOkCancelDialog(this,
                    resources.getString(CR.string.permission_missing_title),
                    resources.getString(CR.string.setting_permission_missing_message, resources.getString(CR.string.pref_cat_aod)),
                    { _, _ -> LockscreenSettingsFragment.requestAccessibilitySettings(this) },
                    { _, _ ->
                        with(sharedPref.edit()) {
                            putBoolean(Constants.SHARED_PREF_AOD_WP_ENABLED, false)
                            apply()
                        }
                    }
                )
            }
        }
    }

    private fun checkNewSettings() {
        try {
            if(!sharedPref.contains(Constants.SHARED_PREF_DISCLAIMER_SHOWN)) {
                Dialogs.showOkDialog(this,
                    CR.string.gdh_disclaimer_title,
                    CR.string.gdh_disclaimer_message,
                    null
                )
                with(sharedPref.edit()) {
                    putString(Constants.SHARED_PREF_DISCLAIMER_SHOWN, BuildConfig.VERSION_NAME)
                    apply()
                }
            }
            if(!sharedPref.contains(Constants.SHARED_PREF_LIBRE_AUTO_ACCEPT_TOU)) {
                if(sharedPref.getBoolean(Constants.SHARED_PREF_LIBRE_ENABLED, false)) {
                    Dialogs.showOkCancelDialog(this,
                        resources.getString(CR.string.src_cat_libreview),
                        resources.getString(CR.string.src_libre_tou_message),
                        { _, _ ->
                            with(sharedPref.edit()) {
                                putBoolean(Constants.SHARED_PREF_LIBRE_AUTO_ACCEPT_TOU, true)
                                apply()
                            }
                        },
                        { _, _ ->
                            with(sharedPref.edit()) {
                                putBoolean(Constants.SHARED_PREF_LIBRE_AUTO_ACCEPT_TOU, false)
                                apply()
                            }
                        })
                } else {
                    with(sharedPref.edit()) {
                        putBoolean(Constants.SHARED_PREF_LIBRE_AUTO_ACCEPT_TOU, true)
                        apply()
                    }
                }
            }

        } catch (exc: Exception) {
            Log.e(LOG_ID, "checkNewSettings exception: " + exc.message.toString() )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            Log.v(LOG_ID, "onCreateOptionsMenu called")
            val inflater = menuInflater
            inflater.inflate(R.menu.menu_items, menu)
            MenuCompat.setGroupDividerEnabled(menu!!, true)
            optionsMenu = menu
            alarmIcon = optionsMenu.findItem(R.id.action_alarm_toggle)
            snoozeMenu = optionsMenu.findItem(R.id.group_snooze_title)
            floatingWidgetItem = optionsMenu.findItem(R.id.action_floating_widget_toggle)
            updateAlarmIcon()
            updateMenuItems()
            return true
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onCreateOptionsMenu exception: " + exc.message.toString() )
        }
        return true
    }

    private fun updateMenuItems() {
        if(floatingWidgetItem!=null) {
            floatingWidgetItem!!.isVisible = Settings.canDrawOverlays(this)
            if(floatingWidgetItem!!.isVisible) {
                if(sharedPref.getBoolean(Constants.SHARED_PREF_FLOATING_WIDGET, false)) {
                    floatingWidgetItem!!.title = resources.getString(CR.string.pref_floating_widget) + " " + resources.getString(CR.string.enabled)
                } else {
                    floatingWidgetItem!!.title = resources.getString(CR.string.pref_floating_widget) + " " + resources.getString(CR.string.disabled)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            Log.v(LOG_ID, "onOptionsItemSelected for " + item.itemId.toString())
            when(item.itemId) {
                R.id.action_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra(SettingsActivity.FRAGMENT_EXTRA, SettingsFragmentClass.SETTINGS_FRAGMENT.value)
                    startActivity(intent)
                    return true
                }
                R.id.action_sources -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra(SettingsActivity.FRAGMENT_EXTRA, SettingsFragmentClass.SORUCE_FRAGMENT.value)
                    startActivity(intent)
                    return true
                }
                R.id.action_alarms -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra(SettingsActivity.FRAGMENT_EXTRA, SettingsFragmentClass.ALARM_FRAGMENT.value)
                    startActivity(intent)
                    return true
                }
                R.id.action_help -> {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(resources.getText(CR.string.help_link).toString())
                    )
                    startActivity(browserIntent)
                    return true
                }
                R.id.action_support -> {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(resources.getText(CR.string.support_link).toString())
                    )
                    startActivity(browserIntent)
                    return true
                }
                R.id.action_contact -> {
                    val mailIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                        "mailto","GlucoDataHandler@michel-inside.de", null))
                    mailIntent.putExtra(Intent.EXTRA_SUBJECT, "GlucoDataHander v" + BuildConfig.VERSION_NAME)
                    mailIntent.putExtra(Intent.EXTRA_TEXT, "")
                    if (mailIntent.resolveActivity(packageManager) != null) {
                        startActivity(mailIntent)
                    }
                    return true
                }
                R.id.action_google_groups -> {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(resources.getText(CR.string.google_gdh_group_url).toString())
                    )
                    startActivity(browserIntent)
                    return true
                }
                R.id.action_facebook -> {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(resources.getText(CR.string.facebook_gdh_group_url).toString())
                    )
                    startActivity(browserIntent)
                    return true
                }
                R.id.group_snooze_title -> {
                    Log.v(LOG_ID, "snooze group selected - snoozeActive=${AlarmHandler.isSnoozeActive}")
                    val snoozeStop: MenuItem = optionsMenu.findItem(R.id.action_stop_snooze)
                    snoozeStop.isVisible = AlarmHandler.isSnoozeActive
                }
                R.id.action_stop_snooze -> {
                    AlarmHandler.setSnooze(0L)
                    return true
                }
                R.id.action_snooze_30 -> {
                    if(BuildConfig.DEBUG) {
                        AlarmHandler.setSnooze(1L)
                    } else {
                        AlarmHandler.setSnooze(30L)
                    }
                    return true
                }
                R.id.action_snooze_60 -> {
                    AlarmHandler.setSnooze(60L)
                    return true
                }
                R.id.action_snooze_90 -> {
                    AlarmHandler.setSnooze(90L)
                    return true
                }
                R.id.action_snooze_120 -> {
                    AlarmHandler.setSnooze(120L)
                    return true
                }
                R.id.action_alarm_toggle -> {
                    toggleAlarm()
                    return true
                }
                R.id.action_floating_widget_toggle -> {
                    Log.v(LOG_ID, "Floating widget toggle")
                    with(sharedPref.edit()) {
                        putBoolean(Constants.SHARED_PREF_FLOATING_WIDGET, !sharedPref.getBoolean(Constants.SHARED_PREF_FLOATING_WIDGET, false))
                        apply()
                    }
                    updateMenuItems()
                }
                else -> return super.onOptionsItemSelected(item)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onOptionsItemSelected exception: " + exc.message.toString() )
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleAlarm() {
        try {
            doNotUpdate = true
            val state = AlarmNotification.getAlarmState(this)
            if(AlarmNotification.channelActive(this)) {
                Log.v(LOG_ID, "toggleAlarm called for state $state")
                when (state) {
                    AlarmState.SNOOZE -> AlarmHandler.setSnooze(0)  // disable snooze
                    AlarmState.DISABLED -> {
                        with(sharedPref.edit()) {
                            putBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, true)
                            apply()
                        }
                    }
                    AlarmState.INACTIVE,
                    AlarmState.ACTIVE -> {
                        with(sharedPref.edit()) {
                            putBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, false)
                            apply()
                        }
                    }
                    AlarmState.TEMP_DISABLED -> {
                        AlarmHandler.disableInactiveTime()
                    }
                }
            } else {
                with(sharedPref.edit()) {
                    putBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, false)
                    apply()
                }
                Dialogs.showOkDialog(this, CR.string.permission_alarm_notification_title, CR.string.permission_alarm_notification_message) { _, _ ->
                    val intent: Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, this.packageName)
                    startActivity(intent)
                }
            }
            updateAlarmIcon()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updateAlarmIcon exception: " + exc.message.toString() )
        }
        doNotUpdate = false
    }

    private fun updateAlarmIcon() {
        try {
            if(!AlarmNotification.channelActive(this)) {
                with(sharedPref.edit()) {
                    putBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, false)
                    apply()
                }
            }
            val state = AlarmNotification.getAlarmState(this)
            Log.v(LOG_ID, "updateAlarmIcon called for state $state")
            if(alarmIcon != null) {
                alarmIcon!!.isEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_ENABLE_ALARM_ICON_TOGGLE, true)
                alarmIcon!!.icon = ContextCompat.getDrawable(this, state.icon)
                alarmIcon!!.title = resources.getString(CR.string.alarm_toggle_state,
                    when(state) {
                        AlarmState.SNOOZE -> resources.getString(state.descr, AlarmHandler.snoozeShortTimestamp)
                        AlarmState.TEMP_DISABLED -> resources.getString(state.descr, AlarmHandler.inactiveEndTimestamp)
                        else -> resources.getString(state.descr)
                    })
            }
            if(snoozeMenu != null) {
                snoozeMenu!!.isVisible = (state == AlarmState.ACTIVE || state == AlarmState.SNOOZE)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updateAlarmIcon exception: " + exc.message.toString() )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun update() {
        try {
            Log.v(LOG_ID, "update values - doNotUpdate=$doNotUpdate mainPageInitialized=$mainPageInitialized")
            if(doNotUpdate || !mainPageInitialized)
                return
            updateLandscapeItems()
            txtBgValue.text = ReceiveData.getGlucoseAsString()
            txtBgValue.setTextColor(ReceiveData.getGlucoseColor())
            if (ReceiveData.isObsoleteShort() && !ReceiveData.isObsoleteLong()) {
                txtBgValue.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                txtBgValue.paintFlags = 0
            }
            // ML prediction arrow at top (main arrow)
            val showModelArrow = PredictionData.predictionsEnabled && 
                                 sharedPref.getBoolean(Constants.SHARED_PREF_SHOW_MODEL_ARROW, true)
            if (showModelArrow) {
                val modelIcon = BitmapUtils.getModelRateAsIcon("main_model_trend", withShadow = true)
                if (modelIcon != null) {
                    modelViewIcon?.setImageIcon(modelIcon)
                    modelViewIcon?.contentDescription = "Model: ${PredictionData.modelTrendLabel}. Tap to see Dexcom arrow."
                    modelViewIcon?.visibility = View.VISIBLE
                    
                    // Add click listener to show Dexcom arrow tooltip
                    modelViewIcon?.setOnClickListener { view ->
                        showDexcomArrowTooltip(view)
                    }
                } else {
                    modelViewIcon?.visibility = View.GONE
                }
            } else {
                modelViewIcon?.visibility = View.GONE
            }

            timeText.text = "🕒 ${ReceiveData.getElapsedRelativeTimeAsString(this)}"
            timeText.contentDescription = ReceiveData.getElapsedRelativeTimeAsString(this, true)
            deltaText.text = "Δ ${ReceiveData.getDeltaAsString()}"
            iobText.text = "💉 " + ReceiveData.getIobAsString()
            iobText.contentDescription = getString(CR.string.info_label_iob) + " " + ReceiveData.getIobAsString()
            iobText.visibility = if (ReceiveData.isIobCobObsolete()) View.GONE else View.VISIBLE
            if(ReceiveData.cob.isNaN())
                cobText.text = ""
            else
                cobText.text = "🍔 " + ReceiveData.getCobAsString()
            cobText.contentDescription = getString(CR.string.info_label_cob) + " " + ReceiveData.getCobAsString()
            cobText.visibility = iobText.visibility
            iobCobLayout.visibility = iobText.visibility

            noDataLayout.visibility = if(ReceiveData.time>0) View.GONE else View.VISIBLE

            //chartHandler.update()
            updateNotesTable()
            updateStatisticsTable()
            updateTrendProbabilityChart()

            updateAlarmIcon()
            updateMenuItems()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "update exception: " + exc.message.toString() )
        }
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        Log.v(LOG_ID, "OnNotifyData called for $dataSource")
        update()
    }

    private fun updateNotesTable() {
        if (!mainPageInitialized || !::tableNotes.isInitialized) return
        tableNotes.removeViews(1, maxOf(0, tableNotes.childCount - 1))
        if (!Channels.notificationChannelActive(this, ChannelType.MOBILE_FOREGROUND)) {
            val onClickListener = OnClickListener {
                try {
                    if (!Channels.notificationChannelActive(this, ChannelType.MOBILE_FOREGROUND)) {
                        requestNotificationPermission = true
                        val intent: Intent = if (Channels.notificationActive(this)) { // only the channel is inactive!
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, this.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, ChannelType.MOBILE_FOREGROUND.channelId)
                        } else {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, this.packageName)
                        }
                        startActivity(intent)
                    } else {
                        updateNotesTable()
                    }
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "updateNotesTable exception: " + exc.message.toString() )
                    if(requestPermission()) {
                        GlucoDataServiceMobile.start(this)
                        updateNotesTable()
                    }
                }
            }
            tableNotes.addView(createRow(CR.string.activity_main_notification_permission, onClickListener))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Utils.canScheduleExactAlarms(this)) {
            Log.w(LOG_ID, "Schedule exact alarm is not active!!!")
            val onClickListener = OnClickListener {
                try {
                    startActivity(
                        Intent(
                            ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "Schedule exact alarm exception: " + exc.message.toString() )
                }
            }
            tableNotes.addView(createRow(CR.string.activity_main_schedule_exact_alarm, onClickListener))
        }
        if (Utils.isHighContrastTextEnabled(this)) {
            Log.w(LOG_ID, "High contrast is active")
            val onClickListener = OnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "High contrast alarm exception: " + exc.message.toString() )
                }
            }
            tableNotes.addView(createRow(CR.string.activity_main_high_contrast_enabled, onClickListener))
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.w(LOG_ID, "Battery optimization is active")
            val onClickListener = OnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "Battery optimization exception: " + exc.message.toString() )
                }
            }
            tableNotes.addView(createRow(CR.string.activity_main_battery_optimization_disabled, onClickListener))
        }
        checkTableVisibility(tableNotes)
    }

    private fun updateStatisticsTable() {
        if (!statisticsPageInitialized || !::tableStatistics.isInitialized) return
        tableStatistics.removeViews(1, maxOf(0, tableStatistics.childCount - 1))
        val standardStats = sharedPref.getBoolean(Constants.SHARED_PREF_STANDARD_STATISTICS, false)
        GlucoseStatistics.update(standardStats)
        if(GlucoseStatistics.hasStatistics) {
            val statData = when {
                btnStat1d.isChecked -> GlucoseStatistics.statData1d
                btnStat14d?.isChecked == true -> GlucoseStatistics.statData14d
                else -> GlucoseStatistics.statData7d
            }
            Log.d(LOG_ID, "Create statistics for ${statData.days}d with ${statData.count} data points - hasData: ${statData.hasData}")
            val name = resources.getString(CR.string.info_label_average)
            tableStatistics.addView(createRow(name, GlucoDataUtils.getDisplayGlucoseAsString(statData.averageGlucose, true)))
            if(statData.hasData) {
                tableStatistics.addView(createProgressBarRow(GlucoseStatistics.getStatisticsTitle(this, AlarmType.VERY_HIGH, standardStats), statData.percentVeryHigh, ReceiveData.getAlarmTypeColor(AlarmType.VERY_HIGH)))
                tableStatistics.addView(createProgressBarRow(GlucoseStatistics.getStatisticsTitle(this, AlarmType.HIGH, standardStats), statData.percentHigh, ReceiveData.getAlarmTypeColor(AlarmType.HIGH)))
                tableStatistics.addView(createProgressBarRow(GlucoseStatistics.getStatisticsTitle(this, AlarmType.OK, standardStats), statData.percentInRange, ReceiveData.getAlarmTypeColor(AlarmType.OK)))
                tableStatistics.addView(createProgressBarRow(GlucoseStatistics.getStatisticsTitle(this, AlarmType.LOW, standardStats), statData.percentLow, ReceiveData.getAlarmTypeColor(AlarmType.LOW)))
                tableStatistics.addView(createProgressBarRow(GlucoseStatistics.getStatisticsTitle(this, AlarmType.VERY_LOW, standardStats), statData.percentVeryLow, ReceiveData.getAlarmTypeColor(AlarmType.VERY_LOW)))
            }
        }
        checkTableVisibility(tableStatistics)
    }

    private fun checkTableVisibility(table: TableLayout) {
        table.visibility = if(table.childCount <= 1) View.GONE else View.VISIBLE
    }

    private fun createColumn(text: String, end: Boolean, onClickListener: OnClickListener? = null, initWeight: Float = 1F) : TextView {
        val textView = TextView(this)
        textView.layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, initWeight)
        textView.text = text
        textView.textSize = 18F
        if (end)
            textView.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        else
            textView.gravity = Gravity.CENTER_VERTICAL
        if(onClickListener != null)
            textView.setOnClickListener(onClickListener)
        return textView
    }

    private fun createRow(keyResId: Int, value: String, onClickListener: OnClickListener? = null) : TableRow {
        return createRow(resources.getString(keyResId), value, onClickListener)
    }

    private fun createRow(key: String, value: String, onClickListener: OnClickListener? = null) : TableRow {
        val row = TableRow(this)
        row.weightSum = 2f
        //row.setBackgroundColor(resources.getColor(R.color.table_row))
        row.setPadding(Utils.dpToPx(5F, this))
        row.addView(createColumn(key, false, onClickListener))
        row.addView(createColumn(value, true, onClickListener))
        return row
    }

    private fun createProgressBar(value: Int, max: Int, color: Int, description: String) : ProgressBar {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 4F)
        progressBar.progress = value
        progressBar.max = max
        progressBar.scaleY = 3F
        progressBar.contentDescription = description
        progressBar.setProgressTintList(ColorStateList.valueOf(color))
        return progressBar
    }

    private fun createProgressBarRow(keyResId: Int, percentage: Float, color: Int, valueView: TextView? = null) : TableRow {
        return createProgressBarRow(resources.getString(keyResId), percentage, color, valueView)
    }
    private fun createProgressBarRow(key: String, percentage: Float, color: Int, valueView: TextView? = null) : TableRow {
        val row = TableRow(this)
        row.weightSum = 11f
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Utils.dpToPx(5F, this))
        row.addView(createColumn(key, false, null, 4F))
        row.addView(createProgressBar(percentage.toInt(), 100, color, key))
        if(valueView == null)
            row.addView(createColumn("${DecimalFormat("#.#").format(percentage)}%", true, null, 3F))
        else {
            val params = valueView.layoutParams as TableRow.LayoutParams
            params.weight = 3F
            valueView.layoutParams = params
            row.addView(valueView)
        }
        return row
    }

    private fun createRow(valueResId: Int, onClickListener: OnClickListener? = null) : TableRow {
        return createRow(resources.getString(valueResId), onClickListener)
    }

    private fun createRow(value: String, onClickListener: OnClickListener? = null) : TableRow {
        val row = TableRow(this)
        row.weightSum = 1f
        //row.setBackgroundColor(resources.getColor(R.color.table_row))
        row.setPadding(Utils.dpToPx(5F, this))
        row.addView(createColumn(value, false, onClickListener))
        return row
    }

    private fun checkUncaughtException() {
        Log.i(LOG_ID, "Check uncaught exception exists: ${sharedPref.getBoolean(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_DETECT, false)} - " +
                "last occured at ${DateFormat.getDateTimeInstance().format(Date(sharedPref.getLong(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_TIME, 0)))}")
        if(sharedPref.getBoolean(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_DETECT, false)) {
            val excMsg = sharedPref.getString(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_MESSAGE, "") ?: ""
            val time = sharedPref.getLong(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_TIME, 0)
            Log.e(LOG_ID, "Uncaught exception detected at ${DateFormat.getDateTimeInstance().format(Date(time))}: $excMsg")
            with(sharedPref.edit()) {
                putBoolean(Constants.SHARED_PREF_UNCAUGHT_EXCEPTION_DETECT, false)
                apply()
            }

            if(time > 0 && (System.currentTimeMillis()- ReceiveData.time) < (60*60 * 1000) && !GdhUncaughtExecptionHandler.isOutOfMemoryException(excMsg)) {
                if (GdhUncaughtExecptionHandler.isForegroundserviceNotificationException(excMsg)) {
                    Dialogs.showOkDialog(this, CR.string.app_crash_title, CR.string.app_crash_bad_notification, null)
                } else {
                    Dialogs.showOkDialog(this, CR.string.app_crash_title, CR.string.app_crash_message, null)
                }
            }
        }
    }

    private fun toggleFullscreenLandMode() {
        try {
            Log.v(LOG_ID, "toggleFullscreenLandMode actionbar: ${supportActionBar?.isShowing} - insets $systemBars")
            if(expandCollapseView != null) {
                val infoView = findViewById<ScrollView>(R.id.infoView)
                val borderMiddle = findViewById<RelativeLayout>(R.id.borderMiddle)
                val borderLeft = findViewById<RelativeLayout>(R.id.borderLeft)
                val borderRight = findViewById<RelativeLayout>(R.id.borderRight)
                val view = findViewById<LinearLayout>(R.id.root)
                val fullscreen: Boolean
                if(supportActionBar?.isShowing == true) {
                    fullscreen = true
                    supportActionBar?.hide()
                    infoView?.visibility = View.GONE
                    borderMiddle?.visibility = View.GONE
                    borderLeft?.visibility = View.GONE
                    borderRight?.visibility = View.GONE
                    expandCollapseView!!.setImageDrawable(ContextCompat.getDrawable(this, CR.drawable.icon_collapse))
                    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM && systemBars!=null)
                        view?.setPadding(systemBars!!.left, systemBars!!.top/2, systemBars!!.right, systemBars!!.bottom)
                } else {
                    fullscreen = false
                    supportActionBar?.show()
                    infoView?.visibility = View.VISIBLE
                    borderMiddle?.visibility = View.VISIBLE
                    borderLeft?.visibility = View.VISIBLE
                    borderRight?.visibility = View.VISIBLE
                    expandCollapseView!!.setImageDrawable(ContextCompat.getDrawable(this, CR.drawable.icon_expand))
                    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM && systemBars!=null)
                        view?.setPadding(systemBars!!.left, systemBars!!.top, systemBars!!.right, systemBars!!.bottom)
                }
                if (::chart.isInitialized) {
                    chart.moveViewToX(chart.xChartMax)
                }
                with(sharedPref.edit()) {
                    Log.d(LOG_ID, "save fullscreen mode: $fullscreen")
                    putBoolean(Constants.SHARED_PREF_FULLSCREEN_LANDSCAPE, fullscreen)
                    apply()
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "toggleFullscreenLandMode exception: " + exc.message.toString() )
        }
    }

    private fun initTrendProbabilityChart(view: View) {
        try {
            // Initialize horizon buttons
            val btn5 = view.findViewById<Button>(R.id.btnHorizon5)
            val btn10 = view.findViewById<Button>(R.id.btnHorizon10)
            val btn15 = view.findViewById<Button>(R.id.btnHorizon15)
            val btn20 = view.findViewById<Button>(R.id.btnHorizon20)
            val btn25 = view.findViewById<Button>(R.id.btnHorizon25)
            val btn30 = view.findViewById<Button>(R.id.btnHorizon30)
            
            horizonButtons = mapOf(
                5 to btn5,
                10 to btn10,
                15 to btn15,
                20 to btn20,
                25 to btn25,
                30 to btn30
            )
            
            // Set click listeners
            for ((horizon, button) in horizonButtons) {
                button.setOnClickListener {
                    selectHorizon(horizon)
                }
            }
            
            // Initialize the TCN prediction service
            TcnPredictionService.init(this)
            
            // Select default horizon (this will call updateTrendProbabilityChart)
            selectHorizon(15)
            
            // Always show the section - we have test data as fallback
            trendDivider?.visibility = View.VISIBLE
            trendProbabilityLayout?.visibility = View.VISIBLE
        } catch (exc: Exception) {
            Log.e(LOG_ID, "initTrendProbabilityChart exception: ${exc.message}")
        }
    }
    
    private fun selectHorizon(horizon: Int) {
        try {
            selectedHorizon = horizon
            
            // Update button styles
            for ((h, button) in horizonButtons) {
                if (h == horizon) {
                    // Selected style - blue bubble background with white text
                    button.background = ContextCompat.getDrawable(this, R.drawable.button_selected)
                    button.setTextColor(android.graphics.Color.WHITE)
                    button.alpha = 1.0f
                } else {
                    // Unselected style - grey outline with blue text
                    button.background = ContextCompat.getDrawable(this, R.drawable.button_outline_grey)
                    button.backgroundTintList = null
                    button.setTextColor(android.graphics.Color.rgb(30, 144, 255))
                    button.alpha = 1.0f
                }
            }
            
            // Update the trend probability chart
            updateTrendProbabilityChart()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "selectHorizon exception: ${exc.message}")
        }
    }
    
    private fun updateTrendProbabilityChart() {
        try {
            if (trendProbabilityChart == null) {
                return
            }
            
            // Use GlucosePredictionService to respect model selection (baseline vs E2)
            var probabilities = GlucosePredictionService.getTrendProbabilities(this, selectedHorizon)
            val isTestData: Boolean
            
            // If no real data, use test probabilities to verify chart works
            if (probabilities == null) {
                Log.w(LOG_ID, "No real predictions - using TEST data for horizon $selectedHorizon")
                probabilities = TcnPredictionService.getTestProbabilities(selectedHorizon)
                isTestData = true
            } else {
                isTestData = false
            }
            
            val probSum = probabilities.probabilities.values.sum()
            Log.i(LOG_ID, "Trend probs for ${selectedHorizon}min: " +
                    "⬇⬇=${(probabilities.getProbability(TrendCategory.DOUBLE_DOWN)*100).toInt()}%, " +
                    "↓=${(probabilities.getProbability(TrendCategory.DOWN)*100).toInt()}%, " +
                    "↘=${(probabilities.getProbability(TrendCategory.SLIGHTLY_DOWN)*100).toInt()}%, " +
                    "→=${(probabilities.getProbability(TrendCategory.FLAT)*100).toInt()}%, " +
                    "↗=${(probabilities.getProbability(TrendCategory.SLIGHTLY_UP)*100).toInt()}%, " +
                    "↑=${(probabilities.getProbability(TrendCategory.UP)*100).toInt()}%, " +
                    "⬆⬆=${(probabilities.getProbability(TrendCategory.DOUBLE_UP)*100).toInt()}% " +
                    "(sum=$probSum, test=$isTestData)")
            
            // Update the chart with current glucose for projected values
            trendProbabilityChart?.setTrendProbabilities(probabilities, ReceiveData.rawValue)
            
            // Update zone probability pills
            updateZoneProbabilities(isTestData)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updateTrendProbabilityChart exception: ${exc.message}")
        }
    }
    
    private fun updateZoneProbabilities(isTestData: Boolean) {
        try {
            val container = probabilityPillsContainer ?: return
            container.removeAllViews()
            
            val currentGlucose = ReceiveData.rawValue
            if (currentGlucose <= 0) {
                return
            }
            
            // Use GlucosePredictionService to respect model selection (baseline vs E2)
            val zoneProbabilities = if (isTestData) {
                TcnPredictionService.getTestZoneProbabilities(currentGlucose, selectedHorizon)
            } else {
                GlucosePredictionService.getZoneProbabilities(this, selectedHorizon, currentGlucose)
                    ?: TcnPredictionService.getTestZoneProbabilities(currentGlucose, selectedHorizon)
            }
            
            // Get individual zone probabilities for tooltips
            val veryLowProb = zoneProbabilities.getProbability(GlucoseZone.VERY_LOW)
            val lowOnlyProb = zoneProbabilities.getProbability(GlucoseZone.LOW)
            val inRangeProb = zoneProbabilities.getProbability(GlucoseZone.IN_RANGE)
            val highOnlyProb = zoneProbabilities.getProbability(GlucoseZone.HIGH)
            val veryHighProb = zoneProbabilities.getProbability(GlucoseZone.VERY_HIGH)
            
            // Combine zones: Very Low + Low → "Low", Very High + High → "High"
            val lowProb = veryLowProb + lowOnlyProb
            val highProb = highOnlyProb + veryHighProb
            
            // Always show all 3 pills: Low, In Range, High
            val combinedZones = listOf(
                Triple("Low", lowProb, GlucoseZone.LOW.color),
                Triple("In Range", inRangeProb, GlucoseZone.IN_RANGE.color),
                Triple("High", highProb, GlucoseZone.HIGH.color)
            )
            
            // Pill dimensions in dp
            val density = resources.displayMetrics.density
            val pillWidth = (100 * density).toInt()
            val pillHeight = (36 * density).toInt()
            val pillMargin = (4 * density).toInt()
            
            for ((label, probability, color) in combinedZones) {
                val pillView = ProbabilityPillView(this)
                pillView.setData(label, probability, color)
                
                val layoutParams = LinearLayout.LayoutParams(pillWidth, pillHeight)
                layoutParams.setMargins(pillMargin, 0, pillMargin, 0)
                pillView.layoutParams = layoutParams
                
                // Add click handlers for Low and High pills to show breakdown tooltip
                when (label) {
                    "Low" -> {
                        pillView.isClickable = true
                        pillView.setOnClickListener { view ->
                            showZoneBreakdownTooltip(view, isLow = true, lowOnlyProb, veryLowProb)
                        }
                    }
                    "High" -> {
                        pillView.isClickable = true
                        pillView.setOnClickListener { view ->
                            showZoneBreakdownTooltip(view, isLow = false, highOnlyProb, veryHighProb)
                        }
                    }
                }
                
                container.addView(pillView)
            }
            
            Log.d(LOG_ID, "Updated zone pills: Low=${(lowProb*100).toInt()}%, InRange=${(inRangeProb*100).toInt()}%, High=${(highProb*100).toInt()}%")
            
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updateZoneProbabilities exception: ${exc.message}")
        }
    }
    
    /**
     * Shows a tooltip with the breakdown of Low/Very Low or High/Very High probabilities.
     * Auto-dismisses after 3 seconds.
     */
    private fun showZoneBreakdownTooltip(
        anchorView: View,
        isLow: Boolean,
        mainProb: Float,
        veryProb: Float
    ) {
        try {
            val (mainLabel, mainThreshold, veryLabel, veryThreshold) = if (isLow) {
                listOf("Low", "55-69", "Very Low", "<55")
            } else {
                listOf("High", "181-250", "Very High", ">250")
            }
            
            // Create vertical layout for the tooltip
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(android.graphics.Color.argb(230, 40, 40, 40))
            }
            
            // Format probability display
            fun formatProb(prob: Float): String {
                return when {
                    prob < 0.01f -> "<1%"
                    else -> "${(prob * 100).toInt()}%"
                }
            }
            
            // Main zone row (e.g., "High (>180): 25%")
            val mainRow = TextView(this).apply {
                text = "$mainLabel ($mainThreshold): ${formatProb(mainProb)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
            }
            container.addView(mainRow)
            
            // Very zone row (e.g., "Very High (>250): 10%")
            val veryRow = TextView(this).apply {
                text = "$veryLabel ($veryThreshold): ${formatProb(veryProb)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                setPadding(0, 8, 0, 0)
            }
            container.addView(veryRow)
            
            // Create and show popup
            val popup = android.widget.PopupWindow(
                container,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 10f
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            
            // Position above the anchor view (pills are at bottom, tooltip should appear above)
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            
            // Measure the popup content to position it properly
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupHeight = container.measuredHeight
            val popupWidth = container.measuredWidth
            
            // Center horizontally over the anchor, position above it
            val xPos = location[0] + (anchorView.width / 2) - (popupWidth / 2)
            val yPos = location[1] - popupHeight - 10
            
            popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, xPos, yPos)
            
            // Auto-dismiss after 3 seconds
            anchorView.postDelayed({ 
                if (popup.isShowing) popup.dismiss() 
            }, 3000)
            
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showZoneBreakdownTooltip exception: ${exc.message}")
        }
    }
    
    private fun showDexcomArrowTooltip(anchorView: View) {
        try {
            val dexcomIcon = BitmapUtils.getRateAsIcon("tooltip_dexcom_trend", withShadow = false)
            
            // Create a horizontal layout with label and arrow
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(android.graphics.Color.argb(230, 40, 40, 40))
            }
            
            val label = TextView(this).apply {
                text = "Dexcom Arrow:"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
            }
            container.addView(label)
            
            val arrowImage = ImageView(this).apply {
                if (dexcomIcon != null) {
                    setImageIcon(dexcomIcon)
                }
                layoutParams = LinearLayout.LayoutParams(72, 72).apply {
                    marginStart = 16
                }
            }
            container.addView(arrowImage)
            
            // Create and show popup
            val popup = android.widget.PopupWindow(
                container,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 10f
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            
            // Position below the anchor view
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, location[0] - 50, location[1] + anchorView.height + 10)
            
            // Auto-dismiss after 2 seconds
            anchorView.postDelayed({ 
                if (popup.isShowing) popup.dismiss() 
            }, 2000)
            
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showDexcomArrowTooltip exception: ${exc.message}")
        }
    }

    private fun updateLandscapeItems() {
        try {
            if(layoutWithGraph!=null && layoutWithoutGraph!=null) {
                // depending on landscape mode and if graph is visible change items
                Log.v(LOG_ID, "updateLandscapeItems: landscape: ${BitmapUtils.isLandscapeOrientation(this)} - chart: ${chartCreator.enabled} ")
                if(chartCreator.enabled) {
                    layoutWithGraph?.visibility = View.VISIBLE
                    layoutWithoutGraph?.visibility = View.GONE

                    txtBgValue = findViewById(R.id.txtBgValue)
                    viewIcon = findViewById(R.id.viewIcon)
                    modelViewIcon = findViewById(R.id.modelViewIcon)
                    timeText = findViewById(R.id.timeText)
                    deltaText = findViewById(R.id.deltaText)
                    iobText = findViewById(R.id.iobText)
                    cobText = findViewById(R.id.cobText)
                    iobCobLayout = findViewById(R.id.layout_iob_cob)
                } else {
                    layoutWithGraph?.visibility = View.GONE
                    layoutWithoutGraph?.visibility = View.VISIBLE
                    txtBgValue = findViewById(R.id.txtBgValue2)
                    viewIcon = findViewById(R.id.viewIcon2)
                    modelViewIcon = findViewById(R.id.modelViewIcon2)
                    timeText = findViewById(R.id.timeText2)
                    deltaText = findViewById(R.id.deltaText2)
                    iobText = findViewById(R.id.iobText2)
                    cobText = findViewById(R.id.cobText2)
                    iobCobLayout = findViewById(R.id.layout_iob_cob2)
                    if(sharedPref.getBoolean(Constants.SHARED_PREF_FULLSCREEN_LANDSCAPE,true)) {
                        toggleFullscreenLandMode()
                    }
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updateLandscapeItems exception: " + exc.message.toString() )
        }
    }
}