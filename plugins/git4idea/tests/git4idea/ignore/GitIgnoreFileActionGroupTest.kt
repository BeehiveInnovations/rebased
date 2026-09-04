// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.changes.IgnoredBeanFactory
import com.intellij.openapi.vcs.changes.ignore.actions.CreateNewIgnoreFileAction
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileAction
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileSelectionEntry
import com.intellij.openapi.vcs.changes.ignore.actions.writeIgnoreFileEntries
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitVcs
import git4idea.ignore.actions.AddToGitExcludeAction
import git4idea.ignore.actions.GitIgnoreFileActionGroup
import git4idea.ignore.actions.GitIgnoreFilesOperation
import git4idea.index.GitFileStatus
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.GitStageDataKeys
import git4idea.index.ui.NodeKind
import git4idea.test.GitSingleRepoTest
import git4idea.test.addCommit
import git4idea.test.cd
import git4idea.test.createSubRepository
import git4idea.test.git
import kotlinx.coroutines.runBlocking

class GitIgnoreFileActionGroupTest : GitSingleRepoTest() {

  fun `test tracked file can be added to ignore`() {
    val trackedFile = file("tracked.txt").create("tracked").add()
    addCommit("Track file")
    updateChangeListManager()

    val action = GitIgnoreFileActionGroup()
    val event = createActionEvent(action, getVirtualFile(trackedFile.file))
    runReadAction { action.update(event) }

    assertTrue("The ignore action should be offered for a tracked file", event.presentation.isEnabledAndVisible)
  }

  fun `test tracked directory can be added to ignore`() {
    file("cache/tracked.db").create("tracked").add()
    addCommit("Track directory")
    refresh(repo.root)
    updateChangeListManager()

    val action = GitIgnoreFileActionGroup()
    val event = createActionEvent(action, repo.root.findFileByRelativePath("cache")!!)
    runReadAction { action.update(event) }

    assertTrue("The ignore action should be offered for a tracked directory", event.presentation.isEnabledAndVisible)
  }

