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

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.sqldelight)
}

group = "com.example"
version = "1.0-SNAPSHOT"

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

                implementation(libs.koin.compose)
                implementation(libs.koin.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.network)

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

                implementation(libs.sqldelight.coroutines)
                implementation(libs.sqldelight.sqliteDriver)
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application {
        mainClass = "com.bendb.ama.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ama-kt"
            packageVersion = "1.0.0"
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
