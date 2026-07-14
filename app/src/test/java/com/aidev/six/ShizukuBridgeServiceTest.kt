package com.aidev.six

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuBridgeServiceTest {

    @Test
    fun allow_knownPrefixes() {
        assertTrue(ShizukuBridgeService.isCommandAllowed("pm list packages com.aidev.six"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("am force-stop com.aidev.six"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("input tap 100 200"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("dumpsys battery"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("cmd package list packages"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("svc wifi enable"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("getprop ro.build.version.sdk"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("settings get system screen_brightness"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("wm size"))
        assertTrue(ShizukuBridgeService.isCommandAllowed("logcat -d"))
    }

    @Test
    fun reject_injectionMetachars() {
        // 前缀合法但含注入元字符 -> 拒绝
        assertFalse(ShizukuBridgeService.isCommandAllowed("am start -n com.x/.A ; rm -rf /data"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("pm list packages | tee /sdcard/x"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("input text \"\$(reboot)\""))
        assertFalse(ShizukuBridgeService.isCommandAllowed("dumpsys `id`"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("am start && reboot"))
    }

    @Test
    fun reject_dangerousVerbs() {
        assertFalse(ShizukuBridgeService.isCommandAllowed("reboot"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("rm -rf /data"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("dd if=/dev/zero of=/dev/block/by-name/boot"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("chmod 777 /system/bin/sh"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("mount -o rw,remount /system"))
    }

    @Test
    fun reject_arbitrarySafeCharCommands() {
        // 旧逻辑靠「全是安全字符」放行，新逻辑仅认前缀白名单
        assertFalse(ShizukuBridgeService.isCommandAllowed("getenforce"))
        assertFalse(ShizukuBridgeService.isCommandAllowed("id"))
        assertFalse(ShizukuBridgeService.isCommandAllowed(""))
    }
}
