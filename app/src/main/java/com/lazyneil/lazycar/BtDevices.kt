package com.lazyneil.lazycar

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Bonded-device enumeration for the car stereo picker (SettingsActivity). mac,name,iconRes triples. */
object BtDevices {
    const val ICON = android.R.drawable.stat_sys_data_bluetooth

    private val AUDIO_UUIDS = setOf(
        "0000110b-0000-1000-8000-00805f9b34fb",
        "0000111e-0000-1000-8000-00805f9b34fb",
        "0000184e-0000-1000-8000-00805f9b34fb"
    )

    /** note != null when the list is empty for a reason worth showing; canEnable = BT is off. */
    data class Result(
        val audio: List<Triple<String, String, Int>>,
        val note: String?,
        val canEnable: Boolean
    )

    fun load(ctx: Context): Result {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return when {
            !ok -> Result(emptyList(), "Bluetooth permission needed to list paired devices.", false)
            adapter == null -> Result(emptyList(), "No Bluetooth on this device.", false)
            !adapter.isEnabled -> Result(emptyList(), "Bluetooth is off.", true)
            else -> {
                val bonded = try { adapter.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
                val audio = bonded.filter { isAudio(it) }
                val audioTriples = (if (audio.isEmpty()) bonded else audio)
                    .map { Triple(it.address, it.name ?: it.address, iconFor(it)) }
                Result(audioTriples, if (bonded.isEmpty()) "No paired devices." else null, false)
            }
        }
    }

    private fun isAudio(d: BluetoothDevice): Boolean {
        val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: SecurityException) { null }
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        val uuids = try { d.uuids } catch (e: SecurityException) { null } ?: return false
        return uuids.any { it.uuid.toString().lowercase() in AUDIO_UUIDS }
    }

    private fun iconFor(d: BluetoothDevice): Int {
        val dc = try { d.bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
        return when (dc) {
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> R.drawable.ic_car_glyph
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> R.drawable.ic_headphones
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> R.drawable.ic_speaker
            else -> ICON
        }
    }
}
