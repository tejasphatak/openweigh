package io.github.openweigh.ble.protocol

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordered registry of all known [ScaleProtocol]s. The set is contributed via Hilt `@IntoSet`
 * bindings; [resolve] returns the first protocol whose [ScaleProtocol.matches] returns true.
 *
 * The incoming `Set` has no defined iteration order, so we sort deterministically by
 * [ScaleProtocol.id] to make "first match wins" stable and reproducible.
 */
@Singleton
class ProtocolRegistry @Inject constructor(
    protocols: Set<@JvmSuppressWildcards ScaleProtocol>
) {
    private val ordered: List<ScaleProtocol> = protocols.sortedBy { it.id }

    /** @return the first protocol that matches the device, or null if none do. */
    fun resolve(deviceName: String?, services: List<UUID>): ScaleProtocol? =
        ordered.firstOrNull { it.matches(deviceName, services) }

    /** All registered protocols, in resolution order. */
    fun all(): List<ScaleProtocol> = ordered
}
