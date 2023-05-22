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

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import net.harawata.appdirs.AppDirsFactory

fun getConfigurationStorage(): KStore<Configuration> {
    val appdirs = AppDirsFactory.getInstance()!!
    val configDir = appdirs.getUserConfigDir(
        AppInfo.name,
        AppInfo.version,
        AppInfo.author,
        false, // roaming
    )!!
    return storeOf(
        filePath = "$configDir/config.json",
        default = Configuration(),
        enableCache = true,
    )
}
