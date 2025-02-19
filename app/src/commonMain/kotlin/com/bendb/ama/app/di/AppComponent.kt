// Amanuensis - a local web proxy for debugging
// Copyright (C) 2023 Benjamin Bader
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bendb.ama.app.di

import com.bendb.ama.app.Configuration
import com.bendb.ama.app.getConfigurationStorage
import com.bendb.ama.proxy.DefaultProxyServer
import com.bendb.ama.proxy.ProxyServer
import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class AppComponent {

    @Provides
    fun provideProxyServer(configuration: Configuration): ProxyServer {
        return DefaultProxyServer(Dispatchers.IO, configuration.http.port)
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
