// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.InitProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil

class ProjectLevelVcsManagerInitializationTest : HeavyPlatformTestCase() {
  private lateinit var projectRoot: VirtualFile
  private var projectWasInitializedDuringIgnoredRootCheck: Boolean? = null
  private var ignoredRootResultBeforeProjectInitialization: Boolean? = null

  override fun setUpProject() {
    val root = FileUtil.toSystemIndependentName(VcsTestUtil.getTestDataPath() + "/vcs/directoryMappings/")
    projectRoot = createTestProjectStructure(null, root, false, tempDir)

    val activity = object : InitProjectActivity {
      override suspend fun run(project: Project) {
        projectWasInitializedDuringIgnoredRootCheck = project.isInitialized
        ignoredRootResultBeforeProjectInitialization =
          (ProjectLevelVcsManager.getInstance(project) as ProjectLevelVcsManagerImpl).isIgnoredFileRoot(projectRoot)
      }
    }
    ExtensionPointName<InitProjectActivity>("com.intellij.projectPreInit").point
      .registerExtension(activity, LoadingOrder.FIRST, testRootDisposable)

    myProject = PlatformTestUtil.loadAndOpenProject(projectRoot.toNioPath().resolve("directoryMappings.ipr"), testRootDisposable)
  }

  fun testIgnoredFileRootCheckBeforeProjectInitialization() {
    assertEquals(false, projectWasInitializedDuringIgnoredRootCheck)
    assertEquals(false, ignoredRootResultBeforeProjectInitialization)
  }
}
