package org.ice1000.arend.lsp

import org.arend.ext.error.ListErrorReporter
import org.arend.ext.module.ModulePath
import org.arend.extImpl.DefinitionRequester
import org.arend.frontend.ConcreteReferableProvider
import org.arend.frontend.FileLibraryResolver
import org.arend.frontend.PositionComparator
import org.arend.frontend.library.FileLoadableHeaderLibrary
import org.arend.frontend.library.TimedLibraryManager.timeToString
import org.arend.frontend.reference.ConcreteLocatedReferable
import org.arend.frontend.reference.ParsedLocalReferable
import org.arend.library.Library
import org.arend.library.LibraryManager
import org.arend.naming.reference.converter.IdReferableConverter
import org.arend.prelude.Prelude
import org.arend.prelude.PreludeResourceLibrary
import org.arend.term.concrete.BaseConcreteExpressionVisitor
import org.arend.term.concrete.Concrete
import org.arend.term.concrete.ConcreteReferableDefinitionVisitor
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
import org.arend.util.FileUtils
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture
import org.arend.frontend.parser.Position as AntlrPosition

class ArendServices : WorkspaceService, TextDocumentService {
  private val errorReporter = ListErrorReporter()
  private val libraryErrorReporter = ListErrorReporter()
  private val libraryResolver = FileLibraryResolver(ArrayList(), errorReporter)
  private val instanceProviders = InstanceProviderSet()
  private val libraryManager = object : LibraryManager(libraryResolver, instanceProviders, errorReporter, libraryErrorReporter, DefinitionRequester.INSTANCE) {
    private val times: Deque<Long> = ArrayDeque()
    override fun beforeLibraryLoading(library: Library) {
      Logger.i("[INFO] Loading library " + library.name)
      times.push(System.currentTimeMillis())
    }

    override fun afterLibraryLoading(library: Library, successful: Boolean) {
      val time = System.currentTimeMillis() - times.pop()
      Logger.i("[INFO] " + (if (successful) "Loaded " else "Failed loading ") + "library " + library.name + if (successful) " (" + timeToString(time) + ")" else "")
    }
  }

  private val typechecking = TypecheckingOrderingListener(instanceProviders, ConcreteReferableProvider.INSTANCE, IdReferableConverter.INSTANCE, errorReporter, PositionComparator.INSTANCE, LibraryArendExtensionProvider(libraryManager))

  /**
   * Recommended to call in an asynchronous environment.
   */
  fun registerLibrary(value: Path) {
    if (!Prelude.isInitialized()) {
      val prelude = PreludeResourceLibrary()
      libraryManager.loadLibrary(prelude, typechecking)
    }
    libraryResolver.addLibraryDirectory(value.parent)
    val lib = libraryResolver.registerLibrary(value)
    libraryManager.loadLibrary(lib, typechecking)
    lib.loadTests(libraryManager)
  }

  fun currentLibrary(containing: Path) = libraryManager.registeredLibraries
      .asSequence()
      .filterIsInstance<FileLoadableHeaderLibrary>()
      .mapNotNull {
        when {
          containing.startsWith(it.sourceBasePath) -> it to false
          containing.startsWith(it.testBasePath) -> it to true
          else -> null
        }
      }
      .firstOrNull()

  fun reload() {
    for (library in libraryManager.registeredLibraries) {
      typechecking.typecheckLibrary(library)
      typechecking.typecheckTests(library, null)
    }
    reportErrorsToConsole()
  }

