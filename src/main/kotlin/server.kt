package org.ice1000.arend.lsp

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.arend.frontend.repl.jline.JLineCliRepl
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

fun main(argv: Array<String>) {
  val opt = Options()
  opt.addOption("h", "help", false, "print this message")
  opt.addOption(Option.builder("c")
      .longOpt("client-port")
      .hasArg()
      .argName("port")
      .desc("language client port")
      .build())
  opt.addOption(Option.builder("s")
      .longOpt("server-port")
      .hasArg()
      .argName("port")
      .desc("language server port")
      .build())
  opt.addOption(Option.builder("a")
      .longOpt("client-host")
      .hasArg()
      .argName("host")
      .optionalArg(true)
      .desc("language client hostname, default localhost")
      .build())
  opt.addOption(Option.builder("i")
      .longOpt("interactive")
      .optionalArg(true)
      .desc("start the Arend REPL")
      .build())

  val cmdLine = DefaultParser().parse(opt, argv)
  if (cmdLine.hasOption("h")) {
    HelpFormatter().printHelp("arend-lsp [OPTIONS]", opt)
    return
  }
  if (cmdLine.hasOption("i")) {
    JLineCliRepl.launch(false, emptyList())
    return
  }

  val (inStream, outStream) = cmdLine.getOptionValue('c')?.let { clientPort ->
    tcpConnectToClient(cmdLine.getOptionValue('a', "localhost"), clientPort.toInt())
  } ?: cmdLine.getOptionValue('s')?.let { serverPort ->
    tcpStartServer(serverPort.toInt())
  } ?: Pair(System.`in`, System.out)

  val server = ArendLanguageServer()
  val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
  val launcher = LSPLauncher.createServerLauncher(server, ExitingInputStream(inStream), outStream, threads) { it }

  server.connect(launcher.remoteProxy)
  launcher.startListening()
}

class ArendLanguageServer : LanguageServer, LanguageClientAware {
  private val sabisu = ArendServices()

  override fun initialize(params: InitializeParams) = CompletableFuture.supplyAsync {
    val serverCapabilities = ServerCapabilities()
    serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.None)
    serverCapabilities.completionProvider = CompletionOptions(true, listOf("QWERTYUIOPASDFGHJKLZXCVBNM.qwertyuiopasdfghjklzxcvbnm+-*/_[]:"))
    serverCapabilities.definitionProvider = Either.forLeft(true)
    serverCapabilities.workspace = WorkspaceServerCapabilities().apply {
      workspaceFolders = WorkspaceFoldersOptions().apply {
        supported = true
        setChangeNotifications(true)
      }
    }
    // val clientCapabilities = params.capabilities
    params.workspaceFolders.forEach { uri ->
      sabisu.registerLibrary(Paths.get(parseURI(uri.uri)))
    }

    sabisu.reload()
    InitializeResult(serverCapabilities)
  }

  override fun connect(client: LanguageClient) = IO.initialize(client)
  override fun getWorkspaceService() = sabisu
  override fun getTextDocumentService() = sabisu
  override fun exit() = Unit

  override fun shutdown(): CompletableFuture<Any?> =
      CompletableFuture.completedFuture(null)
}
