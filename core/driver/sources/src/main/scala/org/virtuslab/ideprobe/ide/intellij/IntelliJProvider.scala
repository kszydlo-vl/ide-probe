package org.virtuslab.ideprobe.ide.intellij

import java.nio.file.{Files, Path}
import java.util.stream.{Collectors, Stream => JStream}
import org.virtuslab.ideprobe.Extensions._
import org.virtuslab.ideprobe.IdeProbePaths
import org.virtuslab.ideprobe.config.{DependenciesConfig, DriverConfig, IntellijConfig}
import org.virtuslab.ideprobe.dependencies._
import org.virtuslab.ideprobe.dependencies.Resource._

sealed trait IntelliJProvider {
  def version: IntelliJVersion
  def plugins: Seq[Plugin]
  def config: DriverConfig
  def paths: IdeProbePaths
  def withConfig(config: DriverConfig): IntelliJProvider
  def withPaths(paths: IdeProbePaths): IntelliJProvider
  def withPlugins(plugins: Plugin*): IntelliJProvider
  def setup(): InstalledIntelliJ

  // This method differentiates plugins by their root entries in zip
  // assuming that plugins with same root entries are the same plugin
  // and only installs last occurrance of such plugin in the list
  // in case of duplicates.
  protected def installPlugins(dependencies: DependencyProvider, plugins: Seq[Plugin], root: Path): Unit = {
    val allPlugins = InternalPlugins.probePluginForIntelliJ(version) +: plugins

    case class PluginArchive(plugin: Plugin, archive: Resource.Archive) {
      val rootEntries: Set[String] = archive.rootEntries.toSet
    }
    val targetDir = root.resolve("plugins")
    val archives = withParallel[Plugin, PluginArchive](allPlugins)(_.map { plugin =>
      val file = dependencies.fetch(plugin)
      PluginArchive(plugin, file.toArchive)
    })

    val distinctPlugins = archives.reverse.distinctBy(_.rootEntries).reverse

    parallel(distinctPlugins).forEach { pluginArchive =>
      pluginArchive.archive.extractTo(targetDir)
      println(s"Installed ${pluginArchive.plugin}")
    }
  }

  private def withParallel[A, B](s: Seq[A])(f: JStream[A] => JStream[B]): Seq[B] = {
    f(parallel(s)).collect(Collectors.toList[B]).asScala.toList
  }

  private def parallel[A](s: Seq[A]) = {
    s.asJava.parallelStream
  }
}

final class ExistingIntelliJ(
    dependencies: DependencyProvider,
    path: Path,
    override val plugins: Seq[Plugin],
    val paths: IdeProbePaths,
    val config: DriverConfig
) extends IntelliJProvider {
  override val version = IntelliJVersionResolver.version(path)
  override def withConfig(config: DriverConfig): ExistingIntelliJ =
    new ExistingIntelliJ(dependencies, path, plugins, paths, config)

  override def withPaths(paths: IdeProbePaths): ExistingIntelliJ =
    new ExistingIntelliJ(dependencies, path, plugins, paths, config)

  override def withPlugins(plugins: Plugin*): ExistingIntelliJ =
    new ExistingIntelliJ(dependencies, path, this.plugins ++ plugins, paths, config)

  override def setup(): InstalledIntelliJ = {
    val pluginsDir = path.resolve("plugins")
    val backupDir = Files.createTempDirectory(path, "plugins")
    pluginsDir.copyDir(backupDir)
    installPlugins(dependencies, plugins, path)

    new LocalIntelliJ(path, paths, config, backupDir)
  }
}

final class IntelliJFactory(
    dependencies: DependencyProvider,
    override val plugins: Seq[Plugin],
    override val version: IntelliJVersion,
    val paths: IdeProbePaths,
    val config: DriverConfig
) extends IntelliJProvider {
  override def withConfig(config: DriverConfig): IntelliJFactory =
    new IntelliJFactory(dependencies, plugins, version, paths, config)

  override def withPaths(paths: IdeProbePaths): IntelliJFactory =
    new IntelliJFactory(dependencies, plugins, version, paths, config)

  override def withPlugins(plugins: Plugin*): IntelliJProvider =
    new IntelliJFactory(dependencies, plugins = this.plugins ++ plugins, version, paths, config)

  override def setup(): InstalledIntelliJ = {
    val root = createInstanceDirectory(version)

    installIntelliJ(version, root)
    installPlugins(dependencies, plugins, root)

    new DownloadedIntelliJ(root, paths, config)
  }

  private def createInstanceDirectory(version: IntelliJVersion): Path = {
    val path = paths.instances.createTempDirectory(s"intellij-instance-${version.build}-")

    Files.createDirectories(path)
  }

  private def installIntelliJ(version: IntelliJVersion, root: Path): Unit = {
    println(s"Installing $version")
    val file = dependencies.fetch(version)
    file.toArchive.extractTo(root)
    root.resolve("bin/linux/fsnotifier64").makeExecutable()
  }
}

object IntelliJProvider {
  val Default =
    new IntelliJFactory(
      dependencies = new DependencyProvider(
        new IntelliJDependencyProvider(Seq(IntelliJZipResolver.Community), ResourceProvider.Default),
        new PluginDependencyProvider(Seq(PluginResolver.Official), ResourceProvider.Default)
      ),
      plugins = Seq.empty,
      version = IntelliJVersion.Latest,
      paths = IdeProbePaths.Default,
      config = DriverConfig()
    )

  def from(
      intelliJConfig: IntellijConfig,
      resolversConfig: DependenciesConfig.Resolvers,
      paths: IdeProbePaths,
      driverConfig: DriverConfig
  ): IntelliJProvider = {
    val intelliJResolver = IntelliJZipResolver.from(resolversConfig.intellij)
    val pluginResolver = PluginResolver.from(resolversConfig.plugins)
    val resourceProvider = ResourceProvider.from(paths)
    val intelliJDependencyProvider = new IntelliJDependencyProvider(Seq(intelliJResolver), resourceProvider)
    val pluginDependencyProvider = new PluginDependencyProvider(Seq(pluginResolver), resourceProvider)
    val dependencyProvider = new DependencyProvider(intelliJDependencyProvider, pluginDependencyProvider)
    intelliJConfig match {
      case IntellijConfig.Default(version, plugins) =>
        new IntelliJFactory(dependencyProvider, plugins, version, paths, driverConfig)
      case IntellijConfig.Existing(path, plugins) =>
        new ExistingIntelliJ(dependencyProvider, path, plugins, paths, driverConfig)
    }
  }
}