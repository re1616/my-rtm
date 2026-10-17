package com.taskly.shared.smartadd

import com.taskly.shared.domain.model.Priority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Тип распознанного токена — для подсветки в поле ввода (SPEC F-2). */
enum class SmartTokenType { DUE, START, PRIORITY, LIST, TAG, REPEAT, LOCATION, ESTIMATE, CONTACT, NOTE }

/** Позиция токена в исходной строке [start, end) + распознанное значение. */
data class SmartToken(
    val type: SmartTokenType,
    val start: Int,
    val end: Int,
    val value: String,
)

/** Структурированный результат разбора строки быстрого ввода. */
data class SmartAddResult(
    val name: String,
    val due: ParsedDue? = null,
    val start: ParsedDue? = null,
    val priority: Priority? = null,
    val listName: String? = null,
    val tags: List<String> = emptyList(),
    val repeat: ParsedRepeat? = null,
    val location: String? = null,
    val estimateMin: Int? = null,
    val contact: String? = null,
    val note: String? = null,
    val tokens: List<SmartToken> = emptyList(),
)

/** Сегмент строки: непрерывный непробельный кусок + его границы в оригинале. */
private data class Segment(val text: String, val clean: String, val start: Int, val end: Int)

/**
 * Парсер Smart Add (SPEC F-2): одна строка → свойства задачи.
 * Символы: ^ срок, ~ начало, ! приоритет, # список/тег, * повтор, @ локация,
 * = оценка, + контакт, // заметка. Правила:
 * - распознаётся только символ в начале сегмента («C#», «a@b» — часть названия);
 * - даты/повторы/оценки жадно склеивают следующие сегменты, пока разбор успешен;
 * - первый # — список, остальные # — теги; при контексте список приоритетнее тега;
 * - `//` берёт весь остаток строки как заметку.
 * Детерминированный, rule-based, без ML. [today] фиксирует «сегодня» для тестов.
 */
class SmartAddParser {

