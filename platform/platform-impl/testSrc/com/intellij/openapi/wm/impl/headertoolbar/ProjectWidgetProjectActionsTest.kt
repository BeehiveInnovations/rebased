// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.ide.RecentProjectManagerState
import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.RecentProjectMetaInfo
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.ui.IdeUICustomization
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.list.ListPopupModel
import com.intellij.ui.speedSearch.SpeedSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Predicate
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JList

@TestApplication
@RunInEdt
internal class ProjectWidgetProjectActionsTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @TestDisposable
  private lateinit var disposable: Disposable

  @AfterEach
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun `the only open project also appears in pinned after pinning`() {
    val manager = RecentProjectsManagerBase(scope)
    val current = project("Current")
    manager.loadState(RecentProjectManagerState().also {
      it.additionalInfo[current.projectPath] = RecentProjectMetaInfo().also { info -> info.opened = true }
    })
    manager.setProjectPinned(current.projectPath, true)
    // Build the real popup items too: action groups reject duplicate action objects.
    val items = popupStep(createProjectWidgetProjectActions(listOf(current), Predicate { true }, manager)).values
    assertEquals(listOf(openHeading(), pinnedHeading()), items.map { it.separatorText })
    assertEquals(listOf("Current", "Current"), items.map { (it.action as ProjectToolbarWidgetPresentable).projectNameToDisplay })

    manager.setProjectPinned(current.projectPath, false)
    val unpinnedItems = popupStep(createProjectWidgetProjectActions(listOf(current), Predicate { true }, manager)).values
    assertEquals(listOf(openHeading()), unpinnedItems.map { it.separatorText })
    assertEquals(listOf("Current"), unpinnedItems.map { (it.action as ProjectToolbarWidgetPresentable).projectNameToDisplay })
  }

  @Test
  fun `recent rows alone offer the remove action`() {
    val manager = RecentProjectsManagerBase(scope)
    val current = project("Current")
    val pinned = project("Pinned")
    val recent = project("Recent")
    manager.loadState(RecentProjectManagerState().also { state ->
      for (action in listOf(current, pinned, recent)) {
        state.additionalInfo[action.projectPath] = RecentProjectMetaInfo().also {
          it.opened = action == current
          it.pinned = action == pinned
        }
      }
    })
    ApplicationManager.getApplication().replaceService(RecentProjectsManager::class.java, manager, disposable)
    val items = popupStep(createProjectWidgetProjectActions(listOf(current, pinned, recent), Predicate { it == current }, manager)).values
    assertEquals(mapOf("Current" to 1, "Pinned" to 1, "Recent" to 2), items.associate {
      (it.action as ProjectToolbarWidgetPresentable).projectNameToDisplay to it.inlineItems.size
    })
  }

  @Test
  fun `open projects precede alphabetical pins and recents keep their order`() {
    val manager = RecentProjectsManagerBase(scope)
    val current = project("Current")
    val zebra = project("Zebra")
    val alpha = project("alpha")
    val recent = project("Recent")
    val older = project("Older")
    manager.loadState(RecentProjectManagerState().also { state ->
      for (action in listOf(current, zebra, alpha)) {
        state.additionalInfo[action.projectPath] = RecentProjectMetaInfo().also { it.pinned = true }
      }
    })
    val actions = listOf(zebra, recent, current, alpha, older)
    val isOpen = Predicate<AnAction> { it == current }

    assertEquals(listOf(openHeading(), current, pinnedHeading(), alpha, current, zebra, recentHeading(), recent, older),
                 sections(createProjectWidgetProjectActions(actions, isOpen, manager)))

    manager.setProjectPinned(alpha.projectPath, false)
    manager.setProjectPinned(zebra.projectPath, false)
    assertEquals(listOf(openHeading(), current, pinnedHeading(), current, recentHeading(), zebra, recent, alpha, older),
                 sections(createProjectWidgetProjectActions(actions, isOpen, manager)))
  }

  @Test
  fun `pins and open projects beyond the recent limit remain visible`() {
    val manager = RecentProjectsManagerBase(scope)
    val recent = (0 until 110).map { project("Recent$it") }
    val pinned = project("Pinned")
    val current = project("Current")
    manager.loadState(RecentProjectManagerState().also {
      it.additionalInfo[pinned.projectPath] = RecentProjectMetaInfo().also { info -> info.pinned = true }
    })

    val result = createProjectWidgetProjectActions(recent + pinned + current, Predicate { it == current }, manager)
    assertEquals(listOf(openHeading(), current, pinnedHeading(), pinned, recentHeading()) + recent.take(100), sections(result))
  }

  private fun project(name: String) = ReopenProjectAction("/projects/$name", name, name)

  private fun sections(actions: List<AnAction>): List<Any?> = actions.map {
    when (it) {
      is Separator -> it.text
      is ProjectWidgetProjectAction -> it.projectAction
      else -> it
    }
  }

  @Test
  fun `pinned order and membership stay stable when a project opens`() {
    val manager = RecentProjectsManagerBase(scope)
    val alpha = project("Alpha")
    val beta = project("Beta")
    val actions = listOf(beta, alpha)
    manager.loadState(RecentProjectManagerState().also { state ->
      for (action in actions) state.additionalInfo[action.projectPath] = RecentProjectMetaInfo().also { it.pinned = true }
    })
    fun pinnedRows(isOpen: Predicate<AnAction>) = popupStep(createProjectWidgetProjectActions(actions, isOpen, manager)).values
      .map { it.action as ProjectWidgetProjectAction }
      .filter { it.rowKey.section == ProjectWidgetSection.Pinned }
      .map { it.rowKey.projectPath }

    assertEquals(listOf(alpha.projectPath, beta.projectPath), pinnedRows(Predicate { false }))
    assertEquals(listOf(alpha.projectPath, beta.projectPath), pinnedRows(Predicate { it == beta }))
  }

  @Test
  fun `pins with duplicate names keep their order when recency changes`() {
    val manager = RecentProjectsManagerBase(scope)
    ApplicationManager.getApplication().replaceService(RecentProjectsManager::class.java, manager, disposable)
    val firstPin = "/projects/a/Demo"
    val secondPin = "/projects/b/Demo"
    val olderRecent = "/projects/Older"
    val newerRecent = "/projects/Newer"
    for (history in listOf(listOf(firstPin, secondPin, olderRecent, newerRecent),
                           listOf(secondPin, olderRecent, newerRecent, firstPin))) {
      manager.loadState(RecentProjectManagerState().also { state ->
        for (path in history) {
          state.additionalInfo[path] = RecentProjectMetaInfo().also {
            it.pinned = path == firstPin || path == secondPin
            if (it.pinned) it.displayName = "Demo"
          }
        }
      })
      val actions = createProjectWidgetProjectActions(RecentProjectListActionProvider().getActions(), Predicate { false }, manager)
        .filterIsInstance<ProjectWidgetProjectAction>()
      assertEquals(listOf(firstPin, secondPin), actions.filter { it.rowKey.section == ProjectWidgetSection.Pinned }.map { it.rowKey.projectPath })
      assertEquals(listOf(newerRecent, olderRecent), actions.filter { it.rowKey.section == ProjectWidgetSection.Recent }.map { it.rowKey.projectPath })
    }
  }

  @Test
  fun `remove action confirms and removes only the selected recent project`() {
    val manager = RecentProjectsManagerBase(scope)
    val recent = project("Recent")
    val pinned = project("Pinned")
    manager.loadState(RecentProjectManagerState().also { state ->
      state.additionalInfo[recent.projectPath] = RecentProjectMetaInfo()
      state.additionalInfo[pinned.projectPath] = RecentProjectMetaInfo().also { it.pinned = true }
    })
    val step = popupStep(createProjectWidgetProjectActions(listOf(recent, pinned), Predicate { false }, manager))
    val remove = step.values.single { (it.action as ProjectWidgetProjectAction).rowKey.section == ProjectWidgetSection.Recent }
      .inlineItems.last()
    assertEquals(KeepPopupOnPerform.Never, remove.keepPopupOnPerform)
    var answer = Messages.NO
    var confirmations = 0
    TestDialogManager.setTestDialog({ message ->
      assertTrue(message.contains("Recent"))
      confirmations++
      answer
    }, disposable)

    step.performActionItem(remove, null)
    assertEquals(1, confirmations)
    assertTrue(manager.hasPath(recent.projectPath))
    assertFalse(manager.isProjectPinned(recent.projectPath))
    assertTrue(manager.isProjectPinned(pinned.projectPath))

    answer = Messages.YES
    step.performActionItem(remove, null)
    assertEquals(2, confirmations)
    assertEquals(listOf(pinned.projectPath), manager.getRecentPaths())
    assertTrue(manager.isProjectPinned(pinned.projectPath))
  }

  @Test
  fun `remove action refuses a project pinned or opened during confirmation`() {
    val manager = RecentProjectsManagerBase(scope)
    val recent = project("Recent")
    manager.loadState(RecentProjectManagerState().also { it.additionalInfo[recent.projectPath] = RecentProjectMetaInfo() })
    var opened = false
    ApplicationManager.getApplication().replaceService(OpenProjectSelectionPredicateSupplier::class.java,
      object : OpenProjectSelectionPredicateSupplier {
        override fun getPredicate(): Predicate<AnAction> = Predicate { opened }
      }, disposable)
    val step = popupStep(createProjectWidgetProjectActions(listOf(recent), Predicate { false }, manager))
    val remove = step.values.single().inlineItems.last()
    var pinDuringConfirmation = true
    var confirmations = 0
    TestDialogManager.setTestDialog({
      confirmations++
      if (pinDuringConfirmation) manager.setProjectPinned(recent.projectPath, true) else opened = true
      Messages.YES
    }, disposable)

    step.performActionItem(remove, null)
    assertEquals(1, confirmations)
    assertTrue(manager.hasPath(recent.projectPath))
    assertTrue(manager.isProjectPinned(recent.projectPath))

    manager.setProjectPinned(recent.projectPath, false)
    pinDuringConfirmation = false
    step.performActionItem(remove, null)
    assertEquals(2, confirmations)
    assertTrue(manager.hasPath(recent.projectPath))
    assertFalse(manager.isProjectPinned(recent.projectPath))
    step.performActionItem(remove, null)
    assertEquals(2, confirmations)
  }

  @Test
  fun `filtered rows use the heading moved by the popup model`() {
    val manager = RecentProjectsManagerBase(scope)
    val first = project("First")
    val second = project("Second")
    manager.loadState(RecentProjectManagerState().also { state ->
      for (action in listOf(first, second)) state.additionalInfo[action.projectPath] = RecentProjectMetaInfo()
    })
    val step = popupStep(createProjectWidgetProjectActions(listOf(first, second), Predicate { false }, manager))
    val visibleItem = step.values[1]
    assertEquals(null, visibleItem.separatorText)
    val model = ListPopupModel({ item -> item === visibleItem }, SpeedSearch(), step)
    val separator = projectWidgetSeparator(JList(model), visibleItem)!!
    assertEquals(recentHeading(), separator.text)
    val headingHeight = createProjectWidgetSeparator(separator, true).preferredSize.height
    val bounds = projectWidgetButtonBounds(Rectangle(0, 0, 300, headingHeight + 100), 16, true,
                                          headingHeight, projectWidgetRowInsets())
    assertEquals(2, bounds.size)
    assertTrue(bounds[0].y >= headingHeight)
    assertFalse(bounds.any { it.contains(Point(it.x + 1, headingHeight - 1)) })
  }

  @Test
  fun `open and pinned rows invoke the same original project action`() {
    val manager = RecentProjectsManagerBase(scope)
    var opens = 0
    val action = object : ReopenProjectAction("/projects/Current", "Current", "Current") {
      override fun actionPerformed(e: AnActionEvent) {
        opens++
      }
    }
    manager.loadState(RecentProjectManagerState().also {
      it.additionalInfo[action.projectPath] = RecentProjectMetaInfo().also { info -> info.pinned = true }
    })
    val step = popupStep(createProjectWidgetProjectActions(listOf(action), Predicate { true }, manager))
    assertEquals(2, step.values.size)
    for (item in step.values) step.performActionItem(item, null)
    assertEquals(2, opens)
    assertEquals(listOf(action.projectPath), manager.getRecentPaths())
    assertTrue(manager.isProjectPinned(action.projectPath))
  }

  private fun popupStep(actions: List<AnAction>): ProjectWidgetPopupStep {
    val group = DefaultActionGroup().apply { addAll(actions) }
    val context = DataContext.EMPTY_CONTEXT
    val factory = PresentationFactory()
    val options = ActionPopupOptions.showDisabled()
    val items = ActionPopupStep.createActionItems(group, context, ActionPlaces.PROJECT_WIDGET_POPUP, factory, options)
    return ProjectWidgetPopupStep(items, context, factory, ActionPopupOptions.convertForStep(options, items))
  }

  private fun openHeading() = IdeUICustomization.getInstance().projectMessage("project.widget.open.projects")
  private fun pinnedHeading() = IdeUICustomization.getInstance().projectMessage("project.widget.pinned.projects")
  private fun recentHeading() = IdeUICustomization.getInstance().projectMessage("project.widget.recent.projects")
}
