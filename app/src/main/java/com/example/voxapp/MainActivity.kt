package com.example.voxapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    // (नाम, बोलने का locale, ML Kit कोड)
    private data class Lang(val name: String, val locale: String, val mlkit: String)

    private val dictateLangs = listOf(
        Lang("हिन्दी", "hi-IN", TranslateLanguage.HINDI),
        Lang("English", "en-IN", TranslateLanguage.ENGLISH),
        Lang("मराठी", "mr-IN", TranslateLanguage.MARATHI),
        Lang("தமிழ்", "ta-IN", TranslateLanguage.TAMIL)
    )
    private val translateLangs = listOf(
        Lang("Off (अनुवाद बंद)", "", ""),
        Lang("English", "", TranslateLanguage.ENGLISH),
        Lang("हिन्दी", "", TranslateLanguage.HINDI),
        Lang("मराठी", "", TranslateLanguage.MARATHI),
        Lang("தமிழ்", "", TranslateLanguage.TAMIL)
    )

    private lateinit var spinnerDictate: Spinner
    private lateinit var spinnerTranslate: Spinner
    private lateinit var editText: EditText
    private lateinit var btnMic: Button
    private lateinit var btnCopy: Button
    private lateinit var tvWords: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSaved: TextView

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var sessionStartMs = 0L
    private var totalWords = 0
    private var totalSeconds = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerDictate = findViewById(R.id.spinnerDictate)
        spinnerTranslate = findViewById(R.id.spinnerTranslate)
        editText = findViewById(R.id.editText)
        btnMic = findViewById(R.id.btnMic)
        btnCopy = findViewById(R.id.btnCopy)
        tvWords = findViewById(R.id.tvWords)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvSaved = findViewById(R.id.tvSaved)

        spinnerDictate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, dictateLangs.map { it.name }
        )
        spinnerTranslate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, translateLangs.map { it.name }
        )

        btnMic.setOnClickListener { if (listening) stopListening() else startListening() }
        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vox", editText.text.toString()))
            Toast.makeText(this, "कॉपी हो गया", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "इस फ़ोन में speech recognition उपलब्ध नहीं है", Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        btnMic.text = "⏹ रोकें"
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        listenOnce()
    }

    private fun listenOnce() {
        val locale = dictateLangs[spinnerDictate.selectedItemPosition].locale
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sessionStartMs = System.currentTimeMillis()
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        listening = false
        btnMic.text = "🎤 बोलना शुरू करें"
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                val seconds = (System.currentTimeMillis() - sessionStartMs) / 1000.0
                val words = text.trim().split(Regex("\\s+")).size
                totalWords += words
                totalSeconds += seconds
                updateStats()
                handleText(text)
            }
            if (listening) listenOnce() // लगातार सुनते रहो
        }

        override fun onError(error: Int) {
            if (listening && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                listenOnce()
            } else if (listening) {
                stopListening()
                Toast.makeText(this@MainActivity, "Error code: $error", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleText(text: String) {
        val target = translateLangs[spinnerTranslate.selectedItemPosition]
        val source = dictateLangs[spinnerDictate.selectedItemPosition]
        if (target.mlkit.isEmpty() || target.mlkit == source.mlkit) {
            appendText(text)
            return
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source.mlkit)
                .setTargetLanguage(target.mlkit)
                .build()
        )
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { appendText(it) }
                    .addOnFailureListener { appendText(text) }
            }
            .addOnFailureListener {
                Toast.makeText(this, "अनुवाद मॉडल डाउनलोड नहीं हुआ (इंटरनेट चेक करें)", Toast.LENGTH_SHORT).show()
                appendText(text)
            }
    }

    private fun appendText(text: String) {
        val current = editText.text.toString()
        editText.setText(if (current.isEmpty()) text else "$current $text")
        editText.setSelection(editText.text.length)
    }

    private fun updateStats() {
        val minutes = totalSeconds / 60.0
        val wpm = if (minutes > 0) (totalWords / minutes).toInt() else 0
        val savedMin = (totalWords / 40.0 - minutes).coerceAtLeast(0.0).toInt()
        tvWords.text = "$totalWords words"
        tvSpeed.text = "SPEED\n$wpm WPM\nKeyboard 40 WPM"
        tvSaved.text = "TIME SAVED\n$savedMin min"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
