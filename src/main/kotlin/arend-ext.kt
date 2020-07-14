package org.ice1000.arend.lsp

import org.eclipse.lsp4j.Range
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
