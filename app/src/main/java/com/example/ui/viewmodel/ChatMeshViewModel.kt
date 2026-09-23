package com.example.ui.viewmodel

import android.app.Application
import android.net.wifi.p2p.WifiP2pDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.ChatMeshDatabase
import com.example.data.entity.CallEntity
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshNodeEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.UserProfile
import com.example.data.repository.ChatMeshRepository
import com.example.mesh.ContactSyncUtil
import com.example.mesh.MeshEngineState
import com.example.mesh.SimCardInfo
import com.example.mesh.SimDetectionUtil
import com.example.mesh.WiFiMeshEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatMeshViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatMeshRepository
    val meshEngine: WiFiMeshEngine

    val userProfile: StateFlow<UserProfile?>
    val chatContacts: StateFlow<List<ContactEntity>>
    val allContacts: StateFlow<List<ContactEntity>>
    val meshNodes: StateFlow<List<MeshNodeEntity>>
    val calls: StateFlow<List<CallEntity>>
    val engineState: StateFlow<MeshEngineState>

    private val _realSimDetails = MutableStateFlow(SimDetectionUtil.getRealSimDetails(application))
    val realSimDetails: StateFlow<SimCardInfo> = _realSimDetails.asStateFlow()

    private val _selectedContact = MutableStateFlow<ContactEntity?>(null)
    val selectedContact: StateFlow<ContactEntity?> = _selectedContact.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val activeMessages: StateFlow<List<MessageEntity>> = _activeMessages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isRecordingAudio = MutableStateFlow(false)
    val isRecordingAudio: StateFlow<Boolean> = _isRecordingAudio.asStateFlow()

    private val _recordingTimerSeconds = MutableStateFlow(0)
    val recordingTimerSeconds: StateFlow<Int> = _recordingTimerSeconds.asStateFlow()

    init {
        val db = ChatMeshDatabase.getInstance(application)
        repository = ChatMeshRepository(db)
        meshEngine = WiFiMeshEngine(application, repository)

        userProfile = repository.userProfileFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        chatContacts = repository.chatContactsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allContacts = repository.allContactsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        meshNodes = repository.allMeshNodesFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        calls = repository.allCallsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        engineState = meshEngine.engineState

        // Real SIM Detection & Engine Initialization
        viewModelScope.launch {
            val sim = SimDetectionUtil.getRealSimDetails(application)
            _realSimDetails.value = sim

            val existing = repository.getUserProfile()
            val phone = when {
                !existing?.phoneNumber.isNullOrBlank() -> existing!!.phoneNumber
                !sim.phoneNumber.isNullOrBlank() -> sim.phoneNumber!!
                else -> ""
            }

            val ssid = SimDetectionUtil.generateSsid(phone)
            val nickname = existing?.nickname ?: if (sim.carrierName.isNotBlank()) "Usuario (${sim.carrierName})" else "Usuario WiFi Direct"

            if (existing == null) {
                val newProfile = UserProfile(
                    phoneNumber = phone,
                    nickname = nickname,
                    ssid = ssid,
                    ipAddress = "192.168.49.1",
                    registeredAt = System.currentTimeMillis()
                )
                repository.saveUserProfile(newProfile)
            }

            meshEngine.initialize(phone, nickname)
            ContactSyncUtil.syncDeviceContacts(application, repository, phone)
        }
    }

    fun onPermissionsGranted() {
        meshEngine.onPermissionsGranted()
    }

    fun reloadSimDetails() {
        val sim = SimDetectionUtil.getRealSimDetails(getApplication())
        _realSimDetails.value = sim
    }

    fun saveRealSimPhoneNumber(number: String) {
        viewModelScope.launch {
            val clean = SimDetectionUtil.sanitizePhoneNumber(number)
            SimDetectionUtil.saveUserSimPhoneNumber(getApplication(), clean)
            reloadSimDetails()

            val current = userProfile.value
            val nickname = current?.nickname ?: "Usuario"
            updateProfile(nickname, clean)
            meshEngine.reCreateP2pGroup()
        }
    }

    fun selectContact(contact: ContactEntity?) {
        _selectedContact.value = contact
        if (contact != null) {
            viewModelScope.launch {
                repository.markChatAsRead(contact.phoneNumber)
                val myPhone = userProfile.value?.phoneNumber ?: ""
                repository.getConversationFlow(contact.phoneNumber, myPhone).collect { list ->
                    _activeMessages.value = list
                }
            }
        } else {
            _activeMessages.value = emptyList()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun sendTextMessage(text: String) {
        val contact = _selectedContact.value ?: return
        if (text.isBlank()) return
        meshEngine.sendChatMessage(
            recipientPhone = contact.phoneNumber,
            content = text.trim(),
            mediaType = "TEXT"
        )
    }

    fun sendImageMessage(imageUri: String, caption: String = "") {
        val contact = _selectedContact.value ?: return
        meshEngine.sendChatMessage(
            recipientPhone = contact.phoneNumber,
            content = if (caption.isNotBlank()) caption else "Foto adjunta",
            mediaType = "IMAGE",
            mediaUri = imageUri
        )
    }

    fun sendAudioVoiceMessage(durationSeconds: Int) {
        val contact = _selectedContact.value ?: return
        meshEngine.sendChatMessage(
            recipientPhone = contact.phoneNumber,
            content = "Mensaje de voz",
            mediaType = "AUDIO",
            audioDuration = durationSeconds
        )
    }

    fun sendFileMessage(fileName: String, fileUri: String? = null) {
        val contact = _selectedContact.value ?: return
        meshEngine.sendChatMessage(
            recipientPhone = contact.phoneNumber,
            content = fileName,
            mediaType = "FILE",
            mediaUri = fileUri
        )
    }

    fun startAudioCall(contact: ContactEntity) {
        meshEngine.startCall(contact, isVideo = false)
    }

    fun startVideoCall(contact: ContactEntity) {
        meshEngine.startCall(contact, isVideo = true)
    }

    fun endCall() {
        meshEngine.endCall()
    }

    fun toggleMute() {
        meshEngine.toggleMute()
    }

    fun toggleSpeaker() {
        meshEngine.toggleSpeaker()
    }

    fun inviteContact(contact: ContactEntity) {
        viewModelScope.launch {
            val updated = contact.copy(
                isRegisteredInMesh = true,
                statusText = "Invitado a conectar en WiFi Direct"
            )
            repository.saveContact(updated)
        }
    }

    fun addNewManualContact(displayName: String, phoneNumber: String) {
        viewModelScope.launch {
            val cleanPhone = SimDetectionUtil.sanitizePhoneNumber(phoneNumber)
            val newContact = ContactEntity(
                phoneNumber = cleanPhone,
                displayName = displayName.ifBlank { cleanPhone },
                isRegisteredInMesh = true,
                isConnected = false,
                lastSeen = 0L,
                statusText = "Contacto agregado directamente"
            )
            repository.insertContact(newContact)
            selectContact(newContact)
        }
    }

    fun updateProfile(nickname: String, phoneNumber: String) {
        viewModelScope.launch {
            val cleanPhone = SimDetectionUtil.sanitizePhoneNumber(phoneNumber)
            val cleanSsid = SimDetectionUtil.generateSsid(cleanPhone)
            val updated = UserProfile(
                phoneNumber = cleanPhone,
                nickname = nickname,
                ssid = cleanSsid,
                ipAddress = userProfile.value?.ipAddress ?: "192.168.49.1"
            )
            repository.saveUserProfile(updated)
            meshEngine.initialize(cleanPhone, nickname)
        }
    }

    fun refreshContacts() {
        viewModelScope.launch {
            val phone = userProfile.value?.phoneNumber ?: ""
            ContactSyncUtil.syncDeviceContacts(getApplication(), repository, phone)
            reloadSimDetails()
        }
    }

    fun reCreateWiFiDirectGroup() {
        meshEngine.reCreateP2pGroup()
    }

    fun scanP2pPeers() {
        meshEngine.startP2pDiscovery()
    }

    fun connectToP2pDevice(device: WifiP2pDevice) {
        meshEngine.connectToPeer(device)
    }

    fun setRecording(isRecording: Boolean, seconds: Int = 0) {
        _isRecordingAudio.value = isRecording
        _recordingTimerSeconds.value = seconds
        val contact = _selectedContact.value
        if (contact != null) {
            meshEngine.sendUserStatus(contact.phoneNumber, if (isRecording) "RECORDING" else "IDLE")
        }
    }

    fun onUserTyping(text: String) {
        val contact = _selectedContact.value
        if (contact != null && text.isNotEmpty()) {
            meshEngine.sendUserStatus(contact.phoneNumber, "TYPING")
        }
    }

    override fun onCleared() {
        super.onCleared()
        meshEngine.cleanUp()
    }
}
