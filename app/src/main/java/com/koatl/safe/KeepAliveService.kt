package com.koatl.safe


import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class KeepAliveService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val SERVICE_UUID = java.util.UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = java.util.UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val DESCRIPTOR_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    companion object {
        private const val TAG = "KoatlSafe"
        private const val DEVICE_NAME = "PulseraSOS"
        private const val SCAN_PERIOD = 10000L
        const val ACTION_CONNECTED = "com.koatl.safe.CONNECTED"
        const val ACTION_DISCONNECTED = "com.koatl.safe.DISCONNECTED"
        var estaConectado = false
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacion()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        iniciarEscaneo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun iniciarNotificacion() {
        val channelId = "koatl_bg"
        val channel = NotificationChannel(
            channelId, "Koatl Safe", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Conexion activa con la pulsera" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Koatl Safe activo")
            .setContentText("Monitoreando tu pulsera en segundo plano")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        startForeground(2, notification)
    }

    @SuppressLint("MissingPermission")
    private fun iniciarEscaneo() {
        if (scanning) return
        scanning = true
        enviarBroadcast(ACTION_DISCONNECTED)

        handler.postDelayed({
            if (scanning) {
                scanning = false
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                if (bluetoothGatt == null) {
                    handler.postDelayed({ iniciarEscaneo() }, 2000)
                }
            }
        }, SCAN_PERIOD)

        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                scanning = false
                bluetoothGatt = result.device.connectGatt(
                    this@KeepAliveService, false, gattCallback
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt, status: Int, newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    estaConectado = true
                    enviarBroadcast(ACTION_CONNECTED)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    bluetoothGatt = null
                    enviarBroadcast(ACTION_DISCONNECTED)
                    handler.postDelayed({ iniciarEscaneo() }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(DESCRIPTOR_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.getStringValue(0) == "SOS") {
                handler.post { lanzarEmergencia() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lanzarEmergencia() {
        val fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                mostrarEmergencia(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
            }
            .addOnFailureListener {
                mostrarEmergencia(0.0, 0.0)
            }
    }

    private fun mostrarEmergencia(lat: Double, lng: Double) {
        val intent = Intent(this, EmergencyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EmergencyActivity.EXTRA_LAT, lat)
            putExtra(EmergencyActivity.EXTRA_LNG, lng)
            putExtra(EmergencyActivity.EXTRA_ES_PRUEBA, false)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "emergency_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId, "Emergencias", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ALERTA DE EMERGENCIA")
            .setContentText("Activando protocolo de emergencia")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
        startActivity(intent)
    }

    private fun enviarBroadcast(action: String) {
        sendBroadcast(Intent(action))
    }
}