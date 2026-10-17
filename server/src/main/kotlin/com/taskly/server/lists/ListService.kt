package com.taskly.server.lists

import com.taskly.server.db.Database
import com.taskly.server.db.ListsTable
import com.taskly.server.dto.*
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * List service providing CRUD operations for task lists.
 */
object ListService {

    fun create(userId: String, request: CreateListRequest): ListResponse {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val now = OffsetDateTime.now()

        val record = ctx.insertInto(ListsTable.TABLE)
            .set(ListsTable.OWNER_ID, ownerId)
            .set(ListsTable.NAME, request.name)
            .set(ListsTable.COLOR, request.color)
            .set(ListsTable.IS_SMART, request.isSmart ?: false)
            .set(ListsTable.QUERY, request.query)
            .set(ListsTable.CREATED_AT, now)
            .set(ListsTable.UPDATED_AT, now)
            .returning(
                ListsTable.ID, ListsTable.NAME, ListsTable.IS_SMART,
                ListsTable.QUERY, ListsTable.COLOR, ListsTable.SORT_ORDER,
                ListsTable.VERSION, ListsTable.CREATED_AT
            )
            .fetchOne()!!

        return toResponse(record)
    }

    fun get(userId: String, listId: String): ListResponse? {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val record = ctx.selectFrom(ListsTable.TABLE)
            .where(ListsTable.ID.eq(UUID.fromString(listId)))
            .and(ListsTable.OWNER_ID.eq(ownerId))
            .and(ListsTable.DELETED_AT.isNull)
            .fetchOne() ?: return null

        return toResponse(record)
    }

    fun list(userId: String): List<ListResponse> {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val records = ctx.selectFrom(ListsTable.TABLE)
            .where(ListsTable.OWNER_ID.eq(ownerId))
            .and(ListsTable.DELETED_AT.isNull)
            .orderBy(ListsTable.SORT_ORDER.asc(), ListsTable.CREATED_AT.asc())
            .fetch()

        return records.map { toResponse(it) }
    }

    fun update(userId: String, listId: String, request: UpdateListRequest): ListResponse {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)
        val id = UUID.fromString(listId)

        val existing = ctx.selectFrom(ListsTable.TABLE)
            .where(ListsTable.ID.eq(id))
            .and(ListsTable.OWNER_ID.eq(ownerId))
            .and(ListsTable.DELETED_AT.isNull)
            .fetchOne()
            ?: throw IllegalArgumentException("List not found")

        if (existing.get(ListsTable.VERSION) != request.version) {
            throw IllegalArgumentException("Version conflict")
        }

        val now = OffsetDateTime.now()
        val update = ctx.update(ListsTable.TABLE)
            .set(ListsTable.UPDATED_AT, now)
            .set(ListsTable.VERSION, existing.get(ListsTable.VERSION) + 1)

        request.name?.let { update.set(ListsTable.NAME, it) }
        request.color?.let { update.set(ListsTable.COLOR, it) }
        request.isSmart?.let { update.set(ListsTable.IS_SMART, it) }
        request.query?.let { update.set(ListsTable.QUERY, it) }
        request.sortOrder?.let { update.set(ListsTable.SORT_ORDER, it) }
        request.archived?.let { update.set(ListsTable.ARCHIVED, it) }

        update
            .where(ListsTable.ID.eq(id))
            .and(ListsTable.VERSION.eq(request.version))
            .execute()

        val updated = ctx.selectFrom(ListsTable.TABLE)
            .where(ListsTable.ID.eq(id))
            .fetchOne()!!

        return toResponse(updated)
    }

    fun delete(userId: String, listId: String) {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val deleted = ctx.update(ListsTable.TABLE)
            .set(ListsTable.DELETED_AT, OffsetDateTime.now())
            .set(ListsTable.UPDATED_AT, OffsetDateTime.now())
            .where(ListsTable.ID.eq(UUID.fromString(listId)))
            .and(ListsTable.OWNER_ID.eq(ownerId))
            .execute()

        if (deleted == 0) {
            throw IllegalArgumentException("List not found")
        }
    }

    private fun toResponse(rec: org.jooq.Record): ListResponse {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        return ListResponse(
            id = rec.get(ListsTable.ID).toString(),
            name = rec.get(ListsTable.NAME),
            isSmart = rec.get(ListsTable.IS_SMART),
            query = rec.get(ListsTable.QUERY),
            color = rec.get(ListsTable.COLOR),
            sortOrder = rec.get(ListsTable.SORT_ORDER),
            version = rec.get(ListsTable.VERSION),
            createdAt = rec.get(ListsTable.CREATED_AT).format(fmt)
        )
    }
}