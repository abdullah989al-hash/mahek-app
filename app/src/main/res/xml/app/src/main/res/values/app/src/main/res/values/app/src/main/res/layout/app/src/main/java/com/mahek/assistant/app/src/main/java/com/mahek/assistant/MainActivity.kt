package com.mahek.assistant

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var txtChat: TextView
    private lateinit var editInput: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var prefs: SharedPreferences
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var tts: TextToSpeech? = null

    private val lockKeywords = listOf(
        "lock kar do", "lock kardo", "screen lock", "phone lock",
        "lock kar", "band kar do", "screen band kar"
    )

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.get(0) ?: ""
            if (spoken.isNotBlank()) {
                editInput.setText(spoken)
                handleUserInput(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mahek_prefs", MODE_PRIVATE)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        txtChat = findViewById(R.id.txtChat)
        editInput = findViewById(R.id.editInput)
        scrollView = findViewById(R.id.scrollView)
        val btnSend: Button = findViewById(R.id.btnSend)
        val btnMic: Button = findViewById(R.id.btnMic)
        val btnAdmin: Button = findViewById(R.id.btnAdmin)

        tts = TextToSpeech(this, this)

        appendMessage("Mahek", "Namaste! Main Mahek hoon. Kuch bhi poochiye, ya \"screen lock kar do\" boliye.")

        ensureApiKey()

        btnSend.setOnClickListener {
            val text = editInput.text.toString().trim()
            if (text.isNotEmpty()) {
                editInput.setText("")
                handleUserInput(text)
            }
        }

        btnMic.setOnClickListener { startVoiceInput() }

        btnAdmin.setOnClickListener { requestDeviceAdmin() }

        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            requestDeviceAdmin()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun requestDeviceAdmin() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            Toast.makeText(this, "Permission pehle se hai", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Isse Mahek voice command par aapki screen lock kar sakega."
        )
        startActivity(intent)
    }

    private fun lockScreen() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            appendMessage("Mahek", "Theek hai, screen lock kar raha hoon...")
            speak("Theek hai, lock kar rahi hoon")
            devicePolicyManager.lockNow()
        } else {
            appendMessage("Mahek", "Mujhe pehle 'Permission' button se Device Admin access dijiye, tabhi main screen lock kar paunga.")
            speak("Mujhe pehle permission dijiye")
            requestDeviceAdmin()
        }
    }

    private fun handleUserInput(text: String) {
        appendMessage("Aap", text)
        val lower = text.lowercase(Locale.getDefault())
        val isLockCommand = lockKeywords.any { lower.contains(it) }
        if (isLockCommand) {
            lockScreen()
            return
        }
        sendToClaude(text)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input is device pe available nahi hai", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    private fun speak(text: St
