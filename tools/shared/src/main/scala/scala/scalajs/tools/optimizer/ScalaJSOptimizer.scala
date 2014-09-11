/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js tools             **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package scala.scalajs.tools.optimizer

import scala.annotation.{switch, tailrec}

import scala.collection.mutable
import scala.collection.immutable.{Seq, Traversable}

import java.net.URI

import scala.scalajs.ir
import ir.Infos
import ir.ClassKind

import scala.scalajs.tools.logging._
import scala.scalajs.tools.io._
import scala.scalajs.tools.classpath._
import scala.scalajs.tools.sourcemap._
import scala.scalajs.tools.corelib._

/** Scala.js optimizer: does type-aware global dce. */
class ScalaJSOptimizer(optimizerFactory: () => GenIncOptimizer) {
  import ScalaJSOptimizer._

  private[this] var persistentState: PersistentState = new PersistentState
  private[this] var inliner: GenIncOptimizer = optimizerFactory()

  def this() = this(() => new IncOptimizer)

  /** Applies Scala.js-specific optimizations to a CompleteIRClasspath.
   *  See [[ScalaJSOptimizer.Inputs]] for details about the required and
   *  optional inputs.
   *  See [[ScalaJSOptimizer.OutputConfig]] for details about the configuration
   *  for the output of this method.
   *  Returns a [[CompleteCIClasspath]] containing the result of the
   *  optimizations.
   *
   *  analyzes, dead code eliminates and concatenates IR content
   *  - Maintains/establishes order
   *  - No IR in result
   *  - CoreJSLibs in result (since they are implicitly in the CompleteIRCP)
   */
  def optimizeCP(inputs: Inputs[CompleteIRClasspath], outCfg: OutputConfig,
      logger: Logger): CompleteCIClasspath = {

    val cp = inputs.input

    CacheUtils.cached(cp.version, outCfg.output, outCfg.cache) {
      logger.info(s"Fast optimizing ${outCfg.output.path}")
      optimizeIR(inputs.copy(input = inputs.input.scalaJSIR), outCfg, logger)
    }

    CompleteCIClasspath(cp.jsLibs, outCfg.output :: Nil,
        cp.requiresDOM, cp.version)
  }

  def optimizeIR(inputs: Inputs[Traversable[VirtualScalaJSIRFile]],
      outCfg: OutputConfig, logger: Logger): Unit = {

    val builder = {
      import outCfg._
      if (wantSourceMap)
        new JSFileBuilderWithSourceMap(output.name,
            output.contentWriter,
            output.sourceMapWriter,
            relativizeSourceMapBase)
      else
        new JSFileBuilder(output.name, output.contentWriter)
    }

    builder.addLine("'use strict';")
    CoreJSLibs.libs.foreach(builder.addFile _)

    optimizeIR(inputs, outCfg, builder, logger)

    builder.complete()
    builder.closeWriters()
  }

