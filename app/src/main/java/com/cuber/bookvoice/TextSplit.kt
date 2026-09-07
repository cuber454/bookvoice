package com.cuber.bookvoice

import java.util.ArrayList

/** Разбивка текста на предложения и сборка списка [Sentence] из абзацев. */
object TextSplit {

    /** Режет текст на предложения. Не режет: десятичные дроби, "т.е.", "и т.п." и т.п. */
    fun splitToSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = text.length
        fun punct(c: Char) = c == '.' || c == '!' || c == '?' || c == '…'
        while (i < n) {
            val c = text[i]
            if (punct(c)) {
                var j = i
                while (j < n && punct(text[j])) j++
                var k = j
                while (k < n && text[k].isWhitespace()) k++
                val prevNum = i > 0 && text[i - 1].isDigit()
                val nextNum = k < n && text[k].isDigit()
                val nextLower = k < n && text[k].isLowerCase()
                val abbreviation = prevNum && nextNum || nextLower && (j - i) <= 2
                if (!abbreviation) {
                    val piece = text.substring(start, k).trim()
                    if (piece.isNotEmpty()) out.add(piece)
                    start = k
                    i = k
                    continue
                }
            }
            i++
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Абзацы -> плоский список предложений; первое предложение абзаца помечается paragraphStart. */
    fun fromParagraphs(paragraphs: List<String>): List<Sentence> {
        val res = ArrayList<Sentence>()
        for (p in paragraphs) {
            val parts = splitToSentences(p)
            for ((idx, part) in parts.withIndex()) {
                res.add(Sentence(part, paragraphStart = idx == 0))
            }
        }
        return res
    }
}
