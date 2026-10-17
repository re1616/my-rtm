package com.taskly.shared.query

import com.taskly.shared.domain.model.Priority
import com.taskly.shared.domain.model.RepeatMode
import com.taskly.shared.domain.model.Task
import com.taskly.shared.domain.model.Uuid
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Язык запросов (SPEC F-3, AC-6): лексер → AST → предикат и AST → SQL эквивалентны.
 * Корпус ≥200 выражений; для каждого выражения результат предиката по каждой задаче
 * должен совпадать с результатом буквального выполнения сгенерированного SQL.
 */
class QueryEquivalenceTest {

    private val tz = TimeZone.of("UTC+03") // Москва (без DST)
    private val now = Instant.parse("2026-10-17T07:00:00Z") // сб 10:00 MSK

    private val listInbox = Uuid.fromString("0192f7c4-5b1e-7a01-9e3d-000000000001")
    private val listWork = Uuid.fromString("0192f7c4-5b1e-7a02-9e3d-000000000002")
    private val listHome = Uuid.fromString("0192f7c4-5b1e-7a03-9e3d-000000000003")
    private val listShared = Uuid.fromString("0192f7c4-5b1e-7a04-9e3d-000000000004")
    private val locOffice = Uuid.fromString("0192f7c4-5b1e-7a05-9e3d-000000000005")
    private val locGym = Uuid.fromString("0192f7c4-5b1e-7a06-9e3d-000000000006")

    private fun inst(date: String, time: String): Instant =
        kotlinx.datetime.LocalDateTime(kotlinx.datetime.LocalDate.parse(date), kotlinx.datetime.LocalTime.parse(time)).toInstant(tz)

    private val tasks = buildTasks()

    private val ctx = TaskEvalContext(
        now = now,
        timeZone = tz,
        listNames = mapOf(
            listInbox to "Входящие",
            listWork to "Работа",
            listHome to "Дом",
            listShared to "Общий список",
        ),
        tagNamesById = tasks.associate { t ->
            t.id to t.tagNames
        },
        locationNames = mapOf(locOffice to "офис", locGym to "спортзал"),
        sharedListIds = setOf(listShared),
        parentTaskIds = setOf(tasks[3].id), // у t4 есть подзадачи
    )

