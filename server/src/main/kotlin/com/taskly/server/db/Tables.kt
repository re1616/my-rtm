package com.taskly.server.db

import org.jooq.*
import org.jooq.impl.*
import java.time.OffsetDateTime
import java.util.UUID

// All table fields typed as Field<T> — DSL.field() returns Field<T>,
// which is the base type jOOQ DSL expects in SELECT, INSERT, UPDATE etc.
// Use FIELD_NAME directly in select().from(TABLE) context.

object UsersTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("users"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val EMAIL = DSL.field(DSL.name("email"), SQLDataType.CLOB.nullable(false))
    /** Null for accounts created through Google sign-in that never set a password. */
    val PASSWORD_HASH = DSL.field(DSL.name("password_hash"), SQLDataType.CLOB.nullable(true))
    val TOTP_SECRET = DSL.field(DSL.name("totp_secret"), SQLDataType.CLOB.nullable(true))
    val TOTP_ENABLED = DSL.field(DSL.name("totp_enabled"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val GOOGLE_SUB = DSL.field(DSL.name("google_sub"), SQLDataType.CLOB.nullable(true))
    val TZ = DSL.field(DSL.name("tz"), SQLDataType.CLOB.nullable(false).defaultValue(DSL.inline("Europe/Moscow")))
    val LOCALE = DSL.field(DSL.name("locale"), SQLDataType.CLOB.nullable(false).defaultValue(DSL.inline("ru")))
    val PLAN = DSL.field(DSL.name("plan"), SQLDataType.CLOB.nullable(false).defaultValue(DSL.inline("free")))
    val SETTINGS = DSL.field(DSL.name("settings"), SQLDataType.JSONB.nullable(false).defaultValue(DSL.inline("{}", SQLDataType.JSONB)))
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object ListsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("lists"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val OWNER_ID = DSL.field(DSL.name("owner_id"), SQLDataType.UUID.nullable(false))
    val NAME = DSL.field(DSL.name("name"), SQLDataType.CLOB.nullable(false))
    val IS_SMART = DSL.field(DSL.name("is_smart"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val QUERY = DSL.field(DSL.name("query"), SQLDataType.CLOB)
    val COLOR = DSL.field(DSL.name("color"), SQLDataType.CLOB)
    val ARCHIVED = DSL.field(DSL.name("archived"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val SORT_ORDER = DSL.field(DSL.name("sort_order"), SQLDataType.INTEGER.nullable(false).defaultValue(DSL.inline(0)))
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val DELETED_AT = DSL.field(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object TasksTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("tasks"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val OWNER_ID = DSL.field(DSL.name("owner_id"), SQLDataType.UUID.nullable(false))
    val LIST_ID = DSL.field(DSL.name("list_id"), SQLDataType.UUID.nullable(false))
    val PARENT_ID = DSL.field(DSL.name("parent_id"), SQLDataType.UUID)
    val NAME = DSL.field(DSL.name("name"), SQLDataType.CLOB.nullable(false))
    val PRIORITY = DSL.field(DSL.name("priority"), SQLDataType.INTEGER)
    val DUE_AT = DSL.field(DSL.name("due_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val DUE_HAS_TIME = DSL.field(DSL.name("due_has_time"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val START_AT = DSL.field(DSL.name("start_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val ESTIMATE_MIN = DSL.field(DSL.name("estimate_min"), SQLDataType.INTEGER)
    val RRULE = DSL.field(DSL.name("rrule"), SQLDataType.CLOB)
    val REPEAT_MODE = DSL.field(DSL.name("repeat_mode"), SQLDataType.CLOB)
    val COMPLETED_AT = DSL.field(DSL.name("completed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val POSTPONED_COUNT = DSL.field(DSL.name("postponed_count"), SQLDataType.INTEGER.nullable(false).defaultValue(DSL.inline(0)))
    val URL = DSL.field(DSL.name("url"), SQLDataType.CLOB)
    val LOCATION_ID = DSL.field(DSL.name("location_id"), SQLDataType.UUID)
    val ASSIGNEE_ID = DSL.field(DSL.name("assignee_id"), SQLDataType.UUID)
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val FIELD_VERSIONS = DSL.field(DSL.name("field_versions"), SQLDataType.JSONB.nullable(false).defaultValue(DSL.inline("{}", SQLDataType.JSONB)))
    val DELETED_AT = DSL.field(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))

    /** All columns — used for RETURNING, since these plain-SQL tables have no generated field list. */
    val ALL: List<Field<*>> = listOf(
        ID, OWNER_ID, LIST_ID, PARENT_ID, NAME, PRIORITY, DUE_AT, DUE_HAS_TIME, START_AT, ESTIMATE_MIN,
        RRULE, REPEAT_MODE, COMPLETED_AT, POSTPONED_COUNT, URL, LOCATION_ID, ASSIGNEE_ID, VERSION,
        FIELD_VERSIONS, DELETED_AT, CREATED_AT, UPDATED_AT,
    )
}

object TagsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("tags"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val OWNER_ID = DSL.field(DSL.name("owner_id"), SQLDataType.UUID.nullable(false))
    val NAME = DSL.field(DSL.name("name"), SQLDataType.CLOB.nullable(false))
    val COLOR = DSL.field(DSL.name("color"), SQLDataType.CLOB)
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object TaskTagsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("task_tags"))
    val TASK_ID = DSL.field(DSL.name("task_id"), SQLDataType.UUID.nullable(false))
    val TAG_ID = DSL.field(DSL.name("tag_id"), SQLDataType.UUID.nullable(false))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object NotesTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("notes"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val TASK_ID = DSL.field(DSL.name("task_id"), SQLDataType.UUID.nullable(false))
    val CONTENT = DSL.field(DSL.name("content"), SQLDataType.CLOB.nullable(false))
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val DELETED_AT = DSL.field(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object AttachmentsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("attachments"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val TASK_ID = DSL.field(DSL.name("task_id"), SQLDataType.UUID.nullable(false))
    val NAME = DSL.field(DSL.name("name"), SQLDataType.CLOB.nullable(false))
    val URL = DSL.field(DSL.name("url"), SQLDataType.CLOB.nullable(false))
    val VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT.nullable(false).defaultValue(DSL.inline(1L)))
    val DELETED_AT = DSL.field(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object ChangeLogTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("change_log"))
    val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT.nullable(false))
    val SCOPE_ID = DSL.field(DSL.name("scope_id"), SQLDataType.CLOB.nullable(false))
    val ENTITY = DSL.field(DSL.name("entity"), SQLDataType.CLOB.nullable(false))
    val ENTITY_ID = DSL.field(DSL.name("entity_id"), SQLDataType.UUID.nullable(false))
    val OP = DSL.field(DSL.name("op"), SQLDataType.CLOB.nullable(false))
    val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.JSONB.nullable(false).defaultValue(DSL.inline("{}", SQLDataType.JSONB)))
    val ACTOR_ID = DSL.field(DSL.name("actor_id"), SQLDataType.UUID.nullable(false))
    val TS = DSL.field(DSL.name("ts"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object ProcessedOpsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("processed_ops"))
    val OP_ID = DSL.field(DSL.name("op_id"), SQLDataType.CLOB.nullable(false))
    val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.UUID.nullable(false))
    val RESULT = DSL.field(DSL.name("result"), SQLDataType.JSONB.nullable(false).defaultValue(DSL.inline("{}", SQLDataType.JSONB)))
    val TS = DSL.field(DSL.name("ts"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object LocationsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("locations"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val OWNER_ID = DSL.field(DSL.name("owner_id"), SQLDataType.UUID.nullable(false))
    val NAME = DSL.field(DSL.name("name"), SQLDataType.CLOB.nullable(false))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}

object OutboxTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("outbox"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.BIGINT.nullable(false))
    val TYPE = DSL.field(DSL.name("type"), SQLDataType.CLOB.nullable(false))
    val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.JSONB.nullable(false).defaultValue(DSL.inline("{}", SQLDataType.JSONB)))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
    val SENT_AT = DSL.field(DSL.name("sent_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
}

object PasswordResetTokensTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("password_reset_tokens"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.UUID.nullable(false))
    val TOKEN_HASH = DSL.field(DSL.name("token_hash"), SQLDataType.CLOB.nullable(false))
    val EXPIRES_AT = DSL.field(DSL.name("expires_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false))
    val USED_AT = DSL.field(DSL.name("used_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(true))
}

object RefreshTokensTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("refresh_tokens"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.UUID.nullable(false))
    val TOKEN_HASH = DSL.field(DSL.name("token_hash"), SQLDataType.CLOB.nullable(false))
    val EXPIRES_AT = DSL.field(DSL.name("expires_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false))
    val REVOKED = DSL.field(DSL.name("revoked"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
    val CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.function("now", SQLDataType.TIMESTAMPWITHTIMEZONE)))
}
object RemindersTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("reminders"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val TASK_ID = DSL.field(DSL.name("task_id"), SQLDataType.UUID.nullable(false))
    val FIRE_AT = DSL.field(DSL.name("fire_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false))
    val CHANNEL = DSL.field(DSL.name("channel"), SQLDataType.CLOB.nullable(false))
    val SENT = DSL.field(DSL.name("sent"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
}

object PushSubscriptionsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("push_subscriptions"))
    val ID = DSL.field(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("gen_random_uuid()", SQLDataType.UUID)))
    val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.UUID.nullable(false))
    val ENDPOINT = DSL.field(DSL.name("endpoint"), SQLDataType.CLOB.nullable(false))
    val P256DH = DSL.field(DSL.name("p256dh"), SQLDataType.CLOB.nullable(false))
    val AUTH = DSL.field(DSL.name("auth"), SQLDataType.CLOB.nullable(false))
}

object DigestLogTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("digest_log"))
    val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.UUID.nullable(false))
    val DAY = DSL.field(DSL.name("day"), SQLDataType.LOCALDATE.nullable(false))
}
