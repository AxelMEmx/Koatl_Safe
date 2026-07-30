package com.koatl.safe

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ProfileActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "KoatlPrefs"
        const val KEY_NOMBRE = "nombre"
        const val KEY_SANGRE = "sangre"
        const val KEY_PADECIMIENTOS = "padecimientos"
        const val KEY_MEDICAMENTOS = "medicamentos"
        const val KEY_ALERGIAS = "alergias"
        const val KEY_CONTACTO1_NOMBRE = "contacto1Nombre"
        const val KEY_CONTACTO1_TELEFONO = "contacto1Telefono"
        const val KEY_CONTACTO2_NOMBRE = "contacto2Nombre"
        const val KEY_CONTACTO2_TELEFONO = "contacto2Telefono"
        const val KEY_CONTACTO3_NOMBRE = "contacto3Nombre"
        const val KEY_CONTACTO3_TELEFONO = "contacto3Telefono"
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
            this, android.R.layout.simple_spinner_dropdown_item, tiposSangre
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERFIL_COMPLETO, false)) {
            findViewById<EditText>(R.id.etNombre).setText(prefs.getString(KEY_NOMBRE, ""))
            val indexSangre = tiposSangre.indexOf(prefs.getString(KEY_SANGRE, "O+"))
            spinner.setSelection(if (indexSangre >= 0) indexSangre else 0)
            findViewById<EditText>(R.id.etPadecimientos).setText(prefs.getString(KEY_PADECIMIENTOS, ""))
            findViewById<EditText>(R.id.etMedicamentos).setText(prefs.getString(KEY_MEDICAMENTOS, ""))
            findViewById<EditText>(R.id.etAlergias).setText(prefs.getString(KEY_ALERGIAS, ""))
            findViewById<EditText>(R.id.etContacto1Nombre).setText(prefs.getString(KEY_CONTACTO1_NOMBRE, ""))
            findViewById<EditText>(R.id.etContacto1Telefono).setText(prefs.getString(KEY_CONTACTO1_TELEFONO, ""))
            findViewById<EditText>(R.id.etContacto2Nombre).setText(prefs.getString(KEY_CONTACTO2_NOMBRE, ""))
            findViewById<EditText>(R.id.etContacto2Telefono).setText(prefs.getString(KEY_CONTACTO2_TELEFONO, ""))
            findViewById<EditText>(R.id.etContacto3Nombre).setText(prefs.getString(KEY_CONTACTO3_NOMBRE, ""))
            findViewById<EditText>(R.id.etContacto3Telefono).setText(prefs.getString(KEY_CONTACTO3_TELEFONO, ""))
        }

        findViewById<Button>(R.id.btnGuardar).setOnClickListener {
            val nombre = findViewById<EditText>(R.id.etNombre).text.toString().trim()
            val contacto1Nombre = findViewById<EditText>(R.id.etContacto1Nombre).text.toString().trim()
            val contacto1Telefono = findViewById<EditText>(R.id.etContacto1Telefono).text.toString().trim()

            if (nombre.isEmpty()) {
                Toast.makeText(this, "El nombre del paciente es obligatorio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (contacto1Nombre.isEmpty() || contacto1Telefono.isEmpty()) {
                Toast.makeText(this, "El contacto principal es obligatorio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(KEY_NOMBRE, nombre)
                .putString(KEY_SANGRE, spinner.selectedItem.toString())
                .putString(KEY_PADECIMIENTOS, findViewById<EditText>(R.id.etPadecimientos).text.toString().trim())
                .putString(KEY_MEDICAMENTOS, findViewById<EditText>(R.id.etMedicamentos).text.toString().trim())
                .putString(KEY_ALERGIAS, findViewById<EditText>(R.id.etAlergias).text.toString().trim())
                .putString(KEY_CONTACTO1_NOMBRE, contacto1Nombre)
                .putString(KEY_CONTACTO1_TELEFONO, contacto1Telefono)
                .putString(KEY_CONTACTO2_NOMBRE, findViewById<EditText>(R.id.etContacto2Nombre).text.toString().trim())
                .putString(KEY_CONTACTO2_TELEFONO, findViewById<EditText>(R.id.etContacto2Telefono).text.toString().trim())
                .putString(KEY_CONTACTO3_NOMBRE, findViewById<EditText>(R.id.etContacto3Nombre).text.toString().trim())
                .putString(KEY_CONTACTO3_TELEFONO, findViewById<EditText>(R.id.etContacto3Telefono).text.toString().trim())
                .putBoolean(KEY_PERFIL_COMPLETO, true)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}