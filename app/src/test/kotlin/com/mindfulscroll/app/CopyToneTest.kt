package com.mindfulscroll.app

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The mechanical half of the neutral-tone rule in CONTRIBUTING.md ("Writing user-facing copy").
 *
 * Copy that shames, scolds or praises the user's own behaviour measures small effects at best and
 * can entrench the behaviour through reactance - and it quietly corrupts the data, because people
 * start giving the answers that sound better. The checklist covers tone; this covers the words that
 * are never neutral, so a future PR cannot reintroduce them without someone noticing.
 *
 * It scans string literals only (comments are free to discuss "failure" and "scores" - the doc
 * comments on PauseOutcome do exactly that), and only in the packages that talk to the user.
 * The Diagnostics screen, Logcat calls and diagnostics.log() lines are excluded: they describe the
 * app's own machinery ("overlay FAILED to display"), never the user.
 *
 * A literal that genuinely needs one of these words can be allowed with `// copy-tone: ok` on the
 * same line, plus a reason in the PR.
 */
class CopyToneTest {

    @Test
    fun `user-facing strings contain no judgemental vocabulary`() {
        val roots = USER_FACING_DIRS.map { File(SOURCE_ROOT, it) }
        assertWithMessage("expected user-facing source dirs under ${SOURCE_ROOT.absolutePath}")
            .that(roots.all { it.isDirectory })
            .isTrue()

        val violations = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filterNot { file -> EXCLUDED_DIRS.any { file.path.contains(it) } }
            .flatMap { file -> scan(file) }

        assertWithMessage(
            "Judgemental wording in user-facing copy - see CONTRIBUTING.md \"Writing user-facing copy\":\n" +
                violations.joinToString("\n"),
        ).that(violations).isEmpty()
    }

    @Test
    fun `the scanner catches a violation and ignores comments`() {
        val file = File.createTempFile("CopyToneSample", ".kt").apply {
            writeText(
                """
                // a comment about failure is fine
                /** "Not really" is not a failure - KDoc may quote words. */
                val a = "You broke your goal again."
                val b = "Great job - you closed it!"
                val c = "Opening Instagram"
                val d = "Streak kept" // copy-tone: ok
                Log.e(TAG, "Failed to add the window")
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val hits = scan(file).joinToString("\n")

        // Line 3 trips twice ("broke", "again"), line 4 once; the comments, the KDoc quote, the
        // neutral literal and the explicitly allowed one do not trip at all.
        assertWithMessage(hits).that(hits.lines()).hasSize(3)
        assertWithMessage(hits).that(hits).contains(":3: \"broke\"")
        assertWithMessage(hits).that(hits).contains(":3: \"again\"")
        assertWithMessage(hits).that(hits).contains(":4: \"great job\"")
    }

    private fun scan(file: File): List<String> =
        file.readLines().withIndex().flatMap { (index, line) ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@flatMap emptyList()
            if (line.contains(ALLOW_MARKER)) return@flatMap emptyList()
            if (MACHINERY_CALL.containsMatchIn(line)) return@flatMap emptyList()
            STRING_LITERAL.findAll(line.substringBefore(" //")).flatMap { literal ->
                val text = literal.groupValues[1].lowercase()
                DENYLIST.filter { phrase -> Regex("\\b${Regex.escape(phrase)}\\b").containsMatchIn(text) }
                    .map { phrase -> "${file.name}:${index + 1}: \"$phrase\" in ${literal.value}" }
            }.toList()
        }

    private companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        val SOURCE_ROOT = File("src/main/kotlin/com/mindfulscroll/app")

        val USER_FACING_DIRS = listOf("ui", "overlay", "intention")

        val EXCLUDED_DIRS = listOf("ui/diagnostics")

        const val ALLOW_MARKER = "copy-tone: ok"

        /**
         * Logcat and the Diagnostics log describe the app's own machinery ("Failed to add the
         * overlay window"), never the user, and "failed" is exactly the right word there.
         */
        val MACHINERY_CALL = Regex("""\bLog\.[vdiwe]\(|diagnostics\.log\(""")

        val STRING_LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

        /**
         * Words that are never neutral when said about the user's own behaviour. Kept to the
         * unambiguous ones on purpose: a denylist this blunt that also flagged "only" or "limit"
         * would be switched off within a month.
         */
        val DENYLIST = listOf(
            // failure and rule-breaking
            "fail", "failed", "failure", "broke", "broken", "blew", "cheat", "cheated", "caught",
            "exceeded", "violated",
            // waste and excess
            "wasted", "waste", "too much", "too long", "addict", "addicted", "addiction",
            // guilt
            "guilt", "guilty", "shame", "ashamed", "should", "oops", "again",
            // praise
            "great job", "good job", "well done", "proud", "congrats", "congratulations",
            // gamification - permanently out of scope
            "streak", "streaks", "score", "points", "badge", "badges", "level up",
        )
    }
}
