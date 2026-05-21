package com.koatl.safe

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ProfileActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "KoatlPrefs"
        const val KEY_NOMBRE = "nombre"
        const val KEY_EDAD = "edad"
        const val KEY_SANGRE = "sangre"
        const val KEY_DOMICILIO = "domicilio"
        const val KEY_ALERGIAS = "alergias"
        const val KEY_PERFIL_COMPLETO = "perfilCompleto"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val tiposSangre = arrayOf(
            "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "No se / Otro"
        )
        val spinner = findViewById<Spinner>(R.id.spinnerSangre)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            tiposSangre
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERFIL_COMPLETO, false)) {
            findViewById<EditText>(R.id.etNombre).setText(prefs.getString(KEY_NOMBRE, ""))
            findViewById<EditText>(R.id.etEdad).setText(prefs.getString(KEY_EDAD, ""))
            val indexSangre = tiposSangre.indexOf(prefs.getString(KEY_SANGRE, "O+"))
            spinner.setSelection(if (indexSangre >= 0) indexSangre else 0)
            findViewById<EditText>(R.id.etDomicilio).setText(prefs.getString(KEY_DOMICILIO, ""))
            findViewById<EditText>(R.id.etAlergias).setText(prefs.getString(KEY_ALERGIAS, ""))
        }

        findViewById<Button>(R.id.btnGuardar).setOnClickListener {
            val nombre = findViewById<EditText>(R.id.etNombre).text.toString().trim()
            val edad = findViewById<EditText>(R.id.etEdad).text.toString().trim()
            val domicilio = findViewById<EditText>(R.id.etDomicilio).text.toString().trim()

            if (nombre.isEmpty() || edad.isEmpty() || domicilio.isEmpty()) {
                Toast.makeText(
                    this,
                    "Nombre, edad y domicilio son obligatorios",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(KEY_NOMBRE, nombre)
                .putString(KEY_EDAD, edad)
                .putString(KEY_SANGRE, spinner.selectedItem.toString())
                .putString(KEY_DOMICILIO, domicilio)
                .putString(
                    KEY_ALERGIAS,
                    findViewById<EditText>(R.id.etAlergias).text.toString().trim()
                )
                .putBoolean(KEY_PERFIL_COMPLETO, true)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}