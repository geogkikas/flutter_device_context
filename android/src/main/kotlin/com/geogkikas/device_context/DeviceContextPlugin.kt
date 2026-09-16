package com.geogkikas.device_context

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class DeviceContextPlugin: FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "max.device.collector/context")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getSensorData") {
            val sensorData = HashMap<String, Any?>()

            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            // 1. Process Synchronous One-Time Reads
            if (call.argument<Boolean>("fetchDeviceInfo") == true) fetchDeviceInfo(sensorData)
            if (call.argument<Boolean>("fetchBasic") == true) fetchBasicInfo(sensorData, batteryIntent, batteryStatus)
            if (call.argument<Boolean>("fetchThermal") == true) fetchThermalInfo(sensorData, batteryIntent)
            if (call.argument<Boolean>("fetchElectrical") == true) fetchElectricalInfo(sensorData, batteryIntent, batteryManager, batteryStatus)
            if (call.argument<Boolean>("fetchHealth") == true) fetchHealthInfo(sensorData, batteryIntent, batteryManager)
            if (call.argument<Boolean>("fetchLocation") == true) fetchLocationInfo(sensorData)

            // 2. Extract Sampling Parameters
            val windowSeconds = call.argument<Int>("samplingWindowSeconds") ?: 0
            val hz = call.argument<Int>("samplingHz") ?: 10

            val fetchMeanEnv = call.argument<Boolean>("fetchMeanEnvironment") == true
            val fetchMeanMotion = call.argument<Boolean>("fetchMeanMotion") == true
            val fetchMeanElec = call.argument<Boolean>("fetchMeanElectrical") == true

            val fetchInstantEnv = call.argument<Boolean>("fetchEnvironment") == true
            val fetchInstantMotion = call.argument<Boolean>("fetchMotion") == true

            // 3. Route to the correct Async Engine
            if (windowSeconds > 0) {
                // PASS the instant flags (fetchInstantEnv, fetchInstantMotion) into the sampler
                executeContinuousSampling(
                    sensorData, fetchMeanEnv, fetchMeanMotion, fetchMeanElec,
                    fetchInstantEnv, fetchInstantMotion,
                    windowSeconds, hz, batteryManager, batteryStatus, result
                )
            } else if (fetchInstantEnv || fetchInstantMotion) {
                fetchSensorsAsync(sensorData, fetchInstantEnv, fetchInstantMotion, result)
            } else {
                result.success(sensorData)
            }
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    // MARK: - Continuous Sampling Engine

    private fun executeContinuousSampling(
        dataMap: HashMap<String, Any?>,
        fetchMeanEnv: Boolean,
        fetchMeanMotion: Boolean,
        fetchMeanElec: Boolean,
        fetchInstantEnv: Boolean,
        fetchInstantMotion: Boolean,
        windowSeconds: Int,
        hz: Int,
        batteryManager: BatteryManager,
        status: Int,
        result: Result
    ) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var hasResponded = false

        val luxSamples = mutableListOf<Float>()
        val accelXSamples = mutableListOf<Float>()
        val accelYSamples = mutableListOf<Float>()
        val accelZSamples = mutableListOf<Float>()
        val currentSamples = mutableListOf<Int>()

        var movingSampleCount = 0

        val handler = Handler(Looper.getMainLooper())
        val intervalMs = if (hz > 0) 1000L / hz else 100L

        // Poller for battery current (which lacks an event-driven sensor interface)
        val currentPoller = object : Runnable {
            override fun run() {
                if (hasResponded) return
                val currentApiValue = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

                if (currentApiValue != Int.MIN_VALUE && currentApiValue != 0) {
                    currentSamples.add(enforceStandardSign(normalizeToMilliAmps(currentApiValue), status))
                } else {
                    readCurrentSysfs()?.let { currentSamples.add(enforceStandardSign(it, status)) }
                }
                handler.postDelayed(this, intervalMs)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (hasResponded || event == null) return
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        // Capture instant proximity
                        if (!dataMap.containsKey("proximity_cm")) {
                            dataMap["proximity_cm"] = event.values[0]
                            dataMap["isCovered"] = event.values[0] < 2.0
                        }
                    }

                    Sensor.TYPE_LIGHT -> {
                        luxSamples.add(event.values[0])
                        // Capture instant light on first tick
                        if (fetchInstantEnv && !dataMap.containsKey("light_lux")) {
                            dataMap["light_lux"] = event.values[0]
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        accelXSamples.add(x)
                        accelYSamples.add(y)
                        accelZSamples.add(z)

                        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                        if (abs(magnitude - 9.81) > 1.0) movingSampleCount++

                        // Capture instant motion on first tick
                        if (fetchInstantMotion && !dataMap.containsKey("accelX")) {
                            dataMap["accelX"] = x; dataMap["accelY"] = y; dataMap["accelZ"] = z
                            dataMap["posture"] = when {
                                z > 7.5 -> "Face Up"
                                z < -7.5 -> "Face Down"
                                y > 7.5 -> "Portrait"
                                else -> "Other"
                            }
                            dataMap["motionState"] = if (abs(magnitude - 9.81) > 1.0) "Moving" else "Still"
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val sensorDelayMicroseconds = (intervalMs * 1000).toInt()

        if (fetchMeanEnv || fetchInstantEnv) sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensorManager.registerListener(listener, it, sensorDelayMicroseconds) }
        if (fetchMeanMotion || fetchInstantMotion) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(listener, it, sensorDelayMicroseconds) }
        if (fetchInstantMotion) sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensorManager.registerListener(listener, it, sensorDelayMicroseconds) }

        // Time-Bound Shutdown
        if (fetchMeanElec) handler.post(currentPoller)

        handler.postDelayed({
            if (hasResponded) return@postDelayed
            hasResponded = true

            try {
                sensorManager.unregisterListener(listener)
                handler.removeCallbacks(currentPoller)
            } catch (e: Exception) {
                // Ignore unregistration errors
            }

            try {
                if (luxSamples.isNotEmpty()) dataMap["mean_light_lux"] = luxSamples.average()
                if (currentSamples.isNotEmpty()) dataMap["mean_current_mA"] = currentSamples.average()

                if (accelXSamples.isNotEmpty()) {
                    dataMap["mean_accelX"] = accelXSamples.average()
                    dataMap["mean_accelY"] = accelYSamples.average()
                    dataMap["mean_accelZ"] = accelZSamples.average()

                    val percentMoving = movingSampleCount.toDouble() / accelXSamples.size
                    dataMap["mean_motionState"] = if (percentMoving > 0.05) "Moving" else "Still"
                }
            } catch (e: Exception) {
                // Ignore processing errors
            }

            try {
                result.success(dataMap)
            } catch (e: Exception) {
                // Flutter channel closed
            }
        }, windowSeconds * 1000L)
    }

    // MARK: - Synchronous Fetchers

    private fun fetchDeviceInfo(map: HashMap<String, Any?>) {
        map["manufacturer"] = Build.MANUFACTURER
        map["model"] = Build.MODEL
        map["brand"] = Build.BRAND
        map["board"] = Build.BOARD
        map["hardware"] = Build.HARDWARE
        map["osName"] = "Android"
        map["osVersion"] = Build.VERSION.RELEASE
        map["deviceId"] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun fetchBasicInfo(map: HashMap<String, Any?>, intent: Intent?, status: Int) {
        map["status"] = status
        map["batteryLevel"] = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        map["pluggedStatus"] = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
    }

    private fun fetchThermalInfo(map: HashMap<String, Any?>, intent: Intent?) {
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10000) ?: -10000
        if (temp != -10000) map["batteryTemp"] = temp / 10.0

        readCpuTemp()?.let { map["cpuTemp"] = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            map["thermalStatus"] = powerManager.currentThermalStatus
        }
    }

    private fun fetchElectricalInfo(map: HashMap<String, Any?>, intent: Intent?, batteryManager: BatteryManager, status: Int) {
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (voltage != -1) map["voltage"] = voltage

        val currentApiValue = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentApiValue != Int.MIN_VALUE && currentApiValue != 0) {
            map["currentNow_mA"] = enforceStandardSign(normalizeToMilliAmps(currentApiValue), status)
        } else {
            readCurrentSysfs()?.let { map["currentNow_mA"] = enforceStandardSign(it, status) }
        }
    }

    private fun fetchHealthInfo(map: HashMap<String, Any?>, intent: Intent?, batteryManager: BatteryManager) {
        map["health"] = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            map["cycleCount"] = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
        }
        val charge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (charge != Int.MIN_VALUE && charge != 0) map["chargeCounter_mAh"] = charge / 1000
    }

    private fun fetchLocationInfo(map: HashMap<String, Any?>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) ?: return
            val hasInvalidFix = !lastLocation.hasAccuracy() || lastLocation.accuracy < 0f
            val isStale = System.currentTimeMillis() - lastLocation.time > 300_000L
            val isNoFixDefault =
                abs(lastLocation.latitude - 37.33233141) < 1e-4 &&
                abs(lastLocation.longitude + 122.0312186) < 1e-4
            val isNullIsland = lastLocation.latitude == 0.0 && lastLocation.longitude == 0.0
            if (hasInvalidFix || isStale || isNoFixDefault || isNullIsland) return
            map["latitude"] = lastLocation.latitude
            map["longitude"] = lastLocation.longitude
            map["altitude"] = lastLocation.altitude
        }
    }

    // MARK: - Asynchronous Sensor Handler (Single-Shot)

    private fun fetchSensorsAsync(map: HashMap<String, Any?>, fetchEnv: Boolean, fetchMotion: Boolean, result: Result) {
        var hasResponded = false
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val requiredSensors = mutableSetOf<Int>()

        if (fetchEnv) sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { requiredSensors.add(Sensor.TYPE_LIGHT) }
        if (fetchMotion) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { requiredSensors.add(Sensor.TYPE_ACCELEROMETER) }
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { requiredSensors.add(Sensor.TYPE_PROXIMITY) }
        }

        if (requiredSensors.isEmpty()) {
            result.success(map)
            return
        }

        fun dispatchResultSafely() {
            if (hasResponded) return
            hasResponded = true
            Handler(Looper.getMainLooper()).post {
                try { result.success(map) } catch (e: Exception) { /* Handle flutter channel closed */ }
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (hasResponded || event == null) return
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> map["light_lux"] = event.values[0]
                    Sensor.TYPE_PROXIMITY -> {
                        map["proximity_cm"] = event.values[0]
                        map["isCovered"] = event.values[0] < 2.0
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        map["accelX"] = x; map["accelY"] = y; map["accelZ"] = z
                        map["posture"] = when {
                            z > 7.5 -> "Face Up"
                            z < -7.5 -> "Face Down"
                            y > 7.5 -> "Portrait"
                            else -> "Other"
                        }
                        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                        map["motionState"] = if (abs(magnitude - 9.81) > 1.0) "Moving" else "Still"
                    }
                }

                requiredSensors.remove(event.sensor.type)
                if (requiredSensors.isEmpty()) {
                    sensorManager.unregisterListener(this)
                    dispatchResultSafely()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        requiredSensors.forEach { sensorManager.registerListener(listener, sensorManager.getDefaultSensor(it), SensorManager.SENSOR_DELAY_UI) }

        // Fallback timeout
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hasResponded) {
                sensorManager.unregisterListener(listener)
                dispatchResultSafely()
            }
        }, 5000)
    }

    // MARK: - Hardware Helpers & Math

    /**
     * Standardizes microAmps to milliAmps if a device reports unusually large values.
     */
    private fun normalizeToMilliAmps(raw: Int): Int = if (abs(raw) > 20000) raw / 1000 else raw

    /**
     * Ensures consistent sign representation across Android OEMs:
     * Negative (-) means discharging, Positive (+) means charging.
     */
    private fun enforceStandardSign(milliAmps: Int, batteryStatus: Int): Int {
        val isCharging = (batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL)
        return if (isCharging && milliAmps < 0) milliAmps * -1
        else if (!isCharging && milliAmps > 0) milliAmps * -1
        else milliAmps
    }

    /**
     * Fallback for devices that don't support BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
     */
    private fun readCurrentSysfs(): Int? {
        val sysfsPaths = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/BatteryAverageCurrent"
        )
        for (path in sysfsPaths) {
            try {
                val file = File(path)
                if (file.exists()) {
                    return normalizeToMilliAmps(file.readText().trim().toInt())
                }
            } catch (e: Exception) {
                // Silently skip if permission denied or file format is unexpected
            }
        }
        return null
    }

    /**
     * Reads the raw CPU thermal zone if available.
     */
    private fun readCpuTemp(): Double? {
        try {
            val file = File("/sys/class/thermal/thermal_zone0/temp")
            if (file.exists()) {
                return file.readText().trim().toDouble() / 1000.0
            }
        } catch (e: Exception) {
            // Silently skip if permission denied
        }
        return null
    }
}