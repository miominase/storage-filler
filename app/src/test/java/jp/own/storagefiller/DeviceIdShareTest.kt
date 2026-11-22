package jp.own.storagefiller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdShareTest {
    @Test fun onlyTheTikTokManagerGetsTheNumber() {
        assertEquals("54", DeviceIdShare.answer("jp.own.tiktokdevice", DeviceIdShare.ACTION, " 54 "))
        assertNull(DeviceIdShare.answer("com.example.other", DeviceIdShare.ACTION, "54"))
        assertNull(DeviceIdShare.answer(null, DeviceIdShare.ACTION, "54"))
        assertNull(DeviceIdShare.answer("jp.own.tiktokdevice", "other", "54"))
        assertNull(DeviceIdShare.answer("jp.own.tiktokdevice", DeviceIdShare.ACTION, ""))  // 未設定
        assertNull(DeviceIdShare.answer("jp.own.tiktokdevice", DeviceIdShare.ACTION, "a b"))
    }
}
