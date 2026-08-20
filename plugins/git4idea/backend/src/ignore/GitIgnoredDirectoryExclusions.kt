// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore

import com.intellij.dvcs.ignore.TransientIgnoredDirectoryIndexExcludePolicy
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
import com.intellij.openapi.vcs.impl.DefaultVcsRootPolicy
import com.intellij.openapi.vcs.impl.ModuleVcsDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitContentRevision
import git4idea.GitUtil
import git4idea.GitVcs
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
   * Publishes a read-only Git snapshot before indexing without activating the project's VCS pipeline.
   *
   * VCS startup must wait until the project is open because its dirty-scope manager captures that lifecycle state. This initializer therefore
   * reads persisted mappings and Git metadata directly, then lets normal post-open repository events replace the provisional snapshot.
   */
  suspend fun initialize() {
    repositoryStateMutex.withLock {
      if (!TrustedProjects.isProjectTrusted(project)) {
        replaceInitialSnapshot(emptyMap(), emptySet())
        active.set(true)
        return@withLock
      }

      val roots = initialGitRoots()
      val initialSnapshot = LinkedHashMap<String, Set<FilePath>>(roots.gitRoots.size)
      for (root in roots.gitRoots) {
        initialSnapshot[root.url] = try {
          queryIgnoredDirectories(root)
        }
        catch (e: VcsException) {
          // A failed root stays in the ownership snapshot so an outer repository cannot hide it.
          LOG.warn("Unable to initialize Git-ignored directory exclusions for ${root.presentableUrl}", e)
          emptySet()
        }
      }

      replaceInitialSnapshot(initialSnapshot, roots.vcsRootPaths)
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
      queryIgnoredDirectories(repository.root)
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
        queryIgnoredDirectories(repository.root)
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

  private fun replaceInitialSnapshot(byRepository: Map<String, Set<FilePath>>, vcsRoots: Set<String>) {
    updateSnapshot(
      vcsRoots = { vcsRoots },
      transform = { byRepository },
    )
  }

  private fun updateSnapshot(transform: (Map<String, Set<FilePath>>) -> Map<String, Set<FilePath>>) {
    updateSnapshot(
      vcsRoots = ::vcsRootPaths,
      transform = transform,
    )
  }

  private fun updateSnapshot(
    vcsRoots: () -> Set<String>,
    transform: (Map<String, Set<FilePath>>) -> Map<String, Set<FilePath>>,
  ) {
    while (true) {
      val current = snapshot.get()
      val replacementByRepository = transform(current.byRepository)
      val replacementSourceRoots = sourceRootPaths()
      val replacementVcsRoots = vcsRoots()
      if (replacementByRepository == current.byRepository &&
          replacementSourceRoots == current.sourceRoots &&
          replacementVcsRoots == current.vcsRoots) return

      val replacement = Snapshot(replacementByRepository, replacementSourceRoots, replacementVcsRoots)

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

  private suspend fun queryIgnoredDirectories(root: VirtualFile): Set<FilePath> {
    val statuses = withContext(Dispatchers.IO) {
      coroutineToIndicator {
        getFileStatus(project, root, emptyList(), false, true, true)
      }
    }
    val ignoredPaths = statuses.asSequence()
      .filter { isIgnored(it.index) }
      .map { GitContentRevision.createPath(root, it.path, it.path.endsWith("/")) }
    return ignoredPaths.filter(FilePath::isDirectory).toSet()
  }

  private fun rawIgnoredDirectories(repository: GitRepository): Set<FilePath> {
    return repository.untrackedFilesHolder.rawIgnoredFilePaths.filterTo(mutableSetOf(), FilePath::isDirectory)
  }

  /**
   * Resolves the Git roots needed for the pre-index snapshot from persisted project state only.
   *
   * This mirrors direct-mapping priority and checks only each project root and its ancestors. It never scans descendants, persists mappings,
   * creates repositories, or starts VCS initialization.
   */
  @Suppress("UnstableApiUsage")
  private fun initialGitRoots(): InitialRoots {
    val vcsManager = ProjectLevelVcsManager.getInstance(project)
    val mappings = vcsManager.directoryMappings
    val localFileSystem = LocalFileSystem.getInstance()
    val directMappings = mappings.asSequence()
      .filterNot { it.isDefaultMapping }
      .mapNotNull { mapping ->
        localFileSystem.findFileByPath(mapping.directory)
          ?.takeIf(VirtualFile::isDirectory)
          ?.let { mapping to it }
      }
      .toList()
    val directMappingDirectories = directMappings.mapTo(mutableSetOf()) { it.second }

    val gitRoots = directMappings.asSequence()
      .filter { it.first.vcs == GitVcs.NAME }
      .map { it.second }
      .filter { it.isDirectory && GitUtil.isGitRoot(it.toNioPath()) }
      .toCollection(linkedSetOf())

    val hasDefaultGitMapping = mappings.any { it.isDefaultMapping && it.vcs == GitVcs.NAME }
    val needsInitialDetection = project.getService(ModuleVcsDetector::class.java)
      .needInitialDetection(PropertiesComponent.getInstance(project), vcsManager)
    if (hasDefaultGitMapping || needsInitialDetection) {
      for (projectRoot in DefaultVcsRootPolicy.getInstance(project).getDefaultVcsRoots()) {
        findGitRoot(projectRoot, directMappingDirectories)?.let(gitRoots::add)
      }
    }

    val vcsRootPaths = directMappingDirectories.mapTo(mutableSetOf(), VirtualFile::getPath)
    gitRoots.mapTo(vcsRootPaths, VirtualFile::getPath)
    return InitialRoots(gitRoots, vcsRootPaths)
  }

  /** Returns the nearest Git root above [projectRoot], unless a direct mapping owns the path first. */
  @Suppress("UnstableApiUsage")
  private fun findGitRoot(projectRoot: VirtualFile, directMappingDirectories: Set<VirtualFile>): VirtualFile? {
    return generateSequence(projectRoot) { it.parent }
      .takeWhile { it !in directMappingDirectories }
      .firstOrNull { GitUtil.isGitRoot(it.toNioPath()) }
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

  /** Git roots to query and all resolved VCS roots that protect nested ownership during the provisional snapshot. */
  private data class InitialRoots(
    val gitRoots: Set<VirtualFile>,
    val vcsRootPaths: Set<String>,
  )

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
