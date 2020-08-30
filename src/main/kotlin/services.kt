package org.ice1000.arend.lsp

import org.arend.core.definition.Definition
import org.arend.ext.error.GeneralError
import org.arend.ext.error.ListErrorReporter
import org.arend.ext.error.LocalError
import org.arend.ext.module.ModulePath
import org.arend.ext.reference.ArendRef
import org.arend.frontend.ConcreteReferableProvider
import org.arend.frontend.FileLibraryResolver
import org.arend.frontend.PositionComparator
import org.arend.frontend.group.SimpleNamespaceCommand
import org.arend.frontend.library.FileLoadableHeaderLibrary
import org.arend.frontend.parser.BuildVisitor
import org.arend.frontend.parser.ParserError
import org.arend.frontend.reference.ConcreteLocatedReferable
import org.arend.frontend.reference.ParsedLocalReferable
import org.arend.frontend.repl.CommonCliRepl
import org.arend.library.Library
import org.arend.library.error.LibraryIOError
import org.arend.module.ModuleLocation
import org.arend.naming.error.NotInScopeError
import org.arend.naming.reference.FullModuleReferable
import org.arend.naming.reference.LocatedReferable
import org.arend.naming.reference.LocatedReferableImpl
import org.arend.naming.reference.TCReferable
import org.arend.naming.reference.converter.IdReferableConverter
import org.arend.prelude.Prelude
import org.arend.prelude.PreludeResourceLibrary
import org.arend.source.SourceLoader
import org.arend.term.concrete.BaseConcreteExpressionVisitor
import org.arend.term.concrete.Concrete
import org.arend.term.concrete.ConcreteReferableDefinitionVisitor
import org.arend.term.group.ChildGroup
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.error.TerminationCheckError
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder
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
  private val libraryManager = LspLibraryManager(libraryResolver, instanceProviders, errorReporter, libraryErrorReporter)
  private val typechecking = TypecheckingOrderingListener(instanceProviders, ConcreteReferableProvider.INSTANCE, IdReferableConverter.INSTANCE, errorReporter, PositionComparator.INSTANCE, LibraryArendExtensionProvider(libraryManager))
  private var maybeLibrary: FileLoadableHeaderLibrary? = null
  private var lastErrorReportedFiles: Collection<String> = emptyList()

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

  private fun diagnostic(cause: Any?, it: GeneralError): Diagnostic = when (cause) {
    is AntlrPosition -> Diagnostic(cause.toRange(1), it.toString(), severity(it), "Arend")
    is TCReferable -> diagnostic(cause.data, it)
    is Definition -> diagnostic(cause.referable, it)
    else -> Diagnostic(emptyRange, it.toString(), severity(it), "Arend")
  }

  private fun severity(it: GeneralError) = when (it.level) {
    GeneralError.Level.INFO -> DiagnosticSeverity.Information
    GeneralError.Level.WARNING_UNUSED -> DiagnosticSeverity.Hint
    GeneralError.Level.GOAL -> DiagnosticSeverity.Hint
    GeneralError.Level.WARNING -> DiagnosticSeverity.Warning
    GeneralError.Level.ERROR -> DiagnosticSeverity.Error
  }

  fun currentLibrary(containing: Path) = loadedLibraries()
      .mapNotNull {
        when {
          containing.startsWith(it.sourceBasePath) -> it to false
          containing.startsWith(it.testBasePath) -> it to true
          else -> null
        }
      }
      .firstOrNull()
      ?.also { if (maybeLibrary == null) maybeLibrary = it.first }

  private fun loadedLibraries() = libraryManager
      .registeredLibraries
      .asSequence()
      .filterIsInstance<FileLoadableHeaderLibrary>()

  fun reload() {
    for (library in libraryManager.registeredLibraries) typecheckLibrary(library)
    reportErrors()
  }

  private fun typecheckLibrary(library: Library) {
    typechecking.typecheckLibrary(library)
    typechecking.typecheckTests(library, null)
  }

  private fun reportErrors() {
    val allErrors = errorReporter.errorList + libraryErrorReporter.errorList
    val groupLocal = allErrors.groupBy(::errorUri)
    for (file in lastErrorReportedFiles) IO.reportErrors(PublishDiagnosticsParams(file, emptyList()))
    IO.log("Found ${allErrors.size} issues in ${groupLocal.size} files.")
    for ((uri, errors) in groupLocal) {
      if (uri.isEmpty()) {
        IO.log(errors.joinToString(prefix = "Unhandled error: ") { it.javaClass.canonicalName })
        continue
      }
      IO.reportErrors(PublishDiagnosticsParams(uri, errors.map {
        diagnostic(it.cause, it)
      }))
    }
    lastErrorReportedFiles = groupLocal.keys
    errorReporter.errorList.clear()
    libraryErrorReporter.errorList.clear()
  }

  private fun errorUri(e: GeneralError) = when (e) {
    is TerminationCheckError -> errorUri(e.definition)
    is NotInScopeError -> e.referable?.let { errorUri(it) }
        ?: (e.cause as? AntlrPosition)?.let { errorUri(it) }
        ?: ""
    is LocalError -> errorUri(e.definition)
    is ParserError -> errorUri(e.position)
    is LibraryIOError -> describeUri(Paths.get(e.fileName))
    else -> ""
  }

  private fun errorUri(position: AntlrPosition) = maybeLibrary
      ?.let { pathOf(it, position.module) }
      ?.let(::describeUri)
      .orEmpty()

  private fun errorUri(ref: ArendRef?): String {
    fun log(s: String): String {
      IO.w(s)
      return ""
    }

    if (ref !is LocatedReferable) return log("Unsupported ref type: ${ref?.javaClass}")
    val loc = ref.location ?: return log("No location for ref ${ref.refName}")
    val lib = libraryManager.getRegisteredLibrary(loc.libraryName)
        as? FileLoadableHeaderLibrary ?: return log("Not a loaded library: ${loc.libraryName}")
    val path = loc.modulePath?.let { pathOf(lib, it) } ?: return log("Cannot find a path corresponds to ${loc.modulePath}")
    return describeUri(path)
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
    reportErrors()
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
    val resolved = mutableListOf<LocationLink>()
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
    val nsCmd = topGroup.namespaceCommands.firstOrNull { nsCmd ->
      if (nsCmd is SimpleNamespaceCommand) nsCmd.data.line == inPos.line + 1 else false
    }
    if (nsCmd == null) searchGroup.traverseGroup { group ->
      when (val ref = group.referable) {
        is ConcreteLocatedReferable -> resolveTo(ref)
        is FullModuleReferable -> {
          IO.w("Unexpected FullModuleReferable: ${ref.textRepresentation()}")
        }
      }
    } else {
      // TODO: handle references in "using" and "hiding"
      val uri = pathOf(lib, ModulePath(nsCmd.path))?.toUri()
      if (uri != null) {
        val simpleNsCmd = nsCmd as SimpleNamespaceCommand
        resolved.add(LocationLink(describeUri(uri), emptyRange, emptyRange, simpleNsCmd.data.toRange(moduleNameLength(nsCmd.path))))
      }
    }
    for (result in resolved) {
      @Suppress("UnstableApiUsage")
      val range = ToStringBuilder(result.targetRange.start)
          .addDeclaredFields()
          .singleLine()
      IO.i("Jumping to ($range) in ${result.targetUri}")
    }
    Either.forRight(resolved)
  }

  private fun collectDefVisitor(inPos: Position, lib: FileLoadableHeaderLibrary, resolved: MutableList<LocationLink>) = object : BaseConcreteExpressionVisitor<Unit>(), ConcreteReferableDefinitionVisitor<Unit, Void?> {
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
          val range = defPos.toRange(nameLength)
          resolved.add(LocationLink(describeUri(file), nextLine(range.start), range, refPos.toRange(nameLength)))
        }
        is ParsedLocalReferable -> {
          val file = pathOf(lib, referent.position.module)?.toAbsolutePath()
              ?: return super.visitReference(expr, unit)
          val range = referent.position.toRange(nameLength)
          resolved.add(LocationLink(describeUri(file), nextLine(range.start), range, refPos.toRange(nameLength)))
        }
        is LocatedReferableImpl -> {
          IO.log("{ref = ${referent.refName}, long = ${referent.refLongName}, parent = ${referent.locatedReferableParent?.javaClass}}")
        }
        else -> IO.w("Unsupported reference: ${referent.javaClass}")
      }
      return super.visitReference(expr, unit)
    }

    private fun nextLine(start: Position) = Range(start, Position(start.line + 1, 0))
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
