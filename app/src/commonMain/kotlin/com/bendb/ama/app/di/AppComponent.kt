package com.bendb.ama.app.di

import com.bendb.ama.app.Configuration
import com.bendb.ama.app.getConfigurationStorage
import com.bendb.ama.proxy.ProxyServer
import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class AppComponent {

    @Provides
    fun provideProxyServer(configuration: Configuration): ProxyServer {
        return ProxyServer(Dispatchers.IO, configuration.http.port)
    }

    @Provides
    protected suspend fun provideConfigStore(): KStore<Configuration> {
        return getConfigurationStorage()
    }

    @Provides
    protected suspend fun provideConfiguration(configStore: KStore<Configuration>): Configuration {
        return configStore.get() ?: Configuration()
    }
}