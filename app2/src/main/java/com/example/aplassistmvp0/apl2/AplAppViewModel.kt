package com.example.aplassistmvp0.apl2

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aplassistmvp0.assistant.AplVoiceInteractionService
import com.example.aplassistmvp0.guide.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupStatus(val assistant: Boolean = false, val microphone: Boolean = false)

/** App settings do not start network requests when the app opens or resumes. */
class AplAppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BridgeConnectionStore(application)
    private val mutableSetup = MutableStateFlow(SetupStatus())
    val setup = mutableSetup.asStateFlow()
    private val mutableConnection = MutableStateFlow(BridgeConnectionConfig(BridgeConnectionMode.HTTPS, BridgeConnectionPolicy.PUBLIC_ENDPOINT, ""))
    val connection = mutableConnection.asStateFlow()
    private val mutableMessage = MutableStateFlow("")
    val connectionMessage = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val connectionBusy = mutableBusy.asStateFlow()
    private var connectionJob: Job? = null
    private var generation = 0L

    fun refresh() {
        val app = getApplication<Application>()
        mutableSetup.value = SetupStatus(
            assistant = VoiceInteractionService.isActiveService(app, ComponentName(app, AplVoiceInteractionService::class.java)),
            microphone = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        runCatching { store.load() }.onSuccess { mutableConnection.value = it }
            .onFailure { mutableMessage.value = "AI 연결 설정을 다시 저장해주세요." }
    }

    private fun cancelConnectionWork() {
        generation++
        connectionJob?.cancel()
        connectionJob = null
        mutableBusy.value = false
    }

    fun saveConnection(config: BridgeConnectionConfig) {
        cancelConnectionWork()
        GuideController.stop()
        runCatching { store.save(config) }.onSuccess {
            GuideModelSettings(getApplication()).save(GuideBackend.defaultSubscription)
            mutableConnection.value = config
            mutableMessage.value = "연결 설정을 저장했어요."
        }.onFailure { mutableMessage.value = "저장하지 못했어요. 연결 설정을 확인해주세요." }
    }

    fun disconnect() {
        cancelConnectionWork()
        GuideController.stop()
        runCatching { store.clear() }.onSuccess {
            mutableConnection.value = BridgeConnectionConfig(BridgeConnectionMode.HTTPS, BridgeConnectionPolicy.PUBLIC_ENDPOINT, "")
            mutableMessage.value = "연결을 해제했어요."
        }.onFailure { mutableMessage.value = "연결을 해제하지 못했어요. 다시 시도해주세요." }
    }

    fun checkConnection(config: BridgeConnectionConfig) {
        cancelConnectionWork()
        val requested = generation
        mutableBusy.value = true
        mutableMessage.value = "연결을 확인하고 있어요."
        connectionJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SubscriptionBridgeClient(store).status(config, GuideBackend.defaultSubscription.model, verifyStored = false)
                }
                if (requested == generation) mutableMessage.value = result.message
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (requested == generation) mutableMessage.value = "연결하지 못했어요. 주소와 연결 키를 확인해주세요." }
            finally { if (requested == generation) mutableBusy.value = false }
        }
    }

    fun dismissConnection() { cancelConnectionWork(); mutableMessage.value = "" }
}
