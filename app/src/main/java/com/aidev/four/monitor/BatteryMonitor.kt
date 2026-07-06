package com.aidev.four.monitor

import android.util.Log

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryMonitor(private val context: Context) {
    var level: Int = -1; private set
    var temp: Float = 0f; private set
    var status: String = "未知"; private set
    var health: String = "未知"; private set
    var voltage: Int = 0; private set
    var chargingType: String = "未知"; private set

    private var receiver: BroadcastReceiver? = null

    fun register() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                collectFromIntent(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w("BatteryMonitor", "unregister failed", e)
        }
        receiver = null
    }

    fun collectApiInfo() {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
            val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (cap >= 0) level = cap
        } catch (e: Exception) {
            Log.w("BatteryMonitor", "collectApiInfo failed", e)
        }
    }

    private fun collectFromIntent(intent: Intent) {
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val s = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        status = when (s) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }

        val h = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        health = when (h) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            BatteryManager.BATTERY_HEALTH_DEAD -> "耗尽"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
            else -> "未知"
        }

        val plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        chargingType = when {
            plug and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC 电源"
            plug and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            plug and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "无线充电"
            else -> "未连接"
        }
    }
}
