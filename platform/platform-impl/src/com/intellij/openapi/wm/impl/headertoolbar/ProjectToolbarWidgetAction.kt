// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet")

package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.icons.AllIcons
import com.intellij.ide.ProjectWidgetGradientLocationService
import com.intellij.ide.ProjectWindowCustomizerService
import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.RecentProjectsManager.RecentProjectsChange
import com.intellij.ide.ReopenProjectAction
import com.intellij.ide.UpdatesInfoProviderManager
import com.intellij.ide.impl.ProjectUtilCore
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.ide.userScaledProjectIconSize
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.SpeedSearchFilter
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.impl.ExpandableComboAction
import com.intellij.openapi.wm.impl.ToolbarComboButton
import com.intellij.openapi.wm.impl.ToolbarComboButtonModel
import com.intellij.project.ProjectStoreOwner
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClientProperty
import com.intellij.ui.ExpandableItemsHandler
import com.intellij.ui.IconManager
import com.intellij.ui.IdeUICustomization
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.EmptySpacingConfiguration
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.PopupInlineActionsSupport
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.ui.popup.list.buttonWidth
import com.intellij.ui.popup.list.createSupport
import com.intellij.ui.util.maximumWidth
import com.intellij.util.IconUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.accessibility.AccessibleContextUtil
import com.intellij.util.ui.launchOnShow
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsListener
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.util.function.Function
import java.util.function.Predicate
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlin.io.path.invariantSeparatorsPathString

private val projectKey = Key.create<Project>("project-widget-project")
private val showChevronKey = Key.create<Boolean>("project-widget-show-chevron")

internal class DefaultOpenProjectSelectionPredicateSupplier : OpenProjectSelectionPredicateSupplier {
  override fun getPredicate(): Predicate<AnAction> {
    val openProjects = ProjectUtilCore.getOpenProjects()
    val paths = openProjects.mapNotNullTo(HashSet(openProjects.size)) {
      (it as? ProjectStoreOwner ?: return@mapNotNullTo null).componentStore.storeDescriptor.projectIdentityFile.invariantSeparatorsPathString
    }
    return Predicate { action ->
      when (action) {
        is ReopenProjectAction -> paths.contains(action.projectPath)
        is ProjectToolbarWidgetPresentable -> action.status?.isOpened == true
        else -> false
      }
    }
  }
}

