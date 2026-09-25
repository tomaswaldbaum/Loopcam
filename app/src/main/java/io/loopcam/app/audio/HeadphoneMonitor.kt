package io.loopcam.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Indica si hay auriculares conectados. Sin auriculares el metrónomo sale por el
 * parlante y el micrófono lo capta, así que la UI avisa.
 */
@Singleton
class HeadphoneMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _connected = MutableStateFlow(isAnyHeadphoneConnected())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    _connected.value = isAnyHeadphoneConnected()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    _connected.value = isAnyHeadphoneConnected()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun isAnyHeadphoneConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }

    private companion object {
        val headphoneTypes: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
