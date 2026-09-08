package com.thatsimpletech.assist.device

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import com.thatsimpletech.assist.core.device.DeviceControl
import com.thatsimpletech.assist.core.grammar.VolumeChange
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.intent.PermissionGate

/**
 * Direct device APIs (Workstream E). No AndroidX. No WifiManager / BluetoothAdapter.
 * Volume always passes [AudioManager.FLAG_SHOW_UI] so the slider is visible.
 */
class DeviceControls(
    private val context: Context,
    private val gate: PermissionGate,
) {
    init {
        // Volume always uses the framework slider bit; keep the core constant honest.
        check(AudioManager.FLAG_SHOW_UI == DeviceControl.FLAG_SHOW_UI)
    }
    fun torch(on: Boolean): ExecResult {
        val cam = context.getSystemService(CameraManager::class.java)
            ?: return ExecResult.error("no camera torch")
        val id = torchCameraId(cam) ?: return ExecResult.error("no camera torch")
        return try {
            cam.setTorchMode(id, on)
            ExecResult.OK
        } catch (_: SecurityException) {
            gate.missingCamera() ?: ExecResult.error("no camera torch")
        } catch (_: Exception) {
            ExecResult.error("no camera torch")
        }
    }

    fun dnd(on: Boolean): ExecResult {
        gate.missingDnd()?.let { return it }
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return ExecResult.error("Do Not Disturb access is off; grant it in Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS")
        return try {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            ExecResult.OK
        } catch (_: SecurityException) {
            gate.missingDnd() ?: ExecResult.error("Do Not Disturb access is off; grant it in Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS")
        } catch (_: Exception) {
            ExecResult.error("Do Not Disturb access is off; grant it in Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS")
        }
    }

    fun brightness(percent: Int): ExecResult {
        gate.missingWriteSettings()?.let { return it }
        val cr = context.contentResolver
        val value = (DeviceControl.clampPercent(percent) * 255) / 100
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
            ExecResult.OK
        } catch (_: SecurityException) {
            gate.missingWriteSettings() ?: ExecResult.error("write settings is off; grant it in Settings.ACTION_MANAGE_WRITE_SETTINGS")
        } catch (_: Exception) {
            ExecResult.error("write settings is off; grant it in Settings.ACTION_MANAGE_WRITE_SETTINGS")
        }
    }

    fun volume(change: VolumeChange): ExecResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return ExecResult.error("volume is unavailable")
        // FLAG_SHOW_UI is mandatory: the person must see the slider (Workstream E).
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (change) {
                VolumeChange.Up -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
                VolumeChange.Down -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
                is VolumeChange.Percent -> {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val n = DeviceControl.clampPercent(change.n)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (n * max) / 100, flags)
                }
            }
            ExecResult.OK
        } catch (_: Exception) {
            ExecResult.error("volume is unavailable")
        }
    }

    private fun torchCameraId(cam: CameraManager): String? {
        val ids = try {
            cam.cameraIdList
        } catch (_: Exception) {
            return null
        }
        for (id in ids) {
            val chars = try {
                cam.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) return id
        }
        return null
    }
}
