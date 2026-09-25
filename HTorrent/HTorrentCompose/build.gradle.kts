plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.htorrent"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.jsoup:jsoup:1.21.2")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            modules("jdk.httpserver", "java.xml", "jdk.crypto.ec", "java.net.http")
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
//     player/               <- HTorrentPlayer (InfiniFrame/WebView2 stream player)
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

val buildPlayer by tasks.registering(Exec::class) {
    description = "Builds HTorrentPlayer, the InfiniFrame stream player"
    group = "distribution"
    workingDir = project.file("player")
    val playerOut = project.layout.buildDirectory.dir("player-out")
    doFirst { delete(playerOut) }
    commandLine(
        "dotnet", "publish",
        "HTorrentPlayer.fsproj",
        "-c", "Release",
        "-r", "win-x64",
        "--self-contained", "true",
        "-o", project.layout.buildDirectory.dir("player-out").get().asFile.absolutePath
    )
}

// Sync (not Copy) so files dropped from runtime/ or player/ do not linger in the dist folder and installer.
val packageApp by tasks.registering(Sync::class) {
    description = "Assembles the final HTorrent distribution folder"
    group = "distribution"
    dependsOn("createDistributable", buildLauncher, buildPlayer)

    val distName = providers.gradleProperty("distName").getOrElse("HTorrent")
    require(distName.matches(Regex("[A-Za-z0-9_-]+")))
    val distDir = project.layout.buildDirectory.dir("dist/$distName")

    // Copy the createDistributable output into runtime/
    from(project.layout.buildDirectory.dir("compose/binaries/main/app/HTorrent")) {
        into("runtime")
    }

    // Copy the launcher stub EXE to the root
    from(project.layout.buildDirectory.dir("launcher-out")) {
        include("HTorrent.exe")
    }

    from(project.layout.buildDirectory.dir("player-out")) {
        exclude("*.pdb")
        into("player")
    }

    from("RQBIT-NOTICE.md")
    from("TORRENTSEARCH-NOTICE.md")
    from("licenses") { into("licenses") }

    into(distDir)

    doLast {
        println("=== HTorrent distribution assembled ===")
        println("Output: ${distDir.get().asFile.absolutePath}")
        println("Run:    ${distDir.get().asFile.absolutePath}\\HTorrent.exe")
    }
}

// ---------------------------------------------------------------------------
// packageInstaller — wraps the packageApp folder in a Windows setup EXE:
//   build/installer/HTorrent-Setup-<version>.exe
// Needs NSIS 3 (makensis on PATH, or -Pmakensis=C:\path\to\makensis.exe).
// ---------------------------------------------------------------------------
val packageInstaller by tasks.registering(Exec::class) {
    description = "Builds the Windows setup EXE from the packageApp folder"
    group = "distribution"
    dependsOn(packageApp)

    val distName = providers.gradleProperty("distName").getOrElse("HTorrent")
    val appVersion = project.version.toString()
    val sourceDir = project.layout.buildDirectory.dir("dist/$distName").get().asFile
    val script = project.file("installer/HTorrent.nsi")
    val setupExe = project.layout.buildDirectory.file("installer/HTorrent-Setup-$appVersion.exe").get().asFile

    inputs.dir(sourceDir)
    inputs.file(script)
    outputs.file(setupExe)

    doFirst { setupExe.parentFile.mkdirs() }
    commandLine(
        providers.gradleProperty("makensis").getOrElse("makensis"),
        "-V2",
        "-DVERSION=$appVersion",
        "-DSOURCE_DIR=${sourceDir.absolutePath}",
        "-DOUT_FILE=${setupExe.absolutePath}",
        script.absolutePath
    )

    doLast { println("Installer: ${setupExe.absolutePath}") }
}

kotlin { jvmToolchain(21) }
