package com.andforce.andclaw

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.andforce.andclaw.databinding.ActivityAudioRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUDIO_RECORD_ACTION = "audio_record_action"
        const val ACTION_START_RECORD = "start_record"
        const val ACTION_STOP_RECORD = "stop_record"

        @Volatile
        var lastResult: String? = null

        @Volatile
        var lastAudioUri: Uri? = null
    }

    private lateinit var binding: ActivityAudioRecordBinding
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var pendingAction: String? = null
    private var currentFileName: String? = null
    private var currentFilePath: String? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordStartTime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
                val min = elapsed / 60
                val sec = elapsed % 60
                binding.tvTimer.text = String.format("%02d:%02d", min, sec)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupButtons()

        pendingAction = intent.getStringExtra(EXTRA_AUDIO_RECORD_ACTION)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        } else {
            onPermissionReady()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.getStringExtra(EXTRA_AUDIO_RECORD_ACTION) ?: return
        executeAction(action)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionReady()
        } else {
            lastResult = "录音权限被拒绝"
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onPermissionReady() {
        updateStatus("录音就绪")
        pendingAction?.let {
            pendingAction = null
            executeAction(it)
        }
    }

    private fun setupButtons() {
        binding.btnStartRecord.setOnClickListener { startRecording() }
        binding.btnStopRecord.setOnClickListener { stopRecording() }
    }

    private fun executeAction(action: String) {
        when (action) {
            ACTION_START_RECORD -> startRecording()
            ACTION_STOP_RECORD -> stopRecording()
        }
    }

    private fun startRecording() {
        if (isRecording) {
            updateStatus("已在录音中")
            return
        }

        val fileName = "audio_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.m4a"
        currentFileName = fileName

        val filePath = "${externalCacheDir?.absolutePath}/$fileName"
        currentFilePath = filePath

        try {
            mediaRecorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(filePath)
                prepare()
                start()
            }

            isRecording = true
            recordStartTime = System.currentTimeMillis()
            timerHandler.post(timerRunnable)

            lastResult = "录音已开始"
            updateStatus("正在录音...")
            binding.btnStartRecord.isEnabled = false
            binding.btnStopRecord.isEnabled = true
        } catch (e: Exception) {
            val msg = "录音启动失败: ${e.message}"
            lastResult = msg
            updateStatus(msg)
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            updateStatus("当前没有在录音")
            return
        }

        updateStatus("正在停止录音...")
        timerHandler.removeCallbacks(timerRunnable)

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val filePath = currentFilePath
            val fileName = currentFileName
            if (filePath != null && fileName != null) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Andclaw")
                }
                val uri = contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        java.io.File(filePath).inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    lastAudioUri = uri
                    java.io.File(filePath).delete()
                }

                val msg = "录音完成: Music/Andclaw/$fileName"
                lastResult = msg
                updateStatus(msg)
            }

            binding.btnStartRecord.isEnabled = true
            binding.btnStopRecord.isEnabled = false
            binding.root.postDelayed({ finish() }, 1500)
        } catch (e: Exception) {
            val msg = "停止录音失败: ${e.message}"
            lastResult = msg
            updateStatus(msg)
            isRecording = false
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        if (isRecording) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (_: Exception) { }
            mediaRecorder = null
        }
    }
}
