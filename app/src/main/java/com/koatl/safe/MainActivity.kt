package com.koatl.safe

import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvEstado: TextView
    private lateinit var tvDispositivo: TextView
    private lateinit var tvPerfilNombre: TextView
    private lateinit var tvPerfilDatos: TextView
    private lateinit var llContactos: LinearLayout
    private lateinit var tvSinContactos: TextView
    private lateinit var layoutEmergenciaActiva: LinearLayout
    private lateinit var tvUltimaUbicacion: TextView

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var ultimaUbicacion: Location? = null
    private var locationCallback: LocationCallback? = null

    // Emergencia
    private var emergencyTimer: Timer? = null
    private var emergenciaActiva = false

    // Contactos
    private val contactos = mutableListOf<ContactoEmergencia>()

    companion object {
        private const val TAG = "KoatlSafe"
        private const val PERMISSION_REQUEST_CODE = 100
        const val KEY_CONTACTOS = "contactos"
    }

    data class ContactoEmergencia(val nombre: String, val telefono: String)

    // ─── BroadcastReceiver para estado BLE ───────────────────────────────────

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                KeepAliveService.ACTION_CONNECTED ->
                    actualizarEstado(true, "PulseraSOS conectada")
                KeepAliveService.ACTION_DISCONNECTED ->
                    actualizarEstado(false, "Buscando PulseraSOS...")
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
        llContactos = findViewById(R.id.llContactos)
        tvSinContactos = findViewById(R.id.tvSinContactos)
        layoutEmergenciaActiva = findViewById(R.id.layoutEmergenciaActiva)
        tvUltimaUbicacion = findViewById(R.id.tvUltimaUbicacion)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        cargarPerfil()
        cargarContactos()

        findViewById<TextView>(R.id.tvEditarPerfil).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<TextView>(R.id.tvAgregarContacto).setOnClickListener {
            mostrarDialogoAgregarContacto()
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

        val filter = IntentFilter().apply {
            addAction(KeepAliveService.ACTION_CONNECTED)
            addAction(KeepAliveService.ACTION_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this, bleReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val activa = prefs.getBoolean(EmergencyActivity.KEY_EMERGENCY_ACTIVE, false)
        if (activa && !emergenciaActiva) {
            emergenciaActiva = true
            layoutEmergenciaActiva.visibility = View.VISIBLE
            iniciarTimerUbicacion()
        }
        actualizarEstado(
            KeepAliveService.estaConectado,
            if (KeepAliveService.estaConectado) "PulseraSOS conectada"
            else "Buscando PulseraSOS..."
        )
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
            Manifest.permission.SEND_SMS
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                iniciarActualizacionUbicacion()
                iniciarActualizacionUbicacion()
                startForegroundService(Intent(this, KeepAliveService::class.java))
                pedirPermisoOverlaySiNecesario()
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } else {
                Toast.makeText(
                    this,
                    "Se necesitan todos los permisos para funcionar",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── Perfil ───────────────────────────────────────────────────────────────

    private fun cargarPerfil() {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "") ?: ""
        val edad = prefs.getString(ProfileActivity.KEY_EDAD, "") ?: ""
        val sangre = prefs.getString(ProfileActivity.KEY_SANGRE, "") ?: ""
        val alergias = prefs.getString(ProfileActivity.KEY_ALERGIAS, "") ?: ""

        tvPerfilNombre.text = nombre
        val datos = StringBuilder()
        datos.append("$edad anos  |  Sangre: $sangre")
        if (alergias.isNotEmpty()) datos.append("\nAlergias: $alergias")
        tvPerfilDatos.text = datos.toString()
    }

    // ─── Contactos ────────────────────────────────────────────────────────────

    private fun cargarContactos() {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_CONTACTOS, "[]") ?: "[]"
        contactos.clear()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            contactos.add(
                ContactoEmergencia(obj.getString("nombre"), obj.getString("telefono"))
            )
        }
        refrescarListaContactos()
    }

    private fun guardarContactos() {
        val array = JSONArray()
        contactos.forEach {
            val obj = JSONObject()
            obj.put("nombre", it.nombre)
            obj.put("telefono", it.telefono)
            array.put(obj)
        }
        getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_CONTACTOS, array.toString()).apply()
    }

    private fun mostrarDialogoAgregarContacto() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_agregar_contacto, null)
        AlertDialog.Builder(this)
            .setTitle("Agregar contacto de emergencia")
            .setView(dialogView)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = dialogView.findViewById<EditText>(R.id.etNombreContacto)
                    .text.toString().trim()
                val telefono = dialogView.findViewById<EditText>(R.id.etTelefonoContacto)
                    .text.toString().trim()
                if (nombre.isEmpty() || telefono.isEmpty()) {
                    Toast.makeText(this, "Completa nombre y telefono", Toast.LENGTH_SHORT).show()
                } else {
                    contactos.add(ContactoEmergencia(nombre, telefono))
                    guardarContactos()
                    refrescarListaContactos()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refrescarListaContactos() {
        llContactos.removeAllViews()
        if (contactos.isEmpty()) {
            tvSinContactos.visibility = View.VISIBLE
            return
        }
        tvSinContactos.visibility = View.GONE
        contactos.forEachIndexed { index, contacto ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val tvNombre = TextView(this).apply {
                text = contacto.nombre
                textSize = 15f
                setTextColor(getColor(android.R.color.black))
            }
            val tvTelefono = TextView(this).apply {
                text = contacto.telefono
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            }
            textos.addView(tvNombre)
            textos.addView(tvTelefono)

            val btnEliminar = TextView(this).apply {
                text = "Eliminar"
                textSize = 12f
                setTextColor(0xFFC0392B.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    contactos.removeAt(index)
                    guardarContactos()
                    refrescarListaContactos()
                }
            }
            fila.addView(textos)
            fila.addView(btnEliminar)
            llContactos.addView(fila)

            if (index < contactos.size - 1) {
                val divisor = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.setMargins(0, 8, 0, 8) }
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                llContactos.addView(divisor)
            }
        }
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

    // ─── UI estado ────────────────────────────────────────────────────────────

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
            .setContentText("Toca para ver la cuenta regresiva")
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
                runOnUiThread { enviarActualizacionUbicacion() }
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
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Usuario") ?: "Usuario"
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
        val mensaje = "ACTUALIZACION: $nombre esta bien. Emergencia cancelada. Hora: $fecha"

        val smsManager = obtenerSmsManager()
        contactos.forEach { contacto ->
            try {
                smsManager.sendTextMessage(contacto.telefono, null, mensaje, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error SMS: ${e.message}")
            }
        }
        Toast.makeText(
            this, "Emergencia detenida. Contactos notificados.", Toast.LENGTH_LONG
        ).show()
    }

    private fun enviarActualizacionUbicacion() {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Usuario") ?: "Usuario"
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

        val ubicacionTexto = if (ultimaUbicacion != null) {
            "https://maps.google.com/?q=" +
                    "${ultimaUbicacion!!.latitude},${ultimaUbicacion!!.longitude}"
        } else "No disponible"

        val mensaje = "ACTUALIZACION ubicacion de $nombre: $ubicacionTexto. Hora: $fecha"
        tvUltimaUbicacion.text = "Ultima actualizacion: $fecha"

        val smsManager = obtenerSmsManager()
        contactos.forEach { contacto ->
            try {
                val partes = smsManager.divideMessage(mensaje)
                if (partes.size == 1) {
                    smsManager.sendTextMessage(contacto.telefono, null, mensaje, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(
                        contacto.telefono, null, partes, null, null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error SMS: ${e.message}")
            }
        }
    }

    private fun pedirExclusionBateria() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
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

    private fun pedirPermisoOverlaySiNecesario() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Permiso necesario")
                    .setMessage("Para que la alerta aparezca sobre otras apps, necesitamos un permiso adicional. Toca Aceptar y activa la opcion en la siguiente pantalla.")
                    .setPositiveButton("Aceptar") { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
    }
}