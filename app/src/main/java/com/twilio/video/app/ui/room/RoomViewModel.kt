package com.twilio.video.app.ui.room

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.twilio.audioswitch.selection.AudioDeviceSelector
import com.twilio.video.Participant
import com.twilio.video.app.participant.ParticipantManager
import com.twilio.video.app.participant.buildLocalParticipantViewState
import com.twilio.video.app.participant.buildParticipantViewState
import com.twilio.video.app.sdk.RoomManager
import com.twilio.video.app.sdk.VideoTrackViewState
import com.twilio.video.app.udf.BaseViewModel
import com.twilio.video.app.ui.room.RoomEvent.ConnectFailure
import com.twilio.video.app.ui.room.RoomEvent.Connected
import com.twilio.video.app.ui.room.RoomEvent.Connecting
import com.twilio.video.app.ui.room.RoomEvent.Disconnected
import com.twilio.video.app.ui.room.RoomEvent.DominantSpeakerChanged
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.MuteParticipant
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.NetworkQualityLevelChange
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ParticipantConnected
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ParticipantDisconnected
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ScreenTrackUpdated
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.TrackSwitchOff
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.VideoTrackUpdated
import com.twilio.video.app.ui.room.RoomEvent.TokenError
import com.twilio.video.app.ui.room.RoomViewEffect.CheckLocalMedia
import com.twilio.video.app.ui.room.RoomViewEffect.ShowConnectFailureDialog
import com.twilio.video.app.ui.room.RoomViewEffect.ShowTokenErrorDialog
import com.twilio.video.app.ui.room.RoomViewEvent.ActivateAudioDevice
import com.twilio.video.app.ui.room.RoomViewEvent.CheckPermissions
import com.twilio.video.app.ui.room.RoomViewEvent.Connect
import com.twilio.video.app.ui.room.RoomViewEvent.DeactivateAudioDevice
import com.twilio.video.app.ui.room.RoomViewEvent.Disconnect
import com.twilio.video.app.ui.room.RoomViewEvent.PinParticipant
import com.twilio.video.app.ui.room.RoomViewEvent.RefreshViewState
import com.twilio.video.app.ui.room.RoomViewEvent.ScreenTrackRemoved
import com.twilio.video.app.ui.room.RoomViewEvent.SelectAudioDevice
import com.twilio.video.app.ui.room.RoomViewEvent.ToggleLocalVideo
import com.twilio.video.app.ui.room.RoomViewEvent.VideoTrackRemoved
import com.twilio.video.app.util.PermissionUtil
import com.twilio.video.app.util.plus
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class RoomViewModel(
    private val roomManager: RoomManager,
    private val audioDeviceSelector: AudioDeviceSelector,
    private val permissionUtil: PermissionUtil,
    private val participantManager: ParticipantManager = ParticipantManager(),
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val rxDisposables: CompositeDisposable = CompositeDisposable(),
    scheduler: Scheduler = AndroidSchedulers.mainThread(),
    initialViewState: RoomViewState = RoomViewState()
) : BaseViewModel<RoomViewEvent, RoomViewState, RoomViewEffect>(initialViewState) {

    init {
        audioDeviceSelector.start { audioDevices, selectedDevice ->
            updateState { it.copy(
                selectedDevice = selectedDevice,
                availableAudioDevices = audioDevices)
            }
        }

        rxDisposables + roomManager.roomEvents
                .observeOn(scheduler)
                .subscribe({
            observeRoomEvents(it)
        }, {
            Timber.e(it, "Error in RoomManager RoomEvent stream")
        })
    }

    override fun onCleared() {
        super.onCleared()
        audioDeviceSelector.stop()
        rxDisposables.clear()
    }

    override fun processInput(viewEvent: RoomViewEvent) {
        Timber.d("View Event: $viewEvent")
        when (viewEvent) {
            is RefreshViewState -> updateState { it.copy() }
            is CheckPermissions -> checkLocalMedia()
            is SelectAudioDevice -> {
                audioDeviceSelector.selectDevice(viewEvent.device)
            }
            ActivateAudioDevice -> { audioDeviceSelector.activate() }
            DeactivateAudioDevice -> { audioDeviceSelector.deactivate() }
            is Connect -> {
                connect(viewEvent.identity, viewEvent.roomName)
            }
            is PinParticipant -> {
                participantManager.changePinnedParticipant(viewEvent.sid)
                updateParticipantViewState()
            }
            is ToggleLocalVideo -> {
                participantManager.updateParticipantVideoTrack(viewEvent.sid, null)
            }
            is VideoTrackRemoved -> {
                participantManager.updateParticipantVideoTrack(viewEvent.sid, null)
                updateParticipantViewState()
            }
            is ScreenTrackRemoved -> {
                participantManager.updateParticipantScreenTrack(viewEvent.sid, null)
                updateParticipantViewState()
            }
            Disconnect -> roomManager.disconnect()
        }
    }

    private fun checkLocalMedia() {
        val isCameraEnabled = permissionUtil.isPermissionGranted(Manifest.permission.CAMERA)
        val isMicEnabled = permissionUtil.isPermissionGranted(Manifest.permission.RECORD_AUDIO)

        updateState { it.copy(isCameraEnabled = isCameraEnabled, isMicEnabled = isMicEnabled) }
        if (isCameraEnabled && isMicEnabled) viewEffect { CheckLocalMedia }
    }

    private fun observeRoomEvents(roomEvent: RoomEvent) {
        Timber.d("observeRoomEvents: %s", roomEvent)
        when (roomEvent) {
            is Connecting -> {
                showConnectingViewState()
            }
            is Connected -> {
                showConnectedViewState(roomEvent.roomName)
                checkParticipants(roomEvent.participants)
                viewEffect { RoomViewEffect.Connected(roomEvent.room) }
            }
            is Disconnected -> {
                showLobbyViewState()
                updateState { it.copy(participantThumbnails = null, primaryParticipant = null) }
            }
            is DominantSpeakerChanged -> {
                participantManager.changeDominantSpeaker(roomEvent.newDominantSpeakerSid)
                updateParticipantViewState()
            }
            is ConnectFailure -> viewEffect {
                showLobbyViewState()
                ShowConnectFailureDialog
            }
            is TokenError -> viewEffect {
                showLobbyViewState()
                ShowTokenErrorDialog(roomEvent.serviceError)
            }
            is ParticipantEvent -> handleParticipantEvent(roomEvent)
        }
    }

    private fun handleParticipantEvent(participantEvent: ParticipantEvent) {
        when (participantEvent) {
            is ParticipantConnected -> addParticipant(participantEvent.participant)
            is VideoTrackUpdated -> {
                participantManager.updateParticipantVideoTrack(participantEvent.sid,
                        participantEvent.videoTrack?.let { VideoTrackViewState(it) })
                updateParticipantViewState()
            }
            is TrackSwitchOff -> {
                participantManager.updateParticipantVideoTrack(participantEvent.sid,
                        VideoTrackViewState(participantEvent.videoTrack,
                                participantEvent.switchOff))
                updateParticipantViewState()
            }
            is ScreenTrackUpdated -> {
                participantManager.updateParticipantScreenTrack(participantEvent.sid,
                        participantEvent.screenTrack?.let { VideoTrackViewState(it) })
                updateParticipantViewState()
            }
            is MuteParticipant -> {
                participantManager.muteParticipant(participantEvent.sid,
                        participantEvent.mute)
                updateParticipantViewState()
            }
            is NetworkQualityLevelChange -> {
                participantManager.updateNetworkQuality(participantEvent.sid,
                        participantEvent.networkQualityLevel)
                updateParticipantViewState()
            }
            is ParticipantDisconnected -> {
                participantManager.removeParticipant(participantEvent.sid)
                updateParticipantViewState()
            }
        }
    }

    private fun addParticipant(participant: Participant) {
        val participantViewState = buildParticipantViewState(participant)
        participantManager.addParticipant(participantViewState)
        updateParticipantViewState()
    }

    private fun showLobbyViewState() {
        viewEffect { RoomViewEffect.Disconnected }
        updateState { it.copy(
                isLobbyLayoutVisible = true,
                isConnectingLayoutVisible = false,
                isConnectedLayoutVisible = false
        ) }
        participantManager.clearParticipants()
    }

    private fun showConnectingViewState() {
        viewEffect { RoomViewEffect.Connecting }
        updateState { it.copy(
            isLobbyLayoutVisible = false,
            isConnectingLayoutVisible = true,
            isConnectedLayoutVisible = false
        ) }
    }

    private fun showConnectedViewState(roomName: String) {
        updateState { it.copy(
                title = roomName,
                isLobbyLayoutVisible = false,
                isConnectingLayoutVisible = false,
                isConnectedLayoutVisible = true
        ) }
    }

    private fun checkParticipants(participants: List<Participant>) {
        for ((index, participant) in participants.withIndex()) {
            val participantViewState = if (index == 0) {
                buildLocalParticipantViewState(participant, participant.identity)
            } else buildParticipantViewState(participant)
            participantManager.addParticipant(participantViewState)
        }
        updateParticipantViewState()
    }

    private fun updateParticipantViewState() {
        updateState {
            it.copy(
                participantThumbnails = participantManager.participantThumbnails,
                primaryParticipant = participantManager.primaryParticipant
            )
        }
    }

    private fun connect(identity: String, roomName: String) =
        backgroundScope.launch {
            roomManager.connect(
                    identity,
                    roomName)
        }

    class RoomViewModelFactory(
        private val roomManager: RoomManager,
        private val audioDeviceSelector: AudioDeviceSelector,
        private val permissionUtil: PermissionUtil
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RoomViewModel(roomManager, audioDeviceSelector, permissionUtil) as T
        }
    }
}
