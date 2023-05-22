package com.bendb.ama

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import net.harawata.appdirs.AppDirs
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
