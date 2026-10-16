package com.neex.deliberator.services

/**
 * The tail of a container log is a series of `logs.fetch ... for` windows that overlap by the few milliseconds between one window's
 * end and the next one's start. [newLines] drops from a window's lines the part the console already shows: the longest run that is both a
 * suffix of what was printed and a prefix of the window. No overlap means every line is new.
 */
object TailMerge {
    fun newLines(printedTail: List<String>, window: List<String>): List<String> {
        val max = minOf(printedTail.size, window.size)
        for (k in max downTo 1) {
            if (printedTail.subList(printedTail.size - k, printedTail.size) == window.subList(0, k)) return window.subList(k, window.size)
        }
        return window
    }
}
