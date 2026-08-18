package com.simone.jarvismobile.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.R
import com.simone.jarvismobile.automation.rule.AutomationExecutor
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.automation.Trigger
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.proactive.ProactiveManager
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * Opt-in foreground service that lets device-event automations fire with the app
 * closed. Android delivers airplane/Wi-Fi/headset/unlock broadcasts only to a
 * live process, so a runtime-registered receiver in this service is the honest,
 * visible way to observe them — no hidden background listener. It does no polling
 * and holds no wake lock: it sits idle until the system pushes an event, so the
 * battery cost is essentially the resident process plus the minimal notification.
 *
 * Location-based triggers (Wi-Fi SSID, home geofence) are deliberately NOT handled
 * here: they need the Location permission and land in a later, separate opt-in.
 */
@AndroidEntryPoint
class AutomationEventService : Service() {

    @Inject lateinit var repository: AutomationRepository
    @Inject lateinit var runner: AutomationRunner
    @Inject lateinit var proactive: ProactiveManager
    @Inject lateinit var newEngineExecutor: AutomationExecutor
    @Inject lateinit var newEngineContext: ContextEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastFired = HashMap<String, Long>()
    private var connectivity: ConnectivityManager.NetworkCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The specialUse type only exists on Android 14+ (minSdk here is 31); on
        // older versions the untyped startForeground uses the manifest declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        // STICKY so the OS brings the observer back if it reclaims memory.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        connectivity?.let { cb -> runCatching { cm().unregisterNetworkCallback(cb) } }
        scope.cancel()
        super.onDestroy()
    }

    // --- event handling -----------------------------------------------------

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> onUnlock()
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                fire { it is Trigger.AirplaneMode && it.on == on }
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                when (state) {
                    WifiManager.WIFI_STATE_ENABLED -> fire { it is Trigger.WifiPower && it.on }
                    WifiManager.WIFI_STATE_DISABLED -> fire { it is Trigger.WifiPower && !it.on }
                }
            }
            Intent.ACTION_HEADSET_PLUG -> {
                if (intent.getIntExtra("state", 0) == 1) fire { it is Trigger.HeadphonesConnected }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val name = deviceName(intent)
                fire { it is Trigger.BluetoothConnected && matchesDevice(it.device, name) }
            }
        }
    }

    private fun onUnlock() {
        fire { it is Trigger.ScreenUnlocked }
        // Morning unlock: the first unlock of the day at/after the set time.
        val now = LocalTime.now()
        val today = LocalDate.now().toString()
        scope.launch {
            val rules = runCatching { repository.reload() }.getOrDefault(emptyList())
            rules.filter { it.enabled }.forEach { rule ->
                val trigger = rule.trigger as? Trigger.MorningUnlock ?: return@forEach
                if (now.isBefore(trigger.after)) return@forEach
                if (prefs().getString(morningKey(rule.id), null) == today) return@forEach
                prefs().edit().putString(morningKey(rule.id), today).apply()
                runCatching { runner.run(rule) }
            }
        }
        // The adaptive morning briefing: evaluated at the real unlock, not a
        // periodic guess. ProactiveGovernor's own per-day dedup is the "only once
        // a day" flag — nothing extra to track here.
        scope.launch {
            runCatching { proactive.evaluateOnUnlock(LocalDateTime.now()) }
                .onFailure { Log.w("JarvisAutomation", "proactive_unlock_failed ${it.javaClass.simpleName}") }
        }
        // Same idea for the new (6j) rule-builder engine's FIRST_UNLOCK_OF_DAY
        // trigger. Unlike the block above, no manual day key is tracked here: the
        // gate's own cooldownSeconds is what stands in for "once a day" (the
        // builder sets a ~20h minimum gap for this trigger kind, see RuleDraft),
        // consistent with how every other trigger in this file is dispatched —
        // unconditionally on the raw event, leaving all suppression to the gate.
        // A precise "not before 7am" is a job for the rule's own "Dalle/Alle"
        // condition, already generic, rather than special-cased matching here.
        scope.launch {
            val nowDt = LocalDateTime.now()
            val evalContext = runCatching { newEngineContext.evaluationContext(now = nowDt) }.getOrNull()
                ?: return@launch
            val event = TriggerEvent(type = TriggerRegistry.FIRST_UNLOCK_OF_DAY, at = nowDt)
            runCatching { newEngineExecutor.onTrigger(event, evalContext) }
                .onFailure { Log.w("JarvisAutomation", "first_unlock_dispatch_failed ${it.javaClass.simpleName}") }
        }
    }

    /** Runs every enabled rule whose trigger matches [predicate], with a short debounce. */
    private fun fire(predicate: (Trigger) -> Boolean) {
        scope.launch {
            val rules = runCatching { repository.reload() }.getOrDefault(emptyList())
            rules.filter { it.enabled && predicate(it.trigger) }.forEach { rule ->
                val now = System.currentTimeMillis()
                val last = lastFired[rule.id] ?: 0L
                if (now - last < DEBOUNCE_MS) return@forEach // one delivery, not the burst
                lastFired[rule.id] = now
                runCatching { runner.run(rule) }
            }
        }
    }

    private fun matchesDevice(wanted: String, connected: String?): Boolean {
        if (connected.isNullOrBlank()) return false
        if (wanted.isBlank()) return true
        return connected.contains(wanted, ignoreCase = true) || wanted.contains(connected, ignoreCase = true)
    }

    private fun deviceName(intent: Intent): String? = runCatching {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        device?.name
    }.getOrNull()

    // --- registration -------------------------------------------------------

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Mobile data: no clean "user toggled data" broadcast exists, so this
        // reflects cellular-internet availability — best-effort, and honest about it.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { fire { it is Trigger.MobileData && it.on } }
            override fun onLost(network: Network) { fire { it is Trigger.MobileData && !it.on } }
        }
        connectivity = callback
        runCatching { cm().registerNetworkCallback(request, callback) }
    }

    private fun cm() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun morningKey(id: String) = "morning_$id"

    // --- notification -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.automation_service_channel_name),
            NotificationManager.IMPORTANCE_MIN, // silent, collapsed, no status-bar icon
        ).apply {
            description = getString(R.string.automation_service_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.automation_service_title))
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android requires a foreground service to keep showing this
            // notification for as long as the service runs — it cannot be
            // suppressed while background event automations stay enabled. What
            // CAN go away is its lock-screen appearance, which is what actually
            // bothered the user: SECRET keeps it out of the lock screen entirely
            // (it still shows, collapsed at IMPORTANCE_MIN, once unlocked).
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_automation_service"
        private const val NOTIFICATION_ID = 1102
        private const val PREFS = "automation_service"
        private const val DEBOUNCE_MS = 3_000L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AutomationEventService::class.java))
            }.onFailure { Log.w("JarvisAutomation", "svc_start_failed ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AutomationEventService::class.java)) }
        }
    }
}
