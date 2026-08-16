package com.example.exp1

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

object MarkdownFormatter {

    private const val GREEN_APPLE = "#8DB600"

    private data class LineInfo(val text: String, val isHeading: Boolean, val headingLevel: Int)

    fun toSpannable(input: String): SpannableString {
        // ---- Pass 1: per-line cleanup — strip "#" headings, convert "* "/"- " to bullets ----
        val lineInfos = input.lines().map { line ->
            val trimmed = line.trimStart()
            val leading = line.substring(0, line.length - trimmed.length)

            val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2]
                LineInfo(leading + text, isHeading = true, headingLevel = level)
            } else when {
                trimmed.startsWith("* ") -> LineInfo(leading + "• " + trimmed.substring(2), false, 0)
                trimmed.startsWith("- ") -> LineInfo(leading + "• " + trimmed.substring(2), false, 0)
                else -> LineInfo(line, false, 0)
            }
        }
        val cleaned = lineInfos.joinToString("\n") { it.text }

        // ---- Pass 2: inline **bold** / *italic* parsing ----
        val spans = mutableListOf<Triple<Int, Int, Int>>() // start, end, Typeface style
        val output = StringBuilder()
        var i = 0

        while (i < cleaned.length) {
            when {
                cleaned.startsWith("**", i) -> {
                    val end = cleaned.indexOf("**", i + 2)
                    if (end != -1) {
                        val start = output.length
                        output.append(cleaned.substring(i + 2, end))
                        spans.add(Triple(start, output.length, Typeface.BOLD))
                        i = end + 2
                    } else {
                        output.append(cleaned[i]); i++
                    }
                }
                cleaned[i] == '*' -> {
                    val end = cleaned.indexOf('*', i + 1)
                    if (end != -1) {
                        val start = output.length
                        output.append(cleaned.substring(i + 1, end))
                        spans.add(Triple(start, output.length, Typeface.ITALIC))
                        i = end + 1
                    } else {
                        output.append(cleaned[i]); i++
                    }
                }
                else -> {
                    output.append(cleaned[i]); i++
                }
            }
        }

        val result = SpannableString(output.toString())

        // Apply bold/italic + green color for bold
        for ((start, end, style) in spans) {
            result.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (style == Typeface.BOLD) {
                result.setSpan(
                    ForegroundColorSpan(Color.parseColor(GREEN_APPLE)),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // ---- Pass 3: reapply heading styling per line, using preserved line boundaries ----
        var lineStart = 0
        val outputLines = output.toString().split("\n")
        for ((index, lineText) in outputLines.withIndex()) {
            val lineEnd = lineStart + lineText.length
            val info = lineInfos.getOrNull(index)
            if (info?.isHeading == true && lineText.isNotEmpty()) {
                val sizeScale = when (info.headingLevel) {
                    1 -> 1.25f
                    2 -> 1.15f
                    else -> 1.08f
                }
                result.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(RelativeSizeSpan(sizeScale), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(
                    ForegroundColorSpan(Color.parseColor(GREEN_APPLE)),
                    lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineStart = lineEnd + 1 // +1 for the "\n" we split on
        }

        return result
    }
}