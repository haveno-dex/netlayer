package org.berndpruenster.netlayer.tor.demo

import org.berndpruenster.netlayer.tor.HiddenServiceSocket
import org.berndpruenster.netlayer.tor.NativeTor
import org.berndpruenster.netlayer.tor.Tor
import org.berndpruenster.netlayer.tor.TorSocket
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val ONION_ADDRESS = Regex("""\b[a-z2-7]{16,56}\.onion\b""", RegexOption.IGNORE_CASE)

private enum class PeerRole(
        val folderName: String,
        val displayName: String,
        val defaultLocalServicePort: Int
) {
    ALICE("alice", "Alice", 18181),
    BOB("bob", "Bob", 18182);
}

private data class PeerConfig(
        val baseDir: File = File("tor-peer-message-demo"),
        val hiddenServicePort: Int = 80,
        val aliceLocalServicePort: Int = PeerRole.ALICE.defaultLocalServicePort,
        val bobLocalServicePort: Int = PeerRole.BOB.defaultLocalServicePort,
        val bridgeFile: File? = null,
        val hiddenServiceReadyTimeoutSeconds: Long = 90,
        val peerDiscoveryTimeoutSeconds: Long = 90,
        val connectAttempts: Int = 12,
        val connectRetryDelayMillis: Long = 5_000,
        val socketReadTimeoutMillis: Int = 60_000
) {
    fun exchangeDir(role: PeerRole): File = File(baseDir, role.folderName)
    fun torWorkDir(role: PeerRole): File = File(File(baseDir, "tor"), role.folderName)
    fun localServicePort(role: PeerRole): Int =
            if (role == PeerRole.ALICE) aliceLocalServicePort else bobLocalServicePort
}

private class PeerRuntime(
        val role: PeerRole,
        val tor: Tor,
        val hiddenServiceSocket: HiddenServiceSocket,
        private val serverThread: Thread,
        private val stopServer: AtomicBoolean,
        private val ready: CountDownLatch
) : Closeable {
    val onionAddress: String
        get() = hiddenServiceSocket.serviceName

    fun awaitReady(timeoutSeconds: Long) {
        if (ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
            log(role, "Hidden service descriptor was uploaded")
        } else {
            log(role, "Timed out waiting for descriptor upload; continuing")
        }
    }

    override fun close() {
        stopServer.set(true)
        runCatching { hiddenServiceSocket.close() }
        runCatching { serverThread.join(2_000) }
        runCatching { tor.shutdown() }
    }
}

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return
    }

    val config = parseArgs(args)
    val bridgeLines = parseBridgeLines(config.bridgeFile)
    var alice: PeerRuntime? = null
    var bob: PeerRuntime? = null

    try {
        alice = startPeer(PeerRole.ALICE, config, bridgeLines)
        bob = startPeer(PeerRole.BOB, config, bridgeLines)

        alice.awaitReady(config.hiddenServiceReadyTimeoutSeconds)
        bob.awaitReady(config.hiddenServiceReadyTimeoutSeconds)

        val bobOnion = discoverPeerOnion(PeerRole.ALICE, PeerRole.BOB, config)
        val aliceOnion = discoverPeerOnion(PeerRole.BOB, PeerRole.ALICE, config)

        val aliceMessage = "hello bob, this is alice"
        val bobReply = sendMessage(alice, PeerRole.BOB, bobOnion, aliceMessage, config)
        log(PeerRole.ALICE, "Reply from Bob: $bobReply")

        val bobMessage = "hello alice, this is bob"
        val aliceReply = sendMessage(bob, PeerRole.ALICE, aliceOnion, bobMessage, config)
        log(PeerRole.BOB, "Reply from Alice: $aliceReply")

        log("Completed peer message test in both directions")
    } finally {
        bob?.close()
        alice?.close()
    }
}

