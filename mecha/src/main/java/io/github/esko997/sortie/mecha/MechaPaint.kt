package io.github.esko997.sortie.mecha

import kotlinx.serialization.Serializable

@Serializable
enum class MechaPaintScheme(val id: String, val label: String, val primary: Int, val secondary: Int) {
    ORIGINAL("original", "Original", 0xFFBEC6CD.toInt(), 0xFF48525B.toInt()),
    HAZARD("hazard", "Hazard", 0xFFE9B949.toInt(), 0xFF353F4B.toInt()),
    PORCELAIN("porcelain", "Porcelain", 0xFFE8E3D3.toInt(), 0xFF507CA1.toInt()),
    RESCUE("rescue", "Rescue", 0xFFD65345.toInt(), 0xFFE8DFC8.toInt()),
    HARBOR("harbor", "Harbor", 0xFF3A8F9E.toInt(), 0xFFD4D9BF.toInt()),
    MOSS("moss", "Moss", 0xFF718E60.toInt(), 0xFFD8BA77.toInt()),
    PLUM("plum", "Plum", 0xFF8D6DAB.toInt(), 0xFFDFC2CA.toInt()),
    GLACIER("glacier", "Glacier", 0xFF9DC6D2.toInt(), 0xFF435C7B.toInt()),
    EMBER("ember", "Ember", 0xFFCB7546.toInt(), 0xFF53485B.toInt()),
    CANDY("candy", "Candy", 0xFFCF87AD.toInt(), 0xFF7FBFB7.toInt()),
    MONO("mono", "Mono", 0xFFBEC6CD.toInt(), 0xFF48525B.toInt())
}

/** Each scheme/edition is a reusable entitlement, independent of every other edition. */
@Serializable
data class MechaPaint(val scheme: MechaPaintScheme = MechaPaintScheme.ORIGINAL, val finish: MechaFinish = MechaFinish.PLAIN) {
    val price: Int get() = when (finish) {
        MechaFinish.PLAIN -> if (scheme == MechaPaintScheme.ORIGINAL) 0 else 2
        MechaFinish.ALLOY -> 6
        MechaFinish.HOLO -> 10
    }
    val label: String get() = "${scheme.label} ${finish.label}"
}
