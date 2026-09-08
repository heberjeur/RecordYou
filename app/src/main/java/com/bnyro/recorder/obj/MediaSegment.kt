package com.bnyro.recorder.obj

data class MediaSegment(
    val startMs: Long,
    val endMs: Long,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false
) {
    val durationMs: Long
        get() = ((endMs - startMs).coerceAtLeast(0L) / speed).toLong()
}
