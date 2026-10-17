package com.taskly.shared.smartadd

import com.taskly.shared.domain.model.Priority
import com.taskly.shared.domain.model.RecurrenceFreq
import com.taskly.shared.domain.model.RepeatMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Корпус Smart Add (SPEC F-2, AC-2): ≥300 строк ввода (RU+EN), детерминированный
 * парсинг при today = 2026-10-17 (суббота) и Europe/Moscow. 100% кейсов проходят
 * на JVM и JS (commonTest).
 */
class SmartAddCorpusTest {

    private val today = LocalDate(2026, 10, 17)
    private val tz = TimeZone.of("UTC+03") // Europe/Moscow (без DST) — работает на JVM и JS
    private val parser = SmartAddParser()

    /** Ожидаемый результат разбора. */
    private data class Exp(
        val name: String? = null,
        val due: String? = null,
        val dueTime: String? = null,
        val hasTime: Boolean = false,
        val start: String? = null,
        val priority: Int? = null,
        val list: String? = null,
        val tags: List<String> = emptyList(),
        val freq: RecurrenceFreq? = null,
        val interval: Int? = null,
        val mode: RepeatMode? = null,
        val byDay: List<DayOfWeek> = emptyList(),
        val location: String? = null,
        val estimate: Int? = null,
        val contact: String? = null,
        val note: String? = null,
        val tokens: List<SmartTokenType> = emptyList(),
        val tokenCount: Int? = null,
    )

    // ------------------------------------------------------------------ RU ----

    private val ruCases = mutableListOf<Pair<String, Exp>>()

    private fun ru(input: String, exp: Exp) {
        ruCases += input to exp
    }

    private fun expandRuDates() {
        val names = listOf("Позвонить маме", "Купить молоко", "Записаться к врачу", "Оплатить интернет", "Подготовить отчёт")
        val dates = listOf(
            "^завтра" to "2026-10-18",
            "^сегодня" to "2026-10-17",
            "^послезавтра" to "2026-10-19",
            "^через 3 дня" to "2026-10-20",
            "^через неделю" to "2026-10-24",
            "^пн" to "2026-10-19",
            "^пт" to "2026-10-23",
            "^25 дек" to "2026-12-25",
            "^через месяц" to "2026-11-17",
        )
        for (name in names) {
            for ((expr, date) in dates) {
                ru("$name $expr", Exp(name = name, due = date))
            }
        }
    }

