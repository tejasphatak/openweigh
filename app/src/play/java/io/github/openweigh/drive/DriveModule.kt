package io.github.openweigh.drive

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt providers for the Drive subsystem's shared, stateless HTTP machinery.
 *
 * The auth manager, serializer, and the two Drive services are all `@Inject constructor` /
 * `@Singleton`, so they need no `@Provides`. This module only supplies the heavyweight, reusable
 * [HttpTransport] and [JsonFactory] singletons that back the Drive client builder, so we don't
 * allocate a new transport per request. This module lives in the `drive` package and touches no
 * other subsystem.
 */
@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideHttpTransport(): HttpTransport = NetHttpTransport()

    @Provides
    @Singleton
    fun provideJsonFactory(): JsonFactory = GsonFactory.getDefaultInstance()
}
