package com.dshpet.android

import android.content.Context

/** 极简本地偏好（尺寸 / 是否常驻），不依赖任何第三方库 */
object Prefs {
    private const val FILE = "dsh_pet"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_RUNNING = "running"

    const val DEFAULT_SIZE_DP = 150
    const val MIN_SIZE_DP = 80
    const val MAX_SIZE_DP = 360

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sizeDp(ctx: Context): Int = sp(ctx).getInt(KEY_SIZE, DEFAULT_SIZE_DP)

    fun setSizeDp(ctx: Context, dp: Int) {
        sp(ctx).edit().putInt(KEY_SIZE, dp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun running(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_RUNNING, v).apply()
    }
}
