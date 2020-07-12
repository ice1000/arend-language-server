package org.ice1000.arend.lsp

import org.arend.ext.error.ListErrorReporter
import org.arend.extImpl.DefinitionRequester
import org.arend.frontend.ConcreteReferableProvider
import org.arend.frontend.FileLibraryResolver
import org.arend.frontend.PositionComparator
import org.arend.frontend.library.TimedLibraryManager
import org.arend.library.LibraryManager
import org.arend.naming.reference.converter.IdReferableConverter
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
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
  private val libraryManager = TimedLibraryManager(libraryResolver, InstanceProviderSet(), errorReporter, libraryErrorReporter, DefinitionRequester.INSTANCE)
  private val typechecking = TypecheckingOrderingListener(InstanceProviderSet(), ConcreteReferableProvider.INSTANCE, IdReferableConverter.INSTANCE, errorReporter, PositionComparator.INSTANCE, LibraryArendExtensionProvider(libraryManager))

  /**
   * Recommended to call in an asynchronous environment.
   */
  fun registerLibrary(value: Path) {
    libraryResolver.registerLibrary(value)
    libraryResolver.addLibraryDirectory(value.parent)
  }

  fun reload() {
    for (library in libraryManager.registeredLibraries)
      typechecking.typecheckLibrary(library)
  }

  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    for (change in params.changes) {
      val path = Paths.get(parseURI(change.uri))
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

  override fun completion(position: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
    Logger.log(position.toString())
    return super.completion(position)
  }
}