  fun reportErrorsToConsole(clearAfter: Boolean = true) {
    for (error in errorReporter.errorList) Logger.e(error.toString())
    for (error in libraryErrorReporter.errorList) Logger.e(error.toString())
    if (clearAfter) {
      errorReporter.errorList.clear()
      libraryErrorReporter.errorList.clear()
    }
  }

  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    for (change in params.changes) {
      val (lib, modulePath, inTests) = describe(change.uri) ?: return
      if (inTests) {
        Logger.w("Cannot update test modules at this moment")
        // TODO: update test modules
        // typechecking.typecheckTests(lib)
      } else {
        Logger.i("Reloading module $modulePath from library ${lib.name}'s src")
        lib.updateModule(modulePath)
        typechecking.typecheckLibrary(lib)
      }
    }
  }

  override fun didOpen(params: DidOpenTextDocumentParams) {
  }

  override fun didChange(params: DidChangeTextDocumentParams) {
  }

  override fun didClose(params: DidCloseTextDocumentParams) {
  }

  override fun didSave(params: DidSaveTextDocumentParams) {
  }

  override fun definition(params: DefinitionParams) = CompletableFuture.supplyAsync<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
    val (lib, modulePath, inTests) = describe(params.textDocument.uri)
        ?: return@supplyAsync Either.forLeft(mutableListOf())
    val resolved = mutableListOf<Location>()
    val inPos = params.position
    fun resolveTo(ref: ConcreteLocatedReferable) {
      ref.definition?.accept(collectDefVisitor(inPos, lib, resolved), Unit)
    }

    val topGroup = lib.getModuleGroup(modulePath, inTests) ?: return@supplyAsync Either.forLeft(mutableListOf())
    val searchGroup = topGroup.subgroups.lastOrNull { group ->
      // This may fail, but no failure is observed so far
      val ref = group.referable as ConcreteLocatedReferable
      ref.data!!.line <= inPos.line + 1
    } ?: topGroup
    Logger.i("Searching for (${inPos.line}, ${inPos.character}) in ${searchGroup.referable.textRepresentation()}")
    searchGroup.traverseGroup { group ->
      resolveTo(group.referable as ConcreteLocatedReferable)
    }
    Either.forLeft(resolved)
  }

  private fun collectDefVisitor(inPos: Position, lib: FileLoadableHeaderLibrary, resolved: MutableList<Location>) = object : BaseConcreteExpressionVisitor<Unit>(), ConcreteReferableDefinitionVisitor<Unit, Void?> {
    override fun visitConstructor(def: Concrete.Constructor, params: Unit) = null
    override fun visitClassField(def: Concrete.ClassField, params: Unit) = null
    override fun visitReference(expr: Concrete.ReferenceExpression, unit: Unit): Concrete.Expression {
      val refPos = expr.data as? AntlrPosition
          ?: return super.visitReference(expr, unit)
      val referent = expr.referent
      val nameLength = referent.refName.length
      if (refPos.contains(inPos, nameLength)) when (referent) {
        is ConcreteLocatedReferable -> {
          val defPos = referent.data
              ?: return super.visitReference(expr, unit)
          val file = pathOf(lib, defPos.module)?.toAbsolutePath()
              ?: return super.visitReference(expr, unit)
          resolved.add(Location(describeURI(file.toUri()), defPos.toRange(nameLength)))
        }
        is ParsedLocalReferable -> {
          val file = pathOf(lib, referent.position.module)?.toAbsolutePath()
              ?: return super.visitReference(expr, unit)
          resolved.add(Location(describeURI(file.toUri()), referent.position.toRange(nameLength)))
        }
        else -> {
          Logger.w("Unsupported reference: ${referent.javaClass}")
          return super.visitReference(expr, unit)
        }
      }
      return super.visitReference(expr, unit)
    }
  }

  private fun pathOf(lib: FileLoadableHeaderLibrary, module: ModulePath) =
      FileUtils.sourceFile(lib.sourceBasePath, module).takeIf { p -> Files.exists(p) }
          ?: FileUtils.sourceFile(lib.testBasePath, module).takeIf { p -> Files.exists(p) }

  override fun completion(position: CompletionParams) = CompletableFuture.supplyAsync<Either<MutableList<CompletionItem>, CompletionList>> {
    val group = groupOf(position.textDocument.uri)
        ?: return@supplyAsync Either.forLeft(mutableListOf())
    Logger.log(group.toString())
    Either.forLeft(mutableListOf())
  }

  private fun groupOf(uri: String) = describe(uri)?.let { (lib, modulePath, inTests) ->
    lib.getModuleGroup(modulePath, inTests)
  }

  private fun describe(uri: String): Triple<FileLoadableHeaderLibrary, ModulePath, Boolean>? {
    val path = Paths.get(parseURI(uri))
    val (lib, inTests) = currentLibrary(path) ?: return null
    val relative = basePath(inTests, lib).relativize(path)
    val modulePath = FileUtils.modulePath(relative, FileUtils.EXTENSION)
    return Triple(lib, modulePath, inTests)
  }

  private fun basePath(inTests: Boolean, lib: FileLoadableHeaderLibrary) =
      if (inTests) lib.testBasePath else lib.sourceBasePath
}
