package com.taskly.server.features

import com.taskly.server.db.Database
import com.taskly.server.db.FeaturesTable
import com.taskly.server.db.UsersTable
import org.jooq.impl.DSL
import java.security.MessageDigest
import java.util.*

/**
 * Feature flags service (SEC-7).
 * Returns active flags for a user based on their plan and client version.
 */
object FeaturesService {

    fun getFeatures(userId: String, plan: String?, version: String?): Map<String, Boolean> {
        val effectivePlan = plan ?: lookupUserPlan(userId)
        val effectiveVersion = version ?: "0.0.0"
        val flags = loadFlagsFromDb(effectivePlan, effectiveVersion)
        return applyRollout(flags, userId)
    }

    private fun lookupUserPlan(userId: String): String {
        val ctx = Database.ctx()
        return ctx.select(UsersTable.PLAN)
            .from(UsersTable.TABLE)
            .where(UsersTable.ID.eq(java.util.UUID.fromString(userId)))
            .fetchOneInto(String::class.java) ?: "free"
    }

    private fun loadFlagsFromDb(plan: String, version: String): List<FlagRow> {
        val ctx = Database.ctx()
        val records = ctx.selectFrom(FeaturesTable.TABLE)
            .where(FeaturesTable.ENABLED.isTrue)
            .and(FeaturesTable.KILL_SWITCH.isFalse)
            .and(
                FeaturesTable.PLAN_FILTER.isNull
                    .or(DSL.lower(FeaturesTable.PLAN_FILTER).like(DSL.inline("%$plan%")))
            )
            .and(
                FeaturesTable.VERSION_MIN.isNull
                    .or(FeaturesTable.VERSION_MIN.le(version))
            )
            .and(
                FeaturesTable.VERSION_MAX.isNull
                    .or(FeaturesTable.VERSION_MAX.ge(version))
            )
            .fetch()

        return records.map { rec ->
            FlagRow(
                key = rec.get(FeaturesTable.KEY),
                enabled = rec.get(FeaturesTable.ENABLED),
                rolloutPct = rec.get(FeaturesTable.ROLLOUT_PCT),
                killSwitch = rec.get(FeaturesTable.KILL_SWITCH)
            )
        }
    }

    private class FlagRow(
        val key: String,
        val enabled: Boolean,
        val rolloutPct: Int,
        val killSwitch: Boolean
    )

    private fun applyRollout(flags: List<FlagRow>, userId: String): Map<String, Boolean> {
        val userBucket = rolloutBucket(userId)
        val result = HashMap<String, Boolean>()
        for (flag in flags) {
            val inRollout = userBucket < flag.rolloutPct
            result[flag.key] = flag.enabled && inRollout && !flag.killSwitch
        }
        return result
    }

    private fun rolloutBucket(userId: String): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        return (hash[hash.size - 1].toInt() and 0xFF) * 100 / 256
    }
}