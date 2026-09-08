package com.thatsimpletech.assist.core.device

import com.thatsimpletech.assist.core.grammar.VolumeChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceControlTest {
    @Test
    fun brightnessPercentClampsTo0Through100() {
        assertEquals(0, DeviceControl.brightness(-5).percent)
        assertEquals(0, DeviceControl.brightness(0).percent)
        assertEquals(50, DeviceControl.brightness(50).percent)
        assertEquals(100, DeviceControl.brightness(100).percent)
        assertEquals(100, DeviceControl.brightness(150).percent)
        assertEquals(0, DeviceControl.clampPercent(-1))
        assertEquals(100, DeviceControl.clampPercent(101))
    }

    @Test
    fun volumePercentClampsTo0Through100() {
        val low = DeviceControl.volume(VolumeChange.Percent(-1)).change
        assertIs<VolumeChange.Percent>(low)
        assertEquals(0, low.n)
        val high = DeviceControl.volume(VolumeChange.Percent(999)).change
        assertIs<VolumeChange.Percent>(high)
        assertEquals(100, high.n)
        assertEquals(VolumeChange.Up, DeviceControl.volume(VolumeChange.Up).change)
        assertEquals(VolumeChange.Down, DeviceControl.volume(VolumeChange.Down).change)
    }

    @Test
    fun torchAndDndAreBooleans() {
        assertEquals(true, DeviceControl.torch(true).on)
        assertEquals(false, DeviceControl.torch(false).on)
        assertEquals(true, DeviceControl.dnd(true).on)
        assertEquals(false, DeviceControl.dnd(false).on)
        val torchOn: Torch = DeviceControl.torch(true)
        val dndOff: Dnd = DeviceControl.dnd(false)
        assertEquals(true, torchOn.on)
        assertEquals(false, dndOff.on)
    }
}
