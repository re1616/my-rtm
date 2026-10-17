package com.taskly.shared.query

/** Типы токенов языка запросов умных списков (SPEC F-3). */
enum class TokenType { LPAREN, RPAREN, COLON, COMPARATOR, AND, OR, NOT, IDENT, NUMBER, STRING, EOF }

data class Token(val type: TokenType, val text: String, val start: Int, val end: Int)

class QueryParseException(message: String, val position: Int) :
    IllegalArgumentException("$message (position $position)")

/**
 * Лексер языка запросов (SPEC F-3): AND/OR/NOT, скобки, поля `field:`, значения
 * в кавычках ("1 week"), сравнения `>`/`>=`/`<`/`<=`. Слова свободного текста — IDENT.
 * Ключевые слова AND/OR/NOT распознаются только вне позиции значения (перед ними нет `:`)
 * и только как отдельное слово.
 */
class QueryLexer(private val input: String) {

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                c == '(' -> { out += Token(TokenType.LPAREN, "(", i, i + 1); i++ }
                c == ')' -> { out += Token(TokenType.RPAREN, ")", i, i + 1); i++ }
                c == ':' -> { out += Token(TokenType.COLON, ":", i, i + 1); i++ }
                c == '>' || c == '<' -> {
                    var text = c.toString()
                    var end = i + 1
                    if (end < n && (input[end] == '=')) { text += '='; end++ }
                    out += Token(TokenType.COMPARATOR, text, i, end)
                    i = end
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        val ch = input[j]
                        if (ch == '\\' && j + 1 < n) { sb.append(input[j + 1]); j += 2; continue }
                        if (ch == '"') { closed = true; j++; break }
                        sb.append(ch)
                        j++
                    }
                    if (!closed) throw QueryParseException("Unterminated string literal", i)
                    out += Token(TokenType.STRING, sb.toString(), i, j)
                    i = j
                }
                else -> {
                    val start = i
                    val sb = StringBuilder()
                    while (i < n && input[i] !in " \t\n\r():\"<>") {
                        sb.append(input[i]); i++
                    }
                    val word = sb.toString()
                    val wl = word.lowercase()
                    val isKeywordWord = wl == "and" || wl == "or" || wl == "not"
                    val nextIsColon = i < n && input[i] == ':'
                    if (isKeywordWord && !nextIsColon) {
                        val type = when (wl) {
                            "and" -> TokenType.AND
                            "or" -> TokenType.OR
                            else -> TokenType.NOT
                        }
                        out += Token(type, word, start, i)
                    } else if (word.all { it.isDigit() }) {
                        out += Token(TokenType.NUMBER, word, start, i)
                    } else {
                        out += Token(TokenType.IDENT, word, start, i)
                    }
                }
            }
        }
        out += Token(TokenType.EOF, "", n, n)
        return out
    }
}