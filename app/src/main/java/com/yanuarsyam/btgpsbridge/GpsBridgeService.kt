package com.yanuarsyam.btgpsbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class GpsBridgeService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val ACTION_STATUS_UPDATE = "com.yanuarsyam.btgpsbridge.STATUS_UPDATE"
        const val ACTION_LOCATION_UPDATE = "com.yanuarsyam.btgpsbridge.LOCATION_UPDATE"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHANNEL_ID = "gps_bridge_channel"
        private const val NOTIF_ID = 1001
    }

    private var socket: BluetoothSocket? = null
    private var isRunning = false
    private lateinit var locationManager: LocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        setupMockProvider()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val notif = buildNotification("Menghubungkan ke GPS...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (address != null && !isRunning) {
            connectToBluetooth(address)
        }
        return START_NOT_STICKY
    }

    private fun setupMockProvider() {
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}

        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                1, 1
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (e: SecurityException) {
            broadcastStatus("Gagal: Aktifkan 'Aplikasi lokasi tiruan' di Opsi Pengembang!")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToBluetooth(address: String) {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bm.adapter?.getRemoteDevice(address) ?: return

        isRunning = true
        Thread {
            try {
                broadcastStatus("Menghubungkan ke ${device.name ?: address}...")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                broadcastStatus("Terhubung & Mentransfer GPS!")

                val reader = BufferedReader(InputStreamReader(socket?.inputStream))
                var currentLat = 0.0
                var currentLon = 0.0
                var currentAlt = 0.0
                var currentSpeed = 0f
                var currentBearing = 0f
                var currentSats = 0

                while (isRunning) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("$")) {
                        val parts = line.split("*")[0].split(",")
                        val type = parts[0]

                        if (type.endsWith("GGA") && parts.size > 9) {
                            val lat = parseCoord(parts[2], parts[3])
                            val lon = parseCoord(parts[4], parts[5])
                            if (lat != null && lon != null) {
                                currentLat = lat
                                currentLon = lon
                                currentSats = parts[7].toIntOrNull() ?: currentSats
                                currentAlt = parts[9].toDoubleOrNull() ?: currentAlt
                                pushMockLocation(currentLat, currentLon, currentAlt, currentSpeed, currentBearing)
                            }
                        } else if (type.endsWith("RMC") && parts.size > 8) {
                            if (parts[2] == "A") {
                                val lat = parseCoord(parts[3], parts[4])
                                val lon = parseCoord(parts[5], parts[6])
                                if (lat != null && lon != null) {
                                    currentLat = lat
                                    currentLon = lon
                                    val knots = parts[7].toFloatOrNull() ?: 0f
                                    currentSpeed = knots * 0.514444f
                                    currentBearing = parts[8].toFloatOrNull() ?: currentBearing
                                    pushMockLocation(currentLat, currentLon, currentAlt, currentSpeed, currentBearing)
                                }
                            }
                        }

                        sendBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
                            setPackage(packageName)
                            putExtra("lat", currentLat)
                            putExtra("lon", currentLon)
                            putExtra("alt", currentAlt)
                            putExtra("speed", currentSpeed)
                            putExtra("sats", currentSats)
                            putExtra("raw", line)
                        })
                    }
                }
            } catch (e: Exception) {
                broadcastStatus("Terputus: ${e.localizedMessage}")
            } finally {
                stopSelf()
            }
        }.start()
    }

    private fun parseCoord(raw: String, dir: String): Double? {
        val dot = raw.indexOf('.')
        if (dot < 2) return null
        val degLength = dot - 2
        val deg = raw.substring(0, degLength).toDoubleOrNull() ?: return null
        val min = raw.substring(degLength).toDoubleOrNull() ?: return null
        var dec = deg + (min / 60.0)
        if (dir.equals("S", true) || dir.equals("W", true)) dec = -dec
        return dec
    }

    private fun pushMockLocation(lat: Double, lon: Double, alt: Double, speed: Float, bearing: Float) {
        if (lat == 0.0 && lon == 0.0) return
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = alt
            this.speed = speed
            this.bearing = bearing
            accuracy = 3.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 5f
                speedAccuracyMetersPerSecond = 0.5f
                verticalAccuracyMeters = 2f
            }
        }
        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (_: Exception) {}
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra("status", status)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS Bridge Background", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT GPS Bridge Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
    }
}
