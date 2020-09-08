package org.virtuslab.ideprobe

import java.nio.file.Path

import com.intellij.remoterobot.RemoteRobot
import org.virtuslab.ideprobe.RobotExtensions._
import org.virtuslab.ideprobe.jsonrpc.JsonRpc.{Handler, Method}
import org.virtuslab.ideprobe.jsonrpc.{JsonRpcConnection, JsonRpcEndpoint}
import org.virtuslab.ideprobe.protocol._

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.{Failure, Try}

final class ProbeDriver(
  protected val connection: JsonRpcConnection,
  val robot: RemoteRobot
)(implicit protected val ec: ExecutionContext) extends JsonRpcEndpoint {
  protected val handler: Handler = (_, _) => Failure(new Exception("Receiving requests is not supported"))

  def pid(): Long = send(Endpoints.PID)

  def listOpenProjects(): Seq[ProjectRef] = send(Endpoints.ListOpenProjects)

  def openProjectWithName(path: Path, expectedName: String): ProjectRef = {
    val expectedRef = ProjectRef(expectedName)
    val projectRef = openProject(path)

    @tailrec def attempt(attempts: Int): ProjectRef = {
      awaitIdle()
      val open = listOpenProjects()
      if (open.contains(expectedRef)) {
        expectedRef
      } else {
        if (attempts == 0) {
          throw new RuntimeException(
            s"Failed to open project $expectedName, open projects are: ${open.mkString("[", ", ", "]")}"
          )
        } else {
          attempt(attempts - 1)
        }
      }
    }

    if (projectRef == expectedRef) {
      projectRef
    } else {
      attempt(attempts = 10)
    }
  }

  def openProjectWithModules(path: Path, expectedModules: Set[String]): ProjectRef = {
    val projectRef = openProject(path)
    val modules = projectModel(projectRef).modules.map(_.name).toSet

    @tailrec def attempt(attempts: Int): ProjectRef = {
      awaitIdle()
      val modules = projectModel(projectRef).modules.map(_.name).toSet
      if (expectedModules.subsetOf(modules)) {
        projectRef
      } else {
        if (attempts == 0) {
          throw new RuntimeException(
            s"Failed to open project with modules ${expectedModules.mkString("[", ", ", "]")}, " +
              s"loaded modules are: ${modules.mkString("[", ", ", "]")}"
          )
        } else {
          attempt(attempts - 1)
        }
      }
    }

    if (expectedModules.subsetOf(modules)) {
      projectRef
    } else {
      attempt(attempts = 10)
    }
  }

  def preconfigureJDK(): Unit = send(Endpoints.PreconfigureJDK)

  /**
   * Forces the probe to wait until all background tasks are complete before processing next request
   */
  def awaitIdle(): Unit = send(Endpoints.AwaitIdle)

  /**
   * Forces the probe to wait until the specified notification is issued by the IDE
   */
  def awaitNotification(title: String, duration: Duration = Duration.Inf): IdeNotification = {
    send(Endpoints.AwaitNotification, (title, duration))
  }

  /**
   * Builds the specified files, modules or project
   */
  def build(scope: BuildScope = BuildScope.project): BuildResult = build(BuildParams(scope, rebuild = false))

  /**
   * Closes specified project
   */
  def closeProject(name: ProjectRef = ProjectRef.Default): Unit = send(Endpoints.CloseProject, name)

  /**
   * Invokes the specified actions without waiting for it to finish
   */
  def invokeActionAsync(id: String): Unit = send(Endpoints.InvokeActionAsync, id)

  /**
   * Invokes the specified actions and waits for it to finish
   */
  def invokeAction(id: String): Unit = send(Endpoints.InvokeAction, id)

  /**
   * Opens specified project
   */
  def openProject(path: Path): ProjectRef = {
    val ref = send(Endpoints.OpenProject, path)
    closeTipOfTheDay()
    checkBuildPanelErrors()
    ref
  }

  /**
   * Rebuilds the specified files, modules or project
   */
  def rebuild(scope: BuildScope = BuildScope.project): BuildResult = build(BuildParams(scope, rebuild = true))

  /**
   * starts the process of shutting down the IDE
   */
  def shutdown(): Unit = send(Endpoints.Shutdown)

  /**
   * Refreshes the file cache (useful, when those were modified outside of IDE)
   */
  def syncFiles(): Unit = send(Endpoints.SyncFiles)

  /**
   * Finds all the files referenced by the specified file
   */
  def fileReferences(project: ProjectRef = ProjectRef.Default, path: Path): Seq[Reference] = {
    send(Endpoints.FileReferences, FileRef(path, project))
  }

  /**
   * Finds all the files referenced by the specified file
   */
  def fileReferences(fileRef: FileRef): Seq[Reference] = {
    send(Endpoints.FileReferences, fileRef)
  }

  /**
   * Finds all navigable elements matching the specified pattern in the specified project
   */
  def find(query: NavigationQuery): List[NavigationTarget] = {
    send(Endpoints.Find, query)
  }

  /**
   * Returns the list of all errors produced by the IDE
   */
  def errors: Seq[IdeMessage] = send(Endpoints.Messages).filter(_.isError).toList

  /**
   * Returns the list of all warnings produced by the IDE
   */
  def warnings: Seq[IdeMessage] = send(Endpoints.Messages).filter(_.isWarn).toList

  /**
   * Returns the list of all messages produced by the IDE
   */
  def messages: Seq[IdeMessage] = send(Endpoints.Messages).toList

  /**
   * Returns the model of the specified project
   */
  def projectModel(name: ProjectRef = ProjectRef.Default): Project = send(Endpoints.ProjectModel, name)

  /**
   * Returns the list of all freezes detected by the IDE
   */
  def freezes: Seq[Freeze] = send(Endpoints.Freezes)

  /**
   * Runs the specified test configuration with a test runner containing provided string
   * or with the first available runner when the string is not present
   */
  def run(runConfiguration: TestRunConfiguration): TestsRunResult = send(Endpoints.RunTest, runConfiguration)

  /**
   * Runs the specified application configuration
   */
  def run(runConfiguration: ApplicationRunConfiguration): ProcessResult = send(Endpoints.Run, runConfiguration)

  /**
   * Runs the specified JUnit configuration
   */
  def run(runConfiguration: JUnitRunConfiguration): TestsRunResult = send(Endpoints.RunJUnit, runConfiguration)

  /**
   * Saves the current view of the IDE alongside the automatically captured screenshots
   * with the specified name suffix
   */
  def screenshot(nameSuffix: String = ""): Unit = send(Endpoints.TakeScreenshot, nameSuffix)

  /**
   * Returns the sdk of the specified project
   */
  def projectSdk(project: ProjectRef = ProjectRef.Default): Option[Sdk] = send(Endpoints.ProjectSdk, project)

  /**
   * Returns the sdk of the specified module
   */
  def moduleSdk(module: ModuleRef): Option[Sdk] = send(Endpoints.ModuleSdk, module)

  /**
   * Returns the list of VCS roots of the specified project
   */
  def vcsRoots(project: ProjectRef = ProjectRef.Default): Seq[VcsRoot] = send(Endpoints.VcsRoots, project)

  /**
   * Returns the list of all installed plugins
   */
  def plugins: Seq[InstalledPlugin] = send(Endpoints.Plugins).toList

  /**
   * Runs inspection given by fully qualified class name on specified file.
   * Optionally it can also run some or all of the quick fixes
   */
  def runLocalInspection(className: String, targetFile: FileRef, runFixesSpec: RunFixesSpec = RunFixesSpec.None): InspectionRunResult = {
    send(Endpoints.RunLocalInspection, InspectionRunParams(className, targetFile, runFixesSpec))
  }

  /**
   *  Expand macro in a given file
   */
  def expandMacro(macroText: String, fileRef: FileRef): String =
    send(Endpoints.ExpandMacro, ExpandMacroData(fileRef, macroText))

  def closeTipOfTheDay(): Unit = {
    Try(robot.mainWindow.find(query.dialog("Tip of the Day")).button("Close").click())
  }

  def checkBuildPanelErrors(): Unit = {
    robot.findOpt(query.className("MultipleBuildsPanel")).foreach { buildPanel =>
      val tree = buildPanel.find(query.className("Tree"))
      val treeTexts = tree.fullTexts
      val hasErrors = treeTexts.contains("failed")
      if (hasErrors) {
        val message = buildPanel
          .find(query.div("accessiblename" -> "Editor", "class" -> "EditorComponentImpl"))
          .fullText
        throw new RuntimeException(s"Failed to open project. Output: $message")
      }
    }
  }

  def ping(): Unit = send(Endpoints.Ping)

  def as[A](extensionPluginId: String, convert: ProbeDriver => A): A = {
    val isLoaded = plugins.exists(_.id == extensionPluginId)
    if (isLoaded) convert(this)
    else throw new IllegalStateException(s"Extension plugin $extensionPluginId is not loaded")
  }

  def send[T: ClassTag, R: ClassTag](method: Method[T, R], parameters: T): R = {
    Await.result(sendRequest(method, parameters), 2.hours)
  }

  def send[R: ClassTag](method: Method[Unit, R]): R = {
    send(method, ())
  }

  private def build(params: BuildParams): BuildResult = send(Endpoints.Build, params)
}

object ProbeDriver {
  def start(connection: JsonRpcConnection, robot: RemoteRobot)(implicit ec: ExecutionContext): ProbeDriver = {
    import scala.concurrent.Future
    val driver = new ProbeDriver(connection, robot)
    Future(driver.listen).onComplete(_ => driver.close())
    driver
  }
}
