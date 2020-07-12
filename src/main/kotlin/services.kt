package org.ice1000.arend.lsp

import org.arend.ext.error.ListErrorReporter
import org.arend.extImpl.DefinitionRequester
import org.arend.frontend.ConcreteReferableProvider
import org.arend.frontend.FileLibraryResolver
import org.arend.frontend.PositionComparator
import org.arend.frontend.library.FileLoadableHeaderLibrary
import org.arend.frontend.library.TimedLibraryManager
import org.arend.naming.reference.converter.IdReferableConverter
import org.arend.prelude.Prelude
import org.arend.prelude.PreludeResourceLibrary
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
import org.arend.util.FileUtils
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture

class ArendServices : WorkspaceService, TextDocumentService {
  private val errorReporter = ListErrorReporter()
  private val libraryErrorReporter = ListErrorReporter()
  private val libraryResolver = FileLibraryResolver(ArrayList(), errorReporter)
  private val instanceProviders = InstanceProviderSet()
  private val libraryManager = TimedLibraryManager(libraryResolver, instanceProviders, errorReporter, libraryErrorReporter, DefinitionRequester.INSTANCE)
  private val typechecking = TypecheckingOrderingListener(instanceProviders, ConcreteReferableProvider.INSTANCE, IdReferableConverter.INSTANCE, errorReporter, PositionComparator.INSTANCE, LibraryArendExtensionProvider(libraryManager))

  /**
   * Recommended to call in an asynchronous environment.
   */
  fun registerLibrary(value: Path) {
    if (!Prelude.isInitialized()) {
      Logger.log("Loading prelude...")
      libraryManager.loadLibrary(PreludeResourceLibrary(), typechecking)
      Logger.log("Done loading prelude.")
    }
    Logger.log("Loading library from path $value...")
    libraryResolver.addLibraryDirectory(value.parent)
    val lib = libraryResolver.registerLibrary(value)
    libraryManager.loadLibrary(lib, typechecking)
    Logger.log("Library ${lib.name} loaded.")
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
    for (library in libraryManager.registeredLibraries)
      typechecking.typecheckLibrary(library)
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
      val path = Paths.get(parseURI(change.uri))
      Logger.log(path.toString())
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

  override fun completion(position: CompletionParams) = CompletableFuture.supplyAsync<Either<MutableList<CompletionItem>, CompletionList>> {
    val path = Paths.get(parseURI(position.textDocument.uri))
    Logger.log(libraryManager.registeredLibraries.toString())
    val (lib, inTests) = currentLibrary(path)
        ?: return@supplyAsync Either.forLeft(mutableListOf())
    val modulePath = FileUtils.modulePath(lib.headerFile.parent.relativize(path), FileUtils.EXTENSION)
    val group = lib.getModuleGroup(modulePath, inTests)
    Logger.log(group.toString())
    Either.forLeft(mutableListOf())
  }
}
