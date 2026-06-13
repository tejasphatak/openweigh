package io.github.openweigh.ble.protocol

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.UserProfile
import java.util.UUID

/**
 * The per-device extension point. Each supported scale family implements one [ScaleProtocol].
 *
 * Protocols are matched against an advertising device by [matches]; the first match (per
 * [ProtocolRegistry] ordering) wins. The chosen protocol declares which GATT characteristics to
 * subscribe to via [characteristicsToSubscribe], and turns each incoming notification into zero or
 * more [ScaleReading]s via [decode].
 *
 * To add a new scale, implement this interface and contribute it into the protocol `Set` with
 * `@IntoSet` from a Hilt module in your own package.
 */
interface ScaleProtocol {

    /** Stable, unique identifier for this protocol (e.g. "bt-sig-weight-scale"). */
    val id: String

    /**
     * @return true if this protocol can handle a device with the given advertised name and
     * service UUIDs.
     */
    fun matches(deviceName: String?, advertisedServices: List<UUID>): Boolean

    /** @return the GATT characteristic UUIDs this protocol needs notifications/indications from. */
    fun characteristicsToSubscribe(): List<UUID>

    /**
     * Decode a single characteristic notification into readings.
     *
     * @param characteristic the UUID of the characteristic that produced [value].
     * @param value the raw GATT payload.
     * @param profile the user's profile, when available, for fields that require demographics.
     * @return zero or more readings (empty if the packet is a non-final continuation fragment).
     */
    fun decode(characteristic: UUID, value: ByteArray, profile: UserProfile?): List<ScaleReading>
}
