package com.dshpet.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

/**
 * 设置界面：授权 + 开关 + 大小 + 厂商后台保活提示。
 * 刻意不用任何第三方 UI 库（零依赖构建，包体只有素材）。
 */
class MainActivity : Activity() {

    private lateinit var petName: TextView
    private lateinit var status: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusRun: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnSound: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        petName = findViewById(R.id.petName)
        status = findViewById(R.id.status)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusNotif = findViewById(R.id.statusNotif)
        statusRun = findViewById(R.id.statusRun)
        sizeLabel = findViewById(R.id.sizeLabel)
        seek = findViewById(R.id.seekSize)
        btnSound = findViewById(R.id.btnSound)

        // 宠物名字来自 pet-config.json（现在是「大肥鱼」）
        try {
            PetConfig.load(this).pets.firstOrNull()?.let {
                if (it.name.isNotBlank()) petName.text = it.name
            }
        } catch (_: Throwable) {
        }

        (findViewById<TextView>(R.id.versionTag))?.text = versionName()

        val sizeDp = Prefs.sizeDp(this)
        seek.max = Prefs.MAX_SIZE_DP - Prefs.MIN_SIZE_DP
        seek.progress = sizeDp - Prefs.MIN_SIZE_DP
        sizeLabel.text = getString(R.string.size_value, sizeDp)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = Prefs.MIN_SIZE_DP + progress
                sizeLabel.text = getString(R.string.size_value, dp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val dp = Prefs.MIN_SIZE_DP + (sb?.progress ?: 0)
                Prefs.setSizeDp(this@MainActivity, dp)
                if (Prefs.running(this@MainActivity) && Settings.canDrawOverlays(this@MainActivity)) {
                    PetService.reload(this@MainActivity)
                }
                refresh()
            }
        })

        findViewById<TextView>(R.id.btnOverlay).setOnClickListener {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(i)
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        findViewById<TextView>(R.id.btnNotif).setOnClickListener { requestNotif() }

        // 点击音效开关：立即生效，并给一声试听
        btnSound.setOnClickListener {
            val on = !Prefs.soundOn(this)
            Prefs.setSoundOn(this, on)
            Sfx.enabled = on
            chipSound(on)
            if (on) Sfx.press()
        }

        findViewById<TextView>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                showHint(getString(R.string.need_overlay))
                return@setOnClickListener
            }
            hideHint()
            if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) requestNotif()
            Prefs.setRunning(this, true)
            PetService.start(this)
            status.postDelayed({ refresh() }, 300)
        }

        findViewById<TextView>(R.id.btnStop).setOnClickListener {
            Prefs.setRunning(this, false)
            PetService.stop(this)
            status.postDelayed({ refresh() }, 300)
        }

        // 状态标签本身也能点，直接跳授权
        statusOverlay.setOnClickListener { findViewById<TextView>(R.id.btnOverlay).performClick() }
        statusNotif.setOnClickListener { findViewById<TextView>(R.id.btnNotif).performClick() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun versionName(): String = try {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        "v" + (info.versionName ?: "1.5")
    } catch (_: Throwable) {
        "v1.5"
    }

    private fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun showHint(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideHint() {
        status.visibility = View.GONE
    }

    private fun chip(v: TextView, ok: Boolean, onText: Int, offText: Int) {
        v.text = getString(if (ok) onText else offText)
        v.setBackgroundResource(if (ok) R.drawable.bg_chip_ok else R.drawable.bg_chip_warn)
        v.setTextColor(if (ok) Color.parseColor("#6BE3B0") else Color.parseColor("#FFC46B"))
    }

    private fun chipSound(on: Boolean) {
        btnSound.text = getString(if (on) R.string.sound_on else R.string.sound_off)
        btnSound.setBackgroundResource(if (on) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        btnSound.setTextColor(
            if (on) Color.parseColor("#6BE3B0") else Color.parseColor("#B9A6FF")
        )
    }

    private fun refresh() {
        chip(statusOverlay, Settings.canDrawOverlays(this), R.string.chip_ok, R.string.chip_no)
        chip(statusNotif, hasNotif(), R.string.chip_ok, R.string.chip_no)

        val running = Prefs.running(this) && Settings.canDrawOverlays(this)
        statusRun.text = getString(if (running) R.string.chip_on_state else R.string.chip_off_state)
        statusRun.setBackgroundResource(if (running) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        statusRun.setTextColor(
            if (running) Color.parseColor("#6BE3B0") else Color.parseColor("#B9A6FF")
        )

        sizeLabel.text = getString(R.string.size_value, Prefs.sizeDp(this))
        chipSound(Prefs.soundOn(this))
    }
}
