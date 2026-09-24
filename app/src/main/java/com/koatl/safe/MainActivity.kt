package com.koatl.safe

import android.Manifest
import androidx.cardview.widget.CardView
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvEstado: TextView
    private lateinit var tvDispositivo: TextView
    private lateinit var tvPerfilNombre: TextView
    private lateinit var tvPerfilDatos: TextView
    private lateinit var tvContacto1: TextView
    private lateinit var tvContacto2: TextView
    private lateinit var tvContacto3: TextView
    private lateinit var layoutEmergenciaActiva: CardView
    private lateinit var tvUltimaUbicacion: TextView

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var ultimaUbicacion: Location? = null
    private var locationCallback: LocationCallback? = null

    // Emergencia
    private var emergencyTimer: Timer? = null
    private var emergenciaActiva = false

    companion object {
        private const val TAG = "KoatlSafe"
        private const val PERMISSION_REQUEST_CODE = 100
        const val KEY_CONTACTOS = "contactos"
    }

    // ─── BroadcastReceiver ────────────────────────────────────────────────────

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                KeepAliveService.ACTION_CONNECTED ->
                    actualizarEstado(true, "KoatlSafe conectado")
                KeepAliveService.ACTION_DISCONNECTED ->
                    actualizarEstado(false, "Buscando KoatlSafe...")
            }
        }
    }

    // ─── Ciclo de vida ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(ProfileActivity.KEY_PERFIL_COMPLETO, false)) {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvEstado = findViewById(R.id.tvEstado)
        tvDispositivo = findViewById(R.id.tvDispositivo)
        tvPerfilNombre = findViewById(R.id.tvPerfilNombre)
        tvPerfilDatos = findViewById(R.id.tvPerfilDatos)
        tvContacto1 = findViewById(R.id.tvContacto1)
        tvContacto2 = findViewById(R.id.tvContacto2)
        tvContacto3 = findViewById(R.id.tvContacto3)
        layoutEmergenciaActiva = findViewById(R.id.layoutEmergenciaActiva)
        tvUltimaUbicacion = findViewById(R.id.tvUltimaUbicacion)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        cargarPerfil()

        findViewById<TextView>(R.id.tvEditarPerfil).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<TextView>(R.id.tvEditarContactos).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<Button>(R.id.btnProbar).setOnClickListener {
            mostrarAlerta(esPrueba = true)
        }
        findViewById<Button>(R.id.btnEstoyBien).setOnClickListener {
            detenerEmergencia()
        }

        pedirPermisos()
    }

    override fun onResume() {
        super.onResume()
        if (!::layoutEmergenciaActiva.isInitialized) return

        cargarPerfil()

        val filter = IntentFilter().apply {
            addAction(KeepAliveService.ACTION_CONNECTED)
            addAction(KeepAliveService.ACTION_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this, bleReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        actualizarEstado(
            KeepAliveService.estaConectado,
            if (KeepAliveService.estaConectado) "KoatlSafe conectado"
            else "Buscando KoatlSafe..."
        )

        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val activa = prefs.getBoolean(EmergencyActivity.KEY_EMERGENCY_ACTIVE, false)
        if (activa && !emergenciaActiva) {
            emergenciaActiva = true
            layoutEmergenciaActiva.visibility = View.VISIBLE
            iniciarTimerUbicacion()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(bleReceiver) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        emergencyTimer?.cancel()
        if (::fusedLocationClient.isInitialized && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback!!)
        }
    }

    // ─── Permisos ─────────────────────────────────────────────────────────────

    private fun pedirPermisos() {
        val permisos = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(
            this, permisos.toTypedArray(), PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            iniciarActualizacionUbicacion()
            startForegroundService(Intent(this, KeepAliveService::class.java))
        }
    }

    // ─── Perfil ───────────────────────────────────────────────────────────────

    private fun cargarPerfil() {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "") ?: ""
        val sangre = prefs.getString(ProfileActivity.KEY_SANGRE, "") ?: ""
        val padecimientos = prefs.getString(ProfileActivity.KEY_PADECIMIENTOS, "") ?: ""

        tvPerfilNombre.text = nombre
        val datos = StringBuilder("Sangre: $sangre")
        if (padecimientos.isNotEmpty()) datos.append("  |  $padecimientos")
        tvPerfilDatos.text = datos.toString()

        val c1Nombre = prefs.getString(ProfileActivity.KEY_CONTACTO1_NOMBRE, "") ?: ""
        val c1Tel = prefs.getString(ProfileActivity.KEY_CONTACTO1_TELEFONO, "") ?: ""
        val c2Nombre = prefs.getString(ProfileActivity.KEY_CONTACTO2_NOMBRE, "") ?: ""
        val c3Nombre = prefs.getString(ProfileActivity.KEY_CONTACTO3_NOMBRE, "") ?: ""

        tvContacto1.text = if (c1Nombre.isNotEmpty()) "$c1Nombre  $c1Tel" else "Sin configurar"
        tvContacto2.text = if (c2Nombre.isNotEmpty()) c2Nombre else "Sin configurar"
        tvContacto3.text = if (c3Nombre.isNotEmpty()) c3Nombre else "Sin configurar"
    }

    // ─── GPS ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun iniciarActualizacionUbicacion() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 30000L
        ).setMinUpdateIntervalMillis(15000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                ultimaUbicacion = result.lastLocation
            }
        }
        fusedLocationClient.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun actualizarEstado(conectado: Boolean, mensaje: String) {
        tvEstado.text = if (conectado) "Conectado" else "Desconectado"
        tvEstado.setTextColor(
            getColor(
                if (conectado) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )
        tvDispositivo.text = mensaje
    }

    // ─── Emergencia ───────────────────────────────────────────────────────────

    private fun mostrarAlerta(esPrueba: Boolean = false) {
        val lat = ultimaUbicacion?.latitude ?: 0.0
        val lng = ultimaUbicacion?.longitude ?: 0.0

        val intent = Intent(this, EmergencyActivity::class.java).apply {
            putExtra(EmergencyActivity.EXTRA_LAT, lat)
            putExtra(EmergencyActivity.EXTRA_LNG, lng)
            putExtra(EmergencyActivity.EXTRA_ES_PRUEBA, esPrueba)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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

    private fun iniciarTimerUbicacion() {
        emergencyTimer?.cancel()
        emergencyTimer = Timer()
        emergencyTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (ultimaUbicacion != null) {
                        enviarActualizacionUbicacion()
                    } else {
                        Log.d(TAG, "Sin ubicacion disponible, omitiendo SMS")
                    }
                }
            }
        }, 180000L, 180000L)
    }

    private fun detenerEmergencia() {
        emergenciaActiva = false
        emergencyTimer?.cancel()
        emergencyTimer = null
        layoutEmergenciaActiva.visibility = View.GONE

        getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(EmergencyActivity.KEY_EMERGENCY_ACTIVE, false)
            .apply()

        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Paciente") ?: "Paciente"
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
        val mensaje = "ACTUALIZACION: $nombre esta bien. Emergencia cancelada. Hora: $fecha"
        val smsManager = obtenerSmsManager()

        listOf(
            prefs.getString(ProfileActivity.KEY_CONTACTO2_TELEFONO, "") ?: "",
            prefs.getString(ProfileActivity.KEY_CONTACTO3_TELEFONO, "") ?: ""
        ).forEach { telefono ->
            if (telefono.isNotEmpty()) {
                try {
                    smsManager.sendTextMessage(telefono, null, mensaje, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error SMS: ${e.message}")
                }
            }
        }

        Toast.makeText(
            this, "Emergencia detenida. Contactos notificados.", Toast.LENGTH_LONG
        ).show()
    }

    private fun enviarActualizacionUbicacion() {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Paciente") ?: "Paciente"
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

        val ubicacionTexto = if (ultimaUbicacion != null) {
            "https://maps.google.com/?q=" +
                    "${ultimaUbicacion!!.latitude},${ultimaUbicacion!!.longitude}"
        } else "No disponible"

        val mensaje = "ACTUALIZACION ubicacion de $nombre: $ubicacionTexto. Hora: $fecha"
        tvUltimaUbicacion.text = "Ultima actualizacion: $fecha"

        val smsManager = obtenerSmsManager()
        listOf(
            prefs.getString(ProfileActivity.KEY_CONTACTO2_TELEFONO, "") ?: "",
            prefs.getString(ProfileActivity.KEY_CONTACTO3_TELEFONO, "") ?: ""
        ).forEach { telefono ->
            if (telefono.isNotEmpty()) {
                try {
                    val partes = smsManager.divideMessage(mensaje)
                    if (partes.size == 1) {
                        smsManager.sendTextMessage(telefono, null, mensaje, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(
                            telefono, null, partes, null, null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error SMS: ${e.message}")
                }
            }
        }
    }

    private fun obtenerSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }
}