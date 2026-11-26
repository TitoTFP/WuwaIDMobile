package com.titotfp.wuwaid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure argument boundaries; no Android filesystem, shell, su, or Shizuku is executed. */
class StorageBoundaryTest {
  @Test fun rejectsOfficialGameAndUnknownModFilesEvenInsideAnOwnedDirectory() {
    for (path in listOf("3.7.0/ResManifest", "3.7.0/Lang_en/base.pak",
      "3.7.0/Resource/base.sig", "3.7.0/Mount/MountResource.txt",
      "3.7.0/Mount/other-mod.txt", "3.7.0/wuwaid/user-file.pak",
      "3.7.0/wuwaindonesia/user-file.pak")) {
      assertThrows(path, SecurityException::class.java) {
        requireWritableTarget(path, allowPatchRoot = true, allowMountDirectory = true)
      }
    }
  }

  @Test fun rejectsMalformedVersionsAndTraversalAtTheMutationBoundary() {
    for (version in listOf("", "3.7", "03.7.0", "3.07.0", "3.7.00", "3.7.0-beta", "-1.7.0", "٣.7.0")) {
      assertThrows(version, SecurityException::class.java) {
        requireWritableTarget("$version/wuwaid/WuwaID_99_P.pak", allowPatchRoot = true)
      }
    }
    for (path in listOf("/3.7.0/wuwaid/WuwaID_99_P.pak", "../3.7.0/wuwaid/WuwaID_99_P.pak",
      "3.7.0/wuwaid/../Lang_en/base.pak", "3.7.0/wuwaid//WuwaID_99_P.pak",
      "3.7.0/wuwaid/WuwaID_99_P.pak/extra", "3.7.0/wuwaid\\WuwaID_99_P.pak")) {
      assertThrows(path, SecurityException::class.java) { requireWritableTarget(path, allowPatchRoot = true) }
    }
  }

  @Test fun fileOperationsCannotUseDirectoryPermissionsFromOtherOperations() {
    for (path in listOf("3.7.0/wuwaid", "3.7.0/wuwaindonesia", "3.7.0/Mount")) {
      assertThrows(path, SecurityException::class.java) {
        requireWritableTarget(path, allowPatchRoot = false, allowMountDirectory = false)
      }
    }
  }

  @Test fun mkdirCannotTreatAnOwnedFileAsADirectory() {
    for (path in listOf("3.7.0/wuwaid/WuwaID_99_P.pak", "3.7.0/wuwaid/.owner.json.new",
      "3.7.0/Mount/wuwaid.txt", "3.7.0/wuwaindonesia/WuWaID_99_P.sig")) {
      assertThrows(path, SecurityException::class.java) {
        requireWritableTarget(path, allowPatchRoot = true, allowMountDirectory = true, directoryOnly = true)
      }
    }
  }

  @Test fun rejectsUnprovenLegacyLifecycleFilesAndNestedArtifacts() {
    for (path in listOf("3.7.0/wuwaindonesia/WuWaID_99_P.pak.new",
      "3.7.0/wuwaindonesia/WuWaID_99_P.sig.bak", "3.7.0/Mount/wuwaindonesia.txt.new.tmp",
      "3.7.0/wuwaid/WuwaID_99_P.pak.new.tmp", "3.7.0/wuwaid/.owner.json.bak.bak")) {
      assertThrows(path, SecurityException::class.java) { requireWritableTarget(path, allowPatchRoot = true) }
    }
  }

  @Test fun gameSelectionRejectsEmptyDuplicateForeignAndOversizeCandidateLists() {
    for (candidates in listOf(emptyList(), listOf(GamePackages.GLOBAL, GamePackages.GLOBAL),
      listOf(GamePackages.SAMSUNG, GamePackages.SAMSUNG), listOf("com.example.game"),
      listOf(GamePackages.GLOBAL, "com.example.game"),
      listOf(GamePackages.GLOBAL, GamePackages.SAMSUNG, GamePackages.GLOBAL))) {
      assertThrows(candidates.toString(), IllegalArgumentException::class.java) { GamePackages.choose(candidates) }
    }
  }

  @Test fun unsupportedGamePackageCannotConstructAResourceRoot() {
    for (packageName in listOf("", "com.kurogame.wutheringwaves.global.debug", "../outside", "com.example.game")) {
      assertThrows(packageName, IllegalArgumentException::class.java) { GamePackages.resources(packageName) }
    }
  }

  @Test fun axManagerRequiresSupportedAndroidPrimaryUserAndBothAppAndServerVersions() {
    assertTrue(isAxManagerCompatible(26, 0, 30000, 30000))
    assertTrue(isAxManagerCompatible(36, 0, 30100, 30000))
    assertFalse(isAxManagerCompatible(25, 0, 30000, 30000))
    assertFalse(isAxManagerCompatible(36, 10, 30000, 30000))
    assertFalse(isAxManagerCompatible(36, 0, 29999, 30000))
    assertFalse(isAxManagerCompatible(36, 0, 30000, 29999))
  }

  @Test fun ownerSelectionPrefersNonRootGameOwnerAndFallsBackToGameGroup() {
    assertEquals(10345 to 1078, selectOwner(listOf(10345 to 1078, 0 to 9997)))
    assertEquals(0 to 9997, selectOwner(listOf(0 to 9997, 0 to 0)))
    assertNull(selectOwner(emptyList()))
  }
}
