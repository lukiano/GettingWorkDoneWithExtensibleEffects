package scan

import java.io.IOException
import java.nio.file._

import scala.compat.java8.StreamConverters._
import scala.collection.SortedSet

import cats._
import cats.data._
import cats.implicits._

import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._

import org.atnos.eff.addon.monix._
import org.atnos.eff.addon.monix.task._
import org.atnos.eff.syntax.addon.monix.task._

import monix.eval._

import EffTypes._

import monix.execution.Scheduler.Implicits.global

object Scanner {

  type R = Fx.fx4[Reader[Filesystem, ?], Reader[ScanConfig, ?], Either[Throwable, ?], Task]

  def main(args: Array[String]): Unit = scanReport(Directory(args(0)), 10).map(println).runAsync

  def scanReport(base: FilePath, topN: Int): Task[String] = for {
    start <- Task.eval(System.currentTimeMillis)
    result <- pathScan(base, topN, DefaultFilesystem)
  } yield (result match {
    case Right(scan) => ReportFormat.largeFilesReport(scan, base.path) +
      s"\nElapsed ${System.currentTimeMillis - start}ms"
    case Left(ex) => s"Scan of '${base.path}' failed: $ex"
  })

  def pathScan(base: FilePath, topN: Int, fs: Filesystem): Task[Either[Throwable, PathScan]] = {
    //build an Eff program (ie a data structure)
    val effScan: Eff[R, PathScan] = PathScan.scan[R](base)

    //execute the Eff expression by interpreting it
    effScan.runReader(ScanConfig(topN)).runReader(fs).runEither.runAsync
  }
}

object EffTypes {

  type _filesystem[R] = Reader[Filesystem, ?] <= R
  type _config[R] = Reader[ScanConfig, ?] <= R
}


sealed trait FilePath {
  def path: String
}
case class File(path: String) extends FilePath
case class Directory(path: String) extends FilePath

trait Filesystem {

  @throws(classOf[IOException])
  def length(file: File): Long

  @throws(classOf[IOException])
  def listFiles(directory: Directory): List[FilePath]

}
case object DefaultFilesystem extends Filesystem {

  def length(file: File) = Files.size(Paths.get(file.path))

  def listFiles(directory: Directory) = {
    val files = Files.list(Paths.get(directory.path))
    try files.toScala[List].flatMap {
      case dir if Files.isDirectory(dir) => List(Directory(dir.toString))
      case file if Files.isRegularFile(file) => List(File(file.toString))
      case _ => List.empty
    }
    finally files.close()
  }
}

case class ScanConfig(topN: Int)

case class PathScan(largestFiles: SortedSet[FileSize], totalSize: Long, totalCount: Long)

object PathScan {

  def empty: PathScan = PathScan(SortedSet.empty, 0, 0)

  def scan[R: _filesystem: _config: _throwableEither: _task](path: FilePath): Eff[R, PathScan] = path match {
    case file: File =>
      for {
        fs <- FileSize.ofFile(file)
      }
      yield PathScan(SortedSet(fs), fs.size, 1)
    case dir: Directory =>
      for {
        filesystem <- ask[R, Filesystem]
        topN <- PathScan.takeTopN
        files <- catchNonFatalThrowable(filesystem.listFiles(dir))
        concurrentChildScans <- Eff.traverseA(files)(file => taskSuspend(Task.eval(PathScan.scan[R](file))))
      }
      yield concurrentChildScans.combineAll(topN)
  }

  def takeTopN[R: _config]: Eff[R, Monoid[PathScan]] = for {
    scanConfig <- ask
  } yield new Monoid[PathScan] {
    def empty: PathScan = PathScan.empty

    def combine(p1: PathScan, p2: PathScan): PathScan = PathScan(
      p1.largestFiles.union(p2.largestFiles).take(scanConfig.topN),
      p1.totalSize + p2.totalSize,
      p1.totalCount + p2.totalCount
    )
  }

  //Intercepts all Tasks within `e` and returns an Eff expression ensuring they `fork` into a thread switch when invoked
  def taskFork[R: _Task, A](e: Eff[R, A]): Eff[R, A] =
    interpret.interceptNat[R, Task, A](e)(
      new (Task ~> Task) {
        def apply[X](fa: Task[X]): Task[X] =
          Task.fork(fa)
      })
}

case class FileSize(path: File, size: Long)

object FileSize {

  def ofFile[R: _filesystem: _throwableEither](file: File): Eff[R, FileSize] = for {
    fs <- ask
    size <- catchNonFatalThrowable(fs.length(file))
  } yield FileSize(file, size)

  implicit val ordering: Ordering[FileSize] = Ordering.by[FileSize, Long  ](_.size).reverse
}

object ReportFormat {

  def largeFilesReport(scan: PathScan, rootDir: String): String = {
    if (scan.largestFiles.nonEmpty) {
      s"Largest ${scan.largestFiles.size} file(s) found under path: $rootDir\n" +
        scan.largestFiles.map(fs => s"${(fs.size * 100)/scan.totalSize}%  ${formatByteString(fs.size)}  ${fs.path}").mkString("", "\n", "\n") +
        s"${scan.totalCount} total files found, having total size ${formatByteString(scan.totalSize)} bytes.\n"
    }
    else
      s"No files found under path: $rootDir"
  }

  def formatByteString(bytes: Long): String = {
    if (bytes < 1000)
      s"${bytes} B"
    else {
      val exp = (Math.log(bytes) / Math.log(1000)).toInt
      val pre = "KMGTPE".charAt(exp - 1)
      s"%.1f ${pre}B".format(bytes / Math.pow(1000, exp))
    }
  }
}