@ApiStatus.Internal
open class ProjectToolbarWidgetAction : ExpandableComboAction(), DumbAware {
  override fun createPopup(event: AnActionEvent): JBPopup? {
    val group = createActionGroup(event)
    if (group.childrenCount == 0) return null
    val step = createStep(group, event.dataContext)
    return event.project?.let { createPopup(project = it, step = step, event = event) }
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun createToolbarComboButton(model: ToolbarComboButtonModel): ToolbarComboButton {
    return super.createToolbarComboButton(model).apply {
      accessibleNamePrefix = IdeUICustomization.getInstance().projectMessage("project.widget.accessible.name.prefix")
    }
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return super.createCustomComponent(presentation, place).apply {
      maximumWidth = JBUI.scale(500)
      val widget = this as ToolbarComboButton
      launchOnShow("ProjectWidget") {
        val positionListeners = WidgetPositionListeners(widget, presentation)
        try {
          positionListeners.updatePosition() // this could be in WidgetPositionListeners.init, but it's safer inside the try
          awaitCancellation()
        }
        finally {
          positionListeners.dispose()
        }
      }
    }
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    super.updateCustomComponent(component, presentation)

    val widget = component as? ToolbarComboButton ?: return
    widget.isOpaque = false
    widget.positionListeners?.setProjectFromPresentation(presentation)
    widget.showChevron = presentation.getClientProperty(showChevronKey) ?: true
  }

  override fun update(e: AnActionEvent) {
    val project = e.project
    val projectName = project?.name ?: ""
    e.presentation.setText(projectName, false)
    e.presentation.description = FileUtil.getLocationRelativeToUserHome(project?.guessProjectDir()?.path) ?: projectName
    e.presentation.putClientProperty(projectKey, project)

    e.presentation.putClientProperty(showChevronKey, !hideProjectSwitching(e) || UpdatesInfoProviderManager.getInstance().getUpdateActions().isNotEmpty())

    val icons = buildList {
      UpdatesInfoProviderManager.getInstance().getUpdateIcons().let { updateIcons ->
        for (icon in updateIcons) {
          if (isNotEmpty()) {
            addGap()
          }
          add(icon)
        }
      }

      val customizer = ProjectWindowCustomizerService.getInstance()
      if (project != null && customizer.isAvailable()) {
        if (isNotEmpty()) addGap()
        add(customizer.getProjectIcon(project))
      }
    }
    e.presentation.icon = when (icons.size) {
      0 -> null
      1 -> icons.single()
      else -> IconManager.getInstance().createRowIcon(*icons.toTypedArray())
    }
  }

  private fun createPopup(project: Project, step: ListPopupStep<PopupFactoryImpl.ActionItem>, event: AnActionEvent): ListPopup {
    val renderer = Function<ListCellRenderer<Any>, ListCellRenderer<out Any>> { base ->
      // The producer runs during popup construction, before its initial row measurements.
      val widgetRenderer = ProjectWidgetRenderer(createSupport((base as PopupListElementRenderer<*>).popup))
      ListCellRenderer<PopupFactoryImpl.ActionItem> { list, value, index, isSelected, cellHasFocus ->
        val action = (value as PopupFactoryImpl.ActionItem).action
        if (action is ProjectToolbarWidgetPresentable) {
          widgetRenderer.getListCellRendererComponent(
            list = list,
            value = value,
            index = index,
            isSelected = isSelected,
            cellHasFocus = cellHasFocus,
          )
        }
        else {
          base.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        }
      }
    }

    val result = JBPopupFactory.getInstance().createListPopup(project, step, renderer)

    if (result is ListPopupImpl) {
      ClientProperty.put(result.list, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

      ApplicationManager.getApplication().messageBus.connect(result).subscribe(RecentProjectsManager.RECENT_PROJECTS_CHANGE_TOPIC, object : RecentProjectsChange {
        override fun change() {
          refreshProjectActions(result, event)
        }
      })
    }

    return result
  }

  /** Rebuilds section headings and keeps selection on the same project after its pin state changes. */
  private fun refreshProjectActions(listPopup: ListPopupImpl, event: AnActionEvent) {
    val popupStep = listPopup.listStep as? ActionPopupStep ?: return
    val selectedAction = (listPopup.list.selectedValue as? PopupFactoryImpl.ActionItem)?.action
    val selectedKey = (selectedAction as? ProjectWidgetProjectAction)?.rowKey
    val items = ActionPopupStep.createActionItems(
      createActionGroup(event), Utils.createAsyncDataContext(event.dataContext), popupStep.actionPlace,
      popupStep.presentationFactory, popupStep.options,
    )
    // Clear the inline-button index too: a Recent row has two controls, but a Pinned row has one.
    listPopup.list.clearSelection()
    popupStep.values.clear()
    popupStep.values.addAll(items)
    listPopup.onModelChanged()
    val selectedItem = if (selectedKey == null) items.firstOrNull { it.action == selectedAction }
    else items.firstOrNull { (it.action as? ProjectWidgetProjectAction)?.rowKey == selectedKey }
         ?: items.firstOrNull { (it.action as? ProjectWidgetProjectAction)?.rowKey?.projectPath == selectedKey.projectPath }
    if (selectedItem != null) {
      listPopup.list.setSelectedValue(selectedItem, true)
    }
    listPopup.list.repaint()
  }

  private fun hideProjectSwitching(e: AnActionEvent): Boolean =
    ProjectWidgetActionsFilter.EP_NAME.extensionList.any { it.shouldHideProjectSwitchingActions(e) }

  private fun createActionGroup(initEvent: AnActionEvent): DefaultActionGroup {
    val result = DefaultActionGroup()

    UpdatesInfoProviderManager.getInstance()
      .getUpdateActions()
      .takeIf { it.isNotEmpty() }
      ?.let {
        result.addAll(it)
        result.addSeparator()
      }

    if (!hideProjectSwitching(initEvent)) {
      val group = ActionManager.getInstance().getAction("ProjectWidget.Actions") as ActionGroup
      result.addAll(group.getChildren(initEvent).asList())

      result.addAll(createProjectWidgetProjectActions(
        RecentProjectListActionProvider.getInstance().getActions(initEvent.project),
        OpenProjectSelectionPredicateSupplier.getInstance().getPredicate(),
        RecentProjectsManagerBase.getInstanceEx(),
      ))
    }

    return result
  }

  private fun createStep(actionGroup: ActionGroup, context: DataContext): ListPopupStep<PopupFactoryImpl.ActionItem> {
    val presentationFactory = PresentationFactory()
    val asyncDataContext: DataContext = Utils.createAsyncDataContext(context)
    val options = ActionPopupOptions.showDisabled()
      .withSpeedSearchFilter(ProjectWidgetSpeedsearchFilter())
    val items = ActionPopupStep.createActionItems(
      actionGroup, asyncDataContext, ActionPlaces.PROJECT_WIDGET_POPUP, presentationFactory, options,
    )
    return ProjectWidgetPopupStep(items, asyncDataContext, presentationFactory, ActionPopupOptions.convertForStep(options, items))
  }
}

private val widgetPositionListenersKey = Key.create<WidgetPositionListeners>("project-widget-position-listeners")

private val ToolbarComboButton.positionListeners: WidgetPositionListeners?
  get() = getClientProperty(widgetPositionListenersKey) as WidgetPositionListeners?

private class WidgetPositionListeners(private val widget: ToolbarComboButton, presentation: Presentation) {
  private val componentListener = object : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent?) {
      updatePosition() // resize shouldn't affect the position, but in theory it might move the project icon
    }

    override fun componentMoved(e: ComponentEvent?) {
      updatePosition()
    }
  }

