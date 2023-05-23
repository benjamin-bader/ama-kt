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

package com.bendb.ama

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import io.github.xxfast.kstore.Codec
import io.github.xxfast.kstore.KStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import net.harawata.appdirs.AppDirsFactory
import okio.Path.Companion.toPath
import okio.buffer
import java.io.FileNotFoundException
import java.util.Locale

suspend fun getConfigurationStorage(): KStore<Configuration> {
    // Different OS have different conventions for where to store config files.
    // MacOS and most linux distros assume a reverse-dns style name, while Windows
    // uses the company (read: author) and app names.
    val osName = System.getProperty("os.name")?.lowercase(Locale.US) ?: ""
    val appName = when {
        osName.startsWith("mac os x") -> AppInfo.bundleName
        osName.startsWith("windows") -> AppInfo.name
        else -> AppInfo.bundleName
    }

    val appdirs = AppDirsFactory.getInstance()!!
    val configDir = appdirs.getUserConfigDir(
        appName,
        null, // app version, which we don't want to include as a config-filepath element
        AppInfo.author,
        false, // roaming
    )!!

    // Streamlined kstore APIs assume json - we want toml.
    val filePath = "$configDir/config.toml"
    val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = true,
        ),
        outputConfig = TomlOutputConfig(
            indentation = TomlIndentation.NONE,
        )
    )
    val serializer = Configuration.serializer()
    val codec = TomlFileCodec(filePath, toml, serializer)

    val store = KStore(Configuration(), true, codec)

    // Force creation of the file, if it doesn't exist
    store.set(store.get())

    return store
}

/**
 * A KStore [Codec] that reads from and writes to a TOML file.
 */
class TomlFileCodec<T : @Serializable Any>(
    filePath: String,
    private val toml: Toml,
    private val serializer: KSerializer<T>
) : Codec<T> {

    private val path = filePath.toPath()

    override suspend fun decode(): T? {
        return try {
            FS.source(path).buffer().use {
                val text = it.readUtf8()
                toml.decodeFromString(serializer, text)
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override suspend fun encode(value: T?) {
        val parentPath = path.parent
        if (parentPath != null) {
            FS.createDirectories(parentPath, mustCreate = false)
        }

        if (value != null) {
            FS.sink(path).buffer().use {
                it.writeUtf8(toml.encodeToString(serializer, value))
                it.flush()
            }
        } else {
            FS.delete(path)
        }
    }

    companion object {
        private val FS = okio.FileSystem.SYSTEM
    }
}