  fun `test tracked file is untracked and ignored after confirmation`() {
    val ignoreFile = file(".gitignore").create("").add()
    val trackedFile = file("tracked.txt").create("original").add()
    addCommit("Track files")
    trackedFile.write("staged").add()
    trackedFile.write("modified")
    refresh(repo.root)

    val trackedVirtualFile = getVirtualFile(trackedFile.file)
    val ignoreVirtualFile = getVirtualFile(ignoreFile.file)
    var confirmationCount = 0
    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(trackedVirtualFile),
        null,
        createIgnoreWriter(trackedVirtualFile, ignoreVirtualFile),
      ) { hasTrackedFiles ->
        confirmationCount++
        assertTrue("The action should identify the tracked file before asking", hasTrackedFiles)
        true
      }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertTrue("The ignore operation should complete", result)
    assertEquals("The action should ask once before changing the index", 1, confirmationCount)
    trackedFile.assertExists()
    assertEquals("modified", trackedFile.read())
    assertTrue(ignoreFile.read().lines().contains("/tracked.txt"))
    assertEquals("", git("ls-files -- tracked.txt").trim())
    assertTrue(git("status --porcelain -- tracked.txt").startsWith("D  tracked.txt"))
  }

  fun `test conflicted file is detected and removed from index`() {
    val conflictedFile = file("conflict.txt").create("base").add()
    addCommit("Track conflict file")
    git("branch feature")
    conflictedFile.write("master").add()
    addCommit("Change file on master")
    git("checkout feature")
    conflictedFile.write("feature").add()
    addCommit("Change file on feature")
    git("checkout master")
    git("merge feature", true)
    refresh(repo.root)

    val conflictedVirtualFile = getVirtualFile(conflictedFile.file)
    var confirmationCount = 0
    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(conflictedVirtualFile),
        null,
        Runnable {},
      ) { hasTrackedFiles ->
        confirmationCount++
        assertTrue("Conflict stages must be treated as tracked", hasTrackedFiles)
        true
      }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertTrue("The ignore preparation should complete", result)
    assertEquals(1, confirmationCount)
    conflictedFile.assertExists()
    assertEquals("", git("ls-files --stage -- conflict.txt").trim())
  }

  fun `test cancelling tracked file confirmation changes nothing`() {
    val ignoreFile = file(".gitignore").create("").add()
    val trackedFile = file("tracked.txt").create("original").add()
    addCommit("Track files")
    trackedFile.write("modified")
    refresh(repo.root)

    val trackedVirtualFile = getVirtualFile(trackedFile.file)
    val ignoreVirtualFile = getVirtualFile(ignoreFile.file)
    var writeCalled = false
    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(trackedVirtualFile),
        null,
        Runnable {
          writeCalled = true
          createIgnoreWriter(trackedVirtualFile, ignoreVirtualFile).run()
        },
      ) { false }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertFalse("A cancelled ignore operation should not report success", result)
    assertFalse("A cancelled ignore operation must not update the ignore file", writeCalled)
    trackedFile.assertExists()
    assertEquals("modified", trackedFile.read())
    assertEquals("", ignoreFile.read())
    assertEquals("tracked.txt", git("ls-files -- tracked.txt").trim())
  }

  fun `test untracked directory is ignored without confirmation`() {
    val ignoreFile = file(".gitignore").create("").add()
    addCommit("Add ignore file")
    VcsConfiguration.StandardConfirmation.ADD.doNothing()
    val untrackedDirectory = repo.root.createDir("cache")
    untrackedDirectory.createFile("data.db", "local")
    updateUntrackedFiles()

    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(untrackedDirectory),
        null,
        createIgnoreWriter(untrackedDirectory, getVirtualFile(ignoreFile.file)),
      ) {
        fail("Untracked paths should not require confirmation")
        false
      }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertTrue("The ignore operation should complete", result)
    assertTrue(ignoreFile.read().lines().contains("/cache/"))
    assertTrue(untrackedDirectory.findChild("data.db")?.isValid == true)
  }

  fun `test tracked directory is removed from index recursively`() {
    val ignoreFile = file(".gitignore").create("").add()
    val trackedFile = file("cache/tracked.db").create("tracked").add()
    addCommit("Track cache")
    refresh(repo.root)
    val cacheDirectory = repo.root.findFileByRelativePath("cache")!!
    VcsConfiguration.StandardConfirmation.ADD.doNothing()
    cacheDirectory.createFile("local.db", "local")
    updateUntrackedFiles()

    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(cacheDirectory),
        null,
        createIgnoreWriter(cacheDirectory, getVirtualFile(ignoreFile.file)),
      ) { true }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertTrue("The ignore operation should complete", result)
    trackedFile.assertExists()
    assertTrue(cacheDirectory.findChild("local.db")?.isValid == true)
    assertEquals("", git("ls-files -- cache").trim())
    assertTrue(ignoreFile.read().lines().contains("/cache/"))
  }

  fun `test mixed tracked and untracked selection is ignored together`() {
    val ignoreFile = file(".gitignore").create("").add()
    val trackedFile = file("tracked.txt").create("tracked").add()
    addCommit("Track files")
    VcsConfiguration.StandardConfirmation.ADD.doNothing()
    val untrackedFile = repo.root.createFile("local.db", "local")
    updateUntrackedFiles()

    val trackedVirtualFile = getVirtualFile(trackedFile.file)
    val ignoreVirtualFile = getVirtualFile(ignoreFile.file)
    val selectedFiles = listOf(trackedVirtualFile, untrackedFile)
    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(selectedFiles),
        null,
        Runnable {
          val ignored = selectedFiles.map { IgnoredBeanFactory.ignoreFile(it, project) }
          writeIgnoreFileEntries(project, ignoreVirtualFile, ignored)
        },
      ) { hasTrackedFiles ->
        assertTrue("The mixed selection should require confirmation", hasTrackedFiles)
        true
      }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertTrue("The ignore operation should complete", result)
    trackedFile.assertExists()
    assertTrue(untrackedFile.isValid)
    assertEquals("", git("ls-files -- tracked.txt local.db").trim())
    assertTrue(ignoreFile.read().lines().containsAll(listOf("/tracked.txt", "/local.db")))
  }

  fun `test ignore writer failure is reported after untracking`() {
    val trackedFile = file("tracked.txt").create("tracked").add()
    addCommit("Track file")
    val trackedVirtualFile = getVirtualFile(trackedFile.file)

    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(trackedVirtualFile),
        null,
        Runnable { error("Test ignore writer failure") },
      ) { true }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    assertFalse("A failed ignore writer must not report success", result)
    trackedFile.assertExists()
    assertEquals("", git("ls-files -- tracked.txt").trim())
  }

  fun `test literal pathspecs with pathspec from file`() {
    doTestLiteralPathspecs(usePathspecFromFile = true)
  }

  fun `test literal pathspecs with chunked arguments`() {
    doTestLiteralPathspecs(usePathspecFromFile = false)
  }

  fun `test untracked literal path does not match tracked pathspec`() {
    val trackedFile = file("file1.txt").create("tracked").add()
    addCommit("Track file")
    VcsConfiguration.StandardConfirmation.ADD.doNothing()
    val untrackedFile = repo.root.createFile("file[1].txt", "local")
    updateUntrackedFiles()
    var writeCalled = false

    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(untrackedFile),
        null,
        Runnable { writeCalled = true },
      ) {
        fail("A literal untracked path must not match another tracked file")
        false
      }
    }

    assertTrue("The untracked ignore operation should complete", result)
    assertTrue("The writer should run without an untrack confirmation", writeCalled)
    val indexedFiles = git("ls-files").lineSequence().filter(String::isNotEmpty).toSet()
    assertTrue(indexedFiles.contains(trackedFile.file.name))
    assertFalse(indexedFiles.contains(untrackedFile.name))
  }

  fun `test existing ignore file must cover every selected path`() {
    file("dirA/.gitignore").create("").add()
    val firstFile = file("dirA/a.txt").create("first").add()
    val secondFile = file("dirB/b.txt").create("second").add()
    addCommit("Track files")
    refresh(repo.root)
    updateChangeListManager()

    val children = getIgnoreActions(getVirtualFile(firstFile.file), getVirtualFile(secondFile.file))

    assertFalse("A partially covering ignore file must not be offered", children.any { it is IgnoreFileAction })
    assertTrue("A root ignore file can still be created", children.any { it is CreateNewIgnoreFileAction })
    assertTrue("The repository exclude action should remain available", children.any { it is AddToGitExcludeAction })
  }

  fun `test outer ignore file is not offered for nested repository`() {
    val nestedRepository = repo.createSubRepository("nested", addToGitIgnore = false)
    cd(repo)
    file(".gitignore").create("").add()
    addCommit("Track outer ignore file")
    val nestedFile = nestedRepository.root.createFile("nested.txt", "nested")
    nestedRepository.addCommit("Track nested file")
    refresh(repo.root)
    updateChangeListManager()

    val children = getIgnoreActions(nestedFile)

    assertFalse("An outer repository ignore file must not be offered", children.any { it is IgnoreFileAction })
    assertTrue("The nested repository can create its own ignore file", children.any { it is CreateNewIgnoreFileAction })
    assertTrue("The nested repository exclude action should remain available", children.any { it is AddToGitExcludeAction })
  }

  fun `test staged gitlink keeps outer repository ownership`() {
    val nestedRepository = repo.createSubRepository("nested", addToGitIgnore = false)
    cd(repo)
    git("add -- nested")
    refresh(repo.root)
    updateChangeListManager()

    val gitlinkEntry = git("ls-files --stage -- nested").trim()
    assertTrue("The fixture must stage a gitlink in the outer repository", gitlinkEntry.startsWith("160000 "))
    assertEquals("A\tnested", git("diff --cached --name-status -- nested").trim())

    val statusNode = GitFileStatusNode(
      repo.root,
      GitFileStatus('A', ' ', VcsUtil.getFilePath(nestedRepository.root)),
      NodeKind.STAGED,
    )
    val action = GitIgnoreFileActionGroup()
    val children = runReadAction {
      action.getChildren(createActionEvent(action, listOf(nestedRepository.root), listOf(statusNode))).toList()
    }
    assertTrue("The staging row should plan an outer .gitignore", children.any { it is CreateNewIgnoreFileAction })

    val nestedIndexBefore = nestedRepository.git("ls-files --stage")
    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        listOf(IgnoreFileSelectionEntry(nestedRepository.root, VcsRoot(GitVcs.getInstance(project), repo.root))),
        null,
        Runnable {},
      ) { hasTrackedFiles ->
        assertTrue("The outer gitlink should be detected as tracked", hasTrackedFiles)
        true
      }
    }

    refresh(repo.root)
    assertTrue("The ignore preparation should complete", result)
    assertEquals("", git("ls-files --stage -- nested").trim())
    assertEquals("The nested repository index must stay unchanged", nestedIndexBefore, nestedRepository.git("ls-files --stage"))
    assertTrue("The nested worktree must stay intact", nestedRepository.root.findChild("initial.txt")?.isValid == true)
  }

  fun `test multi-root selection keeps only repository exclude action`() {
    val outerFile = file("outer.txt").create("outer").add()
    addCommit("Track outer file")
    val nestedRepository = repo.createSubRepository("nested")
    val nestedFile = nestedRepository.root.createFile("nested.txt", "nested")
    nestedRepository.addCommit("Track nested file")
    refresh(repo.root)
    updateChangeListManager()

    val children = getIgnoreActions(getVirtualFile(outerFile.file), nestedFile)

    assertFalse(children.any { it is IgnoreFileAction })
    assertFalse(children.any { it is CreateNewIgnoreFileAction })
    assertEquals(1, children.count { it is AddToGitExcludeAction })
  }

  private fun doTestLiteralPathspecs(usePathspecFromFile: Boolean) {
    Registry.get("git.use.pathspec.from.file").setValue(usePathspecFromFile, testRootDisposable)
    val selectedFile = file("file[1].txt").create("selected").add()
    val unrelatedFile = file("file1.txt").create("unrelated").add()
    val selectedDirectoryFile = file("dir[1]/selected.db").create("selected").add()
    val unrelatedDirectoryFile = file("dir1/unrelated.db").create("unrelated").add()
    addCommit("Track literal pathspec fixtures")
    refresh(repo.root)
    val selectedDirectory = repo.root.findFileByRelativePath("dir[1]")!!

    val result = runBlocking {
      GitIgnoreFilesOperation.execute(
        project,
        selectionEntries(getVirtualFile(selectedFile.file), selectedDirectory),
        null,
        Runnable {},
      ) { true }
    }

    refresh(repo.root)
    updateChangeListManager()
    updateUntrackedFiles()
    val indexedFiles = git("ls-files").lineSequence().filter(String::isNotEmpty).toSet()
    assertTrue("The literal pathspec operation should complete", result)
    selectedFile.assertExists()
    selectedDirectoryFile.assertExists()
    assertFalse(indexedFiles.contains(selectedFile.file.name))
    assertFalse(indexedFiles.contains("dir[1]/selected.db"))
    assertTrue(indexedFiles.contains(unrelatedFile.file.name))
    assertTrue(indexedFiles.contains("dir1/unrelated.db"))
    unrelatedDirectoryFile.assertExists()
  }

  private fun getIgnoreActions(vararg selectedFiles: VirtualFile) = runReadAction {
    val action = GitIgnoreFileActionGroup()
    action.getChildren(createActionEvent(action, *selectedFiles)).toList()
  }

  private fun createActionEvent(action: GitIgnoreFileActionGroup, vararg selectedFiles: VirtualFile): AnActionEvent {
    return createActionEvent(action, selectedFiles.toList(), emptyList())
  }

  private fun createActionEvent(
    action: GitIgnoreFileActionGroup,
    files: List<VirtualFile>,
    statusNodes: List<GitFileStatusNode>,
  ): AnActionEvent {
    val dataContext = DataContext { dataId ->
      when (dataId) {
        CommonDataKeys.PROJECT.name -> project
        ChangesListView.EXACTLY_SELECTED_FILES_DATA_KEY.name -> files
        VcsDataKeys.VIRTUAL_FILES.name -> files
        GitStageDataKeys.GIT_FILE_STATUS_NODES.name -> statusNodes
        else -> null
      }
    }
    return AnActionEvent.createEvent(action, dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
  }

  private fun createIgnoreWriter(selectedFile: VirtualFile, ignoreFile: VirtualFile): Runnable {
    return Runnable {
      writeIgnoreFileEntries(project, ignoreFile, listOf(IgnoredBeanFactory.ignoreFile(selectedFile, project)))
    }
  }

  private fun selectionEntries(selectedFiles: List<VirtualFile>): List<IgnoreFileSelectionEntry> {
    val vcsManager = ProjectLevelVcsManager.getInstance(project)
    return selectedFiles.map { file -> IgnoreFileSelectionEntry(file, vcsManager.getVcsRootObjectFor(file)!!) }
  }

  private fun selectionEntries(vararg selectedFiles: VirtualFile): List<IgnoreFileSelectionEntry> {
    return selectionEntries(selectedFiles.toList())
  }
}
