package org.berndpruenster.netlayer.tor.demo

import org.berndpruenster.netlayer.tor.HiddenServiceSocket
import org.berndpruenster.netlayer.tor.NativeTor
import org.berndpruenster.netlayer.tor.Tor
import org.berndpruenster.netlayer.tor.TorSocket
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val DEFAULT_TARGET_URL =
        "http://runbtcpn7gmbj5rgqeyfyvepqokrijem6rbw7o5wgqbguimuoxrmcdyd.onion/getAllMarketPrices"

private data class Config(
        val targetUrl: String = DEFAULT_TARGET_URL,
        val workDir: File = File("tor-market-prices-demo"),
        val hiddenServiceDir: String = "market-prices-test-service",
        val localServicePort: Int = 18080,
        val hiddenServicePort: Int = 80,
        val bridgeFile: File? = null,
        val hiddenServiceReadyTimeoutSeconds: Long = 90,
        val socketReadTimeoutMillis: Int = 120_000
)

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return
    }

    val config = parseArgs(args)
    var tor: Tor? = null
    var hiddenServiceSocket: HiddenServiceSocket? = null
    val stopServer = AtomicBoolean(false)

    try {
        log("Starting Tor in ${config.workDir.absolutePath}")
        tor = NativeTor(config.workDir, parseBridgeLines(config.bridgeFile), null, automaticShutdown = false)
        Tor.default = tor
        log("Tor bootstrapped")

        val ready = CountDownLatch(1)
        hiddenServiceSocket = HiddenServiceSocket(
                config.localServicePort,
                config.hiddenServiceDir,
                config.hiddenServicePort,
                tor
        )
        val serverThread = serveHiddenService(hiddenServiceSocket, stopServer)
        hiddenServiceSocket.addReadyListener { socket ->
            log("Hidden service announced: http://${socket.serviceName}/")
            log("Hidden service maps onion port ${socket.hiddenServicePort} to local port ${config.localServicePort}")
            ready.countDown()
        }

        log("Publishing hidden service ${hiddenServiceSocket.serviceName}; waiting for descriptor upload")
        if (!ready.await(config.hiddenServiceReadyTimeoutSeconds, TimeUnit.SECONDS)) {
            log("Timed out waiting for the hidden-service ready event; continuing with the outbound onion request")
        }

        log("Requesting ${config.targetUrl}")
        val response = httpGetViaTor(config.targetUrl, config.socketReadTimeoutMillis, tor)
        log("Response status: ${response.statusLine}")
        if (response.headers.isNotEmpty()) {
            log("Response headers:")
            response.headers.forEach { println(it) }
        }
        log("Response body:")
        println(response.body)

        stopServer.set(true)
        hiddenServiceSocket.close()
        serverThread.join(2_000)
    } finally {
        stopServer.set(true)
        hiddenServiceSocket?.let {
            runCatching { it.close() }
        }
        tor?.shutdown()
        Tor.default = null
    }
}

private fun serveHiddenService(hiddenServiceSocket: HiddenServiceSocket, stopServer: AtomicBoolean): Thread =
        thread(name = "market-prices-hidden-service", isDaemon = true) {
            while (!stopServer.get()) {
                try {
                    val client = hiddenServiceSocket.accept()
                    thread(name = "market-prices-hidden-service-client", isDaemon = true) {
                        client.use {
                            handleHiddenServiceRequest(it, hiddenServiceSocket.serviceName)
                        }
                    }
                } catch (e: SocketException) {
                    if (!stopServer.get()) {
                        log("Hidden-service socket closed unexpectedly: ${e.message}")
                    }
                    break
                } catch (e: IOException) {
                    if (!stopServer.get()) {
                        log("Hidden-service accept failed: ${e.message}")
                    }
                }
            }
        }

private fun handleHiddenServiceRequest(socket: Socket, serviceName: String) {
    socket.soTimeout = 10_000
    val requestLine = readRequestLine(socket)
    val body = """
        netlayer hidden service is running
        service=http://$serviceName/
        request=${requestLine ?: "<no request line>"}
    """.trimIndent() + "\n"
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    socket.getOutputStream().use { output ->
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }
}