private fun startPeer(role: PeerRole, config: PeerConfig, bridgeLines: Collection<String>?): PeerRuntime {
    var tor: Tor? = null
    var hiddenServiceSocket: HiddenServiceSocket? = null
    try {
        log(role, "Starting Tor in ${config.torWorkDir(role).absolutePath}")
        tor = NativeTor(config.torWorkDir(role), bridgeLines, null, automaticShutdown = false)
        log(role, "Tor bootstrapped")

        val ready = CountDownLatch(1)
        val stopServer = AtomicBoolean(false)
        hiddenServiceSocket = HiddenServiceSocket(
                config.localServicePort(role),
                "${role.folderName}-message-service",
                config.hiddenServicePort,
                tor
        )
        hiddenServiceSocket.addReadyListener { socket ->
            log(role, "Hidden service announced at ${socket.serviceName}")
            ready.countDown()
        }

        val serverThread = servePeer(role, hiddenServiceSocket, stopServer, config.socketReadTimeoutMillis)
        writeOwnOnion(role, hiddenServiceSocket.serviceName, config.exchangeDir(role))
        log(role, "Wrote onion address to ${config.exchangeDir(role).absolutePath}")

        return PeerRuntime(role, tor, hiddenServiceSocket, serverThread, stopServer, ready)
    } catch (e: Throwable) {
        hiddenServiceSocket?.let { runCatching { it.close() } }
        tor?.let { runCatching { it.shutdown() } }
        throw e
    }
}

private fun servePeer(
        role: PeerRole,
        hiddenServiceSocket: HiddenServiceSocket,
        stopServer: AtomicBoolean,
        socketReadTimeoutMillis: Int
): Thread =
        thread(name = "${role.folderName}-message-hidden-service", isDaemon = true) {
            while (!stopServer.get()) {
                try {
                    val client = hiddenServiceSocket.accept()
                    thread(name = "${role.folderName}-message-client", isDaemon = true) {
                        client.use {
                            handlePeerMessage(role, it, socketReadTimeoutMillis)
                        }
                    }
                } catch (e: SocketException) {
                    if (!stopServer.get()) {
                        log(role, "Hidden-service socket closed unexpectedly: ${e.message}")
                    }
                    break
                } catch (e: IOException) {
                    if (!stopServer.get()) {
                        log(role, "Hidden-service accept failed: ${e.message}")
                    }
                }
            }
        }

private fun handlePeerMessage(role: PeerRole, socket: Socket, socketReadTimeoutMillis: Int) {
    socket.soTimeout = socketReadTimeoutMillis
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
    val message = reader.readLine() ?: "<empty>"
    log(role, "Received message: $message")
    writer.write("ack from ${role.folderName}: received '$message'")
    writer.newLine()
    writer.flush()
}

