package com.htorrent.mobile.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** A small HTTP/1.1 test server on plain sockets (the JDK's built-in server is not on Android's test classpath). */
class MiniHttpServer {
    class Headers {
        private val map = linkedMapOf<String, MutableList<String>>()
        fun add(name: String, value: String) { map.getOrPut(name.lowercase()) { mutableListOf() }.add(value) }
        fun getFirst(name: String): String? = map[name.lowercase()]?.firstOrNull()
        fun all(): Map<String, List<String>> = map
    }

    class Exchange(private val socket: Socket, val path: String, val requestHeaders: Headers) {
        val responseHeaders = Headers()
        lateinit var responseBody: OutputStream
            private set

        /** `length` -1 = no body. */
        fun sendResponseHeaders(code: Int, length: Long) {
            val out = socket.getOutputStream()
            val sb = StringBuilder("HTTP/1.1 $code X\r\n")
            responseHeaders.all().forEach { (k, vs) -> vs.forEach { sb.append(k).append(": ").append(it).append("\r\n") } }
            sb.append("Content-Length: ").append(if (length < 0) 0 else length).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            responseBody = out
        }

        fun close() { runCatching { socket.close() } }
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val handlers = mutableMapOf<String, (Exchange) -> Unit>()
    val port: Int get() = socket.localPort

    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }

    fun start() {
        pool.execute {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { serve(s) }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun serve(s: Socket) {
        try {
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val headers = Headers()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers.add(line.substring(0, i).trim(), line.substring(i + 1).trim())
            }
            val handler = handlers[path]
            val ex = Exchange(s, path, headers)
            if (handler == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
            handler(ex)
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }
}
