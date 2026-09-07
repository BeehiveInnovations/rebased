// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.ide.GeneralSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectNewWindowDoNotAskOption
import com.intellij.ide.impl.toOpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.project.VetoableProjectManagerListener
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.TemporaryDirectoryExtension
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@TestApplication
internal class GitWorkingTreeProjectOpeningTest {
  companion object {
    private val sourceProject = projectFixture()
  }

  @JvmField
  @RegisterExtension
  val tempDir = TemporaryDirectoryExtension()

  @TestDisposable
  lateinit var disposable: Disposable

  @ParameterizedTest
  @ValueSource(ints = [GeneralSettings.OPEN_PROJECT_SAME_WINDOW, GeneralSettings.OPEN_PROJECT_NEW_WINDOW, GeneralSettings.OPEN_PROJECT_ASK])
  fun `opening respects the saved window preference`(mode: Int) = runBlocking(Dispatchers.Default) {
    withWindowPreference(mode) {
      val path = tempDir.newPath("worktree").createDirectories()
      var openCount = 0
      registerProjectOpener(listOf(path)) { openedPath, options ->
        assertEquals(mode, GeneralSettings.getInstance().confirmOpenNewProject)
        assertEquals(path, openedPath)
        assertWindowOptions(options)
        openCount++
        sourceProject.get()
      }

      val openedProject = GitWorkingTreesService.getInstance(sourceProject.get()).openWorkingTreeProject(path)

      assertSame(sourceProject.get(), openedProject)
      assertEquals(1, openCount)
      assertEquals(mode, GeneralSettings.getInstance().confirmOpenNewProject)
    }
  }

  @ParameterizedTest
  @ValueSource(ints = [Messages.YES, Messages.NO])
  fun `remembered choice survives opening and applies to another worktree`(choice: Int) = runBlocking(Dispatchers.Default) {
    withWindowPreference(GeneralSettings.OPEN_PROJECT_ASK) {
      val paths = listOf(tempDir.newPath("first-worktree").createDirectories(), tempDir.newPath("second-worktree").createDirectories())
      val rememberedMode = if (choice == Messages.YES) GeneralSettings.OPEN_PROJECT_SAME_WINDOW else GeneralSettings.OPEN_PROJECT_NEW_WINDOW
      val openedPaths = mutableListOf<Path>()
      registerProjectOpener(paths) { path, _ ->
        val expectedMode = if (openedPaths.isEmpty()) GeneralSettings.OPEN_PROJECT_ASK else rememberedMode
        assertEquals(expectedMode, GeneralSettings.getInstance().confirmOpenNewProject)
        // Exercise the dialog's actual persistence callback without creating a window.
        if (openedPaths.isEmpty()) {
          ProjectNewWindowDoNotAskOption().setToBeShown(false, choice)
        }
        openedPaths.add(path)
        sourceProject.get()
      }

      val service = GitWorkingTreesService.getInstance(sourceProject.get())
      for (path in paths) {
        assertSame(sourceProject.get(), service.openWorkingTreeProject(path))
        assertEquals(rememberedMode, GeneralSettings.getInstance().confirmOpenNewProject)
      }
      assertEquals(paths, openedPaths)
    }
  }

