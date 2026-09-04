// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore.actions

import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.changes.ignore.actions.confirmCreateIgnoreFile
import com.intellij.openapi.vcs.changes.ignore.actions.IgnoreFileSelectionEntry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitNotificationIdsHolder
import git4idea.GitUtil
import git4idea.i18n.GitBundle
import git4idea.ignore.lang.GitIgnoreFileType
import git4idea.index.GitIndexUtil
import git4idea.util.GitFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates tracked-path detection, user confirmation, Git index changes, and the existing ignore-file writer.
 *
 * Inspection remains cancellable. Once the user confirms an index change, all selected roots are attempted without user cancellation so the
 * writer is not skipped between roots.
 */
internal object GitIgnoreFilesOperation {

  /**
   * Starts the ignore operation in the action event's lifecycle scope.
   *
   * @param selection the immutable files and owning roots captured when the child action was created.
   * @param newIgnoreFileRoot the directory where the writer will create `.gitignore`, or `null` for an existing target.
   * @param writeIgnoreEntries the existing ignore writer, which runs on the UI thread after all Git changes succeed.
   */
  fun launch(
    e: AnActionEvent,
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    newIgnoreFileRoot: VirtualFile?,
    writeIgnoreEntries: Runnable,
  ) {
    e.coroutineScope.launch(Dispatchers.IO) {
      execute(project, selection, newIgnoreFileRoot, writeIgnoreEntries)
    }
  }

  /**
   * Stops tracking selected paths when required, then updates the chosen ignore file.
   *
   * @param confirm a testable confirmation boundary that receives whether any selected path is tracked.
   * @return `true` only when the ignore entries were written.
   */
  internal suspend fun execute(
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    newIgnoreFileRoot: VirtualFile?,
    writeIgnoreEntries: Runnable,
    confirm: suspend (hasTrackedFiles: Boolean) -> Boolean = { hasTrackedFiles ->
      confirmOperation(project, selection.size, hasTrackedFiles, newIgnoreFileRoot)
    },
  ): Boolean {
    val trackedPaths = try {
      withBackgroundProgress(project, GitBundle.message("git.ignore.checking.files.progress.title"), cancellable = true) {
        findTrackedPaths(project, selection)
      }
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      notifyFailure(project, e, afterChangesStarted = false)
      return false
    }

    val createsIgnoreFile = newIgnoreFileRoot != null
    if ((trackedPaths.isNotEmpty() || createsIgnoreFile) && !confirm(trackedPaths.isNotEmpty())) return false

    if (trackedPaths.isNotEmpty()) {
      var indexChangeStarted = false
      try {
        withBackgroundProgress(project, GitBundle.message("git.ignore.untracking.files.progress.title"), cancellable = false) {
          for ((root, paths) in trackedPaths) {
            // A chunked Git command can change part of the index before a later chunk fails.
            indexChangeStarted = true
            GitFileUtils.removePathsFromIndex(project, root, paths)
          }
        }
      }
      catch (e: Exception) {
        rethrowControlFlowException(e)
        notifyFailure(project, e, afterChangesStarted = indexChangeStarted)
        return false
      }
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIgnoreEntries.run()
      }
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      notifyFailure(project, e, afterChangesStarted = true)
      return false
    }
    return true
  }

  private fun findTrackedPaths(project: Project, selection: List<IgnoreFileSelectionEntry>): Map<VirtualFile, List<FilePath>> {
    val pathsByRoot = selection.groupBy(
      keySelector = { entry -> entry.vcsRoot.path },
      valueTransform = { entry -> VcsUtil.getFilePath(entry.file) },
    )
    return pathsByRoot.filter { (root, paths) ->
      GitUtil.getRepositoryForRoot(project, root)
      GitIndexUtil.hasIndexEntries(project, root, paths)
    }
  }

  private suspend fun confirmOperation(
    project: Project,
    selectedPathCount: Int,
    hasTrackedFiles: Boolean,
    newIgnoreFileRoot: VirtualFile?,
  ): Boolean = withContext(Dispatchers.EDT) {
    val newIgnoreFileName = GitIgnoreFileType.INSTANCE.ignoreLanguage.filename
    if (!hasTrackedFiles && newIgnoreFileRoot != null) {
      return@withContext confirmCreateIgnoreFile(project, newIgnoreFileName, newIgnoreFileRoot)
    }

    val message = if (newIgnoreFileRoot != null) {
      GitBundle.message(
        "git.ignore.tracked.files.and.create.ignore.file.confirmation.message",
        selectedPathCount,
        newIgnoreFileName,
        FileUtil.getLocationRelativeToUserHome(newIgnoreFileRoot.presentableUrl),
      )
    }
    else {
      GitBundle.message("git.ignore.tracked.files.confirmation.message", selectedPathCount)
    }
    Messages.YES == Messages.showDialog(
      project,
      message,
      GitBundle.message("git.ignore.tracked.files.confirmation.title"),
      null,
      arrayOf(GitBundle.message("git.ignore.stop.tracking.and.ignore.button"), CommonBundle.getCancelButtonText()),
      0,
      1,
      Messages.getWarningIcon(),
    )
  }

  /**
   * Reports an ignore failure and states whether the Git index may already have changed.
   *
   * @param afterChangesStarted whether an index command or ignore-file writer started before the failure.
   */
  internal fun notifyFailure(project: Project, error: Exception, afterChangesStarted: Boolean) {
    val messageKey = if (afterChangesStarted) {
      "git.ignore.files.error.after.changes.started"
    }
    else {
      "git.ignore.files.error.before.index.change"
    }
    VcsNotifier.getInstance(project).notifyError(
      GitNotificationIdsHolder.STAGE_OPERATION_ERROR,
      GitBundle.message("git.ignore.files.error.title"),
      GitBundle.message(messageKey, error.localizedMessage ?: error.javaClass.simpleName),
    )
  }
}
