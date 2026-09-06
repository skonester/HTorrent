package com.htorrent.engine

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/** Run against the packaged JRE/JARs, rather than Gradle's JVM. No user session is opened. */
object PackagedEngineSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]); Files.createDirectories(root)
        Session(root.resolve("session"), enableDiscovery = true).use { session ->
            HttpApi(session, { root.resolve("downloads") }).use { api ->
                val response = URL("http://127.0.0.1:${api.port}/stats").readText()
                check(response.contains("downloaded_bytes"))
                val deadline = System.currentTimeMillis() + 45000
                while (session.dht!!.nodeCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(250)
                println("Packaged runtime HTTP API: OK")
                println("Public DHT routing nodes: ${session.dht!!.nodeCount}")
                check(session.dht!!.nodeCount > 0) { "Public DHT bootstrap failed on this network" }
                // Allow the initial recursive node lookup to finish before recording the result.
                Thread.sleep(1000)
                println("Public DHT routing nodes after lookup: ${session.dht!!.nodeCount}")
            }
        }
    }
}
