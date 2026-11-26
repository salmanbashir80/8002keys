/*
 * Copyright (C) 2026 Salman Bashir (8002Keys)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class AiDiffHighlightResult(
    val annotatedText: AnnotatedString,
    val changedWordCount: Int,
)

/**
 * Intelligent word-level diff highlighter that marks every word or phrase modified/added by
 * Google Gemini or ChatGPT in a crisp Light Green highlight so the user can immediately see
 * what changed—even across long multi-paragraph messages.
 */
object AiDiffHighlighter {
    private val LightGreenBg = Color(0xFF86EFAC)
    private val LightGreenText = Color(0xFF052E16)

    fun buildHighlightedDiff(
        originalText: String,
        fixedText: String,
    ): AiDiffHighlightResult {
        if (fixedText.isBlank()) {
            return AiDiffHighlightResult(AnnotatedString(""), 0)
        }
        if (originalText.isBlank() || originalText.trim() == fixedText.trim()) {
            return AiDiffHighlightResult(AnnotatedString(fixedText), 0)
        }

        val origLines = originalText.split('\n')
        val fixedLines = fixedText.split('\n')
        var totalChanged = 0

        val annotated = buildAnnotatedString {
            fixedLines.forEachIndexed { lineIdx, fixedLine ->
                if (lineIdx > 0) append('\n')
                val origLine = origLines.getOrNull(lineIdx).orEmpty()
                val (lineChangedCount, tokens) = diffLineTokens(origLine, fixedLine)
                totalChanged += lineChangedCount
                tokens.forEachIndexed { tokIdx, (token, isChanged) ->
                    if (tokIdx > 0) append(' ')
                    if (isChanged) {
                        withStyle(
                            SpanStyle(
                                background = LightGreenBg,
                                color = LightGreenText,
                                fontWeight = FontWeight.Bold,
                            ),
                        ) {
                            append(" $token ")
                        }
                    } else {
                        append(token)
                    }
                }
            }
        }
        return AiDiffHighlightResult(
            annotatedText = annotated,
            changedWordCount = totalChanged,
        )
    }

    private fun diffLineTokens(
        origLine: String,
        fixedLine: String,
    ): Pair<Int, List<Pair<String, Boolean>>> {
        val origWords = origLine.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val fixedWords = fixedLine.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (fixedWords.isEmpty()) return 0 to emptyList()
        if (origWords.isEmpty()) {
            return fixedWords.size to fixedWords.map { it to true }
        }

        // Guard against huge quadratic DP on very long paragraphs (> 220 words per line)
        if (origWords.size * fixedWords.size > 48_000) {
            val origSet = origWords.toSet()
            var changed = 0
            val out = fixedWords.map { w ->
                val isChanged = w !in origSet
                if (isChanged) changed++
                w to isChanged
            }
            return changed to out
        }

        val m = origWords.size
        val n = fixedWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (origWords[i] == fixedWords[j]) {
                    1 + dp[i + 1][j + 1]
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val matchedFixedIndices = BooleanArray(n)
        var i = 0
        var j = 0
        while (i < m && j < n) {
            if (origWords[i] == fixedWords[j]) {
                matchedFixedIndices[j] = true
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }

        var changedCount = 0
        val result = fixedWords.mapIndexed { idx, word ->
            val isChanged = !matchedFixedIndices[idx]
            if (isChanged) changedCount++
            word to isChanged
        }
        return changedCount to result
    }
}
