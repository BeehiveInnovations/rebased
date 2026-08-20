// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.waitUntil
import git4idea.GitVcs
import git4idea.repo.GitRepositoryFiles.GITIGNORE
import git4idea.test.GitSingleRepoTest
import git4idea.test.add
import git4idea.test.createSubRepository
import git4idea.test.initRepo
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class GitIgnoredDirectoryExclusionsTest : GitSingleRepoTest() {
  private lateinit var hiddenIgnoredDir: VirtualFile
  private lateinit var visibleIgnoredDir: VirtualFile
  private lateinit var trackedHiddenDir: VirtualFile
  private lateinit var trackedDotFile: VirtualFile
  private lateinit var ordinaryUntrackedDir: VirtualFile
  private lateinit var ignoredSourceDir: VirtualFile
  private lateinit var exclusions: GitIgnoredDirectoryExclusions
  private var gitIgnoreFile: VirtualFile? = null

  override fun setUpModule() {
    runWriteAction {
      myModule = createMainModule()
      val moduleDir = getOrCreateModuleDir(module)
      myModule.addContentRoot(moduleDir)

      hiddenIgnoredDir = moduleDir.findOrCreateDir(".DerivedData").apply { createFile("hidden.o") }
      visibleIgnoredDir = moduleDir.findOrCreateDir("DerivedData").apply { createFile("visible.o") }
      trackedHiddenDir = moduleDir.findOrCreateDir(".github").apply {
        findOrCreateDir("workflows").createFile("ci.yml")
      }
      trackedDotFile = moduleDir.createFile(".env.example")
      ordinaryUntrackedDir = moduleDir.findOrCreateDir("scratch").apply { createFile("notes.txt") }
      ignoredSourceDir = moduleDir.findOrCreateDir("gen").apply { createFile("Generated.java") }
      myModule.addSourceFolder(ignoredSourceDir)
    }

  }

  override fun setUp() {
    super.setUp()
    add("${trackedHiddenDir.path}/workflows/ci.yml")
    add(trackedDotFile.path)
    exclusions = project.service<GitIgnoredDirectoryExclusions>()
  }

  fun `test current Git-ignored directories are excluded without hiding tracked or untracked content`() = runBlocking {
    createGitignoreAndWait("""
                            /.DerivedData/
                            /DerivedData/
                            /.github/
                            /gen/
                           """.trimIndent())

    exclusions.initialize()
    assertEquals(setOf(hiddenIgnoredDir.url, visibleIgnoredDir.url), exclusions.excludedUrls().toSet())
    fireRootsChange()

    assertExcluded(hiddenIgnoredDir, visibleIgnoredDir)
    assertContainsElements(ProjectManagerEx.getInstanceEx().getAllExcludedUrls(project), hiddenIgnoredDir.url, visibleIgnoredDir.url)
    assertNotExcluded(trackedHiddenDir, trackedDotFile, ordinaryUntrackedDir, ignoredSourceDir)
    assertEmpty(moduleExcludes())
    assertFalse(VcsConfiguration.getInstance(project).MARK_IGNORED_AS_EXCLUDED)

    createGitignoreAndWait("")
    assertEmpty(exclusions.excludedUrls().toList())
    flushRootsChange()

    assertNotExcluded(hiddenIgnoredDir, visibleIgnoredDir, trackedHiddenDir, trackedDotFile, ordinaryUntrackedDir, ignoredSourceDir)
    assertDoesntContain(ProjectManagerEx.getInstanceEx().getAllExcludedUrls(project), hiddenIgnoredDir.url, visibleIgnoredDir.url)
    assertEmpty(moduleExcludes())
    assertFalse(VcsConfiguration.getInstance(project).MARK_IGNORED_AS_EXCLUDED)
  }

  fun `test root changes preserve exclusions when ignored highlighting is disabled`() = runBlocking {
    val settingId = "vcs.process.ignored"
    val previousValue = AdvancedSettings.getBoolean(settingId)
    try {
      AdvancedSettings.setBoolean(settingId, false)
      createGitignoreAndWait("/.DerivedData/")
      assertFalse(repo.untrackedFilesHolder.hasAuthoritativeRefreshResult)

      exclusions.initialize()
      assertEquals(setOf(hiddenIgnoredDir.url), exclusions.excludedUrls().toSet())

      fireRootsChange()
      assertEquals(setOf(hiddenIgnoredDir.url), exclusions.excludedUrls().toSet())
      assertExcluded(hiddenIgnoredDir)
    }
    finally {
      AdvancedSettings.setBoolean(settingId, previousValue)
    }
  }

  fun `test ignored ancestor of a nested Git root remains indexed`() = runBlocking {
    val nestedRepository = repo.createSubRepository("vendor/library", addToGitIgnore = false)
    createGitignoreAndWait("/vendor/")

    exclusions.initialize()
    assertNotExcluded(nestedRepository.root)
    assertFalse(nestedRepository.root.parent.url in exclusions.excludedUrls())
  }

  fun `test removing a nested Git root restores its ignored ancestor when Git refresh fails`() = runBlocking {
    val ignoredAncestor = invokeAndWaitIfNeeded {
      runWriteAction {
        repo.root.findOrCreateDir("vendor").apply { findOrCreateDir("library").createFile("nested.txt") }
      }
    }
    val settingId = "vcs.process.ignored"
    val previousSetting = AdvancedSettings.getBoolean(settingId)
    val previousGitPath = appSettings.savedPathToGit
    try {
      AdvancedSettings.setBoolean(settingId, false)
      createGitignoreAndWait("/vendor/")
      exclusions.initialize()
      assertExcluded(ignoredAncestor)

      val nestedRoot = Path.of(ignoredAncestor.path, "library")
      initRepo(project, nestedRoot, makeInitialCommit = true)

      appSettings.setPathToGit("/definitely/missing/rebased-test-git")
      val vcsManager = ProjectLevelVcsManager.getInstance(project)
      vcsManager.setDirectoryMapping(nestedRoot.toString(), GitVcs.NAME)
      project.service<VcsRepositoryManager>().ensureUpToDate(force = true)
      waitUntil("Ignored ancestor still covered its nested Git root", timeout = 5.seconds) {
        ignoredAncestor.url !in exclusions.excludedUrls()
      }
      assertNotExcluded(ignoredAncestor)

      vcsManager.directoryMappings = vcsManager.directoryMappings.filterNot { it.directory == nestedRoot.toString() }
      project.service<VcsRepositoryManager>().ensureUpToDate(force = true)
      waitUntil("Ignored ancestor was not restored after removing its nested Git root", timeout = 5.seconds) {
        ignoredAncestor.url in exclusions.excludedUrls()
      }
      assertExcluded(ignoredAncestor)
    }
    finally {
      appSettings.setPathToGit(previousGitPath)
      AdvancedSettings.setBoolean(settingId, previousSetting)
    }
  }

  fun `test failed holder refresh preserves the last authoritative exclusions`() = runBlocking {
    createGitignoreAndWait("/.DerivedData/")
    exclusions.initialize()
    assertEquals(setOf(hiddenIgnoredDir.url), exclusions.excludedUrls().toSet())

    val previousGitPath = appSettings.savedPathToGit
    try {
      appSettings.setPathToGit("/definitely/missing/rebased-test-git")
      repo.untrackedFilesHolder.invalidate()
      repo.untrackedFilesHolder.awaitNotBusy()

      assertFalse(repo.untrackedFilesHolder.hasAuthoritativeRefreshResult)
      assertEquals(setOf(hiddenIgnoredDir.url), exclusions.excludedUrls().toSet())
    }
    finally {
      appSettings.setPathToGit(previousGitPath)
    }
  }

  private suspend fun createGitignoreAndWait(gitignoreContent: String) {
    val currentGitIgnore = gitIgnoreFile
    if (currentGitIgnore == null) {
      val gitIgnore = file(GITIGNORE).create(gitignoreContent)
      gitIgnoreFile = requireNotNull(VfsUtil.findFileByIoFile(gitIgnore.file, true))
    }
    else {
      invokeAndWaitIfNeeded {
        runWriteAction {
          currentGitIgnore.setBinaryContent(gitignoreContent.toByteArray())
        }
      }
    }
    repo.untrackedFilesHolder.invalidate()
    repo.untrackedFilesHolder.awaitNotBusy()
  }

  private fun fireRootsChange() {
    invokeAndWaitIfNeeded {
      runWriteAction {
        ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
          {},
          RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED,
        )
      }
    }
  }

  private fun flushRootsChange() {
    invokeAndWaitIfNeeded {}
  }

  private fun assertExcluded(vararg files: VirtualFile) {
    invokeAndWaitIfNeeded {
      files.forEach { assertTrue("Expected ${it.path} to be excluded", ProjectFileIndex.getInstance(project).isExcluded(it)) }
    }
  }

  private fun assertNotExcluded(vararg files: VirtualFile) {
    invokeAndWaitIfNeeded {
      files.forEach { assertFalse("Expected ${it.path} to remain included", ProjectFileIndex.getInstance(project).isExcluded(it)) }
    }
  }

  private fun moduleExcludes(): List<VirtualFile> = invokeAndWaitIfNeeded {
    ModuleRootManager.getInstance(module).excludeRoots.toList()
  }
}