  def optimizeIR(inputs: Inputs[Traversable[VirtualScalaJSIRFile]],
      outCfg: OptimizerConfig, builder: JSTreeBuilder, logger: Logger): Unit = {

    /* Handle tree equivalence: If we handled source maps so far, positions are
       still up-to-date. Otherwise we need to flush the state if proper
       positions are requested now.
     */
    if (outCfg.wantSourceMap && !persistentState.wasWithSourceMap)
      clean()

    val treeEquiv =
      /*if (outCfg.wantSourceMap) ir.TreeEquiv.PosStructTreeEquiv
      else*/ ir.TreeEquiv.StructTreeEquiv

    persistentState.wasWithSourceMap = outCfg.wantSourceMap

    persistentState.startRun()
    try {
      import inputs._
      val allData =
        GenIncOptimizer.logTime(logger, "Read info") {
          readAllData(inputs.input, logger)
        }
      val (useInliner, refinedAnalyzer) = GenIncOptimizer.logTime(
          logger, "Optimizations part") {
        val analyzer =
          GenIncOptimizer.logTime(logger, "Compute reachability") {
            val analyzer = new Analyzer(logger, allData)
            analyzer.computeReachability(manuallyReachable, noWarnMissing)
            analyzer
          }
        if (outCfg.checkIR) {
          GenIncOptimizer.logTime(logger, "Check IR") {
            if (analyzer.allAvailable)
              checkIR(analyzer, logger)
            else if (inputs.noWarnMissing.isEmpty)
              sys.error("Could not check IR because there where linking errors.")
          }
        }
        def getClassTreeIfChanged(encodedName: String,
            lastVersion: Option[String]): Option[(ir.Trees.ClassDef, Option[String])] = {
          val persistentFile = persistentState.encodedNameToPersistentFile(encodedName)
          persistentFile.treeIfChanged(lastVersion)
        }

        val useInliner = analyzer.allAvailable && !outCfg.disableInliner

        if (outCfg.batchInline)
          inliner = optimizerFactory()

        val refinedAnalyzer = if (useInliner) {
          GenIncOptimizer.logTime(logger, "Inliner") {
            inliner.update(analyzer, getClassTreeIfChanged, logger)(treeEquiv)
          }
          GenIncOptimizer.logTime(logger, "Refined reachability analysis") {
            val refinedData = computeRefinedData(allData, inliner)
            val refinedAnalyzer = new Analyzer(logger, refinedData,
                globalWarnEnabled = false)
            refinedAnalyzer.computeReachability(manuallyReachable, noWarnMissing)
            refinedAnalyzer
          }
        } else {
          if (inputs.noWarnMissing.isEmpty && !outCfg.disableInliner)
            logger.warn("Not running the inliner because there where linking errors.")
          analyzer
        }
        (useInliner, refinedAnalyzer)
      }
      GenIncOptimizer.logTime(logger, "Write DCE'ed output") {
        buildDCEedOutput(builder, refinedAnalyzer, useInliner)
      }
    } finally {
      persistentState.endRun(outCfg.unCache)
      logger.debug(
          s"Inc. opt stats: reused: ${persistentState.statsReused} -- "+
          s"invalidated: ${persistentState.statsInvalidated} -- "+
          s"trees read: ${persistentState.statsTreesRead}")
    }
  }

  /** Resets all persistent state of this optimizer */
  def clean(): Unit = {
    persistentState = new PersistentState
    inliner = optimizerFactory()
  }

  private def readAllData(ir: Traversable[VirtualScalaJSIRFile],
      logger: Logger): scala.collection.Seq[Infos.ClassInfo] = {
    ir.map(persistentState.getPersistentIRFile(_).info).toSeq
  }

  private def checkIR(analyzer: Analyzer, logger: Logger): Unit = {
    val allClassDefs = for {
      classInfo <- analyzer.classInfos.values
      persistentIRFile <- persistentState.encodedNameToPersistentFile.get(
          classInfo.encodedName)
    } yield persistentIRFile.tree
    val checker = new IRChecker(analyzer, allClassDefs.toSeq, logger)
    if (!checker.check())
      sys.error(s"There were ${checker.errorCount} IR checking errors.")
  }

  private def computeRefinedData(
      allData: scala.collection.Seq[Infos.ClassInfo],
      inliner: GenIncOptimizer): scala.collection.Seq[Infos.ClassInfo] = {

    def refineMethodInfo(container: inliner.MethodContainer,
        methodInfo: Infos.MethodInfo): Infos.MethodInfo = {
      container.methods.get(methodInfo.encodedName).fold(methodInfo) {
        methodImpl => methodImpl.preciseInfo
      }
    }

    def refineMethodInfos(container: inliner.MethodContainer,
        methodInfos: List[Infos.MethodInfo]): List[Infos.MethodInfo] = {
      methodInfos.map(m => refineMethodInfo(container, m))
    }

    def refineClassInfo(container: inliner.MethodContainer,
        info: Infos.ClassInfo): Infos.ClassInfo = {
      val refinedMethods = refineMethodInfos(container, info.methods)
      Infos.ClassInfo(info.name, info.encodedName, info.isExported,
          info.ancestorCount, info.kind, info.superClass, info.ancestors,
          Infos.OptimizerHints.empty, refinedMethods)
    }

    for {
      info <- allData
    } yield {
      info.kind match {
        case ClassKind.Class | ClassKind.ModuleClass =>
          inliner.getClass(info.encodedName).fold(info) {
            cls => refineClassInfo(cls, info)
          }

        case ClassKind.TraitImpl =>
          inliner.getTraitImpl(info.encodedName).fold(info) {
            impl => refineClassInfo(impl, info)
          }

        case _ =>
          info
      }
    }
  }

