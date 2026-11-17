package org.radiolink.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal newline-delimited text transport used to verify a Wi-Fi Direct link.
 * Audio transport will be implemented separately after this path is field-tested.
 */
class LocalChatTransport(
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onReady: () -> Unit
) {
    companion object {
        private const val PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 2_000
    private const val MAX_MESSAGE_CHARS = 4_096
    }

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var writer: PrintWriter? = null

    fun startAsGroupOwner() {
        executor.execute {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                serverSocket = server
                postStatus("گروه محلی آماده است؛ منتظر گوشی دیگر...")
                val client = server.accept()
                if (closed) {
                    client.close()
                    return@execute
                }
                try { server.close() } catch (_: Exception) {}
                serverSocket = null
                attach(client)
            } catch (error: Exception) {
                if (!closed) postStatus("راه‌اندازی میزبان ناموفق بود: ${error.localizedMessage ?: "خطای شبکه"}")
            }
        }
    }

    fun connectToGroupOwner(hostAddress: String) {
        executor.execute {
            var lastError: Exception? = null
            repeat(8) {
                if (closed) return@execute
                try {
                    val client = Socket()
                    client.tcpNoDelay = true
                    client.connect(InetSocketAddress(hostAddress, PORT), CONNECT_TIMEOUT_MS)
                    attach(client)
                    return@execute
                } catch (error: Exception) {
                    lastError = error
                    try { Thread.sleep(700) } catch (_: InterruptedException) { return@execute }
                }
            }
            postStatus("اتصال شبکه‌ای برقرار نشد: ${lastError?.localizedMessage ?: "پاسخی دریافت نشد"}")
        }
    }

    private fun attach(client: Socket) {
        socket = client
        client.tcpNoDelay = true
        client.keepAlive = true
        writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)
        onReady()
        postStatus("اتصال شبکه‌ای برقرار شد؛ پیام آزمایشی بفرست.")
        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                while (!closed && !client.isClosed) {
                    val line = readBoundedLine(reader) ?: break
                    onMessage(line)
                }
                if (!closed) postStatus("ارتباط با گوشی مقابل قطع شد.")
            } catch (error: Exception) {
                if (!closed) postStatus("دریافت پیام با خطا روبه‌رو شد.")
            }
        }
    }

    @Synchronized
    fun send(message: String): Boolean {
        val output = writer ?: return false
        if (message.isBlank() || closed) return false
        val safeMessage = message.trim().replace('\r', ' ').replace('\n', ' ')
        if (safeMessage.length > MAX_MESSAGE_CHARS) return false
        output.println(safeMessage)
        return !output.checkError()
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val result = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next == -1) return if (result.isEmpty()) null else result.toString()
            if (next == '\n'.code) return result.toString().removeSuffix("\r")
            if (result.length >= MAX_MESSAGE_CHARS) {
                throw IOException("Incoming message exceeds the allowed size")
            }
            result.append(next.toChar())
        }
    }

    private fun postStatus(message: String) {
        if (!closed) onStatus(message)
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
