// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore

import com.intellij.dvcs.ignore.TransientIgnoredDirectoryIndexExcludePolicy
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.startup.InitProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.impl.ModuleVcsDetector
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitContentRevision
import git4idea.index.getFileStatus
import git4idea.index.isIgnored
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.status.GitRefreshListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the current Git-ignored directory URLs used to keep project indexing within version-controlled content.
 *
 * The snapshot follows Git's ignored-path results. It does not infer from dot-prefixed names or untracked status,
 * and it does not persist module exclusion roots.
 */
@Service(Service.Level.PROJECT)
@ApiStatus.Internal
class GitIgnoredDirectoryExclusions(private val project: Project, private val coroutineScope: CoroutineScope) {
  private val snapshot = AtomicReference(Snapshot(emptyMap(), emptySet(), emptySet()))
  private val active = AtomicBoolean()
  private val rootsChangeScheduled = AtomicBoolean()
  private val repositoryStateMutex = Mutex()

  init {
    val connection = project.messageBus.simpleConnect()
    connection.subscribe(GitRefreshListener.TOPIC, object : GitRefreshListener {
      override fun repositoryUpdated(repository: GitRepository) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
          repositoryStateMutex.withLock {
            if (AdvancedSettings.getBoolean("vcs.process.ignored")) {
              updateRepository(repository)
            }
            else {
              // The highlighting preference may disable ignored results in the shared holder, but indexing still needs them.
              queryAndUpdateRepository(repository)
            }
          }
        }
      }
    })
    connection.subscribe(
      VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
      VcsRepositoryMappingListener { repositoriesChanged() },
    )
    connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
      override fun rootsChanged(event: ModuleRootEvent) {
        if (!event.isCausedByFileTypesChange) {
          // Reapply the raw Git snapshot because a source root may have been added or removed.
          updateSnapshot { it }
        }
      }
    })
  }

  /**
   * Waits for Git's first ignored-path result and publishes the complete initial snapshot before indexing starts.
   */
  suspend fun initialize() {
    repositoryStateMutex.withLock {
      ProjectLevelVcsManager.getInstance(project).awaitInitialization()
      // Initial VCS mapping detection runs on a merging queue, so VCS initialization alone does not guarantee repositories exist.
      // Its startup activity is intentionally disabled in unit tests, where fixtures install repository mappings directly.
      if (!ApplicationManager.getApplication().isUnitTestMode) {
        project.serviceAsync<ModuleVcsDetector>().awaitInitialDetection()
      }
      // Mapping detection publishes through a separate delayed repository-manager update; force and await that collection refresh.
      project.serviceAsync<VcsRepositoryManager>().ensureUpToDate(force = true)

      val repositories = GitRepositoryManager.getInstance(project).repositories
      val initialSnapshot = LinkedHashMap<String, Set<FilePath>>(repositories.size)
      try {
        for (repository in repositories) {
          initialSnapshot[repository.root.url] = queryIgnoredDirectories(repository)
        }
      }
      catch (e: VcsException) {
        // A partial snapshot could hide files in repositories whose Git status is still unknown.
        LOG.warn("Unable to initialize Git-ignored directory exclusions", e)
        updateSnapshot { emptyMap() }
        active.set(true)
        return@withLock
      }

      updateSnapshot { initialSnapshot }
      active.set(true)
    }
  }

  /** Returns a defensive copy for [DirectoryIndexExcludePolicy]. */
  fun excludedUrls(): Array<String> = snapshot.get().urls.copyOf()

  /** Returns whether the dynamic policy is the reason [file] is excluded. */
  fun isDynamicallyExcluded(file: VirtualFile): Boolean = snapshot.get().isExcluded(file.path)

  private fun updateRepository(repository: GitRepository) {
    if (!isCurrentRepository(repository)) return
    if (!repository.untrackedFilesHolder.hasAuthoritativeRefreshResult) return

    val rootUrl = repository.root.url
    val ignoredDirectories = rawIgnoredDirectories(repository)
    updateSnapshot { current ->
      // Mapping updates may remove this repository while its holder refresh is computing the replacement.
      if (isCurrentRepository(repository)) current + (rootUrl to ignoredDirectories) else current
    }
  }

  private suspend fun queryAndUpdateRepository(repository: GitRepository) {
    if (!isCurrentRepository(repository)) return

    val ignoredDirectories = try {
      queryIgnoredDirectories(repository)
    }
    catch (e: VcsException) {
      LOG.warn("Unable to refresh Git-ignored directory exclusions for ${repository.root.presentableUrl}", e)
      return
    }
    if (!isCurrentRepository(repository)) return

    updateSnapshot { current ->
      if (isCurrentRepository(repository)) current + (repository.root.url to ignoredDirectories) else current
    }
  }

  private fun isCurrentRepository(repository: GitRepository): Boolean {
    return GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(repository.root) === repository
  }

  private fun repositoriesChanged() {
    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
      repositoryStateMutex.withLock {
        refreshRepositories()
      }
    }
  }

  private suspend fun refreshRepositories() {
    val repositories = GitRepositoryManager.getInstance(project).repositories
    if (AdvancedSettings.getBoolean("vcs.process.ignored") &&
        repositories.all { repository -> repository.untrackedFilesHolder.hasAuthoritativeRefreshResult }) {
      replaceRepositories(repositories)
    }
    else {
      // Repository mappings affect path ownership, so bypass a holder configured not to collect ignored paths.
      queryAndReplaceRepositories(repositories)
    }
  }

  private fun replaceRepositories(repositories: Collection<GitRepository>) {
    val current = snapshot.get().byRepository
    val replacement = repositories.associate { repository ->
      val rootUrl = repository.root.url
      val ignoredDirectories = if (repository.ignoredFilesHolder.initialized &&
                                   repository.untrackedFilesHolder.hasAuthoritativeRefreshResult) {
        rawIgnoredDirectories(repository)
      }
      else {
        // Keep the last raw Git result. Snapshot publication reapplies current repository ownership.
        current[rootUrl].orEmpty()
      }
      rootUrl to ignoredDirectories
    }
    updateSnapshot { current ->
      if (areCurrentRepositories(repositories)) replacement else current
    }
  }

  private suspend fun queryAndReplaceRepositories(repositories: Collection<GitRepository>) {
    if (!areCurrentRepositories(repositories)) return

    val queriedDirectories = LinkedHashMap<String, Set<FilePath>?>(repositories.size)
    for (repository in repositories) {
      val rootUrl = repository.root.url
      queriedDirectories[rootUrl] = try {
        queryIgnoredDirectories(repository)
      }
      catch (e: VcsException) {
        LOG.warn("Unable to refresh Git-ignored directory exclusions for ${repository.root.presentableUrl}", e)
        null
      }
    }
    if (!areCurrentRepositories(repositories)) return

    updateSnapshot { current ->
      if (!areCurrentRepositories(repositories)) {
        current
      }
      else {
        repositories.associate { repository ->
          val rootUrl = repository.root.url
          val ignoredDirectories = queriedDirectories[rootUrl]
            // Keep the latest raw Git result on failure. Snapshot publication reapplies current repository ownership.
            ?: current[rootUrl].orEmpty()
          rootUrl to ignoredDirectories
        }
      }
    }
  }

  private fun areCurrentRepositories(repositories: Collection<GitRepository>): Boolean {
    val repositoryManager = GitRepositoryManager.getInstance(project)
    return repositoryManager.repositories.size == repositories.size &&
           repositories.all { repository -> repositoryManager.getRepositoryForRootQuick(repository.root) === repository }
  }

  private fun updateSnapshot(transform: (Map<String, Set<FilePath>>) -> Map<String, Set<FilePath>>) {
    while (true) {
      val current = snapshot.get()
      val replacementByRepository = transform(current.byRepository)
      val sourceRoots = sourceRootPaths()
      val vcsRoots = vcsRootPaths()
      if (replacementByRepository == current.byRepository &&
          sourceRoots == current.sourceRoots &&
          vcsRoots == current.vcsRoots) return

      val replacement = Snapshot(replacementByRepository, sourceRoots, vcsRoots)

      if (snapshot.compareAndSet(current, replacement)) {
        scheduleRootsChange()
        return
      }
    }
  }

  private fun scheduleRootsChange() {
    if (!active.get() || !rootsChangeScheduled.compareAndSet(false, true)) return

    ApplicationManager.getApplication().invokeLater({
      rootsChangeScheduled.set(false)
      if (project.isDisposed) return@invokeLater

      ApplicationManager.getApplication().runWriteAction {
        ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
          {},
          RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED,
        )
      }
    }, project.disposed)
  }

  private suspend fun queryIgnoredDirectories(repository: GitRepository): Set<FilePath> {
    val statuses = withContext(Dispatchers.IO) {
      coroutineToIndicator {
        getFileStatus(project, repository.root, emptyList(), false, true, true)
      }
    }
    val ignoredPaths = statuses.asSequence()
      .filter { isIgnored(it.index) }
      .map { GitContentRevision.createPath(repository.root, it.path, it.path.endsWith("/")) }
    return ignoredPaths.filter(FilePath::isDirectory).toSet()
  }

  private fun rawIgnoredDirectories(repository: GitRepository): Set<FilePath> {
    return repository.untrackedFilesHolder.rawIgnoredFilePaths.filterTo(mutableSetOf(), FilePath::isDirectory)
  }

  private fun vcsRootPaths(): Set<String> {
    return ProjectLevelVcsManager.getInstance(project).allVcsRoots.mapTo(mutableSetOf()) { it.path.path }
  }

  private fun sourceRootPaths(): Set<String> {
    return runReadActionBlocking {
      ProjectRootManager.getInstance(project).contentSourceRoots.mapTo(mutableSetOf()) { it.path }
    }
  }

  private data class Snapshot(
    val byRepository: Map<String, Set<FilePath>>,
    val sourceRoots: Set<String>,
    val vcsRoots: Set<String>,
  ) {
    val urls: Array<String> = byRepository.asSequence()
      .flatMap { (repositoryUrl, ignoredPaths) ->
        val repositoryPath = VfsUtilCore.urlToPath(repositoryUrl)
        val nestedVcsRoots = vcsRoots.filter { vcsRoot ->
          vcsRoot != repositoryPath && FileUtil.isAncestor(repositoryPath, vcsRoot, false)
        }
        ignoredPaths.asSequence().filterNot { ignored ->
          // Do not let an outer repository hide a nested configured VCS root or content owned by it.
          nestedVcsRoots.any { vcsRoot ->
            FileUtil.isAncestor(ignored.path, vcsRoot, false) || FileUtil.isAncestor(vcsRoot, ignored.path, false)
          }
        }
      }
      // A source root is an explicit request to index that directory, even when Git ignores generated content there.
      .filterNot { ignored -> sourceRoots.any { sourceRoot -> FileUtil.isAncestor(ignored.path, sourceRoot, false) } }
      .map { VfsUtilCore.pathToUrl(it.path) }
      .distinct()
      .sorted()
      .toList()
      .toTypedArray()

    fun isExcluded(path: String): Boolean {
      return urls.any { url -> FileUtil.isAncestor(VfsUtilCore.urlToPath(url), path, false) }
    }
  }

  private companion object {
    val LOG = logger<GitIgnoredDirectoryExclusions>()
  }
}

/** Initializes Git-ignored directory exclusions before the workspace file index is built. */
internal class GitIgnoredDirectoryExclusionsInitializer : InitProjectActivity {
  override suspend fun run(project: Project) {
    project.service<GitIgnoredDirectoryExclusions>().initialize()
  }
}

/** Supplies the current Git-ignored directory snapshot to the project file index. */
internal class GitIgnoredDirectoryIndexExcludePolicy(
  private val exclusions: GitIgnoredDirectoryExclusions,
) : DirectoryIndexExcludePolicy, TransientIgnoredDirectoryIndexExcludePolicy {
  constructor(project: Project) : this(project.service<GitIgnoredDirectoryExclusions>())

  override fun getExcludeUrlsForProject(): Array<String> = exclusions.excludedUrls()

  override fun isDynamicallyExcluded(file: VirtualFile): Boolean = exclusions.isDynamicallyExcluded(file)
}
