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
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class GpsBridgeService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val ACTION_STATUS_UPDATE = "com.yanuarsyam.btgpsbridge.STATUS_UPDATE"
        const val ACTION_LOCATION_UPDATE = "com.yanuarsyam.btgpsbridge.LOCATION_UPDATE"
        const val ACTION_TOGGLE_RECORD = "com.yanuarsyam.btgpsbridge.TOGGLE_RECORD"
        const val ACTION_RECORD_STATUS = "com.yanuarsyam.btgpsbridge.RECORD_STATUS"
        const val ACTION_REQUEST_SYNC = "com.yanuarsyam.btgpsbridge.REQUEST_SYNC"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHANNEL_ID = "gps_bridge_channel"
        private const val NOTIF_ID = 1001
    }

    private var socket: BluetoothSocket? = null
    private var isRunning = false
    private lateinit var locationManager: LocationManager
    private var targetDeviceAddress: String? = null
    private var lastBadgeState = "DISCONNECTED"
    private var lastDetailMessage = "Siap menghubungkan perangkat..."

    // Logger GPX
    private var isRecording = false
    private var gpxWriter: FileWriter? = null
    private var recordedCount = 0
    private var currentGpxFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        setupMockProvider()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_RECORD -> {
                handleRecordingToggle()
                return START_STICKY
            }
            ACTION_REQUEST_SYNC -> {
                broadcastState(lastBadgeState, lastDetailMessage)
                broadcastLogState(isRecording, if (isRecording) "Merekam: $recordedCount titik" else "Siap merekam trek perjalanan ke berkas .GPX")
                return START_STICKY
            }
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address != null) {
            targetDeviceAddress = address
        }

        val notif = createServiceNotification("Layanan Bridge GPS Aktif")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (targetDeviceAddress != null && !isRunning) {
            isRunning = true
            startConnectionLoop()
        }
        return START_STICKY
    }

    private fun setupMockProvider() {
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false,
                true, true, true, 1, 1
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (_: SecurityException) {
            broadcastState("ERROR", "Aktifkan Opsi Pengembang: Pilih aplikasi lokasi tiruan!")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startConnectionLoop() {
        Thread {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            while (isRunning) {
                val dev = bm.adapter?.getRemoteDevice(targetDeviceAddress)
                if (dev == null) {
                    broadcastState("ERROR", "Perangkat Bluetooth tidak ditemukan")
                    break
                }

                try {
                    broadcastState("CONNECTING", "Menghubungkan ke ${dev.name ?: targetDeviceAddress}...")
                    socket = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    broadcastState("CONNECTED", "Terhubung & Injeksi Lokasi Aktif")

                    val reader = BufferedReader(InputStreamReader(socket?.inputStream))
                    var lat = 0.0
                    var lon = 0.0
                    var alt = 0.0
                    var speedKmh = 0f
                    var bearing = 0f
                    var sats = 0

                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("$")) {
                            val parts = line.split("*")[0].split(",")
                            val tag = parts[0]

                            if (tag.endsWith("GGA") && parts.size > 9) {
                                val pLat = parseNmeaDegree(parts[2], parts[3])
                                val pLon = parseNmeaDegree(parts[4], parts[5])
                                if (pLat != null && pLon != null) {
                                    lat = pLat
                                    lon = pLon
                                    sats = parts[7].toIntOrNull() ?: sats
                                    alt = parts[9].toDoubleOrNull() ?: alt
                                    injectMockLocation(lat, lon, alt, speedKmh / 3.6f, bearing)
                                }
                            } else if (tag.endsWith("RMC") && parts.size > 8) {
                                if (parts[2] == "A") {
                                    val pLat = parseNmeaDegree(parts[3], parts[4])
                                    val pLon = parseNmeaDegree(parts[5], parts[6])
                                    if (pLat != null && pLon != null) {
                                        lat = pLat
                                        lon = pLon
                                        val knots = parts[7].toFloatOrNull() ?: 0f
                                        speedKmh = knots * 1.852f
                                        bearing = parts[8].toFloatOrNull() ?: bearing
                                        injectMockLocation(lat, lon, alt, speedKmh / 3.6f, bearing)
                                    }
                                }
                            }

                            sendBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
                                setPackage(packageName)
                                putExtra("lat", lat)
                                putExtra("lon", lon)
                                putExtra("alt", alt)
                                putExtra("speed", speedKmh)
                                putExtra("sats", sats)
                                putExtra("raw", line)
                            })
                        }
                    }
                } catch (e: Exception) {
                    try { socket?.close() } catch (_: Exception) {}
                    if (isRunning) {
                        broadcastState("RECONNECTING", "Koneksi terputus. Mencoba ulang dalam 5 detik...")
                        SystemClock.sleep(5000)
                    }
                }
            }
        }.start()
    }

    private fun injectMockLocation(lat: Double, lon: Double, alt: Double, speedMs: Float, bearing: Float) {
        if (lat == 0.0 && lon == 0.0) return
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = alt
            speed = speedMs
            this.bearing = bearing
            accuracy = 2.5f
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

        if (isRecording && gpxWriter != null) {
            try {
                val utcTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                gpxWriter?.write("      <trkpt lat=\"$lat\" lon=\"$lon\">\n        <ele>$alt</ele>\n        <time>$utcTime</time>\n        <speed>$speedMs</speed>\n      </trkpt>\n")
                gpxWriter?.flush()
                recordedCount++
                broadcastLogState(true, "Merekam: $recordedCount titik koordinat")
            } catch (_: Exception) {}
        }
    }

    private fun handleRecordingToggle() {
        if (!isRecording) {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outDir = getExternalFilesDir(null) ?: filesDir
                currentGpxFile = File(outDir, "Trip_$timeStamp.gpx")
                gpxWriter = FileWriter(currentGpxFile, true)
                gpxWriter?.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"BT-GPS-Bridge\">\n  <trk>\n    <name>Trip_$timeStamp</name>\n    <trkseg>\n")
                gpxWriter?.flush()
                isRecording = true
                recordedCount = 0
                broadcastLogState(true, "Merekam: 0 titik koordinat")
            } catch (e: Exception) {
                broadcastLogState(false, "Gagal memulai perekam GPX")
            }
        } else {
            try {
                isRecording = false
                gpxWriter?.write("    </trkseg>\n  </trk>\n</gpx>\n")
                gpxWriter?.flush()
                gpxWriter?.close()
                gpxWriter = null
                broadcastLogState(false, "Tersimpan: ${currentGpxFile?.name} ($recordedCount titik)")
            } catch (e: Exception) {
                broadcastLogState(false, "Gagal mengakhiri berkas GPX")
            }
        }
    }

    private fun parseNmeaDegree(raw: String, dir: String): Double? {
        val dot = raw.indexOf('.')
        if (dot < 2) return null
        val deg = raw.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = raw.substring(dot - 2).toDoubleOrNull() ?: return null
        var dec = deg + (min / 60.0)
        if (dir.equals("S", true) || dir.equals("W", true)) dec = -dec
        return dec
    }

    private fun broadcastState(badgeCode: String, message: String) {
        lastBadgeState = badgeCode
        lastDetailMessage = message
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra("badge", badgeCode)
            putExtra("detail", message)
        })
    }

    private fun broadcastLogState(recording: Boolean, msg: String) {
        sendBroadcast(Intent(ACTION_RECORD_STATUS).apply {
            setPackage(packageName)
            putExtra("is_recording", recording)
            putExtra("rec_detail", msg)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "GPS Bridge Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }

    private fun createServiceNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Hardware Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        lastBadgeState = "DISCONNECTED"
        if (isRecording) handleRecordingToggle()
        try { socket?.close() } catch (_: Exception) {}
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
    }
}
