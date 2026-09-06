// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.headertoolbar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

internal class ProjectWidgetInlineActionsTest {
  @Test
  fun `pin stays on the right and clear replaces the leading icon below the heading`() {
    val bounds = projectWidgetButtonBounds(Rectangle(10, 20, 200, 100), 16, true, 24, Insets(4, 8, 4, 8), 24, 2, 20, 8)
    assertEquals(listOf(Rectangle(176, 74, 24, 16), Rectangle(18, 56, 20, 20)), bounds)
    assertTrue(bounds[0].contains(Point(190, 74)))
    assertFalse(bounds[1].contains(Point(190, 74)))
    assertTrue(bounds[1].contains(Point(28, 66)))
    for (point in listOf(Point(190, 30), Point(100, 74), Point(201, 74), Point(17, 66), Point(28, 55), Point(28, 76), Point(190, 120))) {
      assertFalse(bounds.any { it.contains(point) })
    }
  }

  @Test
  fun `scaled geometry keeps the same drawing and hit regions`() {
    val normal = projectWidgetButtonBounds(Rectangle(10, 20, 200, 100), 16, true, 24, Insets(4, 8, 4, 8), 24, 2, 20, 8)
    val scaled = projectWidgetButtonBounds(Rectangle(20, 40, 400, 200), 32, true, 48, Insets(8, 16, 8, 16), 48, 4, 40, 16)
    assertEquals(normal.map { Rectangle(it.x * 2, it.y * 2, it.width * 2, it.height * 2) }, scaled)
  }

  @Test
  fun `pin position is identical with and without a clear control`() {
    val cell = Rectangle(0, 0, 200, 80)
    val pinOnly = projectWidgetButtonBounds(cell, 16, false, controlWidth = 24, controlPadding = 2, iconSize = 20, iconTopInset = 8)
    val withClear = projectWidgetButtonBounds(cell, 16, true, controlWidth = 24, controlPadding = 2, iconSize = 20, iconTopInset = 8)
    assertEquals(listOf(Rectangle(174, 32, 24, 16)), pinOnly)
    assertEquals(pinOnly.single(), withClear.first())
    assertEquals(Rectangle(0, 8, 20, 20), withClear[1])
    assertTrue(projectWidgetButtonBounds(Rectangle(0, 0, 200, 10), 16, true, controlWidth = 24,
                                        iconSize = 20, iconTopInset = 8).isEmpty())
  }
}