  @Test
  fun `cancelled opening leaves the window preference unchanged`() = runBlocking(Dispatchers.Default) {
    withWindowPreference(GeneralSettings.OPEN_PROJECT_ASK) {
      val path = tempDir.newPath("cancelled-worktree").createDirectories()
      var openCount = 0
      registerProjectOpener(listOf(path)) { openedPath, options ->
        assertEquals(path, openedPath)
        assertWindowOptions(options)
        openCount++
        null
      }

      assertNull(GitWorkingTreesService.getInstance(sourceProject.get()).openWorkingTreeProject(path))
      assertEquals(1, openCount)
      assertEquals(GeneralSettings.OPEN_PROJECT_ASK, GeneralSettings.getInstance().confirmOpenNewProject)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `directory import keeps the initiating project`(asynchronous: Boolean) = runBlocking {
    withWindowPreference(GeneralSettings.OPEN_PROJECT_SAME_WINDOW) {
      val path = tempDir.newPath("unconfigured-worktree").createDirectories()
      val project = sourceProject.get()
      val service = GitWorkingTreesService.getInstance(project)
      val receivedOptions = mutableListOf<OpenProjectTask>()
      val manager = CapturingProjectManager(ProjectManagerEx.getInstanceEx()) { openedPath, options ->
        assertEquals(path, openedPath)
        receivedOptions.add(options)
        null
      }

      // Run the real directory importer and intercept only the final window-opening operation.
      val managerDisposable = Disposer.newDisposable()
      try {
        ApplicationManager.getApplication().replaceService(ProjectManager::class.java, manager, managerDisposable)
        ExtensionTestUtil.maskExtensions(
          ProjectOpenProcessor.EXTENSION_POINT_NAME, listOf(PlatformProjectOpenProcessor()), managerDisposable, fireEvents = false,
        )
        val openedProject = if (asynchronous) {
          service.openWorkingTreeProject(path)
        }
        else {
          PlatformProjectOpenProcessor.doOpenProject(path, OpenProjectTask(projectToClose = project))
        }
        assertNull(openedProject)
      }
      finally {
        Disposer.dispose(managerDisposable)
      }

      val options = receivedOptions.single()
      assertWindowOptions(options)
      assertEquals(path, options.projectRootDir)
      assertTrue(options.isNewProject)
      assertTrue(options.runConfigurators)
      assertTrue(options.useDefaultProjectAsTemplate)
      assertEquals(GeneralSettings.OPEN_PROJECT_SAME_WINDOW, GeneralSettings.getInstance().confirmOpenNewProject)
    }
  }

  private fun assertWindowOptions(options: OpenProjectTask) {
    assertSame(sourceProject.get(), options.projectToClose)
    assertFalse(options.forceOpenInNewFrame)
    assertFalse(options.forceReuseFrame)
  }

  /** Intercepts the real import boundary before project loading or frame creation. */
  private fun registerProjectOpener(paths: List<Path>, open: (Path, OpenProjectTask) -> Project?) {
    val processor = object : ProjectOpenProcessor() {
      override val name: String = "Worktree test"
      override val isStrongProjectInfoHolder: Boolean = true

      override fun canOpenProject(file: VirtualFile): Boolean = file.toNioPath() in paths

      override suspend fun openProjectAsync(virtualFile: VirtualFile, projectOpenOptions: ProjectOpenOptions): Project? {
        return open(virtualFile.toNioPath(), projectOpenOptions.toOpenProjectTask())
      }
    }
    ExtensionTestUtil.maskExtensions(ProjectOpenProcessor.EXTENSION_POINT_NAME, listOf(processor), disposable, fireEvents = false)
  }

  /** Restores the application preference even when an opening assertion fails. */
  private suspend fun withWindowPreference(mode: Int, action: suspend () -> Unit) {
    val settings = GeneralSettings.getInstance()
    val savedMode = settings.confirmOpenNewProject
    settings.confirmOpenNewProject = mode
    try {
      action()
    }
    finally {
      settings.confirmOpenNewProject = savedMode
    }
  }

  /** Records window-opening requests while preserving the project services used by import and VFS refresh. */
  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "NonExtendableApiUsage")
  private class CapturingProjectManager(
    private val delegate: ProjectManagerEx,
    private val capture: (Path, OpenProjectTask) -> Project?,
  ) : ProjectManagerEx() {
    override fun openProject(projectStoreBaseDir: Path, options: OpenProjectTask): Project? = capture(projectStoreBaseDir, options)

    override suspend fun openProjectAsync(projectIdentityFile: Path, options: OpenProjectTask): Project? =
      capture(projectIdentityFile, options)

    override fun addProjectManagerListener(listener: ProjectManagerListener) = delegate.addProjectManagerListener(listener)
    override fun addProjectManagerListener(listener: VetoableProjectManagerListener) = delegate.addProjectManagerListener(listener)
    override fun removeProjectManagerListener(listener: ProjectManagerListener) = delegate.removeProjectManagerListener(listener)
    override fun removeProjectManagerListener(listener: VetoableProjectManagerListener) = delegate.removeProjectManagerListener(listener)
    override fun addProjectManagerListener(project: Project, listener: ProjectManagerListener) =
      delegate.addProjectManagerListener(project, listener)
    override fun removeProjectManagerListener(project: Project, listener: ProjectManagerListener) =
      delegate.removeProjectManagerListener(project, listener)

    override fun getOpenProjects(): Array<Project> = delegate.openProjects
    override fun getDefaultProject(): Project = delegate.defaultProject
    override fun loadAndOpenProject(filePath: String): Project? = delegate.loadAndOpenProject(filePath)
    override fun closeAndDispose(project: Project): Boolean = delegate.closeAndDispose(project)
    override fun closeProject(project: Project): Boolean = delegate.closeProject(project)
    override fun reloadProject(project: Project) = delegate.reloadProject(project)
    override fun createProject(name: String?, path: String): Project = delegate.createProject(name, path)
    override fun newProject(file: Path, options: OpenProjectTask): Project? = delegate.newProject(file, options)
    override suspend fun newProjectAsync(file: Path, options: OpenProjectTask): Project = delegate.newProjectAsync(file, options)
    override fun loadProject(path: Path): Project = delegate.loadProject(path)

    override val isDefaultProjectInitialized: Boolean
      get() = delegate.isDefaultProjectInitialized

    override fun isProjectOpened(project: Project): Boolean = delegate.isProjectOpened(project)
    override fun canClose(project: Project): Boolean = delegate.canClose(project)
    override fun forceCloseProject(project: Project, save: Boolean): Boolean = delegate.forceCloseProject(project, save)
    override suspend fun forceCloseProjectAsync(project: Project, save: Boolean): Boolean = delegate.forceCloseProjectAsync(project, save)
    override fun closeAndDisposeAllProjects(checkCanClose: Boolean): Boolean = delegate.closeAndDisposeAllProjects(checkCanClose)
    override fun getAllExcludedUrls(project: Project?): List<String> = delegate.getAllExcludedUrls(project)
    override fun findOpenProjectByHash(locationHash: String?): Project? = delegate.findOpenProjectByHash(locationHash)
  }
}
