package com.yanuarsyam.btgpsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.yanuarsyam.btgpsbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val deviceList = ArrayList<BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GpsBridgeService.ACTION_STATUS_UPDATE -> {
                    val badge = intent.getStringExtra("badge") ?: "DISCONNECTED"
                    val detail = intent.getStringExtra("detail") ?: ""
                    updateConnectionState(badge, detail)
                }
                GpsBridgeService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra("lat", 0.0)
                    val lon = intent.getDoubleExtra("lon", 0.0)
                    val alt = intent.getDoubleExtra("alt", 0.0)
                    val speed = intent.getFloatExtra("speed", 0f)
                    val sats = intent.getIntExtra("sats", 0)
                    val raw = intent.getStringExtra("raw") ?: ""

                    binding.tvSpeed.text = speed.toInt().toString()
                    binding.tvSatellites.text = sats.toString()
                    binding.tvAltitude.text = "Elevasi: ${alt.toInt()} m"
                    binding.tvLatitude.text = "Lat: %.6f".format(lat)
                    binding.tvLongitude.text = "Lon: %.6f".format(lon)
                    binding.tvRawNmea.text = raw
                }
                GpsBridgeService.ACTION_RECORD_STATUS -> {
                    val isRecording = intent.getBooleanExtra("is_recording", false)
                    val detail = intent.getStringExtra("rec_detail") ?: ""
                    binding.tvRecordDetail.text = detail
                    if (isRecording) {
                        binding.btnRecord.text = "Berhenti & Simpan GPX"
                        binding.btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
                    } else {
                        binding.btnRecord.text = "Mulai Rekam Rute"
                        binding.btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#059669"))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        requestRequiredPermissions()
        refreshPairedDevices()

        binding.btnConnect.setOnClickListener {
            val idx = binding.spinnerDevices.selectedItemPosition
            if (idx >= 0 && idx < deviceList.size) {
                val dev = deviceList[idx]
                val intent = Intent(this, GpsBridgeService::class.java).apply {
                    putExtra(GpsBridgeService.EXTRA_DEVICE_ADDRESS, dev.address)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                Toast.makeText(this, "Pilih perangkat Bluetooth terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, GpsBridgeService::class.java))
            updateConnectionState("DISCONNECTED", "Koneksi diputuskan oleh pengguna")
        }

        binding.btnRecord.setOnClickListener {
            val intent = Intent(this, GpsBridgeService::class.java).apply {
                action = GpsBridgeService.ACTION_TOGGLE_RECORD
            }
            startService(intent)
        }
    }

    private fun updateConnectionState(state: String, detail: String) {
        binding.tvStatusDetail.text = detail
        when (state) {
            "CONNECTED" -> {
                binding.tvStatusBadge.text = "TERHUBUNG"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#15803D"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
                binding.btnRecord.isEnabled = true
            }
            "CONNECTING", "RECONNECTING" -> {
                binding.tvStatusBadge.text = if (state == "CONNECTING") "MENYAMBUNG..." else "RECONNECT..."
                binding.tvStatusBadge.setTextColor(Color.parseColor("#B45309"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
            }
            else -> {
                binding.tvStatusBadge.text = "TERPUTUS"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.isEnabled = false
                binding.btnRecord.isEnabled = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        val labels = ArrayList<String>()
        deviceList.clear()
        bluetoothAdapter?.bondedDevices?.forEach { dev ->
            deviceList.add(dev)
            labels.add("${dev.name ?: "Unknown"} (${dev.address})")
        }
        binding.spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GpsBridgeService.ACTION_STATUS_UPDATE)
            addAction(GpsBridgeService.ACTION_LOCATION_UPDATE)
            addAction(GpsBridgeService.ACTION_RECORD_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Minta status terbaru ke Service setiap kali layar terbuka
        val syncIntent = Intent(this, GpsBridgeService::class.java).apply {
            action = GpsBridgeService.ACTION_REQUEST_SYNC
        }
        startService(syncIntent)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
}