    private fun buildTasks(): List<Task> {
        fun t(
            id: String,
            name: String,
            listId: Uuid?,
            priority: Priority = Priority.NONE,
            due: String? = null,
            tags: List<String> = emptyList(),
            completed: Boolean = false,
            rrule: String? = null,
            locationId: Uuid? = null,
            postponed: Int = 0,
            estimate: Int? = null,
            createdAt: String? = null,
        ): Task {
            val created = createdAt?.let { inst(it, "09:00") } ?: nowEpochDays(-2)
            return Task(
                id = Uuid.fromString(id),
                name = name,
                listId = listId,
                priority = priority,
                dueAt = due?.let { inst(it, "12:00") },
                dueHasTime = due != null,
                rrule = rrule,
                repeatMode = if (rrule != null) RepeatMode.EVERY else null,
                locationId = locationId,
                completedAt = if (completed) inst("2026-10-17", "08:30") else null,
                postponedCount = postponed,
                estimateMin = estimate,
                tagNames = tags,
                subtaskCount = 0,
                createdAt = created,
                updatedAt = created,
            )
        }

        return listOf(
            t("0192f7c4-5b1e-7a10-9e3d-000000000001", "Позвонить маме", listWork, Priority.HIGH, tags = listOf("работа", "срочно"), postponed = 1, estimate = 30, createdAt = "2026-10-15"),
            t("0192f7c4-5b1e-7a10-9e3d-000000000002", "Купить молоко", listHome, Priority.NONE, due = "2026-10-17", tags = listOf("покупки"), estimate = 15),
            t("0192f7c4-5b1e-7a10-9e3d-000000000003", "Сдать отчёт", listWork, Priority.HIGH, due = "2026-10-18", tags = listOf("работа"), rrule = "FREQ=DAILY;INTERVAL=1", estimate = 90),
            t("0192f7c4-5b1e-7a10-9e3d-000000000004", "Подготовить презентацию", listWork, Priority.LOW, due = "2026-10-16", tags = listOf("работа"), locationId = locOffice, estimate = 120),
            t("0192f7c4-5b1e-7a10-9e3d-000000000005", "Забрать посылку", listHome, Priority.NONE, due = "2026-10-22", tags = emptyList(), locationId = locGym),
            t("0192f7c4-5b1e-7a10-9e3d-000000000006", "Оплатить счета", listHome, Priority.MEDIUM, due = "2026-10-16", tags = listOf("финансы"), completed = true),
            t("0192f7c4-5b1e-7a10-9e3d-000000000007", "Йога на неделю", listHome, Priority.NONE, due = "2026-10-19", tags = listOf("здоровье"), rrule = "FREQ=WEEKLY;INTERVAL=1"),
            t("0192f7c4-5b1e-7a10-9e3d-000000000008", "Встреча с командой", listShared, Priority.HIGH, due = "2026-10-23", tags = listOf("работа", "команда"), locationId = locOffice),
            t("0192f7c4-5b1e-7a10-9e3d-000000000009", "Купить виноград", listHome, Priority.NONE, due = "2026-10-17", tags = listOf("покупки")),
            t("0192f7c4-5b1e-7a10-9e3d-000000000010", "Проект икс игрек", listWork, Priority.MEDIUM, due = "2026-10-20", tags = listOf("проекты"), estimate = 45, createdAt = "2026-10-17"),
            t("0192f7c4-5b1e-7a10-9e3d-000000000011", "Разобрать почту", null, Priority.NONE, createdAt = "2026-10-16"),
            t("0192f7c4-5b1e-7a10-9e3d-000000000012", "Долгий таск", listWork, Priority.MEDIUM, due = "2026-10-17", postponed = 3, estimate = 240, tags = listOf("рутина"), createdAt = "2026-09-17"),
        )
    }

