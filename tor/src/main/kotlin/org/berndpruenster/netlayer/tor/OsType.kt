/*
Copyright (c) 2016, 2017 Bernd Prünster
This file is part of of the unofficial Java-Tor-bindings.

Licensed under the EUPL, Version 1.1 or - as soon they will be approved by the
European Commission - subsequent versions of the EUPL (the "Licence"); You may
not use this work except in compliance with the Licence. You may obtain a copy
of the Licence at: http://joinup.ec.europa.eu/software/page/eupl

Unless required by applicable law or agreed to in writing, software distributed
under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the Licence for the
specific language governing permissions and limitations under the Licence.

This project includes components developed by third parties and provided under
various open source licenses (www.opensource.org).


 Copyright (c) 2014-2015 Microsoft Open Technologies, Inc.
 Copyright (C) 2011-2014 Sublime Software Ltd

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

package org.berndpruenster.netlayer.tor

import java.io.IOException
import java.util.*

enum class OsType { WIN,
    LNX32,
    LNX64,
    LNXAA64,
    MACOS,
    MACOS_AARCH64,
    ANDROID;

    companion object {
        @JvmStatic
        val current: OsType by lazy {
            detect(
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    System.getProperty("java.vm.name")
            )
        }

        internal fun detect(osName: String, osArch: String, javaVmName: String): OsType {
            //This also works for ART
            return if (javaVmName.contains("Dalvik")) {
                ANDROID
            } else {
                when {
                    osName.contains("Windows") -> WIN
                    osName.contains("Mac")     -> getMacOSType(osArch)
                    osName.contains("Linux")   -> getLinuxType()
                    else                       -> throw RuntimeException("Unsupported OS: $osName")
                }
            }
        }

        private fun getMacOSType(osArch: String): OsType {
            return when (osArch.lowercase(Locale.ROOT)) {
                "aarch64", "arm64" -> MACOS_AARCH64
                "x86_64", "amd64" -> MACOS
                else -> throw RuntimeException("Unsupported macOS architecture: $osArch")
            }
        }

        private fun getLinuxType(): OsType {
            val cmd = arrayOf("uname", "-m")
            val unameProcess = Runtime.getRuntime().exec(cmd)
            try {
                val unameOutput: String

                val scanner = Scanner(unameProcess.inputStream)
                if (scanner.hasNextLine()) {
                    unameOutput = scanner.nextLine()
                    scanner.close()
                } else {
                    scanner.close()
                    throw  RuntimeException("Couldn't get output from uname call")
                }

                val exit = unameProcess.waitFor()
                if (exit != 0) {
                    throw  RuntimeException("Uname returned error code $exit")
                }

                if (unameOutput.matches(Regex("i.86"))) {
                    return LNX32
                }
                if (unameOutput.compareTo("x86_64") == 0) {
                    return LNX64
                }

                if (unameOutput.matches(Regex("arm64")) || 
                    unameOutput.matches(Regex("aarch64")) ) {
                    return LNXAA64
                }
                throw  RuntimeException("Could not understand uname output, not sure what bitness")
            } catch (e: IOException) {
                throw  RuntimeException("Uname failure", e)
            } catch (e: InterruptedException) {
                throw  RuntimeException("Uname failure", e)
            } finally {
                unameProcess.destroy()
            }
        }

    }

    fun isUnixoid(): Boolean {
        return this == LNX32 || this == LNX64 || this == LNXAA64 || isMacOS()
    }

    fun isMacOS(): Boolean {
        return this == MACOS || this == MACOS_AARCH64
    }
}