    private fun initRu() {
        expandRuDates()

        // ---- Даты: абсолютные слова ----
        ru("Купить молоко ^завтра", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^сегодня", Exp(name = "Купить молоко", due = "2026-10-17"))
        ru("Купить молоко ^послезавтра", Exp(name = "Купить молоко", due = "2026-10-19"))
        ru("Купить молоко ^ завтра", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^завтра.", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^завтра,", Exp(name = "Купить молоко", due = "2026-10-18"))

        // ---- Относительные даты RU ----
        ru("Купить молоко ^через 1 день", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^через 3 дня", Exp(name = "Купить молоко", due = "2026-10-20"))
        ru("Купить молоко ^через 7 дней", Exp(name = "Купить молоко", due = "2026-10-24"))
        ru("Купить молоко ^через 14 дней", Exp(name = "Купить молоко", due = "2026-10-31"))
        ru("Купить молоко ^через 30 дней", Exp(name = "Купить молоко", due = "2026-11-16"))
        ru("Купить молоко ^через день", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^через неделю", Exp(name = "Купить молоко", due = "2026-10-24"))
        ru("Купить молоко ^через 2 недели", Exp(name = "Купить молоко", due = "2026-10-31"))
        ru("Купить молоко ^через 3 недели", Exp(name = "Купить молоко", due = "2026-11-07"))
        ru("Купить молоко ^через месяц", Exp(name = "Купить молоко", due = "2026-11-17"))
        ru("Купить молоко ^через 3 месяца", Exp(name = "Купить молоко", due = "2027-01-17"))
        ru("Купить молоко ^через год", Exp(name = "Купить молоко", due = "2027-10-17"))
        ru("Купить молоко ^через 2 года", Exp(name = "Купить молоко", due = "2028-10-17"))
        ru("Купить молоко ^через час", Exp(name = "Купить молоко", due = "2026-10-17"))

        // ---- Дни недели RU ----
        ru("Купить молоко ^пн", Exp(name = "Купить молоко", due = "2026-10-19"))
        ru("Купить молоко ^вт", Exp(name = "Купить молоко", due = "2026-10-20"))
        ru("Купить молоко ^ср", Exp(name = "Купить молоко", due = "2026-10-21"))
        ru("Купить молоко ^чт", Exp(name = "Купить молоко", due = "2026-10-22"))
        ru("Купить молоко ^пт", Exp(name = "Купить молоко", due = "2026-10-23"))
        ru("Купить молоко ^сб", Exp(name = "Купить молоко", due = "2026-10-17"))
        ru("Купить молоко ^вс", Exp(name = "Купить молоко", due = "2026-10-18"))
        ru("Купить молоко ^понедельник", Exp(name = "Купить молоко", due = "2026-10-19"))
        ru("Купить молоко ^вторник", Exp(name = "Купить молоко", due = "2026-10-20"))
        ru("Купить молоко ^среда", Exp(name = "Купить молоко", due = "2026-10-21"))
        ru("Купить молоко ^четверг", Exp(name = "Купить молоко", due = "2026-10-22"))
        ru("Купить молоко ^пятница", Exp(name = "Купить молоко", due = "2026-10-23"))
        ru("Купить молоко ^суббота", Exp(name = "Купить молоко", due = "2026-10-17"))
        ru("Купить молоко ^воскресенье", Exp(name = "Купить молоко", due = "2026-10-18"))

        // ---- Месяц-день RU ----
        ru("Купить молоко ^25 дек", Exp(name = "Купить молоко", due = "2026-12-25"))
        ru("Купить молоко ^дек 25", Exp(name = "Купить молоко", due = "2026-12-25"))
        ru("Купить молоко ^25 декабря", Exp(name = "Купить молоко", due = "2026-12-25"))
        ru("Купить молоко ^декабря 25", Exp(name = "Купить молоко", due = "2026-12-25"))
        ru("Купить молоко ^3 марта", Exp(name = "Купить молоко", due = "2027-03-03"))
        ru("Купить молоко ^марта 3", Exp(name = "Купить молоко", due = "2027-03-03"))
        ru("Купить молоко ^1 января", Exp(name = "Купить молоко", due = "2027-01-01"))
        ru("Купить молоко ^января 1", Exp(name = "Купить молоко", due = "2027-01-01"))
        ru("Купить молоко ^31 декабря", Exp(name = "Купить молоко", due = "2026-12-31"))
        ru("Купить молоко ^1 октября", Exp(name = "Купить молоко", due = "2027-10-01"))
        ru("Купить молоко ^31 дек 2027", Exp(name = "Купить молоко", due = "2027-12-31"))
        ru("Купить молоко ^25 дек 2027", Exp(name = "Купить молоко", due = "2027-12-25"))

        // ---- ISO/числовые форматы RU ----
        ru("Купить молоко ^2026-11-05", Exp(name = "Купить молоко", due = "2026-11-05"))
        ru("Купить молоко ^2026-10-20", Exp(name = "Купить молоко", due = "2026-10-20"))
        ru("Купить молоко ^2027-01-01", Exp(name = "Купить молоко", due = "2027-01-01"))
        ru("Купить молоко ^20.10.2026", Exp(name = "Купить молоко", due = "2026-10-20"))
        ru("Купить молоко ^31.12.2026", Exp(name = "Купить молоко", due = "2026-12-31"))
        ru("Купить молоко ^01.01.2027", Exp(name = "Купить молоко", due = "2027-01-01"))
        ru("Купить молоко ^17.10.2026", Exp(name = "Купить молоко", due = "2026-10-17"))

        // ---- Время ----
        ru("Купить молоко ^завтра 17:00", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "17:00", hasTime = true))
        ru("Купить молоко ^завтра 9:00", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "09:00", hasTime = true))
        ru("Купить молоко ^завтра 09:05", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "09:05", hasTime = true))
        ru("Купить молоко ^завтра 23:59", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "23:59", hasTime = true))
        ru("Купить молоко ^завтра 17:00:30", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "17:00:30", hasTime = true))
        ru("Купить молоко ^сегодня 23:30", Exp(name = "Купить молоко", due = "2026-10-17", dueTime = "23:30", hasTime = true))
        ru("Купить молоко ^пт 18:30", Exp(name = "Купить молоко", due = "2026-10-23", dueTime = "18:30", hasTime = true))
        ru("Купить молоко ^понедельник 8:15", Exp(name = "Купить молоко", due = "2026-10-19", dueTime = "08:15", hasTime = true))
        ru("Купить молоко ^пн 9 утра", Exp(name = "Купить молоко", due = "2026-10-19", dueTime = "09:00", hasTime = true))
        ru("Купить молоко ^ср 8 вечера", Exp(name = "Купить молоко", due = "2026-10-21", dueTime = "20:00", hasTime = true))
        ru("Купить молоко ^через 3 дня 18:00", Exp(name = "Купить молоко", due = "2026-10-20", dueTime = "18:00", hasTime = true))
        ru("Купить молоко ^завтра в 17:00", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "17:00", hasTime = true))
        ru("Купить молоко ^завтра 5pm", Exp(name = "Купить молоко", due = "2026-10-18", dueTime = "17:00", hasTime = true))

        // ---- Даты начала (~) ----
        ru("Спортзал ~завтра", Exp(name = "Спортзал", start = "2026-10-18"))
        ru("Спортзал ~понедельник", Exp(name = "Спортзал", start = "2026-10-19"))
        ru("Спортзал ~через 2 дня", Exp(name = "Спортзал", start = "2026-10-19"))
        ru("Спортзал ~25 дек", Exp(name = "Спортзал", start = "2026-12-25"))
        ru("Спортзал ~сб 10:00", Exp(name = "Спортзал", start = "2026-10-17"))

        // ---- Приоритеты ----
        ru("Задача !1", Exp(name = "Задача", priority = 1))
        ru("Задача !2", Exp(name = "Задача", priority = 2))
        ru("Задача !3", Exp(name = "Задача", priority = 3))
        ru("Задача ! 1", Exp(name = "Задача", priority = 1))
        ru("Задача ! 2", Exp(name = "Задача", priority = 2))
        ru("Задача !1 срочно", Exp(name = "Задача срочно", priority = 1))
        ru("!2 Позвонить", Exp(name = "Позвонить", priority = 2))

        // ---- Списки и теги ----
        ru("Купить хлеб #Покупки", Exp(name = "Купить хлеб", list = "Покупки"))
        ru("Купить хлеб #Покупки.", Exp(name = "Купить хлеб", list = "Покупки"))
        ru("Отчёт #Работа #срочно", Exp(name = "Отчёт", list = "Работа", tags = listOf("срочно")))
        ru("Дело #дом #важное #личное", Exp(name = "Дело", list = "дом", tags = listOf("важное", "личное")))
        ru("Звонок #звонки", Exp(name = "Звонок", list = "звонки"))

        // ---- Повторы RU ----
        ru("Зарядка *ежедневно", Exp(name = "Зарядка", freq = RecurrenceFreq.DAILY, interval = 1, mode = RepeatMode.EVERY))
        ru("Зарядка *каждый день", Exp(name = "Зарядка", freq = RecurrenceFreq.DAILY, interval = 1, mode = RepeatMode.EVERY))
        ru("Совещание *каждую неделю", Exp(name = "Совещание", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Совещание *еженедельно", Exp(name = "Совещание", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Отчёт *каждый месяц", Exp(name = "Отчёт", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Отчёт *ежемесячно", Exp(name = "Отчёт", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Налоги *каждый год", Exp(name = "Налоги", freq = RecurrenceFreq.YEARLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Налоги *ежегодно", Exp(name = "Налоги", freq = RecurrenceFreq.YEARLY, interval = 1, mode = RepeatMode.EVERY))
        ru("Зарядка *каждые 2 дня", Exp(name = "Зарядка", freq = RecurrenceFreq.DAILY, interval = 2, mode = RepeatMode.EVERY))
        ru("Зарядка *каждые 3 дня", Exp(name = "Зарядка", freq = RecurrenceFreq.DAILY, interval = 3, mode = RepeatMode.EVERY))
        ru("Звонок *каждые 2 недели", Exp(name = "Звонок", freq = RecurrenceFreq.WEEKLY, interval = 2, mode = RepeatMode.EVERY))
        ru("Бассейн *каждые 2 месяца", Exp(name = "Бассейн", freq = RecurrenceFreq.MONTHLY, interval = 2, mode = RepeatMode.EVERY))
        ru("Тест *каждые 2 года", Exp(name = "Тест", freq = RecurrenceFreq.YEARLY, interval = 2, mode = RepeatMode.EVERY))
        ru("Йога *каждый понедельник", Exp(name = "Йога", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.MONDAY)))
        ru("Йога *каждую пятницу", Exp(name = "Йога", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.FRIDAY)))
        ru("Йога *по пятницам", Exp(name = "Йога", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.FRIDAY)))
        ru("Йога *каждое воскресенье", Exp(name = "Йога", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.SUNDAY)))
        ru("Йога *каждую среду", Exp(name = "Йога", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.WEDNESDAY)))
        ru("Уборка *после 2 недель", Exp(name = "Уборка", freq = RecurrenceFreq.WEEKLY, interval = 2, mode = RepeatMode.AFTER))
        ru("Уборка *после 3 дней", Exp(name = "Уборка", freq = RecurrenceFreq.DAILY, interval = 3, mode = RepeatMode.AFTER))
        ru("Уборка *после недели", Exp(name = "Уборка", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.AFTER))
        ru("Приём *после месяца", Exp(name = "Приём", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.AFTER))

        // ---- Локации ----
        ru("Встреча @офис", Exp(name = "Встреча", location = "офис"))
        ru("Купить хлеб @магазин", Exp(name = "Купить хлеб", location = "магазин"))
        ru("Совещание @переговорка 3", Exp(name = "Совещание 3", location = "переговорка"))
        ru("Встреча @офис,", Exp(name = "Встреча", location = "офис"))

        // ---- Оценки ----
        ru("Задача =30 мин", Exp(name = "Задача", estimate = 30))
        ru("Задача =30мин", Exp(name = "Задача", estimate = 30))
        ru("Задача =1ч", Exp(name = "Задача", estimate = 60))
        ru("Задача =1ч.", Exp(name = "Задача", estimate = 60))
        ru("Задача =1.5ч", Exp(name = "Задача", estimate = 90))
        ru("Задача =2 часа", Exp(name = "Задача", estimate = 120))
        ru("Задача =45 минут", Exp(name = "Задача", estimate = 45))
        ru("Задача = 30 мин", Exp(name = "Задача", estimate = 30))
        ru("Задача =3 часа 30", Exp(name = "Задача 30", estimate = 180))

        // ---- Контакты ----
        ru("Задача +anna", Exp(name = "Задача", contact = "anna"))
        ru("Позвонить +петя", Exp(name = "Позвонить", contact = "петя"))
        ru("Написать +ivanov@example.com", Exp(name = "Написать", contact = "ivanov@example.com"))

        // ---- Заметки ----
        ru("Позвонить маме // сказать про отпуск", Exp(name = "Позвонить маме", note = "сказать про отпуск"))
        ru("Купить молоко ^завтра // важно!", Exp(name = "Купить молоко", due = "2026-10-18", note = "важно!"))
        ru("Задача //", Exp(name = "Задача", note = ""))
        ru("// только заметка", Exp(name = "", note = "только заметка"))

        // ---- Комбинации (SPEC 4.4 сценарий) ----
        ru(
            "Купить молоко ^завтра #Покупки !2",
            Exp(name = "Купить молоко", due = "2026-10-18", priority = 2, list = "Покупки"),
        )
        ru(
            "Сдать отчёт ^пт 18:00 #Работа !1 *ежедневно // проверить цифры =30 мин @офис +коллега",
            Exp(
                name = "Сдать отчёт",
                due = "2026-10-23",
                dueTime = "18:00",
                hasTime = true,
                priority = 1,
                list = "Работа",
                freq = RecurrenceFreq.DAILY,
                interval = 1,
                mode = RepeatMode.EVERY,
                note = "проверить цифры =30 мин @офис +коллега",
            ),
        )
        ru(
            "Позвонить ^сегодня !3 #звонки *каждую неделю",
            Exp(name = "Позвонить", due = "2026-10-17", priority = 3, list = "звонки", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY),
        )
        ru(
            "Подготовить отчёт ^через 2 дня ~понедельник #Работа @офис =1ч !2",
            Exp(
                name = "Подготовить отчёт",
                due = "2026-10-19",
                start = "2026-10-19",
                list = "Работа",
                location = "офис",
                estimate = 60,
                priority = 2,
            ),
        )

        // ---- Специальные символы в названии ----
        ru("C# проект", Exp(name = "C# проект"))
        ru("email@work.com", Exp(name = "email@work.com"))
        ru("2+2=4 задачка", Exp(name = "2+2=4 задачка"))
        ru("!important файл", Exp(name = "!important файл"))
        ru("Купить * (звёздочка)", Exp(name = "Купить * (звёздочка)"))
        ru("Обычная задача без токенов", Exp(name = "Обычная задача без токенов"))
        ru("", Exp(name = ""))

        // ---- Позиции токенов (подсветка) ----
        ru(
            "Купить ^завтра молоко #дом",
            Exp(name = "Купить молоко", due = "2026-10-18", list = "дом", tokens = listOf(SmartTokenType.DUE, SmartTokenType.LIST)),
        )
        ru(
            "!1 Срочно ^сегодня @офис =30 мин // помыть",
            Exp(
                name = "Срочно",
                priority = 1,
                due = "2026-10-17",
                location = "офис",
                estimate = 30,
                note = "помыть",
                tokens = listOf(SmartTokenType.PRIORITY, SmartTokenType.DUE, SmartTokenType.LOCATION, SmartTokenType.ESTIMATE, SmartTokenType.NOTE),
            ),
        )
        ru(
            "Зарядка *каждые 2 дня ^завтра",
            Exp(
                name = "Зарядка",
                freq = RecurrenceFreq.DAILY,
                interval = 2,
                mode = RepeatMode.EVERY,
                due = "2026-10-18",
                tokens = listOf(SmartTokenType.REPEAT, SmartTokenType.DUE),
            ),
        )
    }

    // ------------------------------------------------------------------ EN ----

    private val enCases = mutableListOf<Pair<String, Exp>>()

    private fun en(input: String, exp: Exp) {
        enCases += input to exp
    }

    private fun expandEnDates() {
        val names = listOf("Call mom", "Buy milk", "Book dentist", "Pay bills", "Prepare report")
        val dates = listOf(
            "^tomorrow" to "2026-10-18",
            "^today" to "2026-10-17",
            "^in 3 days" to "2026-10-20",
            "^mon" to "2026-10-19",
            "^fri" to "2026-10-23",
            "^dec 25" to "2026-12-25",
            "^in a month" to "2026-11-17",
        )
        for (name in names) {
            for ((expr, date) in dates) {
                en("$name $expr", Exp(name = name, due = date))
            }
        }
    }

    private fun initEn() {
        expandEnDates()

        en("Buy milk ^tomorrow", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^today", Exp(name = "Buy milk", due = "2026-10-17"))
        en("Buy milk ^day after tomorrow", Exp(name = "Buy milk", due = "2026-10-19"))
        en("Buy milk ^the day after tomorrow", Exp(name = "Buy milk", due = "2026-10-19"))
        en("Buy milk ^ tomorrow", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^tomorrow.", Exp(name = "Buy milk", due = "2026-10-18"))

        en("Buy milk ^in 1 day", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^in 3 days", Exp(name = "Buy milk", due = "2026-10-20"))
        en("Buy milk ^in 7 days", Exp(name = "Buy milk", due = "2026-10-24"))
        en("Buy milk ^in 14 days", Exp(name = "Buy milk", due = "2026-10-31"))
        en("Buy milk ^in a day", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^in one day", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^in a week", Exp(name = "Buy milk", due = "2026-10-24"))
        en("Buy milk ^in 2 weeks", Exp(name = "Buy milk", due = "2026-10-31"))
        en("Buy milk ^in a month", Exp(name = "Buy milk", due = "2026-11-17"))
        en("Buy milk ^in 3 months", Exp(name = "Buy milk", due = "2027-01-17"))
        en("Buy milk ^in a year", Exp(name = "Buy milk", due = "2027-10-17"))
        en("Buy milk ^next week", Exp(name = "Buy milk", due = "2026-10-24"))
        en("Buy milk ^next month", Exp(name = "Buy milk", due = "2026-11-17"))
        en("Buy milk ^next year", Exp(name = "Buy milk", due = "2027-10-17"))

        en("Buy milk ^mon", Exp(name = "Buy milk", due = "2026-10-19"))
        en("Buy milk ^tue", Exp(name = "Buy milk", due = "2026-10-20"))
        en("Buy milk ^wed", Exp(name = "Buy milk", due = "2026-10-21"))
        en("Buy milk ^thu", Exp(name = "Buy milk", due = "2026-10-22"))
        en("Buy milk ^fri", Exp(name = "Buy milk", due = "2026-10-23"))
        en("Buy milk ^sat", Exp(name = "Buy milk", due = "2026-10-17"))
        en("Buy milk ^sun", Exp(name = "Buy milk", due = "2026-10-18"))
        en("Buy milk ^monday", Exp(name = "Buy milk", due = "2026-10-19"))
        en("Buy milk ^tuesday", Exp(name = "Buy milk", due = "2026-10-20"))
        en("Buy milk ^wednesday", Exp(name = "Buy milk", due = "2026-10-21"))
        en("Buy milk ^thursday", Exp(name = "Buy milk", due = "2026-10-22"))
        en("Buy milk ^friday", Exp(name = "Buy milk", due = "2026-10-23"))
        en("Buy milk ^saturday", Exp(name = "Buy milk", due = "2026-10-17"))
        en("Buy milk ^sunday", Exp(name = "Buy milk", due = "2026-10-18"))

        en("Buy milk ^dec 25", Exp(name = "Buy milk", due = "2026-12-25"))
        en("Buy milk ^25 december", Exp(name = "Buy milk", due = "2026-12-25"))
        en("Buy milk ^december 25", Exp(name = "Buy milk", due = "2026-12-25"))
        en("Buy milk ^jan 1", Exp(name = "Buy milk", due = "2027-01-01"))
        en("Buy milk ^january 1", Exp(name = "Buy milk", due = "2027-01-01"))
        en("Buy milk ^oct 31", Exp(name = "Buy milk", due = "2026-10-31"))
        en("Buy milk ^dec 25 2027", Exp(name = "Buy milk", due = "2027-12-25"))
        en("Buy milk ^25 dec 2028", Exp(name = "Buy milk", due = "2028-12-25"))

        en("Buy milk ^2026-11-05", Exp(name = "Buy milk", due = "2026-11-05"))
        en("Buy milk ^2027-03-01", Exp(name = "Buy milk", due = "2027-03-01"))

        en("Buy milk ^tomorrow 17:00", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "17:00", hasTime = true))
        en("Buy milk ^tomorrow 09:00", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "09:00", hasTime = true))
        en("Buy milk ^tomorrow 5pm", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "17:00", hasTime = true))
        en("Buy milk ^tomorrow 5:30pm", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "17:30", hasTime = true))
        en("Buy milk ^tomorrow 11 am", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "11:00", hasTime = true))
        en("Buy milk ^tomorrow 8pm", Exp(name = "Buy milk", due = "2026-10-18", dueTime = "20:00", hasTime = true))
        en("Buy milk ^fri 18:30", Exp(name = "Buy milk", due = "2026-10-23", dueTime = "18:30", hasTime = true))
        en("Buy milk ^in 3 days 12:00", Exp(name = "Buy milk", due = "2026-10-20", dueTime = "12:00", hasTime = true))

        en("Workout ~tomorrow", Exp(name = "Workout", start = "2026-10-18"))
        en("Workout ~monday", Exp(name = "Workout", start = "2026-10-19"))
        en("Workout ~in 3 days", Exp(name = "Workout", start = "2026-10-20"))
        en("Workout ~dec 25", Exp(name = "Workout", start = "2026-12-25"))

        en("Task !1", Exp(name = "Task", priority = 1))
        en("Task !2", Exp(name = "Task", priority = 2))
        en("Task !3", Exp(name = "Task", priority = 3))
        en("Task ! 1", Exp(name = "Task", priority = 1))
        en("!2 Urgent", Exp(name = "Urgent", priority = 2))

        en("Buy bread #Groceries", Exp(name = "Buy bread", list = "Groceries"))
        en("Report #Work #urgent", Exp(name = "Report", list = "Work", tags = listOf("urgent")))
        en("Chore #home #important #personal", Exp(name = "Chore", list = "home", tags = listOf("important", "personal")))

        en("Run *every day", Exp(name = "Run", freq = RecurrenceFreq.DAILY, interval = 1, mode = RepeatMode.EVERY))
        en("Run *daily", Exp(name = "Run", freq = RecurrenceFreq.DAILY, interval = 1, mode = RepeatMode.EVERY))
        en("Run *each day", Exp(name = "Run", freq = RecurrenceFreq.DAILY, interval = 1, mode = RepeatMode.EVERY))
        en("Meeting *every week", Exp(name = "Meeting", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY))
        en("Meeting *weekly", Exp(name = "Meeting", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY))
        en("Report *every month", Exp(name = "Report", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.EVERY))
        en("Report *monthly", Exp(name = "Report", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.EVERY))
        en("Taxes *every year", Exp(name = "Taxes", freq = RecurrenceFreq.YEARLY, interval = 1, mode = RepeatMode.EVERY))
        en("Taxes *yearly", Exp(name = "Taxes", freq = RecurrenceFreq.YEARLY, interval = 1, mode = RepeatMode.EVERY))
        en("Taxes *annually", Exp(name = "Taxes", freq = RecurrenceFreq.YEARLY, interval = 1, mode = RepeatMode.EVERY))
        en("Run *every 2 days", Exp(name = "Run", freq = RecurrenceFreq.DAILY, interval = 2, mode = RepeatMode.EVERY))
        en("Run *every 3 days", Exp(name = "Run", freq = RecurrenceFreq.DAILY, interval = 3, mode = RepeatMode.EVERY))
        en("Call *every 2 weeks", Exp(name = "Call", freq = RecurrenceFreq.WEEKLY, interval = 2, mode = RepeatMode.EVERY))
        en("Swim *every 2 months", Exp(name = "Swim", freq = RecurrenceFreq.MONTHLY, interval = 2, mode = RepeatMode.EVERY))
        en("Check *every other day", Exp(name = "Check", freq = RecurrenceFreq.DAILY, interval = 2, mode = RepeatMode.EVERY))
        en("Check *every other week", Exp(name = "Check", freq = RecurrenceFreq.WEEKLY, interval = 2, mode = RepeatMode.EVERY))
        en("Yoga *every monday", Exp(name = "Yoga", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.MONDAY)))
        en("Yoga *each monday", Exp(name = "Yoga", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.MONDAY)))
        en("Yoga *on mondays", Exp(name = "Yoga", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.MONDAY)))
        en("Yoga *on fridays", Exp(name = "Yoga", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.EVERY, byDay = listOf(DayOfWeek.FRIDAY)))
        en("Cleanup *after 2 weeks", Exp(name = "Cleanup", freq = RecurrenceFreq.WEEKLY, interval = 2, mode = RepeatMode.AFTER))
        en("Cleanup *after 3 days", Exp(name = "Cleanup", freq = RecurrenceFreq.DAILY, interval = 3, mode = RepeatMode.AFTER))
        en("Cleanup *after a week", Exp(name = "Cleanup", freq = RecurrenceFreq.WEEKLY, interval = 1, mode = RepeatMode.AFTER))
        en("Cleanup *after a month", Exp(name = "Cleanup", freq = RecurrenceFreq.MONTHLY, interval = 1, mode = RepeatMode.AFTER))

        en("Meeting @office", Exp(name = "Meeting", location = "office"))
        en("Buy bread @store", Exp(name = "Buy bread", location = "store"))

        en("Task =30 minutes", Exp(name = "Task", estimate = 30))
        en("Task =30 min", Exp(name = "Task", estimate = 30))
        en("Task =45m", Exp(name = "Task", estimate = 45))
        en("Task =2h", Exp(name = "Task", estimate = 120))
        en("Task =1 hour", Exp(name = "Task", estimate = 60))
        en("Task =1.5 hours", Exp(name = "Task", estimate = 90))
        en("Task = 30 minutes", Exp(name = "Task", estimate = 30))

        en("Call +bob", Exp(name = "Call", contact = "bob"))
        en("Email +alice", Exp(name = "Email", contact = "alice"))

        en("Call mom // tell about vacation", Exp(name = "Call mom", note = "tell about vacation"))
        en("Buy milk ^tomorrow // important", Exp(name = "Buy milk", due = "2026-10-18", note = "important"))

        en(
            "Buy milk ^tomorrow #Groceries !2",
            Exp(name = "Buy milk", due = "2026-10-18", priority = 2, list = "Groceries"),
        )
        en(
            "Submit report ^fri 18:00 #Work !1 *daily // check numbers =30 min @office +boss",
            Exp(
                name = "Submit report",
                due = "2026-10-23",
                dueTime = "18:00",
                hasTime = true,
                priority = 1,
                list = "Work",
                freq = RecurrenceFreq.DAILY,
                interval = 1,
                mode = RepeatMode.EVERY,
                note = "check numbers =30 min @office +boss",
            ),
        )

        en("Plain task without tokens", Exp(name = "Plain task without tokens"))
        en("", Exp(name = ""))

        en(
            "Buy ^tomorrow milk #home",
            Exp(name = "Buy milk", due = "2026-10-18", list = "home", tokens = listOf(SmartTokenType.DUE, SmartTokenType.LIST)),
        )
        en(
            "!1 Urgent ^today @office =30 min // wash",
            Exp(
                name = "Urgent",
                priority = 1,
                due = "2026-10-17",
                location = "office",
                estimate = 30,
                note = "wash",
                tokens = listOf(SmartTokenType.PRIORITY, SmartTokenType.DUE, SmartTokenType.LOCATION, SmartTokenType.ESTIMATE, SmartTokenType.NOTE),
            ),
        )
    }

    // ------------------------------------------------------------------ CLI ----

    private fun runCorpus(cases: List<Pair<String, Exp>>, lang: String) {
        assertTrue(cases.size >= 100, "$lang corpus too small: ${cases.size}")
        var failed = 0
        val failures = StringBuilder()
        for ((input, exp) in cases) {
            try {
                check(input, exp)
            } catch (t: Throwable) {
                failed++
                if (failures.length < 4000) {
                    failures.append("  [$lang] '").append(input).append("' → ").append(t.message).append('\n')
                }
            }
        }
        assertTrue(failed == 0, "Smart Add $lang corpus: $failed/${cases.size} failed:\n$failures")
    }

    @Test
    fun corpusRu100Percent() {
        initRu()
        runCorpus(ruCases, "RU")
    }

    @Test
    fun corpusEn100Percent() {
        initEn()
        runCorpus(enCases, "EN")
    }

    @Test
    fun corpusTotalAtLeast300() {
        initRu()
        initEn()
        assertTrue(ruCases.size + enCases.size >= 300, "Corpus should be >= 300, got ${ruCases.size + enCases.size} (RU=${ruCases.size}, EN=${enCases.size})")
    }

    @Test
    fun tokenPositionsAreSane() {
        val input = "Купить ^завтра молоко #дом"
        val r = parser.parse(input, today, tz)
        val dueToken = r.tokens.first { it.type == SmartTokenType.DUE }
        assertEquals(input.indexOf("^завтра"), dueToken.start)
        assertEquals(input.indexOf("^завтра") + "^завтра".length, dueToken.end)
        val listToken = r.tokens.first { it.type == SmartTokenType.LIST }
        assertEquals(input.indexOf("#дом"), listToken.start)
        assertEquals(input.indexOf("#дом") + "#дом".length, listToken.end)
    }

    @Test
    fun listPriorityOverTagWithContext() {
        val lists = setOf("Работа", "Покупки")
        val tags = setOf("работа", "звонки")
        // #работа совпадает и со списком, и с тегом — список приоритетнее
        val r1 = parser.parse("Задача #работа", today, tz, lists, tags)
        assertEquals("работа", r1.listName)
        assertTrue(r1.tags.isEmpty())
        // #звонки — только тег
        val r2 = parser.parse("Задача #звонки", today, tz, lists, tags)
        assertEquals(null, r2.listName)
        assertEquals(listOf("звонки"), r2.tags)
        // первый # — список, остальные — теги
        val r3 = parser.parse("Задача #Покупки #звонки", today, tz, lists, tags)
        assertEquals("Покупки", r3.listName)
        assertEquals(listOf("звонки"), r3.tags)
    }

    // ------------------------------------------------------------------ helpers ----

    private fun check(input: String, exp: Exp, listNames: Set<String> = emptySet(), tagNames: Set<String> = emptySet()) {
        val r = parser.parse(input, today, tz, listNames, tagNames)
        exp.name?.let { assertEquals(it, r.name, "name for '$input'") }
        if (exp.due != null) {
            val d = r.due ?: throw AssertionError("due not parsed: '$input'")
            assertEquals(exp.due, d.date.toString(), "due.date for '$input'")
            assertEquals(exp.hasTime, d.hasTime, "due.hasTime for '$input'")
            if (exp.dueTime != null) {
                assertEquals(LocalTime.parse(exp.dueTime), d.time, "due.time for '$input'")
            }
        }
        if (exp.start != null) {
            val s = r.start ?: throw AssertionError("start not parsed: '$input'")
            assertEquals(exp.start, s.date.toString(), "start.date for '$input'")
        }
        exp.priority?.let { assertEquals(Priority.fromLevel(it), r.priority, "priority for '$input'") }
        exp.list?.let { assertEquals(it, r.listName, "list for '$input'") }
        if (exp.tags.isNotEmpty()) {
            assertEquals(exp.tags.sorted(), r.tags.sorted(), "tags for '$input'")
        }
        exp.freq?.let { assertEquals(it, r.repeat?.freq, "repeat.freq for '$input'") }
        exp.interval?.let { assertEquals(it, r.repeat?.interval, "repeat.interval for '$input'") }
        exp.mode?.let { assertEquals(it, r.repeat?.mode, "repeat.mode for '$input'") }
        if (exp.byDay.isNotEmpty()) {
            assertEquals(exp.byDay.sorted(), r.repeat?.byDay?.sorted(), "repeat.byDay for '$input'")
        }
        exp.location?.let { assertEquals(it, r.location, "location for '$input'") }
        exp.estimate?.let { assertEquals(it, r.estimateMin, "estimate for '$input'") }
        exp.contact?.let { assertEquals(it, r.contact, "contact for '$input'") }
        exp.note?.let { assertEquals(it, r.note, "note for '$input'") }
        if (exp.tokens.isNotEmpty()) {
            assertEquals(exp.tokens, r.tokens.map { it.type }, "token types for '$input'")
        }
        exp.tokenCount?.let { assertEquals(it, r.tokens.size, "token count for '$input'") }
    }
}