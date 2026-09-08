package com.bnyro.recorder.ui

sealed class Destination(open val route: String) {
    object Home : Destination("home")
    object Settings : Destination("settings")
    data class RecordingPlayer(val showVideo: Boolean = false) : Destination("player?video=$showVideo") {
        companion object {
            const val ROUTE_PATTERN = "player?video={video}"
        }
    }
}
