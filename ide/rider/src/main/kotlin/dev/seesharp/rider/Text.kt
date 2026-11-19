// UI strings and the exercise HTML. No IntelliJ classes, so both are testable without the IDE.
package dev.seesharp.rider

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/** Strings follow SeeSharp's locale (from GET /api/course, German until known), like the course content; English is the fallback. */
object Strings {
    @Volatile var locale: String = "de"

    fun t(key: String, vararg args: Any?): String {
        val bundle = ResourceBundle.getBundle("messages.SeeSharp", Locale.forLanguageTag(locale), Strings::class.java.classLoader,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
        return MessageFormat(bundle.getString(key), Locale.ROOT).format(args)
    }
}

fun t(key: String, vararg args: Any?) = Strings.t(key, *args)

fun escapeHtml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
        else -> append(c)
    }
}

fun skillText(s: MissingSkill) = if (s.level == null) s.label else t("skillLevel", s.label, s.level)

fun outcomeText(outcome: String) = when (outcome) {
    "Passed", "Failed", "BuildFailed", "TimedOut" -> t("outcome.$outcome")
    else -> outcome
}

/** "2 von 2 Tests bestanden." or, without tests (build failed, timeout), the outcome. */
fun gradeSummary(r: GradingResult): String {
    val tests = r.tests.orEmpty()
    return if (tests.isEmpty()) outcomeText(r.outcome) else t("testsPassed", tests.count { it.outcome == "Passed" }, tests.size)
}

/**
 * The details view: last grade of this exercise, then the instructions. Course content (the trainee's own checkout) is rendered
 * as is; the CSP blocks scripts in it. Test names, messages and output come from the trainee's code and are escaped.
 */
fun exerciseHtml(d: ExerciseDetail, grade: Grade?, serverUrl: String, css: String): String {
    val ex = d.exercise
    val body = StringBuilder()
    if (!ex.unlocked) {
        body.append("<p>${escapeHtml(t("locked"))}</p><ul>")
        ex.missingSkills.orEmpty().forEach { body.append("<li>${escapeHtml(skillText(it))}</li>") }
        body.append("</ul>")
    } else {
        if (!ex.items.coding)
            body.append("<p>${escapeHtml(t("quizOnly"))} <a href=\"${escapeHtml(exerciseUrl(serverUrl, ex.id))}\">${
                escapeHtml(t("openInTab"))}</a></p>")
        if (grade != null) body.append(resultsHtml(grade))
        body.append("<hr>")
        if (d.instructionsFellBack) body.append("<p class=\"note\">${escapeHtml(t("fellBack"))}</p>")
        body.append(d.instructionsHtml.orEmpty())
    }
    return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data:;">
<style>$css</style></head><body><h1>${escapeHtml(ex.title)}</h1>$body</body></html>"""
}

/** SeeSharp's own page of an exercise; the panel opens it in a Rider tab. */
fun exerciseUrl(serverUrl: String, id: String) = "${serverUrl.trimEnd('/')}/exercise/${encodePath(id)}"

/** Links in the panel's HTML that select an exercise in the tree instead of navigating. */
const val SELECT_LINK = "seesharp:select/"

fun selectedId(link: String): String? =
    link.takeIf { it.startsWith(SELECT_LINK) }?.let { java.net.URLDecoder.decode(it.removePrefix(SELECT_LINK), Charsets.UTF_8) }

/**
 * What the panel shows while no exercise is selected: progress, the next exercise with the other open ones of its module
 * (same rule as SeeSharp's dashboard: first unlocked, unpassed mandatory exercise), and every module.
 */
fun overviewHtml(c: Course, css: String): String {
    fun link(ex: Exercise) = "<a href=\"$SELECT_LINK${encodePath(ex.id)}\">${escapeHtml(ex.title)}</a>"
    val mandatory = c.modules.flatMap { m -> m.exercises.filter { !it.optional } }
    val body = StringBuilder("<p>${escapeHtml(t("modulesDone", c.modules.count { it.passed }, c.modules.size))}<br>${
        escapeHtml(t("exercisesDone", mandatory.count { it.passed }, mandatory.size))}</p>")
    body.append("<h2>${escapeHtml(t("nextUp"))}</h2>")
    val next = c.modules.firstOrNull { m -> m.exercises.any { !it.optional && it.unlocked && !it.passed } }
    if (next == null) body.append("<p>${escapeHtml(t("allDone"))}</p>")
    else {
        body.append("<p>${escapeHtml("%02d %s".format(next.level, next.title))}</p><ul>")
        next.exercises.filter { !it.optional && it.unlocked && !it.passed }.forEach { body.append("<li>${link(it)}</li>") }
        body.append("</ul>")
    }
    body.append("<h2>${escapeHtml(t("modules"))}</h2><table>")
    for (m in c.modules) {
        val own = m.exercises.filter { !it.optional }
        val mark = when { m.passed -> "<span class=\"ok\">✓</span>"; !m.unlocked -> "🔒"; else -> "" }
        body.append("<tr><td>${escapeHtml("%02d %s".format(m.level, m.title))}</td><td class=\"note\">${own.count { it.passed }}/${own.size}</td><td>$mark</td></tr>")
    }
    body.append("</table><p class=\"note\">${escapeHtml(t("selectHint"))}</p>")
    return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline';">
<style>$css</style></head><body><h1>${escapeHtml(t("overview"))}</h1>$body</body></html>"""
}

fun resultsHtml(g: Grade): String {
    val r = g.result
    val html = StringBuilder("<p class=\"${if (g.passed) "ok" else "bad"}\"><strong>${escapeHtml(t(if (g.passed) "passed" else "notPassed"))}</strong> · ${
        escapeHtml(outcomeText(r.outcome))} · ${escapeHtml(r.duration.substringBefore('.'))}</p>")
    val tests = r.tests.orEmpty()
    when {
        !r.buildErrors.isNullOrEmpty() -> html.append("<pre>${escapeHtml(r.buildErrors.joinToString("\n"))}</pre>")
        tests.isNotEmpty() -> {
            html.append("<table>")
            for (x in tests) {
                val (cls, mark) = when (x.outcome) { "Passed" -> "ok" to "✓"; "Failed" -> "bad" to "✗"; else -> "" to "–" }
                html.append("<tr><td class=\"$cls\">$mark</td><td><code>${escapeHtml(x.name)}</code>")
                if (!x.message.isNullOrEmpty()) html.append("<pre>${escapeHtml(x.message)}</pre>")
                html.append("</td></tr>")
            }
            html.append("</table>")
        }
        else -> html.append("<pre>${escapeHtml(r.output.orEmpty().takeLast(3000))}</pre>")
    }
    return html.toString()
}

internal fun encodePath(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
