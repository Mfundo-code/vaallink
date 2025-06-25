package com.vaallink

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

class VaalVpnService : VpnService() {
    companion object {
        private const val TAG = "VaalVpnService"
        const val VPN_REQUEST_CODE = 0x0F
        private const val HEARTBEAT_INTERVAL = 3000L
        private const val HEARTBEAT_TIMEOUT = 5000L
        private const val PREFS_NAME = "VpnConfigPrefs"
        private const val HEADER_SIZE = 7

        @Volatile
        var isActive: Boolean = false

        val isConnected = AtomicBoolean(false)

        fun broadcastStatus(context: Context) {
            val intent = Intent("com.vaallink.VPN_STATUS").apply {
                putExtra("active", isActive)
                putExtra("connected", isConnected.get())
            }
            context.sendBroadcast(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var udpThread: Thread? = null
    private var tcpThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var udpChannel: DatagramChannel? = null
    private var tcpChannel: SocketChannel? = null
    private val isRunning = AtomicBoolean(false)
    private var relayServer: String = ""
    private var udpPort: Int = 52000
    private var tcpPort: Int = 52001
    private var role: String = ""
    private var sessionCode: String = ""
    private lateinit var relayAddr: InetAddress
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isActive = false
        isConnected.set(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received start command with intent: ${intent?.action}")

        if (intent == null) {
            Log.w(TAG, "Null intent, loading config from SharedPreferences")
            loadConfigFromPrefs()
        } else {
            saveConfigToPrefs(intent.extras)
            loadConfigFromIntent(intent)
        }

        try {
            relayAddr = InetAddress.getByName(relayServer)
            startVpn()
        } catch (e: Exception) {
            Log.e(TAG, "DNS resolution failed: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun saveConfigToPrefs(extras: Bundle?) {
        if (extras == null) return
        
        with(prefs.edit()) {
            putString("relayServer", extras.getString("relayServer", ""))
            putInt("udpPort", extras.getInt("udpPort", 52000))
            putInt("tcpPort", extras.getInt("tcpPort", 52001))
            putString("role", extras.getString("role", ""))
            putString("sessionCode", extras.getString("sessionCode", ""))
            apply()
        }
    }

    private fun loadConfigFromPrefs() {
        with(prefs) {
            relayServer = getString("relayServer", "") ?: ""
            udpPort = getInt("udpPort", 52000)
            tcpPort = getInt("tcpPort", 52001)
            role = getString("role", "") ?: ""
            sessionCode = getString("sessionCode", "") ?: ""
            
            Log.d(TAG, "Loaded config from SharedPreferences:")
            Log.d(TAG, "Role: $role")
            Log.d(TAG, "Relay: $relayServer")
            Log.d(TAG, "UDP Port: $udpPort")
            Log.d(TAG, "TCP Port: $tcpPort")
            Log.d(TAG, "Session: $sessionCode")
        }
    }

    private fun loadConfigFromIntent(intent: Intent) {
        val extras = intent.extras ?: run {
            Log.w(TAG, "Intent has no extras, using SharedPreferences config")
            loadConfigFromPrefs()
            return
        }

        relayServer = extras.getString("relayServer") ?: prefs.getString("relayServer", "") ?: ""
        udpPort = extras.getInt("udpPort", prefs.getInt("udpPort", 52000))
        tcpPort = extras.getInt("tcpPort", prefs.getInt("tcpPort", 52001))
        role = extras.getString("role") ?: prefs.getString("role", "") ?: ""
        sessionCode = extras.getString("sessionCode") ?: prefs.getString("sessionCode", "") ?: ""

        Log.d(TAG, "Loaded config from intent:")
        Log.d(TAG, "Role: $role")
        Log.d(TAG, "Relay: $relayServer")
        Log.d(TAG, "UDP Port: $udpPort")
        Log.d(TAG, "TCP Port: $tcpPort")
        Log.d(TAG, "Session: $sessionCode")
    }

    private fun startVpn() {
        if (relayServer.isEmpty() || sessionCode.isEmpty() || role.isEmpty()) {
            Log.e(TAG, "Missing required VPN parameters")
            stopSelf()
            return
        }

        val builder = Builder()
        builder.setSession("VaalLink VPN")
        builder.setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
            Log.d(TAG, "Excluded $packageName from VPN tunnel")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to exclude app from VPN: ${e.message}")
        }

        // Configure based on role
        if (role == "host") {
            builder.addAddress("10.8.0.1", 24)
            try {
                builder.addRoute(relayAddr, 32)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add route for relayAddr: ${e.message}")
            }
        } else {
            builder.addAddress("10.8.0.2", 24)
            builder.addRoute("0.0.0.0", 0)  // Route ALL traffic through VPN
        }

        // Add DNS servers
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("8.8.4.4")
        builder.addDnsServer("1.1.1.1")

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface establishment returned null")
                stopSelf()
                return
            }
            Log.i(TAG, "VPN interface established successfully")
        } catch (e: Exception) {
            Log.e(TAG, "VPN establishment failed: ${e.message}", e)
            stopSelf()
            return
        }

        isRunning.set(true)
        isActive = true
        isConnected.set(false)
        broadcastStatus(this)

        udpThread = Thread { handleUdpTraffic() }.apply { start() }
        tcpThread = Thread { handleTcpTraffic() }.apply { start() }
        heartbeatThread = Thread { sendHeartbeats() }.apply { start() }
    }

    private fun reconnectUdp() {
        try {
            udpChannel?.close()
            udpChannel = DatagramChannel.open()
            val relaySocketAddr = InetSocketAddress(relayAddr, udpPort)
            udpChannel!!.connect(relaySocketAddr)
            
            // Re-register
            val sessionBytes = sessionCode.toByteArray()
            val registration = ByteArray(sessionBytes.size + 1).apply {
                System.arraycopy(sessionBytes, 0, this, 0, sessionBytes.size)
                this[sessionBytes.size] = if (role == "host") 1 else 0
            }
            udpChannel!!.write(ByteBuffer.wrap(registration))
            Log.d(TAG, "UDP re-registration sent for session $sessionCode")
        } catch (e: Exception) {
            Log.e(TAG, "UDP reconnect failed: ${e.message}")
        }
    }

    private fun sendHeartbeats() {
        val ackBuffer = ByteBuffer.allocate(1)
        while (isRunning.get()) {
            try {
                if (udpChannel == null || !udpChannel!!.isOpen) {
                    reconnectUdp()
                    Thread.sleep(1000)
                    continue
                }
                
                // Send heartbeat
                val heartbeat = ByteArray(HEADER_SIZE).apply {
                    val codeBytes = sessionCode.toByteArray()
                    System.arraycopy(codeBytes, 0, this, 0, codeBytes.size.coerceAtMost(6))
                    this[6] = if (role == "host") 1 else 0
                }
                udpChannel!!.write(ByteBuffer.wrap(heartbeat))
                
                // Wait for ACK with timeout
                ackBuffer.clear()
                val startTime = System.currentTimeMillis()
                var ackReceived = false
                
                while (System.currentTimeMillis() - startTime < HEARTBEAT_TIMEOUT) {
                    if (udpChannel!!.read(ackBuffer) > 0) {
                        ackReceived = true
                        break
                    }
                    Thread.sleep(100)
                }
                
                if (!ackReceived) {
                    Log.w(TAG, "Heartbeat timeout")
                    if (isConnected.get()) {
                        isConnected.set(false)
                        broadcastStatus(this@VaalVpnService)
                    }
                    reconnectUdp()
                } else {
                    Log.v(TAG, "Heartbeat ACK received")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
                if (isConnected.get()) {
                    isConnected.set(false)
                    broadcastStatus(this@VaalVpnService)
                }
            }

            try {
                Thread.sleep(HEARTBEAT_INTERVAL)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun handleUdpTraffic() {
        try {
            vpnInterface?.fileDescriptor?.let { fd ->
                FileInputStream(fd).use { input ->
                    FileOutputStream(fd).use { output ->
                        DatagramChannel.open().use { channel ->
                            udpChannel = channel
                            val relaySocketAddr = InetSocketAddress(relayAddr, udpPort)
                            channel.connect(relaySocketAddr)

                            // Registration packet
                            val sessionBytes = sessionCode.toByteArray()
                            val registration = ByteArray(sessionBytes.size + 1).apply {
                                System.arraycopy(sessionBytes, 0, this, 0, sessionBytes.size)
                                this[sessionBytes.size] = if (role == "host") 1 else 0
                            }
                            channel.write(ByteBuffer.wrap(registration))
                            Log.d(TAG, "UDP registration sent for session $sessionCode")

                            // Wait for ACK
                            val ackBuffer = ByteBuffer.allocate(1)
                            val ackBytes = channel.read(ackBuffer)
                            if (ackBytes > 0) {
                                Log.d(TAG, "UDP registration confirmed")
                                isConnected.set(true)
                                broadcastStatus(this@VaalVpnService)
                            } else {
                                Log.e(TAG, "UDP registration ACK failed")
                            }

                            val buffer = ByteBuffer.allocate(65535)
                            while (isRunning.get()) {
                                // VPN -> Relay
                                buffer.clear()
                                val bytesRead = try {
                                    input.read(buffer.array())
                                } catch (e: IOException) {
                                    Log.e(TAG, "Input read error: ${e.message}")
                                    -1
                                }

                                if (bytesRead > 0) {
                                    val packet = ByteArray(bytesRead + HEADER_SIZE).apply {
                                        System.arraycopy(sessionCode.toByteArray(), 0, this, 0, 6)
                                        this[6] = if (role == "host") 1 else 0
                                        System.arraycopy(buffer.array(), 0, this, HEADER_SIZE, bytesRead)
                                    }
                                    try {
                                        channel.write(ByteBuffer.wrap(packet))
                                    } catch (e: IOException) {
                                        Log.e(TAG, "UDP write error: ${e.message}")
                                        reconnectUdp()
                                    }
                                }

                                // Relay -> VPN
                                buffer.clear()
                                val bytesReceived = try {
                                    channel.read(buffer)
                                } catch (e: IOException) {
                                    Log.e(TAG, "UDP read error: ${e.message}")
                                    -1
                                }

                                if (bytesReceived > 0) {
                                    when {
                                        bytesReceived < HEADER_SIZE -> {
                                            // Control packet, ignore
                                        }
                                        else -> {
                                            try {
                                                output.write(
                                                    buffer.array(), 
                                                    HEADER_SIZE, 
                                                    bytesReceived - HEADER_SIZE
                                                )
                                            } catch (e: IOException) {
                                                Log.e(TAG, "Output write error: ${e.message}")
                                            }
                                        }
                                    }
                                }

                                Thread.yield()
                            }
                        }
                    }
                }
            } ?: run {
                Log.e(TAG, "VPN file descriptor is null in UDP handler")
            }
        } catch (e: IOException) {
            Log.e(TAG, "UDP error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in UDP handler", e)
        } finally {
            Log.i(TAG, "UDP thread exiting")
            if (isConnected.get()) {
                isConnected.set(false)
                broadcastStatus(this)
            }
        }
    }

    private fun handleTcpTraffic() {
        try {
            val relaySocketAddr = InetSocketAddress(relayAddr, tcpPort)
            SocketChannel.open(relaySocketAddr).use { channel ->
                tcpChannel = channel
                channel.configureBlocking(true)
                
                val header = ByteArray(HEADER_SIZE).apply {
                    System.arraycopy(sessionCode.toByteArray(), 0, this, 0, 6)
                    this[6] = if (role == "host") 1 else 0
                }
                channel.write(ByteBuffer.wrap(header))
                Log.d(TAG, "TCP registration sent for session $sessionCode")

                // Wait for ACK
                val ackBuffer = ByteBuffer.allocate(1)
                var ackReceived = false
                val startTime = System.currentTimeMillis()
                
                while (!ackReceived && System.currentTimeMillis() - startTime < 5000) {
                    if (channel.read(ackBuffer) > 0) {
                        ackReceived = true
                        Log.d(TAG, "TCP registration confirmed")
                    } else {
                        Thread.sleep(100)
                    }
                }
                
                if (!ackReceived) {
                    Log.e(TAG, "TCP registration ACK timeout")
                    return
                }

                vpnInterface?.let { nonNullVpnInterface ->
                    Log.d(TAG, "Starting TCP forwarding")

                    val toRelay = Thread {
                        forwardTcpToRelay(nonNullVpnInterface, channel)
                    }
                    val fromRelay = Thread {
                        forwardTcpFromRelay(channel, nonNullVpnInterface)
                    }

                    toRelay.start()
                    fromRelay.start()

                    toRelay.join()
                    fromRelay.join()
                } ?: run {
                    Log.e(TAG, "VPN interface is null")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "TCP error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in TCP handler", e)
        } finally {
            Log.i(TAG, "TCP thread exiting")
        }
    }

    private fun forwardTcpToRelay(
        vpnInterface: ParcelFileDescriptor,
        dest: SocketChannel
    ) {
        try {
            FileInputStream(vpnInterface.fileDescriptor).use { input ->
                val buffer = ByteArray(32768)
                while (isRunning.get()) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        dest.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                    }
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) Log.e(TAG, "TCP to relay error: ${e.message}", e)
        } finally {
            try {
                dest.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing TCP channel: ${e.message}")
            }
        }
    }

    private fun forwardTcpFromRelay(
        source: SocketChannel,
        vpnInterface: ParcelFileDescriptor
    ) {
        try {
            FileOutputStream(vpnInterface.fileDescriptor).use { output ->
                val buffer = ByteBuffer.allocate(32768)
                while (isRunning.get()) {
                    buffer.clear()
                    val bytesRead = source.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        buffer.flip()
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        output.write(data)
                    }
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) Log.e(TAG, "TCP from relay error: ${e.message}", e)
        } finally {
            try {
                source.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing TCP channel: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Stopping VPN service")
        isRunning.set(false)
        isActive = false
        isConnected.set(false)
        broadcastStatus(this)

        // Clear saved configuration
        prefs.edit().clear().apply()

        try {
            udpChannel?.close()
            tcpChannel?.close()
            Log.d(TAG, "Network channels closed")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing channels: ${e.message}")
        }

        try {
            vpnInterface?.close()
            Log.d(TAG, "VPN interface closed")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }

        udpThread?.interrupt()
        tcpThread?.interrupt()
        heartbeatThread?.interrupt()
        
        // Wait for threads to finish
        try {
            udpThread?.join(2000)
            tcpThread?.join(2000)
            heartbeatThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Thread join interrupted", e)
        }

        Log.i(TAG, "VPN service destroyed")
    }
}