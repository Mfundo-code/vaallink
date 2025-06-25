package com.vaallink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService  // ADD THIS IMPORT
import android.app.Activity  // ADD THIS IMPORT
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import java.util.concurrent.atomic.AtomicBoolean

class VpnModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.vaallink.VPN_STATUS") {
                val active = intent.getBooleanExtra("active", false)
                val connected = intent.getBooleanExtra("connected", false)
                sendEvent("VpnStatus", Arguments.createMap().apply {
                    putBoolean("active", active)
                    putBoolean("connected", connected)
                })
            }
        }
    }

    init {
        reactContext.registerReceiver(
            statusReceiver, 
            IntentFilter("com.vaallink.VPN_STATUS")
        )
    }

    override fun getName() = "VpnService"

    override fun onCatalystInstanceDestroy() {
        reactContext.unregisterReceiver(statusReceiver)
        super.onCatalystInstanceDestroy()
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactContext
            .getJSModule(RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun prepareVpn(promise: Promise) {
        try {
            // Use fully qualified name since we have a VpnService class
            val intent = android.net.VpnService.prepare(reactContext)
            if (intent != null) {
                // Cast to Activity to resolve the type issue
                (currentActivity as Activity?)?.startActivityForResult(
                    intent, 
                    VaalVpnService.VPN_REQUEST_CODE
                )
                promise.resolve(true)
            } else {
                promise.resolve(true) // Already prepared
            }
        } catch (e: Exception) {
            promise.reject("VPN_PREPARE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun startVpn(config: ReadableMap) {
        val role = config.getString("role") ?: ""
        val relayServer = config.getString("relayServer") ?: ""
        val udpPort = config.getInt("udpPort")
        val tcpPort = config.getInt("tcpPort")
        val sessionCode = config.getString("sessionCode") ?: ""

        Intent(reactContext, VaalVpnService::class.java).apply {
            putExtra("role", role)
            putExtra("relayServer", relayServer)
            putExtra("udpPort", udpPort)
            putExtra("tcpPort", tcpPort)
            putExtra("sessionCode", sessionCode)
            reactContext.startService(this)
        }
    }

    @ReactMethod
    fun stopVpn(promise: Promise) {
        try {
            val intent = Intent(reactContext, VaalVpnService::class.java)
            val stopped = reactContext.stopService(intent)
            
            if (stopped) {
                promise.resolve(true)
            } else {
                promise.reject("VPN_STOP_ERROR", "VPN service was not running")
            }
        } catch (e: Exception) {
            promise.reject("VPN_STOP_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        try {
            promise.resolve(Arguments.createMap().apply {
                putBoolean("active", VaalVpnService.isActive)
                putBoolean("connected", VaalVpnService.isConnected.get())
            })
        } catch (e: Exception) {
            promise.reject("VPN_STATUS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Keep: Required for RN built-in Event Emitter in JS
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Keep: Required for RN built-in Event Emitter in JS
    }
}
