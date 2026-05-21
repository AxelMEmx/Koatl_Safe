package com.koatl.safe

import androidx.activity.OnBackPressedCallback
import android.app.NotificationManager
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
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class EmergencyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KoatlSafe"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val KEY_EMERGENCY_ACTIVE = "emergencyActive"

        const val EXTRA_ES_PRUEBA = "esPrueba"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cancelarNotificacion()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No hace nada — bloquea el gesto de regresar
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
            }
        }

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

        val timer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvCuenta.text = (millisUntilFinished / 1000).toString()
            }
            override fun onFinish() {
                vibrator.cancel()
                activarEmergencia(lat, lng, tieneUbicacion)
            }
        }
        timer.start()

        findViewById<View>(R.id.btnCancelar).setOnClickListener {
            timer.cancel()
            vibrator.cancel()
            cancelarNotificacion()
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

        val nombre = prefs.getString(ProfileActivity.KEY_NOMBRE, "Usuario") ?: "Usuario"
        val sangre = prefs.getString(ProfileActivity.KEY_SANGRE, "") ?: ""
        val domicilio = prefs.getString(ProfileActivity.KEY_DOMICILIO, "") ?: ""
        val alergias = prefs.getString(ProfileActivity.KEY_ALERGIAS, "") ?: ""
        val fecha = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

        val ubicacionTexto = if (tieneUbicacion) {
            "https://maps.google.com/?q=$lat,$lng"
        } else "No disponible"

        val esPrueba = intent.getBooleanExtra(EXTRA_ES_PRUEBA, false)
        val prefijo = if (esPrueba) "PRUEBA - " else ""

        val mensaje = "${prefijo}EMERGENCIA - $nombre necesita ayuda. " +
                "Sangre: $sangre. Alergias: $alergias. " +
                "Domicilio: $domicilio. " +
                "Ubicacion: $ubicacionTexto. Hora: $fecha"

        val contactosJson = prefs.getString("contactos", "[]") ?: "[]"
        val array = JSONArray(contactosJson)
        val smsManager = obtenerSmsManager()

        for (i in 0 until array.length()) {
            val telefono = array.getJSONObject(i).getString("telefono")
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