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
import android.content.pm.PackageManager
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
                    val status = intent.getStringExtra("status") ?: ""
                    binding.tvStatus.text = "Status: $status"
                    if (status.contains("Terhubung", ignoreCase = true)) {
                        binding.btnConnect.isEnabled = false
                        binding.btnDisconnect.isEnabled = true
                    } else if (status.contains("Terputus", ignoreCase = true) || status.contains("Gagal", ignoreCase = true)) {
                        binding.btnConnect.isEnabled = true
                        binding.btnDisconnect.isEnabled = false
                    }
                }
                GpsBridgeService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra("lat", 0.0)
                    val lon = intent.getDoubleExtra("lon", 0.0)
                    val alt = intent.getDoubleExtra("alt", 0.0)
                    val speed = intent.getFloatExtra("speed", 0f)
                    val sats = intent.getIntExtra("sats", 0)
                    val raw = intent.getStringExtra("raw") ?: ""

                    binding.tvCoordinates.text = "Latitude: $lat\nLongitude: $lon"
                    binding.tvExtra.text = "Satelit: $sats | Ketinggian: ${alt.toInt()} m | Kecepatan: ${(speed * 3.6).toInt()} km/h"
                    binding.tvRawNmea.text = raw
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

        checkAndRequestPermissions()
        loadPairedDevices()

        binding.btnConnect.setOnClickListener {
            val selectedIndex = binding.spinnerDevices.selectedItemPosition
            if (selectedIndex >= 0 && selectedIndex < deviceList.size) {
                val device = deviceList[selectedIndex]
                val intent = Intent(this, GpsBridgeService::class.java).apply {
                    putExtra(GpsBridgeService.EXTRA_DEVICE_ADDRESS, device.address)
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
            val intent = Intent(this, GpsBridgeService::class.java)
            stopService(intent)
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.tvStatus.text = "Status: Terputus"
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val names = ArrayList<String>()
        deviceList.clear()
        bluetoothAdapter?.bondedDevices?.forEach { device ->
            deviceList.add(device)
            names.add("${device.name ?: "Unknown"} (${device.address})")
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerDevices.adapter = adapter
    }

    private fun checkAndRequestPermissions() {
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
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GpsBridgeService.ACTION_STATUS_UPDATE)
            addAction(GpsBridgeService.ACTION_LOCATION_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
}
