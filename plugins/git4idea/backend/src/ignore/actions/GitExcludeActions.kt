// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.IgnoredBeanFactory
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileSelectionEntry
import com.intellij.openapi.vcs.changes.ignore.actions.writeIgnoreFileEntries
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitUtil
import git4idea.GitVcs
import git4idea.i18n.GitBundle
import git4idea.i18n.GitBundle.messagePointer
import git4idea.ignore.lang.GitExcludeFileType
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NotNull
import java.util.function.Supplier

abstract class DefaultGitExcludeAction(dynamicText: @NotNull Supplier<@Nls String>,
                                       dynamicDescription: @NotNull Supplier<@Nls String>)
  : DumbAwareAction(dynamicText, dynamicDescription, GitExcludeFileType.INSTANCE.icon) {

  override fun update(e: AnActionEvent) {
    val enabled = isEnabled(e)
    e.presentation.isVisible = enabled
    e.presentation.isEnabled = enabled
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  protected open fun isEnabled(e: AnActionEvent): Boolean {
    val project = e.getData(CommonDataKeys.PROJECT)
    return (project != null && GitUtil.getRepositories(project).isNotEmpty())
  }

}

class AddToGitExcludeAction(
  private val selection: List<IgnoreFileSelectionEntry>,
  private val executeIgnoreAction: (AnActionEvent, Runnable) -> Unit,
) : DefaultGitExcludeAction(
  messagePointer("git.add.to.exclude.file.action.text"),
  messagePointer("git.add.to.exclude.file.action.description")
) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT) ?: return
    val gitVcs = GitVcs.getInstance(project)
    if (selection.any { entry ->
        !entry.vcsRoot.path.isValid || !entry.file.isValid || !VfsUtil.isAncestor(entry.vcsRoot.path, entry.file, false)
      }) return

    val filesToIgnore = try {
      VcsUtil.computeWithModalProgress(project, VcsBundle.message("ignoring.files.progress.title"), false) {
        selection.groupBy { entry -> entry.vcsRoot.path }.map { (root, entries) ->
          GitUtil.getRepositoryForRoot(project, root) to entries.map { entry -> entry.file }
        }
      }
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      GitIgnoreFilesOperation.notifyFailure(project, e, afterChangesStarted = false)
      return
    }
    val excludeTargets = filesToIgnore.map { (repository, filesToAdd) ->
      val excludeFile = repository.repositoryFiles.excludeFile
      val gitExclude = VfsUtil.findFileByIoFile(excludeFile, true)
      if (gitExclude == null) {
        GitIgnoreFilesOperation.notifyFailure(
          project,
          VcsException(GitBundle.message("git.ignore.exclude.file.not.found", excludeFile.path)),
          afterChangesStarted = false,
        )
        return
      }
      Triple(repository, gitExclude, filesToAdd)
    }
    executeIgnoreAction(e, Runnable {
      for ((repository, gitExclude, filesToAdd) in excludeTargets) {
        val ignores = filesToAdd.map { file -> IgnoredBeanFactory.ignoreFile(file, project) }
        writeIgnoreFileEntries(project, gitExclude, ignores, gitVcs, repository.root)
      }
    })
  }

}

class OpenGitExcludeAction : DefaultGitExcludeAction(
  messagePointer("git.open.exclude.file.action.text"),
  messagePointer("git.open.exclude.file.action.description")
) {

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT) ?: return
    val excludeToOpen = GitUtil.getRepositories(project).map { it.repositoryFiles.excludeFile }
    for (gitExclude in excludeToOpen) {
      VfsUtil.findFileByIoFile(gitExclude, true)?.let { excludeVf ->
        OpenFileDescriptor(project, excludeVf).navigate(true)
      }
    }
  }

}
