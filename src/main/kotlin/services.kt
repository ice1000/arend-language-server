package org.ice1000.arend.lsp

import org.arend.ext.error.GeneralError
import org.arend.ext.error.ListErrorReporter
import org.arend.ext.error.LocalError
import org.arend.frontend.ConcreteReferableProvider
import org.arend.frontend.FileLibraryResolver
import org.arend.frontend.PositionComparator
import org.arend.frontend.library.FileLoadableHeaderLibrary
import org.arend.frontend.parser.BuildVisitor
import org.arend.frontend.reference.ConcreteLocatedReferable
import org.arend.frontend.reference.ParsedLocalReferable
import org.arend.frontend.repl.CommonCliRepl
import org.arend.library.Library
import org.arend.module.ModuleLocation
import org.arend.naming.reference.FullModuleReferable
import org.arend.naming.reference.LocatedReferable
import org.arend.naming.reference.converter.IdReferableConverter
import org.arend.prelude.Prelude
import org.arend.prelude.PreludeResourceLibrary
import org.arend.source.SourceLoader
import org.arend.term.concrete.BaseConcreteExpressionVisitor
import org.arend.term.concrete.Concrete
import org.arend.term.concrete.ConcreteReferableDefinitionVisitor
import org.arend.term.group.ChildGroup
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import org.arend.frontend.parser.Position as AntlrPosition

class ArendServices : WorkspaceService, TextDocumentService {
  private val errorReporter = ListErrorReporter()
  private val libraryErrorReporter = ListErrorReporter()
  private val libraryResolver = FileLibraryResolver(ArrayList(), errorReporter)
  private val instanceProviders = InstanceProviderSet()
  private val libraryManager = LspLibraryManager(libraryResolver, instanceProviders, errorReporter, libraryErrorReporter)
  private val typechecking = TypecheckingOrderingListener(instanceProviders, ConcreteReferableProvider.INSTANCE, IdReferableConverter.INSTANCE, errorReporter, PositionComparator.INSTANCE, LibraryArendExtensionProvider(libraryManager))
  private var maybeLibrary: FileLoadableHeaderLibrary? = null

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
    if (lib != null) {
      if (lib is FileLoadableHeaderLibrary) maybeLibrary = lib
      libraryManager.loadLibrary(lib, typechecking)
      lib.loadTests(libraryManager)
    }
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
      ?.also { if (maybeLibrary == null) maybeLibrary = it.first }

  fun reload() {
    for (library in libraryManager.registeredLibraries) typecheckLibrary(library)
    reportErrors()
  }

  private fun typecheckLibrary(library: Library) {
    typechecking.typecheckLibrary(library)
    typechecking.typecheckTests(library, null)
  }

  fun reportErrors() {
    val groupLocal = errorReporter.errorList.groupBy(::errorUri)
    for ((uri, errors) in groupLocal) {
      if (uri.isEmpty()) continue
      IO.reportErrors(PublishDiagnosticsParams(uri, errors.map {
        // TODO: pass information into
        Diagnostic()
      }))
    }
    for (error in libraryErrorReporter.errorList) {
      IO.e(error.toString())
    }
    if (true) {
      errorReporter.errorList.clear()
      libraryErrorReporter.errorList.clear()
    }
  }

  private fun errorUri(it: GeneralError): String {
    if (it !is LocalError) return ""
    val ref = it.definition
    if (ref !is LocatedReferable) return ""
    val loc = ref.location ?: return ""
    val lib = libraryManager.getRegisteredLibrary(loc.libraryName)
        as? FileLoadableHeaderLibrary ?: return ""
    val path = loc.modulePath?.let { pathOf(lib, it) } ?: return ""
    return describeURI(path.toUri())
  }

  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    for (change in params.changes) {
      val (lib, modulePath, inTests) = describe(change.uri) ?: run {
        IO.w("Failed to find the module corresponds to ${change.uri}")
        return
      }
      IO.i("Reloading module $modulePath from library ${lib.name}'s ${
        if (inTests) "test" else "source"} directory")
      if (inTests) IO.w("Currently test reloading doesn't work properly")
      val loader = SourceLoader(lib, libraryManager)
      loader.preloadRaw(modulePath, inTests)
      loader.loadRawSources()
      typecheckLibrary(lib)
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

    val topGroup = lib.getModuleGroup(modulePath, inTests)
        ?: return@supplyAsync Either.forLeft(mutableListOf())
    val searchGroup = topGroup.subgroups.lastOrNull { group ->
      // This may fail, but no failure is observed so far
      val ref = group.referable as ConcreteLocatedReferable
      ref.data!!.line <= inPos.line + 1
    } ?: topGroup
    var finalized = false
    searchGroup.traverseGroup { group ->
      if (finalized) return@traverseGroup
      when (val ref = group.referable) {
        is ConcreteLocatedReferable -> resolveTo(ref)
        is FullModuleReferable -> {
          IO.w("Doesn't yet support FullModuleReferable: ${ref.textRepresentation()}")
/*
          Logger.log(ref.path.toString())
          val uri = pathOf(lib, ref.path)?.toUri()
          if (uri != null) {
            resolved.add(Location(describeURI(uri), Range()))
            finalized = true
          }
*/
        }
      }
    }
    for (result in resolved) {
      @Suppress("UnstableApiUsage")
      val range = ToStringBuilder(result.range.start)
          .addDeclaredFields()
          .singleLine()
      IO.i("Jumping to ($range) in ${result.uri}")
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
          IO.w("Unsupported reference: ${referent.javaClass}")
          return super.visitReference(expr, unit)
        }
      }
      return super.visitReference(expr, unit)
    }
  }

  fun parse(path: Path, location: ModuleLocation): ChildGroup {
    val p = CommonCliRepl.createParser(Files.readString(path), location, errorReporter)
    return BuildVisitor(location, errorReporter).visitStatements(p.statements())
  }

  override fun completion(position: CompletionParams) = CompletableFuture.supplyAsync<Either<MutableList<CompletionItem>, CompletionList>> {
    val group = groupOf(position.textDocument.uri)
        ?: return@supplyAsync Either.forLeft(mutableListOf())
    IO.log(group.toString())
    Either.forLeft(mutableListOf())
  }
}
