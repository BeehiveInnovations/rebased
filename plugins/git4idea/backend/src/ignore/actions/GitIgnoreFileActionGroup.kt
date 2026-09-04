// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileActionGroup
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileSelectionEntry
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitVcs
import git4idea.ignore.lang.GitExcludeFileType
import git4idea.ignore.lang.GitIgnoreFileType
import git4idea.index.ui.GitStageDataKeys
import git4idea.repo.GitRepositoryManager

class GitIgnoreFileActionGroup : IgnoreFileActionGroup(GitIgnoreFileType.INSTANCE) {

  override fun createAdditionalActionsForSelection(
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    unversionedFiles: List<VirtualFile>,
  ): List<AnAction> =
    listOf(AddToGitExcludeAction(selection) { e, writeIgnoreEntries ->
      executeIgnoreActionForSelection(e, project, selection, null, writeIgnoreEntries)
    })

  override fun isSelectionSupportedForSelection(
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    unversionedFiles: List<VirtualFile>,
  ): Boolean {
    val repositoryManager = GitRepositoryManager.getInstance(project)
    return selection.all { entry ->
      repositoryManager.getRepositoryForRootQuick(entry.vcsRoot.path) != null &&
      entry.file != entry.vcsRoot.path &&
      entry.file.fileType != GitIgnoreFileType.INSTANCE && entry.file.fileType != GitExcludeFileType.INSTANCE
    }
  }

  override fun executeIgnoreActionForSelection(
    e: AnActionEvent,
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    newIgnoreFileRoot: VirtualFile?,
    writeIgnoreEntries: Runnable,
  ) {
    GitIgnoreFilesOperation.launch(e, project, selection, newIgnoreFileRoot, writeIgnoreEntries)
  }

  override fun getVcsRootForSelectedFile(
    e: AnActionEvent,
    project: Project,
    vcsManager: ProjectLevelVcsManager,
    file: VirtualFile,
  ): VcsRoot? {
    val statusNode = e.getData(GitStageDataKeys.GIT_FILE_STATUS_NODES)
      ?.firstOrNull { node -> node.filePath.virtualFile == file }
    if (statusNode != null) return VcsRoot(GitVcs.getInstance(project), statusNode.root)
    return super.getVcsRootForSelectedFile(e, project, vcsManager, file)
  }
}
