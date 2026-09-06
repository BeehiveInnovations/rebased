// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.ide.userScaledProjectIconSize
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.ui.GroupHeaderSeparator
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.PopupFactoryImpl.ActionItem
import com.intellij.ui.popup.list.INLINE_BUTTON_MARKER
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.ListPopupModel
import com.intellij.ui.popup.list.PopupInlineActionsSupport
import com.intellij.ui.popup.list.PopupInlineActionsSupportImpl
import com.intellij.ui.popup.list.PopupInlineActionsSupportProvider
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

private val buttonPadding: Int get() = JBUI.scale(4)

/** Unscaled space around row content, shared with the leading icon's hit target. */
internal const val PROJECT_WIDGET_CONTENT_PADDING: Int = 6

/** Unscaled vertical gap around each label and the project icon. */
internal const val PROJECT_WIDGET_ROW_GAP: Int = 2

/** Uses stock action execution and keyboard navigation with the project rows' leading and trailing controls. */
internal class ProjectWidgetPopupStep(
  items: List<ActionItem>,
  context: DataContext,
  presentationFactory: PresentationFactory,
  options: ActionPopupOptions,
) : ActionPopupStep(items, null, { context }, ActionPlaces.PROJECT_WIDGET_POPUP, presentationFactory, options),
    PopupInlineActionsSupportProvider {
  override fun createInlineActionsSupport(popup: ListPopupImpl): PopupInlineActionsSupport = ProjectWidgetInlineActionsSupport(popup)
}

/** Gives saved-project controls circular hover areas and matching hit targets, while the popup owns their actions. */
private class ProjectWidgetInlineActionsSupport(
  private val popup: ListPopupImpl,
  private val delegate: PopupInlineActionsSupport = PopupInlineActionsSupportImpl(popup),
) : PopupInlineActionsSupport by delegate {
  override fun createExtraButtons(value: Any?, isSelected: Boolean, activeButtonIndex: Int): List<JComponent> {
    if (value !is ActionItem || value.action !is ProjectWidgetProjectAction) {
      return delegate.createExtraButtons(value, isSelected, activeButtonIndex)
    }
    if (!isSelected) return emptyList()
    return value.inlineItems.mapIndexed { index, item ->
      val size = if (index == 0) JBUI.CurrentTheme.List.rowHeight() else userScaledProjectIconSize()
      val label = JLabel(IconUtil.toSize(item.getIcon(true), size, size))
      label.putClientProperty(INLINE_BUTTON_MARKER, true)
      SelectablePanel.wrap(label).apply {
        isOpaque = false
        selectionArc = size
        selectionColor = if (index == activeButtonIndex) JBUI.CurrentTheme.List.buttonHoverBackground() else null
      }
    }
  }

  override fun calcButtonIndex(element: Any?, point: Point): Int? {
    if (element !is ActionItem || element.action !is ProjectWidgetProjectAction) return delegate.calcButtonIndex(element, point)
    val list = popup.list
    val index = list.selectedIndex
    val cellBounds = list.getCellBounds(index, index) ?: return null
    if (!cellBounds.contains(point)) return null

    val separatorHeight = projectWidgetSeparator(list, element)?.let {
      createProjectWidgetSeparator(it, index == 0).preferredSize.height
    } ?: 0
    val buttons = createExtraButtons(element, true, -1)
    val pin = buttons.firstOrNull() ?: return null
    return projectWidgetButtonBounds(cellBounds, pin.preferredSize.height, buttons.size > 1, separatorHeight, projectWidgetRowInsets(),
                                     controlWidth = pin.preferredSize.width)
      .indexOfFirst { it.contains(point) }.takeIf { it >= 0 }
  }
}

/** Reserves the same trailing slot in every section, even when its pin is hidden. */
internal class ProjectWidgetPinButton(private val button: JComponent, showButton: Boolean) : JPanel(null) {
  init {
    isOpaque = false
    button.isVisible = showButton
    add(button)
  }

  override fun getPreferredSize(): Dimension {
    val padding = buttonPadding
    return Dimension(button.preferredSize.width + padding * 2, button.preferredSize.height + padding * 2)
  }

  override fun getMinimumSize(): Dimension = preferredSize

  override fun doLayout() {
    button.bounds = projectWidgetButtonBounds(Rectangle(0, 0, width, height), button.preferredSize.height, false,
                                             controlWidth = button.preferredSize.width).firstOrNull()
                    ?: Rectangle()
  }
}

/**
 * Returns the trailing pin and optional leading clear target, in native inline-action order.
 * The clear target occupies the project icon's fixed slot; it never changes the pin's vertical position.
 * All metrics are already scaled, and headings and padding stay outside the click targets.
 */
internal fun projectWidgetButtonBounds(
  cellBounds: Rectangle,
  pinHeight: Int,
  hasRemove: Boolean,
  separatorHeight: Int = 0,
  insets: Insets = Insets(0, 0, 0, 0),
  controlWidth: Int = JBUI.CurrentTheme.List.rowHeight(),
  controlPadding: Int = buttonPadding,
  iconSize: Int = userScaledProjectIconSize(),
  iconTopInset: Int = JBUI.scale(PROJECT_WIDGET_CONTENT_PADDING + PROJECT_WIDGET_ROW_GAP),
): List<Rectangle> {
  val bodyHeight = cellBounds.height - separatorHeight - insets.top - insets.bottom
  if (bodyHeight < pinHeight + controlPadding * 2 ||
      cellBounds.width - insets.left - insets.right < controlWidth + controlPadding * 2) {
    return emptyList()
  }
  val x = cellBounds.x + cellBounds.width - insets.right - controlWidth - controlPadding
  val bodyTop = cellBounds.y + separatorHeight + insets.top
  return buildList {
    add(Rectangle(x, bodyTop + (bodyHeight - pinHeight) / 2, controlWidth, pinHeight))
    if (hasRemove) {
      add(Rectangle(cellBounds.x + insets.left, bodyTop + iconTopInset, iconSize, iconSize))
    }
  }
}

/** Matches the popup's flexible-height row insets and its existing inline-action right edge without rescaling. */
internal fun projectWidgetRowInsets(): Insets {
  val inner = JBUI.CurrentTheme.Popup.Selection.innerInsets()
  return Insets(inner.top, inner.left + JBUI.CurrentTheme.Popup.Selection.LEFT_RIGHT_INSET.get(), inner.bottom,
                PopupListElementRenderer.getListCellPadding().right)
}

/** Uses the filtered model's heading, which may have moved to a different row during speed search. */
internal fun projectWidgetSeparator(list: JList<*>?, value: ActionItem): ListSeparator? {
  val model = list?.model as? ListPopupModel<*> ?: return null
  return if (model.isSeparatorAboveOf(value)) ListSeparator(model.getCaptionAboveOf(value)) else null
}

/** Creates the same heading component for drawing and for measuring the project body below it. */
internal fun createProjectWidgetSeparator(separator: ListSeparator, hideLine: Boolean): JComponent {
  val header = GroupHeaderSeparator(JBUI.CurrentTheme.Popup.separatorLabelInsets())
  header.caption = separator.text
  header.setHideLine(hideLine)
  return JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty()
    isOpaque = true
    background = JBUI.CurrentTheme.Popup.BACKGROUND
    add(header)
  }
}