private fun readRequestLine(socket: Socket): String? {
    val buffer = ByteArrayOutputStream()
    val input = socket.getInputStream()
    while (true) {
        val next = input.read()
        if (next < 0) return null
        if (next == '\n'.code) {
            return buffer.toString(StandardCharsets.US_ASCII).trimEnd('\r')
        }
        buffer.write(next)
    }
}

private data class HttpResponse(
        val statusLine: String,
        val headers: List<String>,
        val body: String
)

private fun httpGetViaTor(url: String, readTimeoutMillis: Int, tor: Tor): HttpResponse {
    val uri = URI(url)
    require(uri.scheme.equals("http", ignoreCase = true)) {
        "Only plain http:// URLs are supported by this small TorSocket test app"
    }
    val host = requireNotNull(uri.host) { "Target URL must include a host: $url" }
    val port = if (uri.port == -1) 80 else uri.port
    val path = buildString {
        append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
        uri.rawQuery?.let {
            append('?')
            append(it)
        }
    }
    val hostHeader = if (port == 80) host else "$host:$port"
    val request = "GET $path HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "Accept: application/json, text/plain, */*\r\n" +
            "User-Agent: netlayer-market-prices-test/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n"

    TorSocket(host, port, proxyHost = "127.0.0.1", streamId = "market-prices-test", tor = tor).use { socket ->
        socket.soTimeout = readTimeoutMillis
        val output = socket.getOutputStream()
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val rawResponse = socket.getInputStream().readBytes()
        val responseText = rawResponse.toString(StandardCharsets.UTF_8)
        val headerEnd = responseText.indexOf("\r\n\r\n")
        if (headerEnd < 0) {
            return HttpResponse("<missing status line>", emptyList(), responseText)
        }

        val headerText = responseText.substring(0, headerEnd)
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val body = responseText.substring(headerEnd + 4)
        return HttpResponse(statusLine, lines.drop(1), body)
    }
}

private fun parseBridgeLines(file: File?): Collection<String>? {
    if (file == null) return null
    return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
}

private fun parseArgs(args: Array<String>): Config {
    var config = Config()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--target-url" -> config = config.copy(targetUrl = args.valueAt(++i, arg))
            "--work-dir" -> config = config.copy(workDir = File(args.valueAt(++i, arg)))
            "--hidden-service-dir" -> config = config.copy(hiddenServiceDir = args.valueAt(++i, arg))
            "--local-service-port" -> config = config.copy(localServicePort = args.valueAt(++i, arg).toInt())
            "--hidden-service-port" -> config = config.copy(hiddenServicePort = args.valueAt(++i, arg).toInt())
            "--bridge-file" -> config = config.copy(bridgeFile = File(args.valueAt(++i, arg)))
            "--hidden-service-ready-timeout-seconds" ->
                config = config.copy(hiddenServiceReadyTimeoutSeconds = args.valueAt(++i, arg).toLong())
            "--socket-read-timeout-millis" ->
                config = config.copy(socketReadTimeoutMillis = args.valueAt(++i, arg).toInt())
            else -> throw IllegalArgumentException("Unknown argument: $arg. Run with --help for usage.")
        }
        i++
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
              mvn -pl tor.native -am -DskipTests -Pmarket-prices-test-app verify

              mvn -pl tor.native -am -DskipTests -Pmarket-prices-test-app verify \
                -DmarketPricesTestApp.args="--bridge-file bridges.txt"

            Options:
              --target-url <url>                         Default: $DEFAULT_TARGET_URL
              --work-dir <path>                          Default: tor-market-prices-demo
              --hidden-service-dir <name>                Default: market-prices-test-service
              --local-service-port <port>                Default: 18080
              --hidden-service-port <port>               Default: 80
              --bridge-file <path>                       Optional bridge line file
              --hidden-service-ready-timeout-seconds <n> Default: 90
              --socket-read-timeout-millis <n>           Default: 120000
            """.trimIndent()
    )
}

private fun log(message: String) {
    println("[${Instant.now()}] $message")
}
