package com.taskly.shared.query

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The equivalence corpus evaluates the SqlPred *structure*; this test pins the rendered SQL
 * text, where operator precedence matters (`a AND (b OR c)` ≠ `a AND b OR c`).
 */
class SqlRendererTest {

    @Test
    fun nestedOrInsideAndKeepsParentheses() {
        val pred = SqlPred.And(
            listOf(
                SqlPred.ListName("Работа", false),
                SqlPred.Or(
                    listOf(
                        SqlPred.Column(TaskCol.PRIORITY, CmpOp.EQ, Param.Num(1)),
                        SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, Param.Ts("2026-10-20T21:00:00Z")),
                    )
                ),
            )
        )
        val (sql, params) = SqlRenderer.render(pred)
        assertEquals(
            "( ( EXISTS (SELECT 1 FROM lists l WHERE l.id = t.list_id AND lower(l.name) = lower(?)) ) AND " +
                "( ( t.priority = ? ) OR ( t.due_at < ? ) ) )",
            sql,
        )
        assertEquals(3, params.size)
    }

    @Test
    fun postponedUsesTheRealColumnName() {
        val (sql, _) = SqlRenderer.render(SqlPred.Column(TaskCol.POSTPONED, CmpOp.GT, Param.Num(2)))
        assertEquals("( t.postponed_count > ? )", sql)
    }
}
