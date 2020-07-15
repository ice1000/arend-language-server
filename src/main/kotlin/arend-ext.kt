package org.ice1000.arend.lsp

import org.arend.ext.error.ErrorReporter
import org.arend.extImpl.DefinitionRequester
import org.arend.library.Library
import org.arend.library.LibraryManager
import org.arend.library.resolver.LibraryResolver
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.eclipse.lsp4j.Range
import java.util.*
import org.arend.frontend.parser.Position as AntlrPosition
import org.eclipse.lsp4j.Position as LspPosition

/*
operator fun AntlrPosition.contains(sub: AntlrPosition) =
    sub.line == line && sub.column >= column &&
        sub.column + sub.sourceName.length <= column + sourceName.length
*/

/**
 * Because [LspPosition] is 0-based, while [AntlrPosition] is 1-based.
 */
fun AntlrPosition.contains(pos: LspPosition, nameLength: Int) =
    pos.line + 1 == line && pos.character + 1 in column..column + nameLength

fun AntlrPosition.toRange(nameLength: Int) = Range(
    LspPosition(line - 1, column - 1),
    LspPosition(line - 1, column + nameLength - 1)
)

class LspLibraryManager(libraryResolver: LibraryResolver, instanceProviders: InstanceProviderSet, errorReporter: ErrorReporter, libraryErrorReporter: ErrorReporter)
  : LibraryManager(libraryResolver, instanceProviders, errorReporter, libraryErrorReporter, DefinitionRequester.INSTANCE) {
  private val times: Deque<Long> = ArrayDeque()
  override fun beforeLibraryLoading(library: Library) {
    Logger.i("[INFO] Loading library " + library.name)
    times.push(System.currentTimeMillis())
  }

  override fun afterLibraryLoading(library: Library, successful: Boolean) {
    val time = System.currentTimeMillis() - times.pop()
    Logger.i("[INFO] " + (if (successful) "Loaded " else "Failed loading ") + "library " + library.name + if (successful) " (" + org.arend.frontend.library.TimedLibraryManager.timeToString(time) + ")" else "")
  }
}
