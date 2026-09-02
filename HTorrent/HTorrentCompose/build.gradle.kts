plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.htorrent"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("com.github.atomashpolskiy:bt-core:1.11-SNAPSHOT")
    implementation("com.github.atomashpolskiy:bt-dht:1.11-SNAPSHOT")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // No targetFormats — we use createDistributable, not an installer
            packageName = "HTorrent"
            packageVersion = "1.0.0"
            description = "HTorrent - Torrent Client by Skonester"
            vendor = "Skonester"
            copyright = "Copyright © 2026 Skonester"
            windows {
                iconFile.set(project.file("icon/favicon.ico"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// packageApp — assembles the final distributable folder:
//   build/dist/HTorrent/
//     HTorrent.exe          <- native .NET launcher stub
//     runtime/              <- createDistributable output (JRE + JARs + jpackage launcher)
// ---------------------------------------------------------------------------
val buildLauncher by tasks.registering(Exec::class) {
    description = "Builds the native .NET launcher stub EXE"
    group = "distribution"
    workingDir = project.file("launcher")
    commandLine(
        "dotnet", "publish",
        "HTorrent.Launcher.csproj",
        "-c", "Release",
        "-r", "win-x64",
        "--self-contained", "true",
        "-o", project.layout.buildDirectory.dir("launcher-out").get().asFile.absolutePath
    )
}

val packageApp by tasks.registering(Copy::class) {
    description = "Assembles the final HTorrent distribution folder"
    group = "distribution"
    dependsOn("createDistributable", buildLauncher)

    val distDir = project.layout.buildDirectory.dir("dist/HTorrent")

    // Copy the createDistributable output into runtime/
    from(project.layout.buildDirectory.dir("compose/binaries/main/app/HTorrent")) {
        into("runtime")
    }

    // Copy the launcher stub EXE to the root
    from(project.layout.buildDirectory.dir("launcher-out")) {
        include("HTorrent.exe")
    }

    into(distDir)

    doLast {
        println("=== HTorrent distribution assembled ===")
        println("Output: ${distDir.get().asFile.absolutePath}")
        println("Run:    ${distDir.get().asFile.absolutePath}\\HTorrent.exe")
    }
}
