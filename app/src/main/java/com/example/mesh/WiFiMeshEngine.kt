package com.example.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.entity.CallEntity
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshNodeEntity
import com.example.data.entity.MessageEntity
import com.example.data.repository.ChatMeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class MeshEngineState(
    val isWifiDirectActive: Boolean = false,
    val isWifiAwareActive: Boolean = false,
    val isGroupOwner: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val p2pInterface: String = "",
    val localIpAddress: String = "",
    val isGroupFormed: Boolean = false,
    val hasNearbyDevicesPermission: Boolean = false,
    val p2pStatusMessage: String = "",
    val myPhoneNumber: String = "",
    val myNodeId: String = "",
    val connectedPeersCount: Int = 0,
    val discoveredP2pDevices: List<WifiP2pDevice> = emptyList(),
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsRelayed: Long = 0,
    val activeCallPeer: ContactEntity? = null,
    val isCallActive: Boolean = false,
    val isVideoCall: Boolean = false,
    val callDurationSeconds: Int = 0,
    val isMicMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val activeTypingContactPhone: String? = null,
    val activeRecordingContactPhone: String? = null
)

class WiFiMeshEngine(
    private val context: Context,
    private val repository: ChatMeshRepository
) {
    private val TAG = "WiFiMeshEngine"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _engineState = MutableStateFlow(MeshEngineState())
    val engineState: StateFlow<MeshEngineState> = _engineState.asStateFlow()

    // Real WiFi Direct components
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var isP2pReceiverRegistered = false

    // Real WiFi Aware components
    private var awareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val awarePeerHandles = ConcurrentHashMap<String, PeerHandle>()

    // Real Sockets
    private var serverSocket: ServerSocket? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private val activeClientSockets = ConcurrentHashMap<String, Socket>()
    private val socketWriters = ConcurrentHashMap<String, PrintWriter>()
    private val peerIpByPhone = ConcurrentHashMap<String, String>()

    // WiFi Direct connection state machine
    private val chatMeshPeers = ConcurrentHashMap<String, WifiP2pDevice>()
    private val peerNodeIdByDeviceAddress = ConcurrentHashMap<String, String>()
    @Volatile private var isGroupFormed = false
    @Volatile private var isConnectAttemptInProgress = false
    @Volatile private var isRadioStarted = false
    @Volatile private var lastDiscoveryAtMs = 0L
    @Volatile private var discoveryStartedAtMs = 0L

    // Loop prevention & Packet deduplication
    private val processedPacketUuids = ConcurrentHashMap.newKeySet<String>()

    // Background jobs
    private var meshMaintenanceJob: Job? = null
    private var tcpServerJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var callTimerJob: Job? = null
    private var audioRecordJob: Job? = null
    private var audioPlayJob: Job? = null

    // Audio stream port
    private val AUDIO_UDP_PORT = 8890
    private val TCP_MESH_PORT = 8888
    private val UDP_BEACON_PORT = 8889

    // Tiempo de escucha antes de crear un grupo propio: si en esa ventana aparece
    // otro nodo ChatMesh, este dispositivo se une a él en vez de crear otro grupo.
    private val GROUP_FALLBACK_WINDOW_MS = 12_000L
    private val DISCOVERY_INTERVAL_MS = 20_000L
    private val NODE_TIMEOUT_MS = 45_000L

    fun initialize(myPhone: String, myNickname: String) {
        val mySsid = SimDetectionUtil.generateSsid(myPhone)
        val myNodeId = "NODE_" + (if (myPhone.isNotBlank()) myPhone.replace("+", "") else System.currentTimeMillis().toString())

        _engineState.value = _engineState.value.copy(
            myPhoneNumber = myPhone,
            myNodeId = myNodeId,
            ssid = mySsid
        )

        startRealTcpMeshServer()
        startRealUdpBeaconListener()
        startMeshMaintenanceLoop()
        startRadioIfPermitted()
    }

    /**
     * Debe llamarse cuando el usuario concede los permisos de ubicación / dispositivos
     * cercanos: sin ellos las APIs de WiFi Direct fallan silenciosamente.
     */
    fun onPermissionsGranted() {
        startRadioIfPermitted()
    }

    private fun hasNearbyDevicesPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, required) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRadioIfPermitted() {
        val granted = hasNearbyDevicesPermission()
        _engineState.value = _engineState.value.copy(hasNearbyDevicesPermission = granted)
        if (!granted) {
            updateP2pStatus("Faltan permisos de dispositivos cercanos para WiFi Direct")
            return
        }
        if (isRadioStarted) return
        isRadioStarted = true
        setupRealWifiP2p(_engineState.value.ssid)
        setupRealWifiAware(_engineState.value.ssid)
    }

    private fun updateP2pStatus(message: String) {
        Log.i(TAG, message)
        _engineState.value = _engineState.value.copy(p2pStatusMessage = message)
    }

    /**
     * Requirement 1: Creación real de red WiFi Direct
     */
    @SuppressLint("MissingPermission")
    private fun setupRealWifiP2p(ssid: String) {
        try {
            p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (p2pManager == null) {
                updateP2pStatus("WiFi Direct no disponible en este hardware")
                return
            }

            p2pChannel = p2pManager?.initialize(context, context.mainLooper) {
                // El canal se pierde si el servicio del sistema se reinicia: hay que rehacerlo.
                Log.w(TAG, "Canal WiFi Direct desconectado, reinicializando")
                isRadioStarted = false
                isGroupFormed = false
                scope.launch {
                    delay(1000)
                    startRadioIfPermitted()
                }
            }

            registerP2pReceiver()

            // Setup real DNS-SD Service Discovery
            setupP2pDnsSdService(ssid)

            // Antes de crear nada: adoptar el grupo que ya exista y escuchar a los vecinos.
            requestGroupAndConnectionDetails()
            startP2pDiscovery()
            scheduleAutonomousGroupFallback()
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando WiFi Direct real", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun reCreateP2pGroup() {
        val currentSsid = _engineState.value.ssid
        isGroupFormed = false
        p2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createAutonomousP2pGroup(currentSsid)
            }
            override fun onFailure(reason: Int) {
                createAutonomousP2pGroup(currentSsid)
            }
        })
    }

    /**
     * Si tras la ventana de descubrimiento no apareció ningún grupo ChatMesh al que unirse,
     * este dispositivo crea el grupo autónomo para que los demás puedan entrar.
     */
    private fun scheduleAutonomousGroupFallback() {
        discoveryStartedAtMs = System.currentTimeMillis()
        scope.launch {
            delay(GROUP_FALLBACK_WINDOW_MS)
            if (!isGroupFormed && !isConnectAttemptInProgress && chatMeshPeers.isEmpty()) {
                createAutonomousP2pGroup(_engineState.value.ssid)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAutonomousP2pGroup(ssid: String) {
        p2pManager?.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateP2pStatus("Grupo WiFi Direct creado como Group Owner")
                _engineState.value = _engineState.value.copy(
                    isWifiDirectActive = true,
                    isGroupOwner = true
                )
                requestGroupAndConnectionDetails()
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    // Normalmente significa que ya hay un grupo activo: adoptarlo.
                    requestGroupAndConnectionDetails()
                } else {
                    updateP2pStatus("No se pudo crear el grupo (código $reason), buscando pares")
                }
                startP2pDiscovery()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupAndConnectionDetails() {
        p2pManager?.requestGroupInfo(p2pChannel) { group: WifiP2pGroup? ->
            if (group != null) {
                val iface = group.`interface` ?: ""
                _engineState.value = _engineState.value.copy(
                    passphrase = group.passphrase ?: "",
                    p2pInterface = iface,
                    isGroupOwner = group.isGroupOwner,
                    localIpAddress = localIpForInterface(iface) ?: _engineState.value.localIpAddress
                )
                // Register connected clients
                for (client in group.clientList) {
                    handleDiscoveredP2pDevice(client, isConnected = true)
                }
            }
        }

        p2pManager?.requestConnectionInfo(p2pChannel) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                isGroupFormed = true
                isConnectAttemptInProgress = false
                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                _engineState.value = _engineState.value.copy(
                    isWifiDirectActive = true,
                    isGroupFormed = true,
                    isGroupOwner = info.isGroupOwner
                )
                updateP2pStatus(
                    if (info.isGroupOwner) "Grupo formado: este dispositivo es el Group Owner"
                    else "Conectado al grupo del par $ownerIp"
                )
                stopP2pDiscovery()
                if (!info.isGroupOwner) {
                    connectToMeshSocket(ownerIp, TCP_MESH_PORT)
                }
                sendUdpDiscoveryBeacon()
            } else {
                isGroupFormed = false
                _engineState.value = _engineState.value.copy(isGroupFormed = false)
            }
        }
    }

    /** IP real asignada por el framework en la interfaz p2p (p2p-wlan0-0, etc). */
    private fun localIpForInterface(interfaceName: String): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { interfaceName.isBlank() || it.name == interfaceName }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun startP2pDiscovery() {
        if (!hasNearbyDevicesPermission()) {
            startRadioIfPermitted()
            return
        }
        try {
            lastDiscoveryAtMs = System.currentTimeMillis()
            p2pManager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _engineState.value = _engineState.value.copy(isWifiDirectActive = true)
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers falló: $reason")
                }
            })
            p2pManager?.discoverServices(p2pChannel, null)
        } catch (_: Exception) {}
    }

    private fun stopP2pDiscovery() {
        try {
            p2pManager?.stopPeerDiscovery(p2pChannel, null)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        isConnectAttemptInProgress = true
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Intención baja: el par que recibe la invitación se queda de Group Owner,
            // así el rol queda decidido y no se forman dos grupos separados.
            groupOwnerIntent = 0
        }
        p2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateP2pStatus("Invitación enviada a ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                isConnectAttemptInProgress = false
                updateP2pStatus("Fallo al conectar con ${device.deviceName} (código $reason)")
            }
        })
    }

    /**
     * Auto-conexión con desempate determinista: solo el nodo con identificador menor
     * lanza la invitación, de modo que dos dispositivos no se invitan a la vez.
     */
    private fun maybeAutoConnect(device: WifiP2pDevice) {
        if (isGroupFormed || isConnectAttemptInProgress) return
        if (device.status == WifiP2pDevice.CONNECTED || device.status == WifiP2pDevice.INVITED) return

        val peerNodeId = peerNodeIdByDeviceAddress[device.deviceAddress]
        val myNodeId = _engineState.value.myNodeId
        if (peerNodeId != null && myNodeId.isNotBlank() && myNodeId > peerNodeId) {
            // El otro extremo es quien invita; aquí solo se espera la invitación.
            return
        }
        connectToPeer(device)
    }

    @SuppressLint("MissingPermission")
    private fun setupP2pDnsSdService(ssid: String) {
        try {
            val record = mapOf(
                "phone" to _engineState.value.myPhoneNumber,
                "ssid" to ssid,
                "node" to _engineState.value.myNodeId,
                "port" to TCP_MESH_PORT.toString()
            )
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("ChatMesh", "_chatmesh._tcp", record)
            p2pManager?.addLocalService(p2pChannel, serviceInfo, null)

            val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance("_chatmesh._tcp")
            p2pManager?.setDnsSdResponseListeners(p2pChannel,
                { instanceName, registrationType, device ->
                    if (instanceName == "ChatMesh" || registrationType.startsWith("_chatmesh")) {
                        chatMeshPeers[device.deviceAddress] = device
                        handleDiscoveredP2pDevice(device, isConnected = false)
                        maybeAutoConnect(device)
                    }
                },
                { _, txtRecordMap, device ->
                    val phone = txtRecordMap["phone"] ?: ""
                    val remoteSsid = txtRecordMap["ssid"] ?: device.deviceName
                    txtRecordMap["node"]?.let { peerNodeIdByDeviceAddress[device.deviceAddress] = it }
                    chatMeshPeers[device.deviceAddress] = device
                    if (phone.isNotBlank()) {
                        registerNodeFromDnsSd(phone, remoteSsid, device.deviceAddress)
                    }
                    maybeAutoConnect(device)
                }
            )
            p2pManager?.addServiceRequest(p2pChannel, serviceRequest, null)
        } catch (_: Exception) {}
    }

    private fun registerP2pReceiver() {
        if (isP2pReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        try {
            // A partir de targetSdk 34 registrar un receptor sin indicar la exportación
            // lanza SecurityException y la app se queda sin eventos de WiFi Direct.
            ContextCompat.registerReceiver(
                context,
                p2pReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isP2pReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar el receptor WiFi Direct", e)
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _engineState.value = _engineState.value.copy(isWifiDirectActive = enabled)
                    if (enabled) {
                        startP2pDiscovery()
                    } else {
                        updateP2pStatus("WiFi Direct desactivado: activa el WiFi del dispositivo")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    p2pManager?.requestPeers(p2pChannel) { peers ->
                        peers?.deviceList?.let { deviceList ->
                            _engineState.value = _engineState.value.copy(
                                discoveredP2pDevices = deviceList.toList()
                            )
                            for (device in deviceList) {
                                val isChatMesh = chatMeshPeers.containsKey(device.deviceAddress) ||
                                    (device.deviceName ?: "").startsWith("ChatMesh_")
                                handleDiscoveredP2pDevice(
                                    device,
                                    isConnected = device.status == WifiP2pDevice.CONNECTED
                                )
                                if (isChatMesh) {
                                    chatMeshPeers[device.deviceAddress] = device
                                    maybeAutoConnect(device)
                                }
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestGroupAndConnectionDetails()
                }
            }
        }
    }

    private fun handleDiscoveredP2pDevice(device: WifiP2pDevice, isConnected: Boolean) {
        scope.launch {
            val devName = device.deviceName ?: "Dispositivo WiFi Direct"
            val phone = if (devName.startsWith("ChatMesh_")) {
                devName.removePrefix("ChatMesh_")
            } else {
                ""
            }

            val node = MeshNodeEntity(
                nodeId = "NODE_" + device.deviceAddress.replace(":", ""),
                ssid = devName,
                phoneNumber = phone.ifEmpty { "P2P:" + device.deviceAddress.takeLast(8) },
                nickname = devName,
                ipAddress = peerIpByPhone[phone] ?: "",
                port = TCP_MESH_PORT,
                connectionType = "WIFI_DIRECT",
                isDirectNeighbor = true,
                hopDistance = 1,
                lastSeen = System.currentTimeMillis(),
                isActive = isConnected
            )
            repository.saveMeshNode(node)

            if (phone.isNotBlank()) {
                val contact = repository.getContact(phone)
                if (contact != null) {
                    repository.saveContact(contact.copy(isRegisteredInMesh = true, isConnected = isConnected))
                }
            }
        }
    }

    private fun registerNodeFromDnsSd(phone: String, ssid: String, macAddress: String) {
        scope.launch {
            val node = MeshNodeEntity(
                nodeId = "NODE_" + macAddress.replace(":", ""),
                ssid = ssid,
                phoneNumber = phone,
                nickname = ssid,
                ipAddress = peerIpByPhone[phone] ?: "",
                port = TCP_MESH_PORT,
                connectionType = "WIFI_DIRECT_DNS_SD",
                isDirectNeighbor = true,
                hopDistance = 1,
                lastSeen = System.currentTimeMillis(),
                isActive = true
            )
            repository.saveMeshNode(node)

            val contact = repository.getContact(phone)
            if (contact != null) {
                repository.saveContact(contact.copy(isRegisteredInMesh = true, isConnected = true))
            }
        }
    }

    /**
     * Requirement 2: Uso de WiFi Aware real (NAN)
     */
    private fun setupRealWifiAware(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
                Log.i(TAG, "WiFi Aware (NAN) no soportado en este chip")
                return
            }

            awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            if (awareManager?.isAvailable == true) {
                attachRealWifiAware(ssid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando WiFi Aware", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachRealWifiAware(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            awareManager?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession?) {
                    awareSession = session
                    _engineState.value = _engineState.value.copy(isWifiAwareActive = true)
                    publishRealAwareService(ssid)
                    subscribeRealAwareService()
                }

                override fun onAttachFailed() {
                    _engineState.value = _engineState.value.copy(isWifiAwareActive = false)
                }
            }, null)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun publishRealAwareService(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val config = PublishConfig.Builder()
            .setServiceName("ChatMeshOffline")
            .setServiceSpecificInfo(ssid.toByteArray())
            .build()

        awareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val json = String(message)
                val packet = MeshPacket.fromJson(json)
                if (packet != null) {
                    processIncomingPacket(packet)
                }
            }
        }, null)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeRealAwareService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val config = SubscribeConfig.Builder()
            .setServiceName("ChatMeshOffline")
            .build()

        awareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                val discoveredSsid = if (serviceSpecificInfo != null) String(serviceSpecificInfo) else "ChatMesh_Peer"
                val phone = if (discoveredSsid.startsWith("ChatMesh_")) discoveredSsid.removePrefix("ChatMesh_") else ""
                val peerNodeId = "AWARE_" + peerHandle.hashCode()
                awarePeerHandles[peerNodeId] = peerHandle

                if (phone.isNotBlank()) {
                    awarePeerHandles[phone] = peerHandle
                }

                scope.launch {
                    val node = MeshNodeEntity(
                        nodeId = peerNodeId,
                        ssid = discoveredSsid,
                        phoneNumber = phone.ifEmpty { "AWARE_${peerHandle.hashCode()}" },
                        nickname = discoveredSsid,
                        ipAddress = "192.168.49.1",
                        port = TCP_MESH_PORT,
                        connectionType = "WIFI_AWARE",
                        isDirectNeighbor = true,
                        hopDistance = 1,
                        lastSeen = System.currentTimeMillis(),
                        isActive = true
                    )
                    repository.saveMeshNode(node)

                    if (phone.isNotBlank()) {
                        val contact = repository.getContact(phone)
                        if (contact != null) {
                            repository.saveContact(contact.copy(isRegisteredInMesh = true, isConnected = true, lastSeen = System.currentTimeMillis()))
                        }
                    }
                }
            }
        }, null)
    }

    /**
     * Requirement 1 & 6: Servidor TCP y sockets P2P reales
     */
    private fun startRealTcpMeshServer() {
        if (tcpServerJob?.isActive == true) return
        tcpServerJob = scope.launch {
            while (isActive) {
                try {
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(TCP_MESH_PORT))
                    serverSocket = server
                    Log.i(TAG, "Servidor TCP Malla activo en puerto $TCP_MESH_PORT")
                    while (isActive && !server.isClosed) {
                        handleClientSocket(server.accept())
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Servidor TCP interrumpido: ${e.message}")
                }
                if (!isActive) break
                delay(2000)
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        scope.launch {
            val remoteIp = socket.inetAddress?.hostAddress ?: return@launch
            val existing = activeClientSockets[remoteIp]
            if (existing != null && existing !== socket && !existing.isClosed) {
                // Ya hay un enlace vivo con ese par (ambos extremos marcan a la vez).
                try { socket.close() } catch (_: Exception) {}
                return@launch
            }
            activeClientSockets[remoteIp] = socket
            socket.keepAlive = true
            val writer = PrintWriter(socket.getOutputStream(), true)
            socketWriters[remoteIp] = writer
            sendHandshake(writer)
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val packet = MeshPacket.fromJson(line)
                    if (packet != null) {
                        peerIpByPhone[packet.sourcePhone] = remoteIp
                        processIncomingPacket(packet)
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeClientSockets.remove(remoteIp)
                socketWriters.remove(remoteIp)
                try { socket.close() } catch (_: Exception) {}
                updateConnectedPeersCount()
            }
        }
        updateConnectedPeersCount()
    }

    private fun sendHandshake(writer: PrintWriter) {
        val handshake = MeshPacket(
            packetType = "HANDSHAKE",
            sourceNodeId = _engineState.value.myNodeId,
            sourcePhone = _engineState.value.myPhoneNumber,
            sourceName = _engineState.value.ssid,
            sourceSsid = _engineState.value.ssid,
            destinationPhone = "BROADCAST"
        )
        writeLine(writer, handshake.toJson())
    }

    private fun writeLine(writer: PrintWriter, line: String): Boolean {
        return synchronized(writer) {
            writer.println(line)
            !writer.checkError()
        }
    }

    private fun connectToMeshSocket(host: String, port: Int) {
        if (host.isBlank() || host == _engineState.value.localIpAddress) return
        if (activeClientSockets.containsKey(host)) return
        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 4000)
                handleClientSocket(socket)
            } catch (e: Exception) {
                Log.d(TAG, "No se pudo conectar a $host:$port: ${e.message}")
            }
        }
    }

    private fun updateConnectedPeersCount() {
        _engineState.value = _engineState.value.copy(
            connectedPeersCount = activeClientSockets.size
        )
    }

    /**
     * Descubrimiento UDP broadcast en la subred de WiFi Direct (192.168.49.255)
     */
    private fun startRealUdpBeaconListener() {
        if (udpBeaconJob?.isActive == true) return
        udpBeaconJob = scope.launch {
            try {
                udpDiscoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BEACON_PORT))
                }
                val buffer = ByteArray(2048)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpDiscoverySocket?.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
                    val msg = String(packet.data, 0, packet.length)
                    val meshPacket = MeshPacket.fromJson(msg)

                    val isOwnBeacon = meshPacket != null &&
                        (meshPacket.sourceNodeId == _engineState.value.myNodeId ||
                            senderIp == _engineState.value.localIpAddress)

                    if (meshPacket != null && !isOwnBeacon) {
                        peerIpByPhone[meshPacket.sourcePhone] = senderIp

                        // Register peer in database
                        val node = MeshNodeEntity(
                            nodeId = meshPacket.sourceNodeId,
                            ssid = meshPacket.sourceSsid,
                            phoneNumber = meshPacket.sourcePhone,
                            nickname = meshPacket.sourceName,
                            ipAddress = senderIp,
                            port = TCP_MESH_PORT,
                            connectionType = "WIFI_DIRECT_UDP",
                            isDirectNeighbor = true,
                            hopDistance = 1,
                            lastSeen = System.currentTimeMillis(),
                            isActive = true
                        )
                        repository.saveMeshNode(node)

                        // Connect TCP client socket if not already open
                        if (!activeClientSockets.containsKey(senderIp)) {
                            connectToMeshSocket(senderIp, TCP_MESH_PORT)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP Beacon listener cerrado: ${e.message}")
            }
        }
    }

    private fun startMeshMaintenanceLoop() {
        if (meshMaintenanceJob?.isActive == true) return
        meshMaintenanceJob = scope.launch {
            while (isActive) {
                delay(6000)

                // 1. Latido: mantiene viva la tabla de vecinos y descubre IPs nuevas
                sendUdpDiscoveryBeacon()

                // 2. Descubrimiento solo mientras no haya grupo: relanzarlo con el grupo
                //    formado corta las conexiones activas.
                val now = System.currentTimeMillis()
                if (!isGroupFormed && p2pChannel != null && now - lastDiscoveryAtMs > DISCOVERY_INTERVAL_MS) {
                    startP2pDiscovery()
                    if (now - discoveryStartedAtMs > GROUP_FALLBACK_WINDOW_MS &&
                        !isConnectAttemptInProgress && chatMeshPeers.isEmpty()
                    ) {
                        createAutonomousP2pGroup(_engineState.value.ssid)
                        discoveryStartedAtMs = now
                    }
                }

                // 3. Vecinos caducados
                expireStaleNodes(now)
                updateConnectedPeersCount()
            }
        }
    }

    private suspend fun expireStaleNodes(now: Long) {
        for (node in repository.getActiveNodes()) {
            val hasLiveSocket = node.ipAddress.isNotBlank() && activeClientSockets.containsKey(node.ipAddress)
            if (!hasLiveSocket && now - node.lastSeen > NODE_TIMEOUT_MS) {
                repository.setNodeInactive(node.nodeId)
                val contact = repository.getContact(node.phoneNumber)
                if (contact != null && contact.isConnected) {
                    repository.saveContact(contact.copy(isConnected = false))
                }
            }
        }
        repository.pruneOldNodes(now - 10 * 60 * 1000)
    }

    /** Direcciones de broadcast reales de las interfaces activas (p2p-wlan0-0, wlan0...). */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.interfaceAddresses) {
                    address.broadcast?.let { addresses.add(it) }
                }
            }
        } catch (_: Exception) {}
        if (addresses.isEmpty()) {
            try { addresses.add(InetAddress.getByName("192.168.49.255")) } catch (_: Exception) {}
        }
        return addresses
    }

    private fun sendUdpDiscoveryBeacon() {
        scope.launch {
            try {
                val beacon = MeshPacket(
                    packetType = "BEACON",
                    sourceNodeId = _engineState.value.myNodeId,
                    sourcePhone = _engineState.value.myPhoneNumber,
                    sourceName = _engineState.value.ssid,
                    sourceSsid = _engineState.value.ssid,
                    destinationPhone = "BROADCAST"
                )
                val data = beacon.toJson().toByteArray()

                val sock = DatagramSocket()
                sock.broadcast = true
                for (addr in broadcastAddresses()) {
                    try {
                        sock.send(DatagramPacket(data, data.size, addr, UDP_BEACON_PORT))
                    } catch (_: Exception) {}
                }
                sock.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Requirement 6: Envío real de mensajes a la malla
     */
    fun sendChatMessage(
        recipientPhone: String,
        content: String,
        mediaType: String = "TEXT",
        mediaUri: String? = null,
        mediaData: String? = null,
        audioDuration: Int = 0
    ) {
        scope.launch {
            val messageUuid = java.util.UUID.randomUUID().toString()
            val myPhone = _engineState.value.myPhoneNumber
            val myNodeId = _engineState.value.myNodeId

            val messageEntity = MessageEntity(
                messageUuid = messageUuid,
                senderPhone = myPhone,
                recipientPhone = recipientPhone,
                content = content,
                mediaType = mediaType,
                mediaUri = mediaUri,
                mediaBase64 = mediaData,
                timestamp = System.currentTimeMillis(),
                status = "PENDING",
                hopCount = 0,
                isOutgoing = true,
                audioDurationSeconds = audioDuration
            )
            repository.saveMessage(messageEntity)

            val packet = MeshPacket(
                packetType = "CHAT_MESSAGE",
                packetUuid = messageUuid,
                sourceNodeId = myNodeId,
                sourcePhone = myPhone,
                sourceName = _engineState.value.ssid,
                sourceSsid = _engineState.value.ssid,
                destinationPhone = recipientPhone,
                content = content,
                mediaType = mediaType,
                mediaData = mediaData,
                audioDuration = audioDuration,
                hopCount = 1,
                visitedNodeIds = listOf(myNodeId)
            )

            transmitMeshPacket(packet)

            _engineState.value = _engineState.value.copy(
                packetsSent = _engineState.value.packetsSent + 1
            )
        }
    }

    fun transmitMeshPacket(packet: MeshPacket) {
        scope.launch {
            val json = packet.toJson()

            // 1. Transmit via active TCP sockets
            for ((ip, writer) in socketWriters) {
                val healthy = try {
                    activeClientSockets[ip]?.isClosed == false && writeLine(writer, json)
                } catch (_: Exception) {
                    false
                }
                if (!healthy) {
                    socketWriters.remove(ip)
                    try { activeClientSockets.remove(ip)?.close() } catch (_: Exception) {}
                }
            }

            // 2. Transmit via WiFi Aware if peer handle is known
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && awareSession != null) {
                val handle = awarePeerHandles[packet.destinationPhone]
                if (handle != null) {
                    try {
                        publishSession?.sendMessage(handle, 1, json.toByteArray())
                    } catch (_: Exception) {}
                } else {
                    for ((_, h) in awarePeerHandles) {
                        try {
                            publishSession?.sendMessage(h, 1, json.toByteArray())
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun processIncomingPacket(packet: MeshPacket) {
        scope.launch {
            val uuid = packet.packetUuid
            if (processedPacketUuids.contains(uuid)) {
                return@launch
            }
            processedPacketUuids.add(uuid)

            _engineState.value = _engineState.value.copy(
                packetsReceived = _engineState.value.packetsReceived + 1
            )

            val myPhone = _engineState.value.myPhoneNumber
            val isForMe = packet.destinationPhone == myPhone || packet.destinationPhone == "BROADCAST"

            when (packet.packetType) {
                "CHAT_MESSAGE" -> {
                    if (isForMe) {
                        val msg = MessageEntity(
                            messageUuid = packet.packetUuid,
                            senderPhone = packet.sourcePhone,
                            recipientPhone = myPhone,
                            content = packet.content,
                            mediaType = packet.mediaType,
                            mediaBase64 = packet.mediaData,
                            timestamp = packet.timestamp,
                            status = "DELIVERED",
                            hopCount = packet.hopCount,
                            isOutgoing = false,
                            audioDurationSeconds = packet.audioDuration
                        )
                        repository.saveMessage(msg)

                        // Send real ACK back
                        sendAck(packet.packetUuid, packet.sourcePhone)
                    } else if (packet.hopCount < packet.maxHops) {
                        relayPacket(packet)
                    }
                }
                "ACK" -> {
                    repository.updateMessageStatus(packet.content, "DELIVERED")
                }
                "BEACON", "HANDSHAKE" -> {
                    val node = MeshNodeEntity(
                        nodeId = packet.sourceNodeId,
                        ssid = packet.sourceSsid,
                        phoneNumber = packet.sourcePhone,
                        nickname = packet.sourceName,
                        ipAddress = peerIpByPhone[packet.sourcePhone] ?: "192.168.49.20",
                        connectionType = "WIFI_DIRECT",
                        isDirectNeighbor = true,
                        hopDistance = 1,
                        lastSeen = System.currentTimeMillis(),
                        isActive = true
                    )
                    repository.saveMeshNode(node)

                    // Flush pending Store & Forward messages
                    val pending = repository.getPendingMessagesForRecipient(packet.sourcePhone)
                    for (msg in pending) {
                        sendChatMessage(msg.recipientPhone, msg.content, msg.mediaType, msg.mediaUri, msg.mediaBase64, msg.audioDurationSeconds)
                    }
                }
                "STATUS_UPDATE" -> {
                    if (packet.statusType == "TYPING") {
                        _engineState.value = _engineState.value.copy(activeTypingContactPhone = packet.sourcePhone)
                        delay(2500)
                        if (_engineState.value.activeTypingContactPhone == packet.sourcePhone) {
                            _engineState.value = _engineState.value.copy(activeTypingContactPhone = null)
                        }
                    } else if (packet.statusType == "RECORDING") {
                        _engineState.value = _engineState.value.copy(activeRecordingContactPhone = packet.sourcePhone)
                        delay(3500)
                        if (_engineState.value.activeRecordingContactPhone == packet.sourcePhone) {
                            _engineState.value = _engineState.value.copy(activeRecordingContactPhone = null)
                        }
                    }
                }
                "CALL_SIGNAL" -> {
                    handleCallSignal(packet)
                }
            }
        }
    }

    private suspend fun relayPacket(packet: MeshPacket) {
        val myNodeId = _engineState.value.myNodeId
        if (packet.visitedNodeIds.contains(myNodeId)) return

        val relayedPacket = packet.copy(
            hopCount = packet.hopCount + 1,
            visitedNodeIds = packet.visitedNodeIds + myNodeId
        )
        transmitMeshPacket(relayedPacket)

        _engineState.value = _engineState.value.copy(
            packetsRelayed = _engineState.value.packetsRelayed + 1
        )
    }

    private fun sendAck(originalUuid: String, recipientPhone: String) {
        val ackPacket = MeshPacket(
            packetType = "ACK",
            sourceNodeId = _engineState.value.myNodeId,
            sourcePhone = _engineState.value.myPhoneNumber,
            sourceName = _engineState.value.ssid,
            sourceSsid = _engineState.value.ssid,
            destinationPhone = recipientPhone,
            content = originalUuid
        )
        transmitMeshPacket(ackPacket)
    }

    /**
     * Requirement 3: Llamadas P2P reales con transmisión de audio
     */
    fun startCall(contact: ContactEntity, isVideo: Boolean) {
        _engineState.value = _engineState.value.copy(
            activeCallPeer = contact,
            isCallActive = true,
            isVideoCall = isVideo,
            callDurationSeconds = 0,
            isMicMuted = false,
            isSpeakerOn = true
        )

        val offerPacket = MeshPacket(
            packetType = "CALL_SIGNAL",
            sourceNodeId = _engineState.value.myNodeId,
            sourcePhone = _engineState.value.myPhoneNumber,
            sourceName = _engineState.value.ssid,
            sourceSsid = _engineState.value.ssid,
            destinationPhone = contact.phoneNumber,
            callSignalType = "OFFER",
            callIsVideo = isVideo
        )
        transmitMeshPacket(offerPacket)

        startCallTimer()
        val peerIp = peerIpByPhone[contact.phoneNumber] ?: "192.168.49.1"
        startRealAudioStreaming(peerIp)
    }

    private fun startCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = scope.launch {
            while (_engineState.value.isCallActive) {
                delay(1000)
                _engineState.value = _engineState.value.copy(
                    callDurationSeconds = _engineState.value.callDurationSeconds + 1
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealAudioStreaming(peerIp: String) {
        val sampleRate = 16000
        val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
        val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

        // Real Microphone Capture & UDP Transmission
        audioRecordJob = scope.launch(Dispatchers.IO) {
            var audioRecord: AudioRecord? = null
            var udpSocket: DatagramSocket? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfigIn,
                    audioFormat,
                    bufferSize * 2
                )
                udpSocket = DatagramSocket()
                val targetAddr = InetAddress.getByName(peerIp)
                val buffer = ByteArray(bufferSize)

                audioRecord.startRecording()
                while (isActive && _engineState.value.isCallActive) {
                    if (!_engineState.value.isMicMuted) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val packet = DatagramPacket(buffer, read, targetAddr, AUDIO_UDP_PORT)
                            udpSocket.send(packet)
                        }
                    } else {
                        delay(50)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { udpSocket?.close() } catch (_: Exception) {}
            }
        }

        // Real Audio Playback from incoming UDP packets
        audioPlayJob = scope.launch(Dispatchers.IO) {
            var audioTrack: AudioTrack? = null
            var udpSocket: DatagramSocket? = null
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfigOut)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()

                udpSocket = DatagramSocket(AUDIO_UDP_PORT)
                val buffer = ByteArray(bufferSize)
                audioTrack.play()

                while (isActive && _engineState.value.isCallActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)
                    audioTrack.write(packet.data, 0, packet.length)
                }
            } catch (_: Exception) {
            } finally {
                try { audioTrack?.stop() } catch (_: Exception) {}
                try { audioTrack?.release() } catch (_: Exception) {}
                try { udpSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun endCall() {
        val peer = _engineState.value.activeCallPeer
        val duration = _engineState.value.callDurationSeconds
        val isVideo = _engineState.value.isVideoCall

        audioRecordJob?.cancel()
        audioPlayJob?.cancel()
        callTimerJob?.cancel()

        if (peer != null) {
            val hangupPacket = MeshPacket(
                packetType = "CALL_SIGNAL",
                sourceNodeId = _engineState.value.myNodeId,
                sourcePhone = _engineState.value.myPhoneNumber,
                sourceName = _engineState.value.ssid,
                sourceSsid = _engineState.value.ssid,
                destinationPhone = peer.phoneNumber,
                callSignalType = "HANGUP"
            )
            transmitMeshPacket(hangupPacket)

            scope.launch {
                repository.insertCall(
                    CallEntity(
                        contactPhone = peer.phoneNumber,
                        contactName = peer.displayName,
                        isVideo = isVideo,
                        isOutgoing = true,
                        durationSeconds = duration,
                        status = "COMPLETED"
                    )
                )
            }
        }

        _engineState.value = _engineState.value.copy(
            activeCallPeer = null,
            isCallActive = false,
            callDurationSeconds = 0
        )
    }

    fun toggleMute() {
        _engineState.value = _engineState.value.copy(isMicMuted = !_engineState.value.isMicMuted)
    }

    fun toggleSpeaker() {
        _engineState.value = _engineState.value.copy(isSpeakerOn = !_engineState.value.isSpeakerOn)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.isSpeakerphoneOn = _engineState.value.isSpeakerOn
    }

    private fun handleCallSignal(packet: MeshPacket) {
        when (packet.callSignalType) {
            "HANGUP", "REJECT" -> {
                audioRecordJob?.cancel()
                audioPlayJob?.cancel()
                callTimerJob?.cancel()
                _engineState.value = _engineState.value.copy(
                    activeCallPeer = null,
                    isCallActive = false,
                    callDurationSeconds = 0
                )
            }
            "OFFER" -> {
                scope.launch {
                    val contact = repository.getContact(packet.sourcePhone) ?: ContactEntity(
                        phoneNumber = packet.sourcePhone,
                        displayName = packet.sourceName,
                        isRegisteredInMesh = true,
                        isConnected = true
                    )
                    _engineState.value = _engineState.value.copy(
                        activeCallPeer = contact,
                        isCallActive = true,
                        isVideoCall = packet.callIsVideo,
                        callDurationSeconds = 0
                    )
                    startCallTimer()
                    val peerIp = peerIpByPhone[packet.sourcePhone] ?: "192.168.49.1"
                    startRealAudioStreaming(peerIp)
                }
            }
        }
    }

    fun sendUserStatus(recipientPhone: String, status: String) {
        val packet = MeshPacket(
            packetType = "STATUS_UPDATE",
            sourceNodeId = _engineState.value.myNodeId,
            sourcePhone = _engineState.value.myPhoneNumber,
            sourceName = _engineState.value.ssid,
            sourceSsid = _engineState.value.ssid,
            destinationPhone = recipientPhone,
            statusType = status
        )
        transmitMeshPacket(packet)
    }

    fun cleanUp() {
        try {
            audioRecordJob?.cancel()
            audioPlayJob?.cancel()
            meshMaintenanceJob?.cancel()
            callTimerJob?.cancel()
            udpBeaconJob?.cancel()

            tcpServerJob?.cancel()

            if (isP2pReceiverRegistered) {
                context.unregisterReceiver(p2pReceiver)
                isP2pReceiverRegistered = false
            }
            for (socket in activeClientSockets.values) {
                try { socket.close() } catch (_: Exception) {}
            }
            activeClientSockets.clear()
            socketWriters.clear()
            serverSocket?.close()
            udpDiscoverySocket?.close()
            isRadioStarted = false
            isGroupFormed = false
            try { p2pManager?.clearLocalServices(p2pChannel, null) } catch (_: Exception) {}
            try { p2pManager?.clearServiceRequests(p2pChannel, null) } catch (_: Exception) {}
            p2pManager?.removeGroup(p2pChannel, null)
        } catch (_: Exception) {}
    }
}
