package org.ice1000.arend.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

class ArendServices : WorkspaceService, TextDocumentService {
  lateinit var projectRoot: Path
  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    for (change in params.changes) {
      val path = Paths.get(parseURI(change.uri))
    }
  }

  override fun didOpen(params: DidOpenTextDocumentParams) {
    Logger.log(params.toString())
  }

  override fun didChange(params: DidChangeTextDocumentParams) {
    Logger.log(params.toString())
  }

  override fun didClose(params: DidCloseTextDocumentParams) {
    Logger.log(params.toString())
  }

  override fun didSave(params: DidSaveTextDocumentParams) {
    Logger.log(params.toString())
  }

  override fun completion(position: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
    Logger.log(position.toString())
    return super.completion(position)
  }
}
