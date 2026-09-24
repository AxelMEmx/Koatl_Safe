package com.koatl.safe

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class EmergencyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KoatlSafe"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ES_PRUEBA = "esPrueba"
        const val KEY_EMERGENCY_ACTIVE = "emergencyActive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cancelarNotificacion()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
        }

        setContentView(R.layout.activity_emergency)

        val tvCuenta = findViewById<TextView>(R.id.tvCuentaRegresiva)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        val tieneUbicacion = lat != 0.0 && lng != 0.0

        val vibrator = obtenerVibrator()
        val patron = longArrayOf(0, 200, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(patron, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(patron, 0)
        }

        var ubicacionResuelta = false

        if (!tieneUbicacion) {
            val fusedClient = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(this)
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                fusedClient.lastLocation.addOnSuccessListener { location ->
                    if (!ubicacionResuelta) {
                        ubicacionResuelta = true
                        location?.let {
                            activarEmergencia(it.latitude, it.longitude, true)
                        }
                    }
                }
            }
        }

        var timerActivo = true

        val timer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (timerActivo) {
                    tvCuenta.text = (millisUntilFinished / 1000).toString()
                }
            }
            override fun onFinish() {
                if (timerActivo) {
                    vibrator.cancel()
                    if (!ubicacionResuelta) {
                        ubicacionResuelta = true
                        activarEmergencia(lat, lng, tieneUbicacion)
                    }
                }
            }
        }
        timer.start()

        findViewById<View>(R.id.btnCancelar).setOnClickListener {
            timerActivo = false
            timer.cancel()
            vibrator.cancel()
            cancelarNotificacion()
            getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EMERGENCY_ACTIVE, false)
                .apply()
            finish()
        }
    }

    private fun obtenerVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun activarEmergencia(lat: Double, lng: Double, tieneUbicacion: Boolean) {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_EMERGENCY_ACTIVE, true).apply()

        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Paciente") ?: "Paciente"
        val sangre = prefs.getString(ProfileActivity.KEY_SANGRE, "") ?: ""
        val padecimientos = prefs.getString(ProfileActivity.KEY_PADECIMIENTOS, "") ?: ""
        val medicamentos = prefs.getString(ProfileActivity.KEY_MEDICAMENTOS, "") ?: ""
        val alergias = prefs.getString(ProfileActivity.KEY_ALERGIAS, "") ?: ""
        val contacto1Telefono = prefs.getString(ProfileActivity.KEY_CONTACTO1_TELEFONO, "") ?: ""
        val contacto2Telefono = prefs.getString(ProfileActivity.KEY_CONTACTO2_TELEFONO, "") ?: ""
        val contacto3Telefono = prefs.getString(ProfileActivity.KEY_CONTACTO3_TELEFONO, "") ?: ""
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

        val ubicacionTexto = if (tieneUbicacion) {
            "https://maps.google.com/?q=$lat,$lng"
        } else "No disponible"

        val esPrueba = intent.getBooleanExtra(EXTRA_ES_PRUEBA, false)
        val prefijo = if (esPrueba) "PRUEBA - " else ""

        val mensaje = buildString {
            append("${prefijo}EMERGENCIA MEDICA - $nombre necesita ayuda urgente. ")
            append("Sangre: $sangre. ")
            if (padecimientos.isNotEmpty()) append("Padecimientos: $padecimientos. ")
            if (medicamentos.isNotEmpty()) append("Medicamentos: $medicamentos. ")
            if (alergias.isNotEmpty()) append("Alergias: $alergias. ")
            append("Ubicacion: $ubicacionTexto. Hora: $fecha")
        }

        // Llamada automatica al contacto 1
        if (contacto1Telefono.isNotEmpty() && !esPrueba) {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    this, android.Manifest.permission.CALL_PHONE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val callIntent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$contacto1Telefono")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al llamar: ${e.message}")
                }
            } else {
                Log.w(TAG, "Permiso CALL_PHONE no otorgado")
            }
        }

        // SMS a contactos 2 y 3
        val smsManager = obtenerSmsManager()
        listOf(contacto2Telefono, contacto3Telefono).forEach { telefono ->
            if (telefono.isNotEmpty()) {
                try {
                    val partes = smsManager.divideMessage(mensaje)
                    if (partes.size == 1) {
                        smsManager.sendTextMessage(telefono, null, mensaje, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(telefono, null, partes, null, null)
                    }
                    Log.d(TAG, "SMS enviado a $telefono")
                } catch (e: Exception) {
                    Log.e(TAG, "Error SMS: ${e.message}")
                }
            }
        }

        cancelarNotificacion()
        finish()
    }

    private fun obtenerSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun cancelarNotificacion() {
        getSystemService(NotificationManager::class.java).cancel(1)
    }
}