package com.elderstt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.elderstt.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var manualStop = false
    private var currentLocale: Locale = Locale.SIMPLIFIED_CHINESE

    private val prefs by lazy { getSharedPreferences("stt_prefs", MODE_PRIVATE) }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else Toast.makeText(this, R.string.permission_mic_needed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        setupLangSpinner()
        restoreSettings()
        setupSpeech()
    }

    private fun setupUi() {
        binding.btnToggle.setOnClickListener {
            if (!listening) ensureMicPermissionAndStart() else stopListening(manual = true)
        }
        binding.btnClear.setOnClickListener {
            binding.tvTranscript.text = ""
            binding.tvInterim.text = ""
        }
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }

        binding.seekFont.max = 128
        // SeekBar min in XML requires API 26; set programmatically
        if (Build.VERSION.SDK_INT >= 26) binding.seekFont.min = 24

        binding.btnInc.setOnClickListener { applyFont(binding.seekFont.progress + 4) }
        binding.btnDec.setOnClickListener { applyFont(binding.seekFont.progress - 4) }
        binding.seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyFont(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.chDark.setOnCheckedChangeListener { _, isChecked ->
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            prefs.edit().putBoolean("dark", isChecked).apply()
        }

        // Double tap to toggle fullscreen
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleFullscreen(); return true }
        })
        binding.root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    private fun setupLangSpinner() {
        val items = listOf(
            getString(R.string.language_zh_cn) to Locale.SIMPLIFIED_CHINESE,
            getString(R.string.language_zh_tw) to Locale.TRADITIONAL_CHINESE,
            getString(R.string.language_yue_hk) to Locale("yue", "HK"),
            getString(R.string.language_en_us) to Locale.US,
            getString(R.string.language_ja_jp) to Locale.JAPAN
        )
        val names = items.map { it.first }
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spLang.adapter = ad
        val savedTag = prefs.getString("lang", "zh-CN")
        val idx = items.indexOfFirst { it.second.toLanguageTag() == savedTag }
        if (idx >= 0) binding.spLang.setSelection(idx)
        binding.spLang.setOnItemSelectedListener(SimpleItemSelected { pos ->
            currentLocale = items[pos].second
            prefs.edit().putString("lang", currentLocale.toLanguageTag()).apply()
            restartIfListening()
        })
    }

    private fun restoreSettings() {
        val dark = prefs.getBoolean("dark", false)
        binding.chDark.isChecked = dark
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        val font = prefs.getInt("font", 48).coerceIn(24, 128)
        binding.seekFont.progress = font
        applyFont(font)

        binding.chAutoScroll.isChecked = prefs.getBoolean("autoScroll", true)
        binding.chAutoScroll.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("autoScroll", v).apply()
        }
    }

    private fun applyFont(px: Int) {
        val v = px.coerceIn(24, 128)
        binding.seekFont.progress = v
        binding.txtFontValue.text = "$v px"
        binding.tvTranscript.textSize = v.toFloat()
        binding.tvInterim.textSize = (v * 0.8f).coerceAtLeast(24f)
        prefs.edit().putInt("font", v).apply()
    }

    private fun setupSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_LONG).show()
            binding.btnToggle.isEnabled = false
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (!manualStop) restartSoon()
            }

            override fun onResults(results: Bundle) {
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.joinToString(" ")?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val pre = binding.tvTranscript.text.toString()
                    val needBreak = pre.isNotEmpty() && !pre.endsWith("\n")
                    binding.tvTranscript.text = pre + (if (needBreak) "\n" else "") + text
                    binding.tvInterim.text = ""
                    if (binding.chAutoScroll.isChecked) scrollToBottom()
                }
                if (!manualStop) restartSoon()
            }

            override fun onPartialResults(partialResults: Bundle) {
                val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                binding.tvInterim.text = text
                if (binding.chAutoScroll.isChecked) scrollToBottom()
            }
        })
    }

    private fun intentForRecognizer(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
    }

    private fun ensureMicPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        manualStop = false
        listening = true
        binding.btnToggle.text = getString(R.string.stop_listen)
        binding.tvTip.visibility = View.GONE
        recognizer?.startListening(intentForRecognizer())
    }

    private fun stopListening(manual: Boolean) {
        manualStop = manual
        listening = false
        binding.btnToggle.text = getString(R.string.start_listen)
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    private fun restartIfListening() {
        if (listening) {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            binding.root.postDelayed({
                if (listening && !manualStop) startListening()
            }, 200)
        }
    }

    private fun restartSoon() {
        if (!manualStop) {
            binding.root.postDelayed({
                if (!manualStop) recognizer?.startListening(intentForRecognizer())
            }, 300)
        }
    }

    private fun scrollToBottom() {
        binding.scroll.post {
            binding.scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun toggleFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer?.destroy() } catch (_: Exception) {}
    }
}

// Helper: simple item selected listener
import android.view.ViewGroup
import android.widget.AdapterView

class SimpleItemSelected(private val onSelected: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
        onSelected(position)
    }
    override fun onNothingSelected(parent: AdapterView<*>) {}
}

