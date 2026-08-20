// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.Executor.cd
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.impl.ModuleVcsDetector
import com.intellij.openapi.vcs.impl.VcsInitObject
import com.intellij.openapi.vcs.impl.VcsStartupActivity
import com.intellij.openapi.startup.InitProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import git4idea.test.git
import git4idea.test.initRepo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

abstract class GitIgnoredDirectoryExclusionsInitializationTestBase : HeavyPlatformTestCase() {
  protected abstract val persistVcsMapping: Boolean

  private lateinit var projectRoot: VirtualFile
  private lateinit var modifiedFile: VirtualFile
  private lateinit var trackedDotFile: VirtualFile
  private lateinit var untrackedFile: VirtualFile
  private lateinit var ignoredDirectory: VirtualFile
  private var projectWasOpenDuringVcsStartup: Boolean? = null
  private var exclusionsBeforeProjectOpen: Set<String>? = null
  private var ignoredDirectoryExcludedDuringPreInit: Boolean? = null
  private var nestedMappedRootExcludedDuringPreInit: Boolean? = null
  @Volatile
  private var provisionalExclusionWasCleared = false

  override fun setUpProject() {
    projectRoot = tempDir.createVirtualDir("git-ignored-directory-initialization")
    val projectPath = projectRoot.toNioPath()
    initRepo(null, projectPath, makeInitialCommit = false)

    val gitignore = if (persistVcsMapping) "/.DerivedData/\n/vendor/\n" else "/.DerivedData/\n"
    Files.writeString(projectPath.resolve(".gitignore"), gitignore)
    Files.writeString(projectPath.resolve("tracked.txt"), "committed\n")
    Files.writeString(projectPath.resolve(".env.example"), "tracked dotfile\n")
    cd(projectPath)
    git(null, "add .gitignore tracked.txt .env.example")
    git(null, "commit -m initial")

    Files.writeString(projectPath.resolve("tracked.txt"), "modified\n")
    Files.writeString(projectPath.resolve("untracked.txt"), "untracked\n")
    Files.createDirectories(projectPath.resolve(".DerivedData"))
    Files.writeString(projectPath.resolve(".DerivedData/cache.o"), "ignored\n")
    Files.createDirectories(projectPath.resolve("vendor/library"))
    Files.writeString(projectPath.resolve("vendor/library/nested.txt"), "nested VCS content\n")
    Files.createDirectories(projectPath.resolve(".idea"))
    if (persistVcsMapping) {
      Files.writeString(projectPath.resolve(".idea/vcs.xml"), """
        <project version="4">
          <component name="VcsDirectoryMappings">
            <mapping directory="${'$'}PROJECT_DIR${'$'}" vcs="Git" />
            <mapping directory="${'$'}PROJECT_DIR${'$'}/vendor/library" vcs="TestVCS" />
          </component>
        </project>
      """.trimIndent())
    }
    projectRoot.refresh(false, true)

    val snapshotObserver = object : InitProjectActivity {
      override suspend fun run(project: Project) {
        assertFalse("Project must still be closed before workspace indexing", project.isOpen)
        val exclusions = project.getService(GitIgnoredDirectoryExclusions::class.java)
        exclusionsBeforeProjectOpen = exclusions.excludedUrls().toSet()

        val ignoredDirectoryUrl = requireNotNull(projectRoot.findChild(".DerivedData")).url
        project.messageBus.connect(testRootDisposable).subscribe(
          VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
          VcsRepositoryMappingListener {
            if (ignoredDirectoryUrl !in exclusions.excludedUrls()) {
              provisionalExclusionWasCleared = true
            }
          },
        )
      }
    }
    ExtensionPointName<InitProjectActivity>("com.intellij.projectPreInit").point.registerExtension(
      snapshotObserver,
      LoadingOrder.readOrder("after gitIgnoredDirectoryExclusionsInitializer, before workspaceFileIndexInitializer"),
      testRootDisposable,
    )

    val indexObserver = object : InitProjectActivity {
      override suspend fun run(project: Project) {
        assertFalse("Project must still be closed after workspace index initialization", project.isOpen)
        val ignoredDirectory = requireNotNull(projectRoot.findChild(".DerivedData"))
        ignoredDirectoryExcludedDuringPreInit = runReadActionBlocking {
          ProjectFileIndex.getInstance(project).isExcluded(ignoredDirectory)
        }
        if (persistVcsMapping) {
          val nestedMappedRoot = requireNotNull(projectRoot.findFileByRelativePath("vendor/library"))
          nestedMappedRootExcludedDuringPreInit = runReadActionBlocking {
            ProjectFileIndex.getInstance(project).isExcluded(nestedMappedRoot)
          }
        }
      }
    }
    ExtensionPointName<InitProjectActivity>("com.intellij.projectPreInit").point.registerExtension(
      indexObserver,
      LoadingOrder.after("workspaceFileIndexInitializer"),
      testRootDisposable,
    )

    if (!persistVcsMapping) {
      // Unit-test mode normally omits this activity. Register the production activity so this variant proves post-open mapping detection.
      ExtensionPointName<VcsStartupActivity>("com.intellij.vcsStartupActivity").point.registerExtension(
        ModuleVcsDetector.ModuleVcsDetectorStartUpActivity(runInUnitTests = true),
        testRootDisposable,
      )
    }

    val observer = object : VcsStartupActivity {
      override val order: Int = VcsInitObject.DIRTY_SCOPE_MANAGER.order + 1

      override suspend fun execute(project: Project) {
        projectWasOpenDuringVcsStartup = project.isOpen
      }
    }
    ExtensionPointName<VcsStartupActivity>("com.intellij.vcsStartupActivity").point
      .registerExtension(observer, testRootDisposable)

    myProject = PlatformTestUtil.loadAndOpenProject(projectPath, testRootDisposable)

    modifiedFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPath.resolve("tracked.txt")))
    trackedDotFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPath.resolve(".env.example")))
    untrackedFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPath.resolve("untracked.txt")))
    ignoredDirectory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPath.resolve(".DerivedData")))
  }

  fun testIgnoredDirectoryInitializationPreservesInitialVcsChangeCollection() = runBlocking {
    assertEquals(setOf(ignoredDirectory.url), exclusionsBeforeProjectOpen)
    assertEquals(true, ignoredDirectoryExcludedDuringPreInit)
    if (persistVcsMapping) {
      assertEquals(false, nestedMappedRootExcludedDuringPreInit)
    }

    val vcsManager = ProjectLevelVcsManager.getInstance(project)
    vcsManager.awaitInitialization()
    if (!persistVcsMapping) {
      assertEquals(GitVcs.NAME, vcsManager.getVcsFor(projectRoot)?.name)
    }
    assertEquals(true, projectWasOpenDuringVcsStartup)
    assertFalse("Provisional ignored-directory exclusion was cleared before repository convergence", provisionalExclusionWasCleared)

    project.getService(VcsRepositoryManager::class.java).ensureUpToDate(force = true)
    GitRepositoryManager.getInstance(project).repositories.single().untrackedFilesHolder.run {
      invalidate()
      awaitNotBusy()
    }
    ChangeListManagerImpl.getInstanceImpl(project).ensureUpToDate()

    val statusManager = FileStatusManager.getInstance(project)
    assertEquals(FileStatus.MODIFIED, statusManager.getStatus(modifiedFile))
    assertEquals(FileStatus.UNKNOWN, statusManager.getStatus(untrackedFile))
    assertEquals(FileStatus.NOT_CHANGED, statusManager.getStatus(trackedDotFile))
  }
}

class GitIgnoredDirectoryExclusionsPersistedMappingInitializationTest : GitIgnoredDirectoryExclusionsInitializationTestBase() {
  override val persistVcsMapping: Boolean = true
}

class GitIgnoredDirectoryExclusionsAutomaticDetectionInitializationTest : GitIgnoredDirectoryExclusionsInitializationTestBase() {
  override val persistVcsMapping: Boolean = false
}
