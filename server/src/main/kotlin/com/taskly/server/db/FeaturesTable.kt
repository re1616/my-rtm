package com.taskly.server.db

import org.jooq.*
import org.jooq.impl.*

object FeaturesTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("feature_flags"))
    val KEY = DSL.field(DSL.name("key"), SQLDataType.CLOB.nullable(false))
    val DESCRIPTION = DSL.field(DSL.name("description"), SQLDataType.CLOB)
    val ROLLOUT_PCT = DSL.field(DSL.name("rollout_pct"), SQLDataType.INTEGER.nullable(false).defaultValue(DSL.inline(100)))
    val PLAN_FILTER = DSL.field(DSL.name("plan_filter"), SQLDataType.CLOB)
    val VERSION_MIN = DSL.field(DSL.name("version_min"), SQLDataType.CLOB)
    val VERSION_MAX = DSL.field(DSL.name("version_max"), SQLDataType.CLOB)
    val ENABLED = DSL.field(DSL.name("enabled"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
    val KILL_SWITCH = DSL.field(DSL.name("kill_switch"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}