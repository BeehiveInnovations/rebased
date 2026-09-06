// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.popup.list

import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.util.NlsActions.ActionText
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.popup.ActionPopupStep
import java.awt.Point
import java.awt.event.InputEvent
import javax.swing.JComponent
import javax.swing.JList

internal interface PopupInlineActionsSupport {

  fun calcExtraButtonsCount(element: Any?): Int

  fun calcButtonIndex(element: Any?, point: Point): Int?

  fun getToolTipText(element: Any?, index: Int): @ActionText String?

  fun getKeepPopupOnPerform(element: Any?, index: Int): KeepPopupOnPerform

  fun performAction(element: Any?, index: Int, event: InputEvent?)

  fun createExtraButtons(value: Any?, isSelected: Boolean, activeButtonIndex: Int): List<JComponent>

  fun isMoreButton(element: Any?, index: Int): Boolean


  fun hasExtraButtons(element: Any?): Boolean = calcExtraButtonsCount(element) > 0
  fun getActiveButtonIndex(list: JList<*>): Int? = (list as? ListPopupImpl.ListWithInlineButtons)?.selectedButtonIndex
}

/** Lets a popup step supply its row geometry while retaining the standard inline action handling. */
internal interface PopupInlineActionsSupportProvider {
  /** Called during popup construction; implementations must not access the popup's own support field. */
  fun createInlineActionsSupport(popup: ListPopupImpl): PopupInlineActionsSupport
}

internal fun createSupport(popup: ListPopupImpl): PopupInlineActionsSupport {
  if (!ExperimentalUI.isNewUI()) return Empty
  (popup.listStep as? PopupInlineActionsSupportProvider)?.let { return it.createInlineActionsSupport(popup) }
  if (popup.listStep is ActionPopupStep) return PopupInlineActionsSupportImpl(popup)
  return NonActionsPopupInlineSupport(popup)
}

private val Empty = object : PopupInlineActionsSupport {
  override fun calcExtraButtonsCount(element: Any?): Int = 0
  override fun calcButtonIndex(element: Any?, point: Point): Int? = null
  override fun getToolTipText(element: Any?, index: Int): String? = null
  override fun getKeepPopupOnPerform(element: Any?, index: Int): KeepPopupOnPerform = KeepPopupOnPerform.Always
  override fun performAction(element: Any?, index: Int, event: InputEvent?) {}
  override fun createExtraButtons(value: Any?, isSelected: Boolean, activeButtonIndex: Int): List<JComponent> = emptyList()
  override fun isMoreButton(element: Any?, index: Int): Boolean = false

  override fun getActiveButtonIndex(list: JList<*>): Int? = null
}