  private val hierarchyBoundsListener = object : HierarchyBoundsListener {
    override fun ancestorMoved(e: HierarchyEvent?) {
      updatePosition()
    }

    override fun ancestorResized(e: HierarchyEvent?) {
      updatePosition()
    }
  }

  private val propertyChangeListener = PropertyChangeListener { e ->
    if (e.propertyName == "leftIcons") {
      updatePosition()
    }
  }

  private var project: Project? = null

  init {
    setProjectFromPresentation(presentation)
    ClientProperty.put(widget, widgetPositionListenersKey, this)
    widget.addComponentListener(componentListener)
    widget.addHierarchyBoundsListener(hierarchyBoundsListener)
    widget.addPropertyChangeListener(propertyChangeListener)
  }

  fun dispose() {
    widget.removePropertyChangeListener(propertyChangeListener)
    widget.removeHierarchyBoundsListener(hierarchyBoundsListener)
    widget.removeComponentListener(componentListener)
    ClientProperty.remove(widget, widgetPositionListenersKey)
    project?.service<ProjectWidgetGradientLocationService>()?.setProjectWidgetIconCenterRelativeToRootPane(null)
    project = null
  }

  fun setProjectFromPresentation(presentation: Presentation) {
    val oldProject = project
    project = presentation.getClientProperty(projectKey)
    if (oldProject == null && project != null) {
      updatePosition() // now that the project is finally known, we can tell the service the position
    }
  }

  fun updatePosition() {
    val projectIconWidth = widget.leftIcons.firstOrNull()?.iconWidth?.toFloat() ?: 0f
    val offset = widget.let {
      SwingUtilities.convertPoint(it.parent, it.x, it.y, widget.rootPane).x.toFloat() + it.margin.left.toFloat() + projectIconWidth / 2
    }
    project?.service<ProjectWidgetGradientLocationService>()?.setProjectWidgetIconCenterRelativeToRootPane(offset)
  }
}

private class ProjectWidgetSpeedsearchFilter : SpeedSearchFilter<PopupFactoryImpl.ActionItem> {
  override fun getIndexedString(value: PopupFactoryImpl.ActionItem): String {
    val action = value.action as? ProjectToolbarWidgetPresentable ?: return value.text
    return action.projectNameToDisplay + " " + action.projectPathToDisplay.orEmpty() + " " + action.providerPathToDisplay.orEmpty()
  }
}

