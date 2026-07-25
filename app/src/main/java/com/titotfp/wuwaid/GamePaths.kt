package com.titotfp.wuwaid

import java.util.Locale

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(value: String): SemVer? {
            val match = pattern.matchEntire(value) ?: return null
            return SemVer(
                match.groupValues[1].toIntOrNull() ?: return null,
                match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

data class PatchPaths(
    val resourceVersion: String,
    val directory: String,
    val pak: String,
    val stagedPak: String,
    val signature: String,
    val mount: String,
)

data class InstallInspection(
    val resourceVersion: String?,
    val conflicts: List<String>,
    val anyOwnedPatch: Boolean,
    val currentHealthy: Boolean,
    val matchesLatest: Boolean,
    val currentSha256: String?,
    val diagnostics: List<String>,
)

class GamePaths(
    private val files: PrivilegedFiles,
    private val resourcesRoot: String = RESOURCES_ROOT,
) {
    fun resourceVersions(): List<String> = files.listFiles(resourcesRoot)
        .filter { SemVer.parse(it) != null }
        .sortedBy { SemVer.parse(it) }

    fun resolveResourceVersion(): String? = resourceVersions()
        .filter { files.exists("$resourcesRoot/$it/ResManifest") }
        .maxByOrNull { SemVer.parse(it)!! }

    fun paths(version: String): PatchPaths {
        require(SemVer.parse(version) != null) { "Versi resource tidak valid" }
        val directory = "$resourcesRoot/$version/$PATCH_FOLDER"
        val base = PATCH_FILENAME.removeSuffix(".pak")
        return PatchPaths(
            resourceVersion = version,
            directory = directory,
            pak = "$directory/$PATCH_FILENAME",
            stagedPak = "$directory/$PATCH_FILENAME.new",
            signature = "$directory/$base.sig",
            mount = "$resourcesRoot/$version/Mount/$MOUNT_FILENAME",
        )
    }

    fun inspect(latest: PatchRelease?): InstallInspection {
        val version = resolveResourceVersion()
        val versions = resourceVersions()
        val anyOwned = versions.any { ownedArtifactPaths(paths(it)).any(files::exists) }
        if (version == null) {
            return InstallInspection(
                resourceVersion = null,
                conflicts = emptyList(),
                anyOwnedPatch = anyOwned,
                currentHealthy = false,
                matchesLatest = false,
                currentSha256 = null,
                diagnostics = listOf("Game: data resource belum siap"),
            )
        }

        val target = paths(version)
        val conflicts = detectConflicts(version)
        val pakExists = files.exists(target.pak)
        val sigExists = files.exists(target.signature)
        val mountExists = files.exists(target.mount)
        val pakSha1 = if (pakExists) files.sha1(target.pak).uppercase(Locale.ROOT) else ""
        val sigSha1 = if (sigExists) files.sha1(target.signature).uppercase(Locale.ROOT) else ""
        val sha256 = if (pakExists) files.sha256(target.pak).lowercase(Locale.ROOT) else ""
        val expectedMount = if (pakSha1.isNotEmpty() && sigSha1.isNotEmpty()) {
            mountContent(pakSha1, sigSha1)
        } else {
            ""
        }
        val actualMount = if (mountExists) normalize(files.readText(target.mount)) else ""
        val healthy = pakExists && sigExists && mountExists && expectedMount == actualMount

        val diagnostics = buildList {
            add("Shizuku file service: siap")
            add("Game package: $GAME_PACKAGE")
            add("Resource version: $version")
            add("PAK: ${if (pakExists) "ada" else "tidak ada"}")
            add("SIG: ${if (sigExists) "ada" else "tidak ada"}")
            add("Mount: ${if (healthy) "valid" else if (mountExists) "tidak cocok" else "tidak ada"}")
            if (sha256.isNotBlank()) add("SHA-256: $sha256")
            if (conflicts.isNotEmpty()) add("Konflik: ${conflicts.joinToString()}")
        }

        return InstallInspection(
            resourceVersion = version,
            conflicts = conflicts,
            anyOwnedPatch = anyOwned,
            currentHealthy = healthy,
            matchesLatest = healthy && latest != null && sha256.equals(latest.sha256, ignoreCase = true),
            currentSha256 = sha256.takeIf(String::isNotBlank),
            diagnostics = diagnostics,
        )
    }

    fun install(externalPatchPath: String, release: PatchRelease) {
        val version = resolveResourceVersion() ?: error("Data resource game belum siap")
        val conflicts = detectConflicts(version)
        check(conflicts.isEmpty()) { "Patch lain terdeteksi: ${conflicts.joinToString()}" }
        val target = paths(version)
        check(files.mkdirs(target.directory)) { operationError("Tidak bisa membuat folder patch") }

        val stagedSignature = "${target.signature}.new"
        val stagedMount = "${target.mount}.new"
        val artifacts = listOf(
            InstallArtifact(target = target.pak, staged = target.stagedPak, backup = "${target.pak}.bak"),
            InstallArtifact(target = target.signature, staged = stagedSignature, backup = "${target.signature}.bak"),
            InstallArtifact(target = target.mount, staged = stagedMount, backup = "${target.mount}.bak"),
        )
        cleanupTemporaryArtifacts(artifacts)

        try {
            check(files.copyFile(externalPatchPath, target.stagedPak)) {
                operationError("Tidak bisa menyalin patch ke game")
            }
            val stagedHash = files.sha256(target.stagedPak)
            check(stagedHash.equals(release.sha256, ignoreCase = true)) {
                "SHA-256 berubah setelah patch disalin ke game"
            }

            val officialSig = findOfficialSignature(version)
                ?: error("Tidak menemukan file .sig resmi untuk dikloning")
            check(files.copyFile(officialSig, stagedSignature)) {
                operationError("Tidak bisa mengkloning .sig")
            }

            val pakSha1 = files.sha1(target.stagedPak).uppercase(Locale.ROOT)
            val sigSha1 = files.sha1(stagedSignature).uppercase(Locale.ROOT)
            check(pakSha1.isNotBlank() && sigSha1.isNotBlank()) {
                operationError("Tidak bisa menghitung hash mount")
            }
            val mount = mountContent(pakSha1, sigSha1)
            check(files.writeTextAtomic(stagedMount, mount)) {
                operationError("Tidak bisa menyiapkan mount")
            }
            check(normalize(files.readText(stagedMount)) == mount) {
                "Verifikasi mount sementara gagal"
            }

            backupCurrentArtifacts(artifacts)
            commitArtifacts(artifacts)

            check(files.sha256(target.pak).equals(release.sha256, ignoreCase = true)) {
                "Verifikasi SHA-256 patch terpasang gagal"
            }
            check(normalize(files.readText(target.mount)) == mount) {
                "Verifikasi mount gagal"
            }
            cleanupOldVersions(version)
        } catch (error: Throwable) {
            val rollbackErrors = rollbackArtifacts(artifacts)
            if (rollbackErrors.isNotEmpty()) {
                throw IllegalStateException(
                    "${error.message ?: error.javaClass.simpleName}; rollback gagal: ${rollbackErrors.joinToString()}",
                    error,
                )
            }
            throw error
        } finally {
            cleanupTemporaryArtifacts(artifacts)
        }
    }

    fun uninstall(): Int {
        var removed = 0
        for (version in resourceVersions()) {
            for (path in ownedArtifactPaths(paths(version))) {
                if (files.exists(path) && files.deleteFile(path)) removed++
            }
        }
        return removed
    }

    fun detectConflicts(version: String): List<String> {
        val conflicts = linkedSetOf<String>()
        val versionRoot = "$resourcesRoot/$version"
        val vietnamDirectory = "$versionRoot/wuwaviethoa"
        val mountDirectory = "$versionRoot/Mount"
        if (files.exists(vietnamDirectory)) conflicts += "folder wuwaviethoa"

        for (name in files.listFiles(mountDirectory).filter { it.endsWith(".txt", ignoreCase = true) }) {
            if (name.equals(MOUNT_FILENAME, ignoreCase = true)) continue
            val content = normalize(files.readText("$mountDirectory/$name"))
            val knownVietnam = name.contains("wuwaviethoa", ignoreCase = true) ||
                content.contains("wuwavh", ignoreCase = true) ||
                content.contains("wuwaviethoa", ignoreCase = true)
            val customHighPriority = !name.startsWith("MountLang_", ignoreCase = true) &&
                content.lineSequence().any(::isHighPriorityMountLine)
            if (knownVietnam || customHighPriority) conflicts += "Mount/$name"
        }
        val officialEnglishMount = "$mountDirectory/MountLang_en.txt"
        if (files.readText(officialEnglishMount).contains("WuWaVH_99_P", ignoreCase = true)) {
            conflicts += "Mount/MountLang_en.txt (WuWaVH lama)"
        }
        return conflicts.toList()
    }

    fun mountContent(pakSha1: String, sigSha1: String): String = buildString {
        append("::Mount::\n")
        append("$PATCH_FOLDER/${PATCH_FILENAME.removeSuffix(".pak")},99,$pakSha1,$sigSha1,,\n")
        append("::Del::\n")
    }

    private fun findOfficialSignature(version: String): String? {
        val englishRoot = "$resourcesRoot/$version/Lang_en"
        val directories = files.listFiles(englishRoot).map { "$englishRoot/$it" }.toMutableList()
        directories += "$resourcesRoot/$version/Resource/Base"
        for (directory in directories) {
            val signature = files.listFiles(directory)
                .firstOrNull { it.endsWith(".sig", ignoreCase = true) && !it.startsWith(PATCH_FILENAME.removeSuffix(".pak")) }
            if (signature != null) return "$directory/$signature"
        }
        return null
    }

    private fun backupCurrentArtifacts(artifacts: List<InstallArtifact>) {
        for (artifact in artifacts) {
            artifact.hadOriginal = files.exists(artifact.target)
            check(files.deleteFile(artifact.backup)) {
                operationError("Tidak bisa membersihkan backup ${artifact.target}")
            }
            if (artifact.hadOriginal) {
                check(files.replaceFile(artifact.target, artifact.backup)) {
                    operationError("Tidak bisa membuat backup ${artifact.target}")
                }
                artifact.backedUp = true
            }
        }
    }

    private fun commitArtifacts(artifacts: List<InstallArtifact>) {
        for (artifact in artifacts) {
            check(files.replaceFile(artifact.staged, artifact.target)) {
                operationError("Tidak bisa memasang ${artifact.target}")
            }
            artifact.installed = true
        }
    }

    private fun rollbackArtifacts(artifacts: List<InstallArtifact>): List<String> {
        val errors = mutableListOf<String>()
        for (artifact in artifacts.asReversed()) {
            if (artifact.installed && files.exists(artifact.target) && !files.deleteFile(artifact.target)) {
                errors += "hapus ${artifact.target}"
            }
            if (artifact.backedUp) {
                if (files.exists(artifact.target) && !files.deleteFile(artifact.target)) {
                    errors += "bersihkan ${artifact.target}"
                    continue
                }
                if (!files.replaceFile(artifact.backup, artifact.target)) {
                    errors += "pulihkan ${artifact.target}"
                }
            }
        }
        return errors
    }

    private fun cleanupTemporaryArtifacts(artifacts: List<InstallArtifact>) {
        artifacts.flatMap { artifact ->
            listOf(artifact.staged, "${artifact.staged}.tmp", artifact.backup)
        }.distinct().forEach(files::deleteFile)
    }

    private fun ownedArtifactPaths(target: PatchPaths): List<String> {
        val stagedSignature = "${target.signature}.new"
        val stagedMount = "${target.mount}.new"
        return listOf(
            target.mount,
            target.pak,
            target.signature,
            target.stagedPak,
            stagedSignature,
            stagedMount,
            "${target.stagedPak}.tmp",
            "$stagedSignature.tmp",
            "$stagedMount.tmp",
            "${target.pak}.bak",
            "${target.signature}.bak",
            "${target.mount}.bak",
        )
    }

    private fun cleanupOldVersions(currentVersion: String) {
        for (version in resourceVersions().filterNot { it == currentVersion }) {
            ownedArtifactPaths(paths(version)).forEach(files::deleteFile)
        }
    }

    private fun operationError(prefix: String): String {
        val detail = files.lastError()
        return if (detail.isBlank()) prefix else "$prefix: $detail"
    }

    private data class InstallArtifact(
        val target: String,
        val staged: String,
        val backup: String,
        var hadOriginal: Boolean = false,
        var backedUp: Boolean = false,
        var installed: Boolean = false,
    )

    companion object {
        const val GAME_PACKAGE = "com.kurogame.wutheringwaves.global"
        const val GAME_ROOT = "/storage/emulated/0/Android/data/$GAME_PACKAGE"
        const val RESOURCES_ROOT = "$GAME_ROOT/files/UE4Game/Client/Client/Saved/Resources"
        const val PATCH_FOLDER = "wuwaindonesia"
        const val PATCH_FILENAME = "WuWaID_99_P.pak"
        const val MOUNT_FILENAME = "wuwaindonesia.txt"

        fun normalize(text: String): String = text.replace("\r\n", "\n")

        fun isHighPriorityMountLine(line: String): Boolean {
            val parts = line.split(',')
            return parts.size >= 2 && (parts[1].trim().toIntOrNull() ?: -1) >= 99
        }
    }
}
