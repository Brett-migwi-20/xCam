package com.hazbu.xcam.core.settings

/**
 * The single orientation model shared by UI, the surface renderer, and YUV output.
 * Rotation and either mirror axis deliberately remain independent controls.
 */
data class OrientationConfig(
    val rotationAngle: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
) {
    val normalizedRotation: Int
        get() = ((rotationAngle % 360) + 360) % 360

    fun rotateLeft() = copy(rotationAngle = (normalizedRotation - 90 + 360) % 360)
    fun rotateRight() = copy(rotationAngle = (normalizedRotation + 90) % 360)
}
