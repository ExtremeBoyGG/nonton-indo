rootProject.name = "nonton-indo"

File(rootDir, ".").eachDir { dir ->
    if (dir.name != ".git" && dir.name != ".github" && dir.name != ".gradle" && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
