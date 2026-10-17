package com.dshpet.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/**
 * 设置界面：授权 + 开关 + 大小 + 厂商后台保活提示。
 * 刻意不用任何第三方 UI 库（零依赖构建，包体只有素材）。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var seek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        sizeLabel = findViewById(R.id.sizeLabel)
        seek = findViewById(R.id.seekSize)

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

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
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

        findViewById<Button>(R.id.btnNotif).setOnClickListener { requestNotif() }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = getString(R.string.need_overlay)
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) requestNotif()
            Prefs.setRunning(this, true)
            PetService.start(this)
            status.postDelayed({ refresh() }, 300)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Prefs.setRunning(this, false)
            PetService.stop(this)
            status.postDelayed({ refresh() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = hasNotif()
        val running = Prefs.running(this)
        status.text = buildString {
            append(getString(if (overlay) R.string.perm_ok else R.string.perm_no, getString(R.string.perm_overlay)))
            append('\n')
            append(getString(if (notif) R.string.perm_ok else R.string.perm_no, getString(R.string.perm_notif)))
            append('\n')
            append(getString(R.string.state_value, if (running) getString(R.string.state_on) else getString(R.string.state_off)))
            append('\n')
            append(getString(R.string.size_value, Prefs.sizeDp(this@MainActivity)))
        }
    }
}
