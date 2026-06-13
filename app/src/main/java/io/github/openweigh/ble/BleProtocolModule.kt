package io.github.openweigh.ble

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.openweigh.ble.protocol.ScaleProtocol
import io.github.openweigh.ble.protocol.StandardBodyCompositionProtocol
import io.github.openweigh.ble.protocol.StandardWeightScaleProtocol

/**
 * Contributes the two Bluetooth SIG standard decoders into the `Set<ScaleProtocol>` consumed by
 * [io.github.openweigh.ble.protocol.ProtocolRegistry].
 *
 * Additional per-device protocols should contribute themselves from their own modules with
 * `@IntoSet`; never edit this one.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleProtocolModule {

    @Provides
    @IntoSet
    fun provideWeightScaleProtocol(): ScaleProtocol = StandardWeightScaleProtocol()

    @Provides
    @IntoSet
    fun provideBodyCompositionProtocol(): ScaleProtocol = StandardBodyCompositionProtocol()
}
