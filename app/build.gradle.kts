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

// Ignores a compiler warning about unused variables in sourceSets; allegedly fixed in Kotlin 1.9.
// https://youtrack.jetbrains.com/issue/KT-38871/Kotlin-Gradle-DSL-MPP-UNUSEDVARIABLE-when-configuring-a-sourceset-with-delegated-property
@file:Suppress("UNUSED_VARIABLE")

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.sqldelight)
}

group = properties["GROUP"]!!
version = properties["VERSION"]!!

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.material)

                implementation(libs.appdirs)
                implementation(libs.koin.compose)
                implementation(libs.koin.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktoml.core)
                implementation(libs.kstore.core)
                implementation(libs.kstore.file)

                implementation(libs.ktor.http)
                implementation(libs.ktor.network)

                implementation(libs.okio)

                implementation(project(":proxy"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                implementation(libs.kotlinx.atomicfu.jvm)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.sqldelight.sqliteDriver)
            }
        }
        val jvmTest by getting
    }
}

compose {
    kotlinCompilerPlugin.set("androidx.compose.compiler:compiler:1.4.7")

    desktop {
        application {
            mainClass = "com.bendb.ama.MainKt"
            nativeDistributions {
                targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
                packageName = "ama-kt"
                packageVersion = "1.0.0"

                licenseFile.set(rootProject.file("LICENSE.md"))

                modules(
                    "java.sql", // for sqldelight, which needs JDBC things
                )

                val iconsRoot = rootProject.layout.projectDirectory.dir("assets")
                macOS {
                    iconFile.set(iconsRoot.file("logo.icns"))
                }

                windows {
                    iconFile.set(iconsRoot.file("logo.ico"))
                }

                linux {
                    iconFile.set(iconsRoot.file("logo.png"))
                }
            }
        }
    }
}

sqldelight {
    databases {
        create("Db") {
            packageName.set("com.bendb.ama.db")

            generateAsync.set(true)
        }
    }
}
