package com.denonmusic.heos

/** Unsolicited change events, per spec section 5. */
sealed interface HeosEvent {
    data class PlayStateChanged(val pid: String, val state: PlayState?) : HeosEvent
    data class NowPlayingChanged(val pid: String) : HeosEvent
    data class NowPlayingProgress(val pid: String, val positionMillis: Long, val durationMillis: Long) : HeosEvent
    data class QueueChanged(val pid: String) : HeosEvent
    data class VolumeChanged(val pid: String, val level: Int?, val muted: Boolean?) : HeosEvent
    data class RepeatModeChanged(val pid: String, val repeat: RepeatMode?) : HeosEvent
    data class ShuffleModeChanged(val pid: String, val shuffle: Boolean?) : HeosEvent
    data class PlaybackError(val pid: String, val error: String) : HeosEvent
    data object SourcesChanged : HeosEvent
    data object PlayersChanged : HeosEvent
    data class Unknown(val name: String, val attributes: Map<String, String>) : HeosEvent

    companion object {
        /** Returns null when the frame is a command response rather than an event. */
        fun from(frame: HeosFrame): HeosEvent? {
            val name = frame.eventName ?: return null
            val attributes = frame.attributes
            val pid = attributes["pid"].orEmpty()
            return when (name) {
                "player_state_changed" ->
                    PlayStateChanged(pid, PlayState.fromWire(attributes["state"]))
                "player_now_playing_changed" -> NowPlayingChanged(pid)
                "player_now_playing_progress" ->
                    NowPlayingProgress(
                        pid = pid,
                        positionMillis = attributes["cur_pos"]?.toLongOrNull() ?: 0L,
                        durationMillis = attributes["duration"]?.toLongOrNull() ?: 0L,
                    )
                "player_queue_changed" -> QueueChanged(pid)
                "player_volume_changed" ->
                    VolumeChanged(pid, attributes["level"]?.toIntOrNull(), attributes["mute"]?.toOnOff())
                "repeat_mode_changed" -> RepeatModeChanged(pid, RepeatMode.fromWire(attributes["repeat"]))
                "shuffle_mode_changed" -> ShuffleModeChanged(pid, attributes["shuffle"]?.toOnOff())
                "player_playback_error" -> PlaybackError(pid, attributes["error"].orEmpty())
                "sources_changed" -> SourcesChanged
                "players_changed" -> PlayersChanged
                else -> Unknown(name, attributes)
            }
        }

        private fun String.toOnOff(): Boolean? = when (lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }
}