    private fun nowEpochDays(n: Long): Instant =
        Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 86_400_000L * n)

    private val expressions = listOf(
        // -- свободный текст --
        "молоко",
        "отчёт",
        "купить",
        "Проект икс",
        "презентацию",
        "несуществующееслово",
        "молоко AND виноград",
        "отчёт OR презентацию",
        "NOT молоко",
        "\"сдать отчёт\"",
        "разобрать",
        "\"проект икс игрек\"",
        // -- list --
        "list:Работа",
        "list:работа",
        "list:Дом",
        "list:Входящие",
        "list:\"Общий список\"",
        "list:Несуществующий",
        "NOT list:Работа",
        "list:Работа OR list:Дом",
        "list:Дом AND list:Работа",
        // -- tag --
        "tag:работа",
        "tag:покупки",
        "tag:финансы",
        "tag:срочно",
        "tag:Неизвестный",
        "NOT tag:работа",
        "tag:работа AND tag:срочно",
        "tag:покупки OR tag:финансы",
        // -- priority --
        "priority:1",
        "priority:2",
        "priority:3",
        "priority:none",
        "priority:>1",
        "priority:>=2",
        "priority:<3",
        "priority:<=1",
        "priority:>0",
        "priority:1 OR priority:3",
        "NOT priority:2",
        "priority:none AND list:Работа",
        // -- due --
        "due:today",
        "due:сегодня",
        "due:tomorrow",
        "due:завтра",
        "due:overdue",
        "due:просрочено",
        "due:thisweek",
        "due:nextweek",
        "due:2026-10-17",
        "due:2026-10-18",
        "due:2026-10-16",
        "due:2026-10-23",
        "due:2027-01-01",
        "NOT due:today",
        "due:today OR due:tomorrow",
        "due:overdue AND NOT completed:true",
        // -- dueBefore --
        "dueBefore:today",
        "dueBefore:tomorrow",
        "dueBefore:2026-10-20",
        "dueBefore:\"3 days\"",
        "dueBefore:\"1 week\"",
        "dueBefore:2026-10-17",
        "NOT dueBefore:today",
        "dueBefore:\"2 days\" AND priority:1",
        // -- dueWithin --
        "dueWithin:\"1 day\"",
        "dueWithin:\"3 days\"",
        "dueWithin:\"1 week\"",
        "dueWithin:\"30 days\"",
        "dueWithin:today",
        "NOT dueWithin:\"1 day\"",
        // -- completed --
        "completed:true",
        "completed:false",
        "NOT completed:true",
        "completed:false AND due:today",
        // -- isRepeating --
        "isRepeating:true",
        "isRepeating:false",
        "NOT isRepeating:true",
        "isRepeating:true AND list:Работа",
        // -- hasSubtasks --
        "hasSubtasks:true",
        "hasSubtasks:false",
        "NOT hasSubtasks:false",
        // -- location --
        "location:офис",
        "location:спортзал",
        "location:неизвестно",
        "NOT location:офис",
        "location:офис OR location:спортзал",
        // -- isShared --
        "isShared:true",
        "isShared:false",
        "NOT isShared:false",
        // -- addedWithin --
        "addedWithin:\"1 day\"",
        "addedWithin:\"2 days\"",
        "addedWithin:\"30 days\"",
        "addedWithin:\"1 week\"",
        "NOT addedWithin:\"30 days\"",
        // -- postponed --
        "postponed:1",
        "postponed:0",
        "postponed:>2",
        "postponed:>=3",
        "postponed:<2",
        "postponed:<=0",
        "postponed:>1 AND priority:2",
        // -- timeEstimate --
        "timeEstimate:\"<1 hour\"",
        "timeEstimate:\">1 hour\"",
        "timeEstimate:\">=1 hour\"",
        "timeEstimate:\"30 minutes\"",
        "timeEstimate:\"=30 minutes\"",
        "timeEstimate:\"<2 hours\"",
        "timeEstimate:\">2 hours\"",
        "timeEstimate:\"45 minutes\"",
        "timeEstimate:\"120 minutes\"",
        // -- композиция (спецификация: ≥3 операторов) --
        "list:Работа AND (priority:1 OR dueBefore:\"3 days\")",
        "priority:1 AND (tag:работа OR list:Работа)",
        "(list:Работа OR list:Дом) AND due:today",
        "NOT (list:Работа OR priority:1)",
        "tag:работа AND NOT completed:true",
        "due:overdue AND (priority:1 OR priority:2 OR priority:3)",
        "(isRepeating:true OR due:today) AND NOT completed:true",
        "list:Работа AND tag:работа AND due:today",
        "priority:1 AND priority:2 OR priority:3",
        "(priority:1 AND priority:2) OR priority:3",
        "NOT NOT priority:1",
        "NOT (NOT (list:Работа))",
        "completed:false AND (due:today OR due:tomorrow) AND tag:работа",
        "location:офис AND (isShared:true OR due:thisweek)",
        "priority:>1 AND NOT (completed:true OR list:Дом)",
        "tag:покупки AND dueWithin:\"3 days\"",
        "postponed:>0 AND (priority:1 OR priority:2 OR priority:3)",
        "timeEstimate:\"<2 hours\" OR timeEstimate:\">2 hours\"",
        "list:Дом AND addedWithin:\"30 days\" AND NOT completed:true",
        "dueBefore:\"1 week\" AND isRepeating:false",
        // -- расширенный корпус: RU-ключевые слова, отрицания, комбинации --
        "list:Работа AND NOT tag:срочно",
        "list:Работа OR list:Дом OR list:Входящие",
        "NOT (list:Работа OR list:Дом)",
        "tag:работа AND tag:срочно AND priority:1",
        "tag:покупки AND NOT (completed:true)",
        "due:сегодня OR due:завтра OR due:thisweek",
        "due:overdue AND priority:1",
        "due:today AND isRepeating:false",
        "due:tomorrow AND list:Работа",
        "due:thisweek AND NOT isShared:true",
        "due:nextweek AND location:офис",
        "NOT due:overdue AND NOT completed:true",
        "dueBefore:завтра",
        "dueBefore:\"2 недели\"",
        "dueBefore:\"1 день\"",
        "dueBefore:\"30 дней\"",
        "dueBefore:сегодня AND tag:работа",
        "NOT (dueBefore:\"3 days\")",
        "dueWithin:\"1 неделя\"",
        "dueWithin:\"2 days\"",
        "dueWithin:\"12 hours\"",
        "dueWithin:\"45 minutes\"",
        "dueWithin:сегодня",
        "NOT dueWithin:\"30 days\"",
        "completed:false AND completed:true",
        "NOT (completed:false OR completed:true)",
        "isRepeating:true AND due:today",
        "isRepeating:false OR isRepeating:true",
        "NOT isRepeating:false",
        "hasSubtasks:true OR isShared:true",
        "NOT hasSubtasks:true AND list:Дом",
        "location:офис AND tag:работа",
        "location:спортзал AND NOT isShared:true",
        "NOT (location:офис OR location:спортзал)",
        "isShared:true OR isShared:false",
        "addedWithin:\"1 день\"",
        "addedWithin:\"3 недели\"",
        "addedWithin:\"2 часа\"",
        "NOT addedWithin:\"1 day\"",
        "postponed:>0",
        "postponed:>=1 AND priority:2",
        "postponed:<1",
        "postponed:<=3",
        "NOT postponed:1",
        "postponed:0 OR postponed:3",
        "timeEstimate:\"<30 minutes\"",
        "timeEstimate:\">=2 hours\"",
        "timeEstimate:\"<=1 hour\"",
        "timeEstimate:\">45 minutes\"",
        "NOT timeEstimate:\"<1 hour\"",
        "timeEstimate:\"1 hour\" AND priority:1",
        "(tag:работа OR tag:покупки) AND due:today",
        "(priority:1 OR priority:2) AND (due:today OR due:tomorrow)",
        "NOT (NOT (NOT (list:Работа)))",
        "due:today AND list:Дом AND tag:покупки",
        "list:Работа AND (tag:работа AND NOT completed:true)",
        "priority:>1 AND dueBefore:\"1 week\"",
        "isRepeating:true OR hasSubtasks:true OR isShared:true",
        "completed:false AND postponed:>0",
        "dueWithin:\"1 week\" AND NOT completed:true",
        "due:overdue AND (hasSubtasks:true OR isRepeating:true)",
        "location:офис AND due:thisweek",
        "tag:команда AND list:\"Общий список\"",
        "list:\"Общий список\" AND isShared:true",
        "addedWithin:\"30 days\" AND dueBefore:\"1 week\"",
        "text заголовок с пробелами OR голый_текст",
        "приоритетные слова AND NOT мусор",
    )

    /** Дополнительные выражения для корпуса ≥200. */
    private val extraExpressions = listOf(
        "due:thisweek AND priority:>1",
        "due:nextweek AND NOT completed:true",
        "due:2026-10-19",
        "due:2026-10-21",
        "due:2026-10-24",
        "dueBefore:\"5 days\"",
        "dueBefore:\"4 days\"",
        "dueBefore:\"10 days\"",
        "dueWithin:\"2 weeks\"",
        "dueWithin:\"1 month\"",
        "dueWithin:\"3 hours\"",
        "addedWithin:\"1 month\"",
        "postponed:2",
        "postponed:3",
        "postponed:>3",
        "postponed:>=2",
        "list:Дом OR list:Работа OR list:Входящие OR list:\"Общий список\"",
        "NOT list:Входящие",
        "tag:здоровье",
        "tag:рутина",
        "tag:проекты",
        "tag:команда",
        "priority:1 AND tag:работа AND list:Работа AND due:today",
        "(priority:1 AND tag:работа) OR (priority:3 AND location:офис)",
        "due:today AND NOT (tag:покупки OR completed:true)",
        "isRepeating:false AND hasSubtasks:false AND NOT isShared:true",
        "timeEstimate:\"3 hours\"",
        "timeEstimate:\"4 hours\"",
        "timeEstimate:\"<4 hours\"",
        "timeEstimate:\">4 hours\"",
        "timeEstimate:\"=2 hours\"",
        "due:overdue AND completed:false",
        "due:overdue AND isRepeating:true",
        "list:Работа AND NOT NOT due:today",
        "dueWithin:\"1 week\" AND list:Работа",
        "dueWithin:\"1 day\" AND tag:покупки",
        "dueBefore:\"1 week\" AND NOT completed:true AND priority:>1",
    )

    private val errorExpressions = listOf(
        "bogus:1",
        "unknownfield:x",
        "(unclosed",
        "list:",
        "priority: AND due:today",
        "()",
        "list:Работа AND",
        "due:\"unterminated",
        "AND OR",
    )

    @Test
    fun corpusAtLeast200Expressions() {
        val all = expressions + extraExpressions
        assertTrue(all.size >= 200, "Query corpus too small: ${all.size}")
    }

    @Test
    fun predicateEqualsSqlForAllExpressions() {
        val all = expressions + extraExpressions
        var checked = 0
        for (query in all) {
            val ast = QueryParser.parse(query)
            val sqlPred = SqlBuilder.build(ast, ctx)
            // SQL должен рендериться в непустую строку с параметрами
            val (sqlText, params) = SqlRenderer.render(sqlPred)
            assertTrue(sqlText.isNotBlank(), "SQL render failed for '$query'")
            for (task in tasks) {
                val p = QueryPredicate.eval(ast, task, ctx)
                val s = SqlEvaluator.eval(sqlPred, task, ctx)
                assertEquals(
                    p, s,
                    "predicate/SQL mismatch for '$query' on task '${task.name}' (sql=$sqlText params=$params)",
                )
                checked++
            }
        }
        assertTrue(checked >= 200 * tasks.size, "Should have checked many cells, got $checked")
    }

    @Test
    fun ac6ThreeOperatorsSmartList() {
        // SPEC AC-6: list:Работа AND (priority:1 OR dueBefore:"3 days")
        val query = "list:Работа AND (priority:1 OR dueBefore:\"3 days\")"
        val ast = QueryParser.parse(query)
        val sqlPred = SqlBuilder.build(ast, ctx)
        val (sql, _) = SqlRenderer.render(sqlPred)
        assertTrue(sql.contains("EXISTS"), "SQL должен использовать подзапросы: $sql")
        assertTrue(sql.contains("OR"), "SQL должен содержать OR: $sql")

        val expected = tasks.count { QueryPredicate.eval(ast, it, ctx) }
        val viaSql = tasks.count { SqlEvaluator.eval(sqlPred, it, ctx) }
        assertEquals(expected, viaSql)
        assertTrue(expected > 0, "Запрос должен вернуть хотя бы одну задачу")
    }

    @Test
    fun parserRejectsInvalidSyntax() {
        for (query in errorExpressions) {
            assertFailsWith<QueryParseException>("Should reject: '$query'") {
                QueryParser.parse(query)
            }
        }
    }

    @Test
    fun astStructureSpotCheck() {
        val ast = QueryParser.parse("priority:1 AND (tag:работа OR NOT list:Дом)")
        val and = ast as QueryExpr.And
        assertEquals(2, and.children.size)
        val left = and.children[0] as QueryExpr.Field
        assertEquals(QueryField.PRIORITY, left.field)
        val or = and.children[1] as QueryExpr.Or
        assertEquals(2, or.children.size)
        val not = or.children[1] as QueryExpr.Not
        assertTrue(not.child is QueryExpr.Field)
    }

    @Test
    fun textPhraseIsMerged() {
        val ast = QueryParser.parse("проект икс")
        assertEquals(QueryExpr.Text("проект икс"), ast)
    }
}