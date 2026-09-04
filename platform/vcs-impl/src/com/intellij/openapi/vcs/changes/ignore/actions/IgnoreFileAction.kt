// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.ignore.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.changes.IgnoredBeanFactory
import com.intellij.openapi.vcs.changes.IgnoredFileBean
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.ignore.psi.util.addNewElements
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.asJBIterable
import org.jetbrains.annotations.ApiStatus

/** Adds the captured action-time selection to an existing ignore file. */
@ApiStatus.Internal
class IgnoreFileAction(
  private val ignoreFile: VirtualFile,
  private val vcsRoot: VcsRoot,
  private val selection: List<IgnoreFileSelectionEntry>,
  private val executeIgnoreAction: (AnActionEvent, Runnable) -> Unit,
) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT) ?: return
    if (!ignoreFile.isValid) return
    val ignoreFileRoot = ignoreFile.parent ?: return

    val ignored = getIgnoredFileBeans(project, selection, ignoreFileRoot, vcsRoot)
    if (ignored.size != selection.size) return

    executeIgnoreAction(e, Runnable {
      writeIgnoreFileEntries(project, ignoreFile, ignored)
    })
  }

}

/** Creates an ignore file and adds the captured action-time selection to it. */
@ApiStatus.Internal
class CreateNewIgnoreFileAction(
  private val ignoreFileName: String,
  private val ignoreFileRoot: VirtualFile,
  private val vcsRoot: VcsRoot,
  private val selection: List<IgnoreFileSelectionEntry>,
  private val executeIgnoreAction: (AnActionEvent, Runnable) -> Unit,
) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT) ?: return

    val ignored = getIgnoredFileBeans(project, selection, ignoreFileRoot, vcsRoot)
    if (ignored.size != selection.size) return

    executeIgnoreAction(e, Runnable {
      val ignoreFile = runUndoTransparentWriteAction { ignoreFileRoot.createChildData(ignoreFileRoot, ignoreFileName) }
      writeIgnoreFileEntries(project, ignoreFile, ignored)
    })
  }
}

fun writeIgnoreFileEntries(project: Project,
                           ignoreFile: VirtualFile,
                           ignored: List<IgnoredFileBean>,
                           vcs: AbstractVcs? = null,
                           ignoreEntryRoot: VirtualFile? = null) {
  addNewElements(project, ignoreFile, ignored, vcs?.keyInstanceMethod, ignoreEntryRoot)
  VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
  OpenFileDescriptor(project, ignoreFile).navigate(true)
}

/** Revalidates target validity and containment without replacing the selection's captured root ownership. */
internal fun getIgnoredFileBeans(
  project: Project,
  selection: List<IgnoreFileSelectionEntry>,
  ignoreFileRoot: VirtualFile,
  vcsRoot: VcsRoot,
): List<IgnoredFileBean> {
  if (!ignoreFileRoot.isValid || !vcsRoot.path.isValid || !VfsUtil.isAncestor(vcsRoot.path, ignoreFileRoot, false)) return emptyList()
  if (ProjectLevelVcsManager.getInstance(project).getVcsRootObjectFor(ignoreFileRoot) != vcsRoot) return emptyList()

  return selection
    .asSequence()
    .filter { entry -> entry.vcsRoot == vcsRoot && entry.file.isValid }
    .filter { entry -> VfsUtil.isAncestor(ignoreFileRoot, entry.file, false) }
    .map { entry -> IgnoredBeanFactory.ignoreFile(entry.file, project) }
    .toList()
}

fun getSelectedFiles(e: AnActionEvent): List<VirtualFile> {
  val exactlySelectedFiles = e.getData(ChangesListView.EXACTLY_SELECTED_FILES_DATA_KEY).asJBIterable().toList()

  return if (exactlySelectedFiles.isNotEmpty()) exactlySelectedFiles
  else e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: emptyList()
}