    fun parse(
        input: String,
        today: LocalDate,
        timeZone: TimeZone,
        listNames: Set<String> = emptySet(),
        tagNames: Set<String> = emptySet(),
    ): SmartAddResult {
        val segments = splitSegments(input)
        val listSet = listNames.map { it.lowercase() }.toSet()
        val tagSet = tagNames.map { it.lowercase() }.toSet()

        val nameParts = mutableListOf<String>()
        val tokens = mutableListOf<SmartToken>()
        var due: ParsedDue? = null
        var start: ParsedDue? = null
        var priority: Priority? = null
        var listName: String? = null
        val tags = mutableListOf<String>()
        var repeat: ParsedRepeat? = null
        var location: String? = null
        var estimateMin: Int? = null
        var contact: String? = null
        var note: String? = null

        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            val text = seg.text
            val c = text.firstOrNull()

            when {
                // ---------- Примечание: остаток строки ----------
                text.startsWith("//") -> {
                    val payload = text.substring(2)
                    val rest = segments.subList(i + 1, segments.size).joinToString(" ") { it.text }
                    val value = (listOf(payload) + if (rest.isNotEmpty()) listOf(rest) else emptyList())
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    note = value.ifEmpty { "" }
                    tokens += SmartToken(SmartTokenType.NOTE, seg.start, input.length, value)
                    break
                }

                // ---------- Срок / дата начала ----------
                c == '^' || c == '~' -> {
                    val isStart = c == '~'
                    var payload = seg.clean.removePrefix(c.toString())
                    var j = i
                    if (payload.isEmpty()) {
                        if (i + 1 >= segments.size) { nameParts += text; i++; continue }
                        j = i + 1
                        payload = segments[j].clean
                    }
                    val g = greedy(segments, j, payload) { DateParser.parseDatePhrase(it, today, timeZone) }
                    if (g != null) {
                        val (parsed, endIdx, phrase) = g
                        if (isStart) start = parsed else due = parsed
                        tokens += SmartToken(
                            if (isStart) SmartTokenType.START else SmartTokenType.DUE,
                            seg.start,
                            segments[endIdx].end,
                            phrase,
                        )
                        i = endIdx + 1
                    } else {
                        nameParts += text
                        i++
                    }
                }

                // ---------- Приоритет ----------
                c == '!' -> {
                    var payload = seg.clean.removePrefix(c.toString())
                    var j = i
                    if (payload.isEmpty()) {
                        if (i + 1 >= segments.size) { nameParts += text; i++; continue }
                        j = i + 1
                        payload = segments[j].clean
                    }
                    val level = payload.toIntOrNull()
                    if (level in 1..3) {
                        priority = Priority.fromLevel(level)
                        tokens += SmartToken(SmartTokenType.PRIORITY, seg.start, segments[j].end, "$level")
                        i = j + 1
                    } else {
                        nameParts += text
                        i++
                    }
                }

                // ---------- Список / тег ----------
                c == '#' -> {
                    val raw = text.substring(1)
                    if (raw.isEmpty()) { nameParts += text; i++; continue }
                    val clean = raw.trimEnd(',', ';', '.', '!', '?', ' ')
                    val isFirst = listName == null
                    val lower = clean.lowercase()
                    val knownList = lower in listSet
                    val knownTag = lower in tagSet
                    val asTag = when {
                        !isFirst -> true
                        listSet.isNotEmpty() || tagSet.isNotEmpty() -> knownTag && !knownList
                        else -> false
                    }
                    if (clean.isEmpty()) { nameParts += text; i++; continue }
                    if (asTag) {
                        tags += clean
                        tokens += SmartToken(SmartTokenType.TAG, seg.start, seg.end, clean)
                    } else {
                        listName = clean
                        tokens += SmartToken(SmartTokenType.LIST, seg.start, seg.end, clean)
                    }
                    i++
                }

                // ---------- Повтор ----------
                c == '*' -> {
                    var payload = seg.clean.removePrefix(c.toString())
                    var j = i
                    if (payload.isEmpty()) {
                        if (i + 1 >= segments.size) { nameParts += text; i++; continue }
                        j = i + 1
                        payload = segments[j].clean
                    }
                    val g = greedy(segments, j, payload) { RepeatParser.parse(it) }
                    if (g != null) {
                        val (parsed, endIdx, phrase) = g
                        repeat = parsed
                        tokens += SmartToken(SmartTokenType.REPEAT, seg.start, segments[endIdx].end, phrase)
                        i = endIdx + 1
                    } else {
                        nameParts += text
                        i++
                    }
                }

                // ---------- Локация ----------
                c == '@' -> {
                    val name = text.substring(1).trimEnd(',', ';', '.', '!', '?')
                    if (name.isEmpty()) { nameParts += text; i++; continue }
                    location = name
                    tokens += SmartToken(SmartTokenType.LOCATION, seg.start, seg.end, name)
                    i++
                }

                // ---------- Оценка ----------
                c == '=' -> {
                    var payload = seg.clean.removePrefix(c.toString())
                    var j = i
                    if (payload.isEmpty()) {
                        if (i + 1 >= segments.size) { nameParts += text; i++; continue }
                        j = i + 1
                        payload = segments[j].clean
                    }
                    val g = greedy(segments, j, payload) { EstimateParser.parse(it) }
                    if (g != null) {
                        val (minutes, endIdx, phrase) = g
                        estimateMin = minutes
                        tokens += SmartToken(SmartTokenType.ESTIMATE, seg.start, segments[endIdx].end, phrase)
                        i = endIdx + 1
                    } else {
                        nameParts += text
                        i++
                    }
                }

                // ---------- Контакт ----------
                c == '+' -> {
                    val name = text.substring(1).trimEnd(',', ';', '.', '!', '?')
                    if (name.isEmpty()) { nameParts += text; i++; continue }
                    contact = name
                    tokens += SmartToken(SmartTokenType.CONTACT, seg.start, seg.end, name)
                    i++
                }

                else -> {
                    nameParts += text
                    i++
                }
            }
        }

        return SmartAddResult(
            name = nameParts.joinToString(" ").trim(),
            due = due,
            start = start,
            priority = priority,
            listName = listName,
            tags = tags,
            repeat = repeat,
            location = location,
            estimateMin = estimateMin,
            contact = contact,
            note = note,
            tokens = tokens,
        )
    }

    /**
     * Жадное склеивание сегментов: пробуем наращивать фразу от [payload] и держим
     * ПОСЛЕДНЮЮ успешную склейку (промежуточные неудачи не прерывают поиск —
     * важно для многословных дат «через 3 дня», "the day after tomorrow").
     * Возвращает (результат, индекс последнего съеденного сегмента, текст) или null.
     */
    private fun <T> greedy(
        segments: List<Segment>,
        j0: Int,
        payload: String,
        parse: (String) -> T?,
    ): Triple<T, Int, String>? {
        var best: T? = null
        var bestEnd = j0
        var bestText = payload
        var trial = payload
        var k = j0
        while (true) {
            val p = parse(trial)
            if (p != null) {
                best = p
                bestEnd = k
                bestText = trial
            }
            if (k + 1 >= segments.size) break
            k++
            trial = trial + " " + segments[k].clean
        }
        val result = best ?: return null
        return Triple(result, bestEnd, bestText)
    }

    private fun splitSegments(input: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var start = -1
        for (idx in input.indices) {
            val ch = input[idx]
            val ws = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
            if (!ws && start == -1) start = idx
            if (ws && start != -1) {
                out += makeSegment(input, start, idx)
                start = -1
            }
        }
        if (start != -1) out += makeSegment(input, start, input.length)
        return out
    }

    private fun makeSegment(input: String, start: Int, end: Int): Segment {
        val text = input.substring(start, end)
        return Segment(text, text.trimEnd(',', ';', '.', '!', '?', ':'), start, end)
    }
}