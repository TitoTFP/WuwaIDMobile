package com.titotfp.wuwaid.storage

import java.io.File

internal object GamePackages {
  const val GLOBAL = "com.kurogame.wutheringwaves.global"
  const val SAMSUNG = "com.kurogame.wutheringwaves.samsung"
  val supported = listOf(GLOBAL, SAMSUNG)
  private val versionPattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")

  fun files(packageName: String): File {
    require(packageName in supported) { "Unsupported Wuthering Waves package" }
    return File("/storage/emulated/0/Android/data/$packageName/files")
  }

  fun resources(packageName: String): File = File(files(packageName), "UE4Game/Client/Client/Saved/Resources")

  // Preserve the released launcher's preference: materialized Global first,
  // then materialized Galaxy Store, then the first installed supported package.
  fun choose(candidates: List<String>): String {
    require(candidates.isNotEmpty() && candidates.size <= supported.size &&
      (candidates.size == 1 || candidates[0] != candidates[1]) && candidates.all { it in supported }) {
      "Game selection requires installed, allowlisted packages"
    }
    if (candidates.size == 1) return candidates[0]
    val ordered = supported.filter { it in candidates }
    return ordered.firstOrNull { packageName ->
      val root = resources(packageName)
      root.listFiles()?.any { version ->
        version.isDirectory && versionPattern.matches(version.name) &&
          File(version, "ResManifest").exists() && File(version, "Mount/MountResource.txt").isFile &&
          !File(version, "Resource").list().isNullOrEmpty()
      } == true
    } ?: ordered.first()
  }
}