  private def buildDCEedOutput(builder: JSTreeBuilder,
      analyzer: Analyzer, useInliner: Boolean): Unit = {

    def compareClassInfo(lhs: analyzer.ClassInfo, rhs: analyzer.ClassInfo) = {
      if (lhs.ancestorCount != rhs.ancestorCount) lhs.ancestorCount < rhs.ancestorCount
      else lhs.encodedName.compareTo(rhs.encodedName) < 0
    }

    def addPersistentFile(classInfo: analyzer.ClassInfo,
        persistentFile: PersistentIRFile) = {
      import ir.Trees._
      import ir.{ScalaJSClassEmitter => Emitter}
      import ir.JSDesugaring.{desugarJavaScript => desugar}

      val d = persistentFile.desugared
      lazy val classDef = {
        persistentState.statsTreesRead += 1
        persistentFile.tree
      }

      def addTree(tree: Tree): Unit =
        builder.addJSTree(tree)

      def addReachableMethods(emitFun: (String, MethodDef) => Tree): Unit = {
        /* This is a bit convoluted because we have to:
         * * avoid to use classDef at all if we already know all the needed methods
         * * if any new method is needed, better to go through the defs once
         */
        val methodNames = d.methodNames.getOrElseUpdate(
            classDef.defs collect {
              case MethodDef(Ident(encodedName, _), _, _, _) => encodedName
            })
        val reachableMethods = methodNames.filter(
            name => classInfo.methodInfos(name).isReachable)
        if (reachableMethods.forall(d.methods.contains(_))) {
          for (encodedName <- reachableMethods) {
            addTree(d.methods(encodedName))
          }
        } else {
          for (m @ MethodDef(Ident(encodedName, _), _, _, _) <- classDef.defs) {
            if (classInfo.methodInfos(encodedName).isReachable) {
              addTree(d.methods.getOrElseUpdate(encodedName,
                  desugar(emitFun(classInfo.encodedName, m))))
            }
          }
        }
      }

      if (classInfo.isImplClass) {
        if (useInliner) {
          for {
            method <- inliner.findTraitImpl(classInfo.encodedName).methods.values
            if (classInfo.methodInfos(method.encodedName).isReachable)
          } {
            addTree(method.desugaredDef)
          }
        } else {
          addReachableMethods(Emitter.genTraitImplMethod)
        }
      } else if (!classInfo.hasInstantiation) {
        // there is only the data anyway
        addTree(d.wholeClass.getOrElseUpdate(
            desugar(classDef)))
      } else {
        if (classInfo.isAnySubclassInstantiated) {
          addTree(d.constructor.getOrElseUpdate(
              desugar(Emitter.genConstructor(classDef))))
          if (useInliner) {
            for {
              method <- inliner.findClass(classInfo.encodedName).methods.values
              if (classInfo.methodInfos(method.encodedName).isReachable)
            } {
              addTree(method.desugaredDef)
            }
          } else {
            addReachableMethods(Emitter.genMethod)
          }
          addTree(d.exportedMembers.getOrElseUpdate(desugar(Block {
            classDef.defs collect {
              case m @ MethodDef(_: StringLiteral, _, _, _) =>
                Emitter.genMethod(classInfo.encodedName, m)
              case p: PropertyDef =>
                Emitter.genProperty(classInfo.encodedName, p)
            }
          }(classDef.pos))))
        }
        if (classInfo.isDataAccessed) {
          addTree(d.typeData.getOrElseUpdate(desugar(Block(
            Emitter.genInstanceTests(classDef),
            Emitter.genArrayInstanceTests(classDef),
            Emitter.genTypeData(classDef)
          )(classDef.pos))))
        }
        if (classInfo.isAnySubclassInstantiated)
          addTree(d.setTypeData.getOrElseUpdate(
              desugar(Emitter.genSetTypeData(classDef))))
        if (classInfo.isModuleAccessed)
          addTree(d.moduleAccessor.getOrElseUpdate(
              desugar(Emitter.genModuleAccessor(classDef))))
        addTree(d.classExports.getOrElseUpdate(
            desugar(Emitter.genClassExports(classDef))))
      }
    }


    for {
      classInfo <- analyzer.classInfos.values.toSeq.sortWith(compareClassInfo)
      if classInfo.isNeededAtAll
    } {
      val optPersistentFile =
        persistentState.encodedNameToPersistentFile.get(classInfo.encodedName)

      // if we have a persistent file, this is not a dummy class
      optPersistentFile.fold {
        if (classInfo.isAnySubclassInstantiated) {
          // Subclass will emit constructor that references this dummy class.
          // Therefore, we need to emit a dummy parent.
          builder.addJSTree(
              ir.ScalaJSClassEmitter.genDummyParent(classInfo.encodedName))
        }
      } { pf => addPersistentFile(classInfo, pf) }
    }
  }
}

