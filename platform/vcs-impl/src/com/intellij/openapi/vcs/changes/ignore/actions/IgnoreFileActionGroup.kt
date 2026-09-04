// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.changes.ignore.actions

import com.intellij.CommonBundle
import com.intellij.ide.IdeBundle
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsBundle.message
import com.intellij.openapi.vcs.VcsBundle.messagePointer
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.ScheduleForAdditionAction
import com.intellij.openapi.vcs.changes.ignore.lang.IgnoreFileType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.vcsUtil.VcsImplUtil
import com.intellij.vcsUtil.VcsUtil
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls

open class IgnoreFileActionGroup(private val ignoreFileType: IgnoreFileType) :
  ActionGroup(
    messagePointer("vcs.add.to.ignore.file.action.group.text", ignoreFileType.ignoreLanguage.filename),
    messagePointer("vcs.add.to.ignore.file.action.group.description", ignoreFileType.ignoreLanguage.filename),
    { ignoreFileType.icon }
  ), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  protected open fun createAdditionalActions(
    project: Project,
    selectedFiles: List<VirtualFile>,
    unversionedFiles: List<VirtualFile>,
  ): List<AnAction> = emptyList()

  /**
   * Creates VCS-specific actions for the captured selection.
   *
   * The default delegates to the original file-only extension point so existing VCS implementations keep their behavior.
   */
  protected open fun createAdditionalActionsForSelection(
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    unversionedFiles: List<VirtualFile>,
  ): List<AnAction> = createAdditionalActions(project, selection.map { it.file }, unversionedFiles)

  /**
   * Returns whether the selected paths may be added to this VCS ignore file type.
   *
   * The default keeps ignore actions limited to unversioned paths. VCS implementations that support safely untracking files may widen this
   * contract and handle the index transition in [executeIgnoreAction].
   *
   * @param selectedFiles the filtered action-time selection.
   * @param unversionedFiles the unversioned subset reported by the standard add action.
   */
  protected open fun isSelectionSupported(
    project: Project,
    selectedFiles: List<VirtualFile>,
    unversionedFiles: List<VirtualFile>,
  ): Boolean = unversionedFiles.isNotEmpty()

  /** Applies the eligibility contract to a selection whose VCS roots were captured with the action. */
  protected open fun isSelectionSupportedForSelection(
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    unversionedFiles: List<VirtualFile>,
  ): Boolean = isSelectionSupported(project, selection.map { it.file }, unversionedFiles)

  /**
   * Performs any VCS-specific preparation before [writeIgnoreEntries] updates the ignore file.
   *
   * Implementations must invoke [writeIgnoreEntries] only after preparation and all required confirmations succeed.
   *
   * @param selectedFiles the filtered action-time selection.
   * @param newIgnoreFileRoot the directory where the writer will create an ignore file, or `null` for an existing target.
   * @param writeIgnoreEntries the existing platform writer to run after preparation succeeds.
   */
  protected open fun executeIgnoreAction(
    e: AnActionEvent,
    project: Project,
    selectedFiles: List<VirtualFile>,
    newIgnoreFileRoot: VirtualFile?,
    writeIgnoreEntries: Runnable,
  ) {
    if (newIgnoreFileRoot != null &&
        !confirmCreateIgnoreFile(project, ignoreFileType.ignoreLanguage.filename, newIgnoreFileRoot)) {
      return
    }
    writeIgnoreEntries.run()
  }

  /** Executes an ignore action with the selection roots captured while the child action was planned. */
  protected open fun executeIgnoreActionForSelection(
    e: AnActionEvent,
    project: Project,
    selection: List<IgnoreFileSelectionEntry>,
    newIgnoreFileRoot: VirtualFile?,
    writeIgnoreEntries: Runnable,
  ) {
    executeIgnoreAction(e, project, selection.map { it.file }, newIgnoreFileRoot, writeIgnoreEntries)
  }

  /** Attributes a selected file to the VCS root that owns it in the current action context. */
  protected open fun getVcsRootForSelectedFile(
    e: AnActionEvent,
    project: Project,
    vcsManager: ProjectLevelVcsManager,
    file: VirtualFile,
  ): VcsRoot? = vcsManager.getVcsRootObjectFor(file)

  private fun createActionsFor(e: AnActionEvent): List<AnAction> {
    val project = e.getData(CommonDataKeys.PROJECT)
    if (project == null) {
      return emptyList()
    }

    val vcsManager = ProjectLevelVcsManager.getInstance(project)
    val selection = resolveSelectedFiles(e, vcsManager, project, getSelectedFiles(e))
    if (selection.isEmpty()) return emptyList()

    val unversionedFiles = ScheduleForAdditionAction.Manager.getUnversionedFiles(e, project).toList()
    if (!isSelectionSupportedForSelection(project, selection, unversionedFiles)) return emptyList()

    val commonVcsRoot = selection.first().vcsRoot.takeIf { root ->
      selection.all { entry -> entry.file != root.path && entry.vcsRoot == root }
    }
    val resultedIgnoreFiles = findSuitableIgnoreFiles(project, vcsManager, selection, commonVcsRoot)

    val actions = mutableListOf<AnAction>()
    val additionalActions = createAdditionalActionsForSelection(project, selection, unversionedFiles)
    if (resultedIgnoreFiles.isNotEmpty() && commonVcsRoot != null) {
      actions += resultedIgnoreFiles.toActions(project, commonVcsRoot, selection, additionalActions.size)
    }
    else {
      actions += listOfNotNull(createNewIgnoreFileAction(project, commonVcsRoot, selection))
    }

    if (additionalActions.isNotEmpty()) {
      actions += additionalActions
    }

    return actions
  }

  override fun update(e: AnActionEvent) {
    val presentation = e.presentation
    val actions = createActionsFor(e)

    presentation.isPopupGroup = actions.size > 1
    presentation.isPerformGroup = actions.size == 1
    e.presentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, e.presentation.isPerformGroup)
    presentation.isEnabledAndVisible = actions.isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val action = createActionsFor(e).singleOrNull() ?: return
    action.actionPerformed(e)
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> {
    if (e == null) return EMPTY_ARRAY
    return createActionsFor(e).toTypedArray()
  }

  /** Captures one exact VCS root for every eligible selected file, or fails the whole selection. */
  private fun resolveSelectedFiles(
    e: AnActionEvent,
    vcsManager: ProjectLevelVcsManager,
    project: Project,
    files: List<VirtualFile>,
  ): List<IgnoreFileSelectionEntry> {
    val changeListManager = ChangeListManager.getInstance(project)
    val selectedFiles = files.distinct().filterNot(changeListManager::isIgnoredFile)
    val selection = selectedFiles.mapNotNull { file ->
      getVcsRootForSelectedFile(e, project, vcsManager, file)?.let { root -> IgnoreFileSelectionEntry(file, root) }
    }
    return selection.takeIf { it.size == selectedFiles.size }.orEmpty()
  }

  /** Finds existing ignore files that cover every selected file inside their one common VCS root. */
  private fun findSuitableIgnoreFiles(
    project: Project,
    vcsManager: ProjectLevelVcsManager,
    selection: List<IgnoreFileSelectionEntry>,
    commonVcsRoot: VcsRoot?,
  ): List<VirtualFile> {
    if (commonVcsRoot == null) return emptyList()
    return FileTypeIndex.getFiles(ignoreFileType, ProjectScope.getProjectScope(project))
      .filter { ignoreFile ->
        vcsManager.getVcsRootObjectFor(ignoreFile) == commonVcsRoot && selection.all { entry ->
          val file = entry.file
          val fileParent = file.parent
          fileParent == ignoreFile.parent ||
          fileParent != null && ignoreFile.parent != null && VfsUtil.isAncestor(ignoreFile.parent, fileParent, false)
        }
      }
  }

  private fun Collection<VirtualFile>.toActions(
    project: Project,
    vcsRoot: VcsRoot,
    selection: List<IgnoreFileSelectionEntry>,
    additionalActionsSize: Int,
  ): Collection<AnAction> {
    val projectDir = project.guessProjectDir()
    return map { file ->
      IgnoreFileAction(file, vcsRoot, selection) { e, writeIgnoreEntries ->
        executeIgnoreActionForSelection(e, project, selection, null, writeIgnoreEntries)
      }.apply {
        templatePresentation.apply {
          icon = ignoreFileType.icon
          text = file.toTextRepresentation(project, projectDir, this@toActions.size + additionalActionsSize)
        }
      }
    }
  }

  private fun createNewIgnoreFileAction(
    project: Project,
    commonVcsRoot: VcsRoot?,
    selection: List<IgnoreFileSelectionEntry>,
  ): AnAction? {
    val filename = ignoreFileType.ignoreLanguage.filename
    val rootVcs = commonVcsRoot?.vcs ?: return null
    val commonIgnoreFileRoot = commonVcsRoot.path
    if (commonIgnoreFileRoot.findChild(filename) != null) return null
    val ignoredFileContentProvider = VcsImplUtil.findIgnoredFileContentProvider(rootVcs) ?: return null
    if (ignoredFileContentProvider.fileName != filename) return null

    return CreateNewIgnoreFileAction(filename, commonIgnoreFileRoot, commonVcsRoot, selection) { e, writeIgnoreEntries ->
      executeIgnoreActionForSelection(e, project, selection, commonIgnoreFileRoot, writeIgnoreEntries)
    }.apply {
      templatePresentation.apply {
        icon = ignoreFileType.icon
        text = message("vcs.add.to.ignore.file.action.group.text", filename)
      }
    }
  }

  private fun VirtualFile.toTextRepresentation(project: Project, projectDir: VirtualFile?, size: Int): @Nls String {
    if (size == 1) {
      return message("vcs.add.to.ignore.file.action.group.text", ignoreFileType.ignoreLanguage.filename)
    }
    val projectRootOrVcsRoot = projectDir ?: VcsUtil.getVcsRootFor(project, this) ?: return name
    return VfsUtil.getRelativePath(this, projectRootOrVcsRoot) ?: name
  }

}

/** One selected file and the exact VCS root that owned it when the ignore action was planned. */
@ApiStatus.Internal
data class IgnoreFileSelectionEntry(val file: VirtualFile, val vcsRoot: VcsRoot)

/**
 * Asks whether [ignoreFileName] may be created in [ignoreFileRoot].
 *
 * @param project the project that owns the confirmation dialog.
 * @return `true` when the caller may create the file.
 */
@ApiStatus.Internal
fun confirmCreateIgnoreFile(project: Project, ignoreFileName: String, ignoreFileRoot: VirtualFile): Boolean {
  return Messages.YES == Messages.showDialog(
    project,
    message("vcs.add.to.ignore.file.create.ignore.file.confirmation.message",
            ignoreFileName, FileUtil.getLocationRelativeToUserHome(ignoreFileRoot.presentableUrl)),
    message("vcs.add.to.ignore.file.create.ignore.file.confirmation.title", ignoreFileName),
    null,
    arrayOf(IdeBundle.message("button.create"), CommonBundle.getCancelButtonText()),
    0,
    1,
    Messages.getQuestionIcon(),
  )
}