private class ProjectWidgetRenderer(private val inlineActionsSupport: PopupInlineActionsSupport) : ListCellRenderer<PopupFactoryImpl.ActionItem> {
  override fun getListCellRendererComponent(
    list: JList<out PopupFactoryImpl.ActionItem>?,
    value: PopupFactoryImpl.ActionItem?,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component {
    val activeButtonIndex = if (isSelected && list != null) inlineActionsSupport.getActiveButtonIndex(list) else null
    return createRecentProjectPane(
      value as PopupFactoryImpl.ActionItem, isSelected, projectWidgetSeparator(list, value), index == 0, activeButtonIndex,
    ).apply {
      if (inlineActionsSupport.hasExtraButtons(value)) {
        // An overflow preview would copy the row's trailing controls outside the menu.
        ClientProperty.put(this, ExpandableItemsHandler.RENDERER_DISABLED, true)
      }
    }
  }

  private fun createRecentProjectPane(
    value: PopupFactoryImpl.ActionItem,
    isSelected: Boolean,
    separator: ListSeparator?,
    hideLine: Boolean,
    activeButtonIndex: Int?,
  ): JComponent {
    val action = value.action as ProjectToolbarWidgetPresentable
    val support = inlineActionsSupport
    val savedProjectButtons = if (action is ProjectWidgetProjectAction && support.hasExtraButtons(value)) {
      support.createExtraButtons(value, true, activeButtonIndex ?: -1)
    }
    else emptyList()
    val iconSize = userScaledProjectIconSize()
    val projectIcon = IconUtil.downscaleIconToSize(action.projectIcon, iconSize, iconSize)
    // A fixed icon slot keeps the name and path still when the clear control appears on hover.
    val iconComponent = if (isSelected && savedProjectButtons.size > 1) savedProjectButtons[1]
    else JLabel(if (action is ProjectWidgetProjectAction) IconUtil.toSize(projectIcon, iconSize, iconSize) else projectIcon)
    lateinit var nameLbl: JLabel
    var providerPathLbl: JLabel? = null
    var projectPathLbl: JLabel? = null

    val content = panel {
      customizeSpacingConfiguration(EmptySpacingConfiguration()) {
        row {
          val rowGaps = UnscaledGaps(bottom = PROJECT_WIDGET_ROW_GAP, top = PROJECT_WIDGET_ROW_GAP)

          cell(iconComponent)
            .align(AlignY.TOP)
            .customize(rowGaps.copy(right = 8))

          panel {
            row {
              nameLbl = label(action.projectNameToDisplay)
                .customize(rowGaps)
                .applyToComponent {
                  foreground = if (isSelected) NamedColorUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
                }.component

              val projectStatus = action.status
              if (projectStatus?.statusText != null) {
                label(projectStatus.statusText)
                  .customize(rowGaps.copy(left = 4, right = 8))
                  .applyToComponent {
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getLabelInfoForeground()
                  }
              }

              val hasSubmenuArrow = value.isEnabled && action is ActionGroup && !value.isSubstepSuppressed
              if (projectStatus?.progressText != null || hasSubmenuArrow) {
                // UI DSL is broken for AlignX.RIGHT
                val rightPanel = JPanel()
                rightPanel.layout = BoxLayout(rightPanel, BoxLayout.X_AXIS)
                rightPanel.isOpaque = false

                if (projectStatus?.progressText != null) {
                  val progressLabel = JBLabel(projectStatus.progressText).apply {
                    icon = AnimatedIcon.Default.INSTANCE
                    font = JBFont.smallOrNewUiMedium()
                  }
                  rightPanel.add(progressLabel)
                }

                if (hasSubmenuArrow) {
                  val arrowLabel = JBLabel().apply {
                    icon = if (isSelected) AllIcons.Icons.Ide.MenuArrowSelected else AllIcons.Icons.Ide.MenuArrow
                    border = JBUI.Borders.emptyLeft(6)
                  }
                  rightPanel.add(arrowLabel)
                }

                cell(rightPanel)
                  .align(AlignY.CENTER)
                  .align(AlignX.RIGHT)
              }
            }
            val providerPath = action.providerPathToDisplay
            if (providerPath != null) {
              row {
                providerPathLbl = label(providerPath)
                  .customize(rowGaps)
                  .applyToComponent {
                    icon = action.providerIcon ?: AllIcons.Welcome.RecentProjects.RemoteProject
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getLabelInfoForeground()
                  }.component
              }
            }
            val projectPathToDisplay = action.projectPathToDisplay
            if (projectPathToDisplay != null) {
              row {
                projectPathLbl = label(projectPathToDisplay)
                  .customize(rowGaps)
                  .applyToComponent {
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getLabelInfoForeground()
                  }.component
              }
            }
            action.branchName?.let {
              row {
                label(it)
                  .customize(rowGaps)
                  .applyToComponent {
                    icon = IconUtil.colorize(AllIcons.Vcs.Branch, UIUtil.getLabelInfoForeground(), keepGray = false, keepBrightness = false)
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getLabelInfoForeground()
                  }.component
              }
            }
          }
        }
      }
    }.apply {
      border = JBUI.Borders.empty(PROJECT_WIDGET_CONTENT_PADDING, 0)
      isOpaque = false
    }

    val result = SelectablePanel.wrap(content, JBUI.CurrentTheme.Popup.BACKGROUND)
    PopupUtil.configListRendererFlexibleHeight(result)
    if (support.hasExtraButtons(value)) {
      val buttons = if (action is ProjectWidgetProjectAction) {
        ProjectWidgetPinButton(savedProjectButtons.first(), isSelected)
      }
      else {
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.X_AXIS)
          isOpaque = false
          val extraButtons = support.createExtraButtons(value, isSelected, activeButtonIndex ?: -1)
          if (extraButtons.isEmpty()) {
            add(Box.createHorizontalStrut(buttonWidth() * support.calcExtraButtonsCount(value)))
          }
          else {
            extraButtons.forEach { add(it) }
          }
        }
      }
      result.add(buttons, BorderLayout.EAST)
      // These insets are shared with hit testing and are already scaled.
      @Suppress("UseDPIAwareBorders")
      result.border = EmptyBorder(projectWidgetRowInsets())
    }
    if (isSelected) {
      result.selectionColor = ListPluginComponent.SELECTION_COLOR
    }

