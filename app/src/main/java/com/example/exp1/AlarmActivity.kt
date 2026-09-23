package com.example.exp1

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Full-screen Accept / Decline screen. Rings (looping alarm) for critical requests. */
class AlarmActivity : AppCompatActivity() {

    private var ackId = ""
    private var ackTitle = ""
    private var ackMessage = ""
    private var alarm = false
    private var quickActions = true
    private var responded = false

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        findViewById<View>(R.id.btnAccept).setOnClickListener { respond(true, null) }
        findViewById<View>(R.id.btnDecline).setOnClickListener { askReason() }
        findViewById<View>(R.id.btnMute).setOnClickListener { stopAlarm() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        ackId = i.getStringExtra(AlarmNotifier.EXTRA_ACK_ID) ?: run { finish(); return }
        ackTitle = i.getStringExtra(AlarmNotifier.EXTRA_TITLE) ?: "Waje's Quail Farm"
        ackMessage = i.getStringExtra(AlarmNotifier.EXTRA_MESSAGE) ?: ""
        alarm = i.getBooleanExtra(AlarmNotifier.EXTRA_ALARM, false)
        quickActions = i.getBooleanExtra(AlarmNotifier.EXTRA_QUICK_ACTIONS, true)
        responded = false

        findViewById<TextView>(R.id.alarmTitle).text = ackTitle
        findViewById<TextView>(R.id.alarmMessage).text = ackMessage
        findViewById<TextView>(R.id.alarmBadge).text = if (alarm) "CRITICAL ALERT" else "REQUEST"
        findViewById<View>(R.id.alarmRoot).setBackgroundColor(Color.parseColor(if (alarm) "#B71C1C" else "#2D5016"))
        findViewById<View>(R.id.btnMute).visibility = if (alarm) View.VISIBLE else View.GONE

        AlarmNotifier.cancel(this, ackId)      // this screen takes over from the notification
        if (alarm) startAlarm() else stopAlarm()

        // Already handled (someone else accepted, or answered on another device)?
        val me = AccountManager(this).getCurrentUsername()
        AckRepository.load(ackId) { req ->
            if (req != null && me != null && !AckRepository.isPendingFor(req, me)) {
                Toast.makeText(this, "This request was already handled.", Toast.LENGTH_SHORT).show()
                responded = true
                finish()
            }
        }
        if (i.getBooleanExtra(AlarmNotifier.EXTRA_DECLINE, false)) askReason()
    }

    // ── Accept / Decline ─────────────────────────────────────────────────────

    private fun setButtonsEnabled(on: Boolean) {
        findViewById<View>(R.id.btnAccept).isEnabled = on
        findViewById<View>(R.id.btnDecline).isEnabled = on
    }

    private fun respond(accepted: Boolean, reason: String?) {
        if (responded) return
        setButtonsEnabled(false)
        AckRepository.respond(this, ackId, accepted, reason) { ok ->
            if (ok) {
                responded = true
                Toast.makeText(this, if (accepted) "Accepted" else "Declined - your reason was sent", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                setButtonsEnabled(true)
                Toast.makeText(this, "Couldn't send your response. Check your connection and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askReason() {
        val input = EditText(this).apply {
            hint = "Reason for declining (required)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Decline - why?")
            .setView(box)
            .setPositiveButton("Send", null)
            .setNegativeButton("Back", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = input.text.toString().trim()
                if (reason.length < 3) {
                    input.error = "Please give a short reason"
                    return@setOnClickListener
                }
                dialog.dismiss()
                respond(false, reason)
            }
        }
        dialog.show()
    }

    // ── Alarm sound ──────────────────────────────────────────────────────────

    private fun startAlarm() {
        if (player != null) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio = am
            val target = (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).toInt()
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current < target) {          // make sure it's audible; restored when the alarm stops
                savedVolume = current
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
            vibrate()
            handler.postDelayed(autoStop, 5 * 60 * 1000L)   // never ring forever
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator = v
        val pattern = longArrayOf(0, 800, 400, 800, 400)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else v.vibrate(pattern, 0)
    }

    private fun stopAlarm() {
        handler.removeCallbacks(autoStop)
        player?.let {
            try { it.stop() } catch (e: Exception) { /* not started */ }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
        findViewById<View>(R.id.btnMute)?.visibility = View.GONE
    }

    override fun onDestroy() {
        stopAlarm()
        // Left without answering: keep it as a quiet reminder with Accept/Decline in the shade.
        if (isFinishing && !responded && ackId.isNotEmpty()) {
            AlarmNotifier.show(applicationContext, ackId, ackTitle, ackMessage, alarm = false, quickActions = quickActions)
        }
        super.onDestroy()
    }
}