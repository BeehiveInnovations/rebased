// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.ui.IdeUICustomization
import java.util.function.Predicate

/** Sections have distinct rows even when an open project also belongs to Pinned. */
internal enum class ProjectWidgetSection {
  Open,
  Pinned,
  Recent,
}

/** Identifies a project row across popup refreshes without confusing its Open and Pinned entries. */
internal data class ProjectWidgetRowKey(val projectPath: String, val section: ProjectWidgetSection)

/**
 * Builds the widget sections in O(n + p log p), where p is the number of pinned projects.
 * Pinned always includes every saved pin; only unpinned, closed projects use the recent-row limit.
 */
internal fun createProjectWidgetProjectActions(
  actions: List<AnAction>,
  openProjectsPredicate: Predicate<AnAction>,
  manager: RecentProjectsManagerBase,
): List<AnAction> {
  val (openProjects, closedProjects) = actions.partition { openProjectsPredicate.test(it) }
  val pinnedProjects = actions.filterIsInstance<ReopenProjectAction>().filter { manager.isProjectPinned(it.projectPath) }
  val recentProjects = closedProjects.filterNot {
    it is ReopenProjectAction && manager.isProjectPinned(it.projectPath)
  }
  val sortedPins = pinnedProjects.sortedWith { first, second ->
    val byName = NaturalComparator.INSTANCE.compare(first.projectNameToDisplay, second.projectNameToDisplay)
    // Equal names must keep the same order even when opening one changes its recency.
    if (byName != 0) byName else first.projectPath.compareTo(second.projectPath)
  }

  return buildList {
    val customization = IdeUICustomization.getInstance()
    for ((section, projects) in listOf(ProjectWidgetSection.Open to openProjects,
                                      ProjectWidgetSection.Pinned to sortedPins,
                                      ProjectWidgetSection.Recent to recentProjects.take(100))) {
      if (projects.isEmpty()) continue
      val title = when (section) {
        ProjectWidgetSection.Open -> customization.projectMessage("project.widget.open.projects")
        ProjectWidgetSection.Pinned -> customization.projectMessage("project.widget.pinned.projects")
        ProjectWidgetSection.Recent -> customization.projectMessage("project.widget.recent.projects")
      }
      add(Separator.create(title))
      for (action in projects) {
        // Provider actions keep their own identity and behavior; their display paths are not saved-project keys.
        add(if (action is ReopenProjectAction) ProjectWidgetProjectAction(action, section, manager) else action)
      }
    }
  }
}

/** Supplies section-specific controls while the original action still owns opening and missing-project handling. */
internal class ProjectWidgetProjectAction(
  val projectAction: ReopenProjectAction,
  section: ProjectWidgetSection,
  manager: RecentProjectsManagerBase,
) : AnActionWrapper(projectAction), ProjectToolbarWidgetPresentable by projectAction {
  val rowKey = ProjectWidgetRowKey(projectAction.projectPath, section)
  private val inlineActions: List<AnAction> = buildList {
    add(PinProjectAction(projectAction.projectPath, manager))
    when (section) {
      ProjectWidgetSection.Open, ProjectWidgetSection.Pinned -> Unit
      ProjectWidgetSection.Recent -> add(RemoveRecentProjectAction(projectAction, manager))
    }
  }

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.putClientProperty(ActionUtil.INLINE_ACTIONS, inlineActions)
  }
}

/** A row's independent pin toggle; its captured project path stays valid when the list is reordered. */
private class PinProjectAction(private val projectPath: String, private val manager: RecentProjectsManagerBase) : ToggleAction(), DumbAware {
  init {
    templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun isSelected(e: AnActionEvent): Boolean = manager.isProjectPinned(projectPath)

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    manager.setProjectPinned(projectPath, state)
  }

  override fun update(e: AnActionEvent) {
    super.update(e)
    val pinned = isSelected(e)
    e.presentation.text = IdeUICustomization.getInstance().projectMessage(
      if (pinned) "project.widget.unpin.project" else "project.widget.pin.project"
    )
    // ToggleAction clears popup icons in favor of checkmarks; inline buttons need their own icon.
    e.presentation.icon = if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin
    e.presentation.isEnabledAndVisible = manager.hasPath(projectPath)
  }
}

/** Uses the normal popup dismissal before confirming removal of a closed, unpinned history entry. */
private class RemoveRecentProjectAction(
  private val projectAction: ReopenProjectAction,
  private val manager: RecentProjectsManagerBase,
) : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.text = IdeUICustomization.getInstance().projectMessage("project.widget.remove.project")
    e.presentation.icon = AllIcons.Actions.CloseHovered
    e.presentation.isEnabledAndVisible = canRemove()
  }

  override fun actionPerformed(e: AnActionEvent) {
    if (!canRemove()) return
    val answer = Messages.showYesNoDialog(
      IdeBundle.message("dialog.message.remove.0.from.recent.projects.list", projectAction.projectNameToDisplay),
      IdeBundle.message("dialog.title.remove.recent.project"),
      IdeBundle.message("button.remove"),
      IdeBundle.message("button.cancel"),
      Messages.getQuestionIcon(),
    )
    // The saved entry may have been pinned or opened while confirmation was pending.
    if (answer == Messages.YES && canRemove()) {
      manager.removePath(projectAction.projectPath)
    }
  }

  private fun canRemove(): Boolean = manager.hasPath(projectAction.projectPath) &&
                                     !manager.isProjectPinned(projectAction.projectPath) &&
                                     !OpenProjectSelectionPredicateSupplier.getInstance().getPredicate().test(projectAction)
}
