package org.ice1000.arend.lsp

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.eclipse.lsp4j.*
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

  val cmdLine = DefaultParser().parse(opt, argv)
  if (cmdLine.hasOption("h")) {
    HelpFormatter().printHelp("arend-lsp [OPTIONS]", opt)
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

  override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
    val serverCapabilities = ServerCapabilities()
    serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    serverCapabilities.workspace = WorkspaceServerCapabilities().apply {
      workspaceFolders = WorkspaceFoldersOptions().apply {
        supported = true
        setChangeNotifications(true)
      }
    }
    // val clientCapabilities = params.capabilities
    params.rootUri?.let { uri ->
      sabisu.registerLibrary(Paths.get(parseURI(uri)))
    }

    return CompletableFuture.supplyAsync {
      sabisu.reload()
      InitializeResult(serverCapabilities)
    }
  }

  override fun connect(client: LanguageClient) {
    Logger.initialize { msg, type ->
      client.logMessage(MessageParams(type, msg))
    }
  }

  override fun getWorkspaceService() = sabisu
  override fun getTextDocumentService() = sabisu
  override fun exit() = Unit

  override fun shutdown(): CompletableFuture<Any?> =
      CompletableFuture.completedFuture(null)
}