object ScalaJSOptimizer {
  /** Inputs of the Scala.js optimizer. */
  final case class Inputs[T](
      /** The CompleteNCClasspath or the IR files to be packaged. */
      input: T,
      /** Manual additions to reachability */
      manuallyReachable: Seq[ManualReachability] = Nil,
      /** Elements we won't warn even if they don't exist */
      noWarnMissing: Seq[NoWarnMissing] = Nil
  )

  sealed abstract class ManualReachability
  final case class ReachObject(name: String) extends ManualReachability
  final case class Instantiate(name: String) extends ManualReachability
  final case class ReachMethod(className: String, methodName: String,
      static: Boolean) extends ManualReachability

  sealed abstract class NoWarnMissing
  final case class NoWarnClass(className: String) extends NoWarnMissing
  final case class NoWarnMethod(className: String, methodName: String)
    extends NoWarnMissing

  /** Configurations relevant to the optimizer */
  trait OptimizerConfig {
    /** Ask to produce source map for the output. Is used in the incremental
     *  optimizer to decide whether a position change should trigger re-inlining
     */
    val wantSourceMap: Boolean
    /** If true, performs expensive checks of the IR for the used parts. */
    val checkIR: Boolean
    /** If true, the optimizer removes trees that have not been used in the
     *  last run from the cache. Otherwise, all trees that has been used once,
     *  are kept in memory. */
    val unCache: Boolean
    /** If true, no inlining is performed */
    val disableInliner: Boolean
    /** If true, inlining is not performed incrementally */
    val batchInline: Boolean
  }

  /** Configuration for the output of the Scala.js optimizer. */
  final case class OutputConfig(
      /** Writer for the output. */
      output: WritableVirtualJSFile,
      /** Cache file */
      cache: Option[WritableVirtualTextFile] = None,
      /** Ask to produce source map for the output */
      wantSourceMap: Boolean = false,
      /** Base path to relativize paths in the source map. */
      relativizeSourceMapBase: Option[URI] = None,
      /** If true, performs expensive checks of the IR for the used parts. */
      checkIR: Boolean = false,
      /** If true, the optimizer removes trees that have not been used in the
       *  last run from the cache. Otherwise, all trees that has been used once,
       *  are kept in memory. */
      unCache: Boolean = true,
      /** If true, no inlining is performed */
      disableInliner: Boolean = false,
      /** If true, inlining is not performed incrementally */
      batchInline: Boolean = false
  ) extends OptimizerConfig

