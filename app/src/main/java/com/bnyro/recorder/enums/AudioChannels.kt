package com.bnyro.recorder.enums

enum class AudioChannels(val value: Int) {
    MONO(1),
    STEREO(2);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: STEREO
    }
}
