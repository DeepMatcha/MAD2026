package org.deepmatcha.mad.ondevice.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPassagesTest {
    @Test fun longUnbrokenMaterialKeepsEveryCodePoint() {
        val source = "자료😀".repeat(1200)
        val parts = summaryPassages(source).toList()
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray().size <= 1500 && it.toByteArray().decodeToString() == it })
    }

    @Test fun whitespaceAndParagraphs() {
        val first = "가".repeat(499)
        val second = "나".repeat(40)
        assertEquals(listOf(first, second), summaryPassages("  $first\n\n$second ").toList())
        assertTrue(summaryPassages(" \n  ").none())
    }
}
