package com.taskly.server.tags

import com.taskly.server.db.Database
import com.taskly.server.db.TagsTable
import com.taskly.server.dto.*
import org.jooq.impl.DSL
import java.util.*

/**
 * Tag service providing CRUD operations.
 */
object TagService {

    fun create(userId: String, request: CreateTagRequest): TagResponse {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        // Check for duplicate name
        val existing = ctx.select(DSL.count())
            .from(TagsTable.TABLE)
            .where(TagsTable.OWNER_ID.eq(ownerId))
            .and(DSL.lower(TagsTable.NAME).eq(request.name.lowercase()))
            .fetchOneInto(Int::class.java) ?: 0

        if (existing > 0) {
            throw IllegalArgumentException("Tag with this name already exists")
        }

        val record = ctx.insertInto(TagsTable.TABLE)
            .set(TagsTable.OWNER_ID, ownerId)
            .set(TagsTable.NAME, request.name.trim())
            .set(TagsTable.COLOR, request.color)
            .returning(
                TagsTable.ID, TagsTable.NAME, TagsTable.COLOR,
                TagsTable.VERSION, TagsTable.CREATED_AT
            )
            .fetchOne()!!

        return TagResponse(
            id = record.get(TagsTable.ID).toString(),
            name = record.get(TagsTable.NAME),
            color = record.get(TagsTable.COLOR),
            version = record.get(TagsTable.VERSION)
        )
    }

    fun list(userId: String): List<TagResponse> {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val records = ctx.selectFrom(TagsTable.TABLE)
            .where(TagsTable.OWNER_ID.eq(ownerId))
            .orderBy(DSL.lower(TagsTable.NAME).asc())
            .fetch()

        return records.map { rec ->
            TagResponse(
                id = rec.get(TagsTable.ID).toString(),
                name = rec.get(TagsTable.NAME),
                color = rec.get(TagsTable.COLOR),
                version = rec.get(TagsTable.VERSION)
            )
        }
    }

    fun delete(userId: String, tagId: String) {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val deleted = ctx.deleteFrom(TagsTable.TABLE)
            .where(TagsTable.ID.eq(UUID.fromString(tagId)))
            .and(TagsTable.OWNER_ID.eq(ownerId))
            .execute()

        if (deleted == 0) {
            throw IllegalArgumentException("Tag not found")
        }
    }
}