    AccessibleContextUtil.setCombinedName(result, nameLbl, " - ", providerPathLbl, " - ", projectPathLbl)
    AccessibleContextUtil.setCombinedDescription(result, nameLbl, " - ", providerPathLbl, " - ", projectPathLbl)
    if (activeButtonIndex != null && activeButtonIndex < support.calcExtraButtonsCount(value)) {
      val buttonText = support.getToolTipText(value, activeButtonIndex)
      result.toolTipText = buttonText
      result.accessibleContext.accessibleName = AccessibleContextUtil.combineAccessibleStrings(
        result.accessibleContext.accessibleName, " - ", buttonText,
      )
    }

    if (separator == null) {
      return result
    }

    val res = NonOpaquePanel(BorderLayout())
    res.border = JBUI.Borders.empty()
    res.add(createProjectWidgetSeparator(separator, hideLine), BorderLayout.NORTH)
    res.add(result, BorderLayout.CENTER)

    AccessibleContextUtil.setName(res, result)
    AccessibleContextUtil.setDescription(res, result)
    res.toolTipText = result.toolTipText

    return res
  }
}


@JvmDefaultWithCompatibility
interface ProjectToolbarWidgetPresentable {
  val projectNameToDisplay: @NlsSafe String
  val providerPathToDisplay: @NlsSafe String? get() = null
  val projectPathToDisplay: @NlsSafe String?
  val branchName: @NlsSafe String?
  val projectIcon: Icon
  val providerIcon: Icon?
  val activationTimestamp: Long?

  @get:ApiStatus.Internal
  val status: ProjectStatus?
    get() = null

  /**
   * Combined info to be used, when only a single-line-label is applicable.
   */
  val nameToDisplayAsText: @NlsSafe String get() = projectNameToDisplay
}

@ApiStatus.Internal
class ProjectStatus(
  val isOpened: Boolean,
  val statusText: @Nls String?,
  val progressText: @Nls String?,
)

private fun MutableList<in Icon>.addGap() {
  add(EmptyIcon.create(BETWEEN_ICONS_GAP, 1))
}

private const val BETWEEN_ICONS_GAP = 9
