package com.taskly.shared.query

/** Поля языка запросов (SPEC F-3). */
enum class QueryField { LIST, TAG, PRIORITY, DUE, DUE_BEFORE, DUE_WITHIN, COMPLETED, IS_REPEATING, HAS_SUBTASKS, LOCATION, IS_SHARED, ADDED_WITHIN, POSTPONED, TIME_ESTIMATE }

/** Операторы сравнения полей. */
enum class CmpOp { EQ, LT, LTE, GT, GTE, CONTAINS }

/** Сырое значение условия; интерпретируется по полю (число/строка/булево). */
sealed interface FieldValue {
    data class Str(val value: String) : FieldValue
    data class Num(val value: Int) : FieldValue
    data class Bool(val value: Boolean) : FieldValue
}

/**
 * AST языка запросов: AND/OR/NOT, скобки, условия по полям, свободный текст.
 * Исполняется как предикат над локальной базой и транслируется в SQL (сервер).
 */
sealed interface QueryExpr {
    data class And(val children: List<QueryExpr>) : QueryExpr
    data class Or(val children: List<QueryExpr>) : QueryExpr
    data class Not(val child: QueryExpr) : QueryExpr
    data class Field(val field: QueryField, val op: CmpOp, val value: FieldValue) : QueryExpr
    data class Text(val term: String) : QueryExpr
}

/**
 * Рекурсивный спуск: or → and → unary → primary.
 * Грамматика:
 *   expr    := or
 *   or      := and ( OR and )*
 *   and     := unary ( AND unary )*
 *   unary   := NOT unary | primary
 *   primary := '(' expr ')' | FIELD ':' (CMP)? value | text-phrase
 */
class QueryParser(private val tokens: List<Token>) {

    private var pos = 0

    constructor(input: String) : this(QueryLexer(input).tokenize())

    fun parse(): QueryExpr {
        val e = parseOr()
        if (current().type != TokenType.EOF) {
            throw QueryParseException("Unexpected token '${current().text}'", current().start)
        }
        return e
    }

    private fun parseOr(): QueryExpr {
        val parts = mutableListOf(parseAnd())
        while (current().type == TokenType.OR) {
            advance()
            parts += parseAnd()
        }
        return if (parts.size == 1) parts[0] else QueryExpr.Or(parts)
    }

    private fun parseAnd(): QueryExpr {
        val parts = mutableListOf(parseUnary())
        while (current().type == TokenType.AND) {
            advance()
            parts += parseUnary()
        }
        return if (parts.size == 1) parts[0] else QueryExpr.And(parts)
    }

    private fun parseUnary(): QueryExpr {
        if (current().type == TokenType.NOT) {
            advance()
            return QueryExpr.Not(parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): QueryExpr {
        val t = current()
        if (t.type == TokenType.LPAREN) {
            advance()
            val inner = parseOr()
            expect(TokenType.RPAREN, "Expected ')'")
            return inner
        }
        if (t.type == TokenType.IDENT && peek(1).type == TokenType.COLON) {
            return parseFieldCondition()
        }
        return parseTextPhrase()
    }

    private fun parseFieldCondition(): QueryExpr {
        val nameTok = advance()
        advance() // ':'
        val field = fieldByName(nameTok.text) ?: throw QueryParseException(
            "Unknown field '${nameTok.text}' (expected: list, tag, priority, due, dueBefore, dueWithin, completed, isRepeating, hasSubtasks, location, isShared, addedWithin, postponed, timeEstimate)",
            nameTok.start,
        )
        var op = CmpOp.EQ
        if (current().type == TokenType.COMPARATOR) {
            op = when (advance().text) {
                ">" -> CmpOp.GT
                ">=" -> CmpOp.GTE
                "<" -> CmpOp.LT
                "<=" -> CmpOp.LTE
                else -> throw QueryParseException("Unexpected comparator", current().start)
            }
        }
        val value = parseValue()
        return QueryExpr.Field(field, op, value)
    }

    private fun parseValue(): FieldValue {
        val t = current()
        when (t.type) {
            TokenType.STRING -> { advance(); return FieldValue.Str(t.text) }
            TokenType.NUMBER -> {
                advance()
                return FieldValue.Num(t.text.toInt())
            }
            TokenType.IDENT, TokenType.AND, TokenType.OR, TokenType.NOT -> {
                advance()
                return when (t.text.lowercase()) {
                    "true" -> FieldValue.Bool(true)
                    "false" -> FieldValue.Bool(false)
                    else -> FieldValue.Str(t.text)
                }
            }
            else -> throw QueryParseException("Expected value after ':'", t.start)
        }
    }

    /** Свободный текст: несколько слов подряд склеиваются в одну фразу. */
    private fun parseTextPhrase(): QueryExpr {
        val words = mutableListOf<String>()
        val startTok = current()
        // Операторы не могут начинать выражение
        if (startTok.type == TokenType.AND || startTok.type == TokenType.OR || startTok.type == TokenType.NOT) {
            throw QueryParseException("Unexpected operator '${startTok.text}'", startTok.start)
        }
        while (true) {
            val t = current()
            val isWord = t.type == TokenType.IDENT || t.type == TokenType.STRING ||
                t.type == TokenType.NUMBER || t.type == TokenType.AND ||
                t.type == TokenType.OR || t.type == TokenType.NOT
            if (!isWord) break
            // Стоп перед полем: IDENT COLON
            if (t.type == TokenType.IDENT && peek(1).type == TokenType.COLON) break
            // Стоп: обращаем AND/OR/NOT в операторы, если за ними не значение (строка/число)
            words += t.text
            advance()
        }
        if (words.isEmpty()) {
            throw QueryParseException("Unexpected token '${startTok.text}'", startTok.start)
        }
        return QueryExpr.Text(words.joinToString(" "))
    }

    private fun fieldByName(name: String): QueryField? = when (name.lowercase()) {
        "list" -> QueryField.LIST
        "tag" -> QueryField.TAG
        "priority" -> QueryField.PRIORITY
        "due" -> QueryField.DUE
        "duebefore" -> QueryField.DUE_BEFORE
        "duewithin" -> QueryField.DUE_WITHIN
        "completed" -> QueryField.COMPLETED
        "isrepeating" -> QueryField.IS_REPEATING
        "hassubtasks" -> QueryField.HAS_SUBTASKS
        "location" -> QueryField.LOCATION
        "isshared" -> QueryField.IS_SHARED
        "addedwithin" -> QueryField.ADDED_WITHIN
        "postponed" -> QueryField.POSTPONED
        "timeestimate" -> QueryField.TIME_ESTIMATE
        else -> null
    }

    private fun current(): Token = tokens[pos]

    private fun peek(ahead: Int): Token = tokens[minOf(pos + ahead, tokens.size - 1)]

    private fun advance(): Token = tokens[pos].also { if (pos < tokens.size - 1) pos++ }

    private fun expect(type: TokenType, msg: String) {
        if (current().type != type) throw QueryParseException(msg, current().start)
        advance()
    }

    companion object {
        fun parse(input: String): QueryExpr = QueryParser(input).parse()
    }
}