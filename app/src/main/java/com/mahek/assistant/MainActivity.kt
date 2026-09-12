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

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun ensureApiKey() {
        val key = prefs.getString("api_key", null)
        if (key.isNullOrBlank()) {
            val input = EditText(this)
            input.hint = "Apni Anthropic API key yahan daalein"
            AlertDialog.Builder(this)
                .setTitle("API Key chahiye")
                .setMessage("Mahek ko baat karne ke liye Anthropic API key chahiye (console.anthropic.com se milegi).")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("api_key", input.text.toString().trim()).apply()
                }
                .show()
        }
    }

    private fun sendToClaude(userText: String) {
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            appendMessage("Mahek", "Pehle API key set karo (Settings se).")
            return
        }

        appendMessage("Mahek", "...")

        Thread {
            try {
                val url = URL("https://api.anthropic.com/v1/messages")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true

                val messages = JSONArray()
                val userMsg = JSONObject()
                userMsg.put("role", "user")
                userMsg.put("content", userText)
                messages.put(userMsg)

                val body = JSONObject()
                body.put("model", "claude-sonnet-4-6")
                body.put("max_tokens", 1000)
                body.put(
                    "system",
                    "Tum 'Mahek' ho, ek warm, dost jaisa AI assistant jo Hindi/English/Hinglish mein baat karta hai. Tumhe Abdullah Shaikh ne banaya hai. Casual aur empathetic raho, robotic mat bano."
                )
                body.put("messages", messages)

                val os: OutputStream = conn.outputStream
                os.write(body.toString().toByteArray())
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream.bufferedReader().use { it.readText() }

                var reply = "Maaf kijiye, jawab nahi mil paaya."
                if (responseCode in 200..299) {
                    val json = JSONObject(responseText)
                    val content = json.getJSONArray("content")
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "text") {
                            reply = block.getString("text")
                            break
                        }
                    }
                } else {
                    reply = "Error aaya (code $responseCode). API key check kariye."
                }

                runOnUiThread {
                    removeLastPlaceholder()
                    appendMessage("Mahek", reply)
                    speak(reply)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    removeLastPlaceholder()
                    appendMessage("Mahek", "Kuch technical dikkat aa gayi: ${e.message}")
                }
            }
        }.start()
    }

    private fun appendMessage(sender: String, message: String) {
        txtChat.append("\n$sender: $message\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun removeLastPlaceholder() {
        val current = txtChat.text.toString()
        val idx = current.lastIndexOf("\nMahek: ...\n")
        if (idx >= 0) {
            txtChat.text = current.substring(0, idx) + "\n"
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
