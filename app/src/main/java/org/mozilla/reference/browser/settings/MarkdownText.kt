/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

/**
 * Turns the Markdown of the licence notices the native libraries publish (DEPENDENCIES.md) into plain text: headings
 * and list items lose their marks, links keep their text and address but not in-page anchors, HTML anchors and rules
 * go, and the lines of a paragraph are joined, so that the screen wraps them; in the licence texts (code blocks) only
 * lines of the same indent that do not start a copyright line or list item are, so their layout stays.
 */
object MarkdownText {
    private val fence = Regex("""^\s*```""")
    private val htmlAnchor = Regex("""^\s*<span[^>]*>\s*</span>\s*$""")
    private val rule = Regex("""^\s*-{3,}\s*$""")
    private val heading = Regex("""^\s*#{1,6}\s+(.*)$""")
    private val listItem = Regex("""^\s*[*-]\s+(.*)$""")
    private val anchorLink = Regex("""\[([^\]]+)]\(\s*#[^)]*\)""")
    // Lines in a licence text that start something of their own: a copyright line, a list item or an underline.
    private val newLine =
        Regex("""^\s*(copyright|\(c\)|©|[-*•]\s|\(?[0-9a-z]{1,3}[.)]\s|[-=]{3,}\s*$)""", RegexOption.IGNORE_CASE)
    private val copyright = Regex("""^\s*(copyright|\(c\)|©)""", RegexOption.IGNORE_CASE)
    private val link = Regex("""\[([^\]]+)]\(\s*([^)\s]+)\s*\)""")

    /** The plain text of [markdown], with blank lines between paragraphs. */
    fun toPlainText(markdown: String): String {
        val converter = Converter()
        markdown.lines().forEach(converter::add)
        return converter.out.toString().trim()
    }

    private class Converter {
        val out = StringBuilder()
        private var inFence = false
        private var inProse = false
        private var fenceIndent = -1

        fun add(line: String) {
            when {
                fence.containsMatchIn(line) -> {
                    breakParagraph()
                    inFence = !inFence
                }
                inFence -> addLicenceLine(line)
                htmlAnchor.matches(line) -> Unit
                line.isBlank() || rule.matches(line) -> breakParagraph()
                heading.matches(line) -> {
                    breakParagraph()
                    out.append(inline(heading.find(line)!!.groupValues[1])).append("\n\n")
                }
                listItem.matches(line) -> {
                    if (inProse) breakParagraph()
                    out.append("\u2022 ").append(inline(listItem.find(line)!!.groupValues[1])).append('\n')
                }
                inProse -> joinLine(inline(line.trim()))
                else -> {
                    out.append(inline(line.trim())).append('\n')
                    inProse = true
                }
            }
        }

        private fun addLicenceLine(line: String) {
            val indent = line.length - line.trimStart().length
            if (line.isNotBlank() && indent == fenceIndent && !newLine.containsMatchIn(line)) {
                // A wrapped line of the same paragraph: the screen wraps it where it fits.
                joinLine(line.trim())
            } else {
                out.append(line).append('\n')
                // Nothing is joined onto a copyright line either.
                fenceIndent = if (line.isBlank() || copyright.containsMatchIn(line)) -1 else indent
            }
        }

        // Onto the line before, in place of its line break.
        private fun joinLine(text: String) {
            out.setLength(out.length - 1)
            out.append(' ').append(text).append('\n')
        }

        private fun breakParagraph() {
            if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append(if (out.endsWith("\n")) "\n" else "\n\n")
            inProse = false
        }
    }

    private fun inline(text: String) = link.replace(anchorLink.replace(text, "$1"), "$1 ($2)")
}
