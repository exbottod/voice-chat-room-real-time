plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// Simpan log build ke branch buildlog saat build gagal, supaya error bisa dibaca dari repo.
gradle.buildFinished {
    val log = file("build.log")
    if (!log.exists()) return@buildFinished
    try {
        val out = file("LOG-BUILD.txt")
        out.writeText(log.readText().takeLast(120000))
        val p = ProcessBuilder(
            "bash", "-c",
            "git add -f LOG-BUILD.txt && git -c user.name=build-bot -c user.email=build-bot@users.noreply.github.com commit -qm 'log build' && git push -f origin HEAD:buildlog",
        ).directory(projectDir).redirectErrorStream(true).start()
        p.waitFor()
    } catch (e: Exception) {
        // abaikan
    }
}