private fun writeOwnOnion(role: PeerRole, onionAddress: String, exchangeDir: File) {
    if (!(exchangeDir.exists() || exchangeDir.mkdirs())) {
        throw IOException("Could not create peer exchange directory ${exchangeDir.absolutePath}")
    }
    val onionFile = File(exchangeDir, "onion.txt")
    onionFile.writeText(
            """
            peer=${role.folderName}
            onion=$onionAddress
            writtenAt=${Instant.now()}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8
    )
}

private fun discoverPeerOnion(localRole: PeerRole, remoteRole: PeerRole, config: PeerConfig): String {
    val remoteDir = config.exchangeDir(remoteRole)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.peerDiscoveryTimeoutSeconds)
    log(localRole, "Looking for ${remoteRole.displayName}'s onion address in ${remoteDir.absolutePath}")

    while (System.nanoTime() < deadline) {
        readAnyOnionAddress(remoteDir)?.let {
            log(localRole, "Discovered ${remoteRole.displayName}'s onion address: $it")
            return it
        }
        Thread.sleep(1_000)
    }

    throw IOException("${localRole.displayName} could not find ${remoteRole.displayName}'s onion address in ${remoteDir.absolutePath}")
}

private fun readAnyOnionAddress(directory: File): String? {
    if (!directory.exists()) return null
    val files = directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
    for (file in files) {
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
        ONION_ADDRESS.find(text)?.let { return it.value.lowercase() }
    }
    return null
}

private fun sendMessage(
        sender: PeerRuntime,
        remoteRole: PeerRole,
        remoteOnionAddress: String,
        message: String,
        config: PeerConfig
): String {
    var lastFailure: IOException? = null
    for (attempt in 1..config.connectAttempts) {
        try {
            log(sender.role, "Sending to ${remoteRole.displayName} at $remoteOnionAddress: $message")
            TorSocket(
                    remoteOnionAddress,
                    config.hiddenServicePort,
                    proxyHost = "127.0.0.1",
                    streamId = "${sender.role.folderName}-to-${remoteRole.folderName}",
                    numTries = 1,
                    tor = sender.tor
            ).use { socket ->
                socket.soTimeout = config.socketReadTimeoutMillis
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.write("from=${sender.role.folderName};to=${remoteRole.folderName};message=$message")
                writer.newLine()
                writer.flush()
                return reader.readLine() ?: "<no reply>"
            }
        } catch (e: IOException) {
            lastFailure = e
            log(sender.role, "Attempt $attempt failed sending to ${remoteRole.displayName}: ${e.message}")
            if (attempt < config.connectAttempts) {
                Thread.sleep(config.connectRetryDelayMillis)
            }
        }
    }
    throw IOException(
            "${sender.role.displayName} could not send a message to ${remoteRole.displayName} after ${config.connectAttempts} attempts",
            lastFailure
    )
}

private fun parseBridgeLines(file: File?): Collection<String>? {
    if (file == null) return null
    return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
}

private fun parseArgs(args: Array<String>): PeerConfig {
    var config = PeerConfig()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--base-dir" -> config = config.copy(baseDir = File(args.valueAt(++i, arg)))
            "--hidden-service-port" -> config = config.copy(hiddenServicePort = args.valueAt(++i, arg).toInt())
            "--alice-local-service-port" ->
                config = config.copy(aliceLocalServicePort = args.valueAt(++i, arg).toInt())
            "--bob-local-service-port" ->
                config = config.copy(bobLocalServicePort = args.valueAt(++i, arg).toInt())
            "--bridge-file" -> config = config.copy(bridgeFile = File(args.valueAt(++i, arg)))
            "--hidden-service-ready-timeout-seconds" ->
                config = config.copy(hiddenServiceReadyTimeoutSeconds = args.valueAt(++i, arg).toLong())
            "--peer-discovery-timeout-seconds" ->
                config = config.copy(peerDiscoveryTimeoutSeconds = args.valueAt(++i, arg).toLong())
            "--connect-attempts" -> config = config.copy(connectAttempts = args.valueAt(++i, arg).toInt())
            "--connect-retry-delay-millis" ->
                config = config.copy(connectRetryDelayMillis = args.valueAt(++i, arg).toLong())
            "--socket-read-timeout-millis" ->
                config = config.copy(socketReadTimeoutMillis = args.valueAt(++i, arg).toInt())
            else -> throw IllegalArgumentException("Unknown argument: $arg. Run with --help for usage.")
        }
        i++
    }
    require(config.aliceLocalServicePort != config.bobLocalServicePort) {
        "Alice and Bob need different local service ports"
    }
    return config
}

private fun Array<String>.valueAt(index: Int, optionName: String): String {
    require(index < size) { "Missing value for $optionName" }
    return this[index]
}

private fun printUsage() {
    println(
            """
            Usage:
              mvn -pl tor.native -am -DskipTests -Ppeer-message-test-app verify

              mvn -pl tor.native -am -DskipTests -Ppeer-message-test-app verify \
                -DpeerMessageTestApp.args="--bridge-file bridges.txt"

            What it does:
              Alice writes her onion address to <base-dir>/alice/onion.txt.
              Bob writes his onion address to <base-dir>/bob/onion.txt.
              Alice scans Bob's folder and sends Bob a message over Bob's hidden service.
              Bob scans Alice's folder and sends Alice a message over Alice's hidden service.

            Options:
              --base-dir <path>                         Default: tor-peer-message-demo
              --hidden-service-port <port>              Default: 80
              --alice-local-service-port <port>         Default: ${PeerRole.ALICE.defaultLocalServicePort}
              --bob-local-service-port <port>           Default: ${PeerRole.BOB.defaultLocalServicePort}
              --bridge-file <path>                      Optional bridge line file
              --hidden-service-ready-timeout-seconds <n> Default: 90
              --peer-discovery-timeout-seconds <n>       Default: 90
              --connect-attempts <n>                     Default: 12
              --connect-retry-delay-millis <n>           Default: 5000
              --socket-read-timeout-millis <n>           Default: 60000
            """.trimIndent()
    )
}

private fun log(role: PeerRole, message: String) {
    log("${role.displayName}: $message")
}

private fun log(message: String) {
    println("[${Instant.now()}] $message")
}