  // Private helpers -----------------------------------------------------------

  private final class PersistentState {
    val files = mutable.Map.empty[String, PersistentIRFile]
    val encodedNameToPersistentFile =
      mutable.Map.empty[String, PersistentIRFile]

    var statsReused: Int = 0
    var statsInvalidated: Int = 0
    var statsTreesRead: Int = 0

    var wasWithSourceMap: Boolean = true

    def startRun(): Unit = {
      statsReused = 0
      statsInvalidated = 0
      statsTreesRead = 0
      for (file <- files.values)
        file.startRun()
    }

    def getPersistentIRFile(irFile: VirtualScalaJSIRFile): PersistentIRFile = {
      val file = files.getOrElseUpdate(irFile.path,
          new PersistentIRFile(irFile.path))
      if (file.updateFile(irFile))
        statsReused += 1
      else
        statsInvalidated += 1
      encodedNameToPersistentFile += ((file.info.encodedName, file))
      file
    }

    def endRun(unCache: Boolean): Unit = {
      // "Garbage-collect" persisted versions of files that have disappeared
      files.retain((_, f) => f.cleanAfterRun(unCache))
      encodedNameToPersistentFile.clear()
    }
  }

  private final class PersistentIRFile(val path: String) {
    import ir.Trees._

    private[this] var existedInThisRun: Boolean = false
    private[this] var desugaredUsedInThisRun: Boolean = false

    private[this] var irFile: VirtualScalaJSIRFile = null
    private[this] var version: Option[String] = None
    private[this] var _info: Infos.ClassInfo = null
    private[this] var _tree: ClassDef = null
    private[this] var _desugared: Desugared = null

    def startRun(): Unit = {
      existedInThisRun = false
      desugaredUsedInThisRun = false
    }

    def updateFile(irFile: VirtualScalaJSIRFile): Boolean = {
      existedInThisRun = true
      this.irFile = irFile
      if (version.isDefined && version == irFile.version) {
        // yeepeeh, nothing to do
        true
      } else {
        version = irFile.version
        _info = irFile.info
        _tree = null
        _desugared = null
        false
      }
    }

    def info: Infos.ClassInfo = _info

    def desugared: Desugared = {
      desugaredUsedInThisRun = true
      if (_desugared == null)
        _desugared = new Desugared
      _desugared
    }

    def tree: ClassDef = {
      if (_tree == null)
        _tree = irFile.tree
      _tree
    }

    def treeIfChanged(lastVersion: Option[String]): Option[(ClassDef, Option[String])] = {
      if (lastVersion.isDefined && lastVersion == version) None
      else Some((tree, version))
    }

    /** Returns true if this file should be kept for the next run at all. */
    def cleanAfterRun(unCache: Boolean): Boolean = {
      irFile = null
      if (unCache && !desugaredUsedInThisRun)
        _desugared = null // free desugared if unused in this run
      existedInThisRun
    }
  }

  private final class Desugared {
    import ir.Trees._

    // for class kinds that are not decomposed
    val wholeClass = new OneTimeCache[Tree]

    val constructor = new OneTimeCache[Tree]
    val methodNames = new OneTimeCache[List[String]]
    val methods = mutable.Map.empty[String, Tree]
    val exportedMembers = new OneTimeCache[Tree]
    val typeData = new OneTimeCache[Tree]
    val setTypeData = new OneTimeCache[Tree]
    val moduleAccessor = new OneTimeCache[Tree]
    val classExports = new OneTimeCache[Tree]
  }

  private final class OneTimeCache[A >: Null] {
    private[this] var value: A = null
    def getOrElseUpdate(v: => A): A = {
      if (value == null)
        value = v
      value
    }
  }
}
