package com.example.writeassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofreadingTest {
    @Test fun checksIndependentIssuesAcrossParagraphs() {
        val draft = "I saw teh the dog. She have a freind !\n\nThey is ready,and we should of left."
        val edits = LocalCorrections.suggestions(draft)
        assertTrue(edits.size >= 7)
        for (edit in edits) assertEquals(edit.before, draft.substring(edit.start, edit.end))
        assertEquals(
            "I saw the the dog. She has a friend!\n\nThey are ready, and we should have left.",
            LocalCorrections.correct(draft)
        )
        assertEquals("I saw the the dog. She have a freind !\n\nThey is ready,and we should of left.",
            SuggestionDiff.apply(draft, edits.first()))
    }

    @Test fun protectsUrlsAndParagraphBoundaries() {
        val draft = "Contact teh@example.com or https://example.com/teh?q=x:1.\n\nIf she were ready, I'd go."
        assertTrue(LocalCorrections.suggestions(draft).isEmpty())
    }

    @Test fun handlesRepeatedTextAtDistinctOffsets() {
        val draft = "The freind met my freind"
        val edits = LocalCorrections.suggestions(draft)
        assertEquals(listOf(4, 18), edits.map { it.start })
        assertEquals("The freind met my friend", SuggestionDiff.apply(draft, edits.last()))
    }

    @Test fun preservesCursorPositionRelativeToAnEdit() {
        val edit = SuggestionDiff.Edit(4, 7, "of ", "have ")
        assertEquals(3, SuggestionDiff.mapOffset(3, edit, 15))
        assertEquals(9, SuggestionDiff.mapOffset(6, edit, 15))
        assertEquals(12, SuggestionDiff.mapOffset(10, edit, 15))
    }

    @Test fun diffReconstructsAiRevision() {
        val draft = "I was there.\n\nThey is ready."
        val revision = "I was already there.\n\nThey are ready!"
        assertEquals(revision,
            SuggestionDiff.between(draft, revision).asReversed().fold(draft, SuggestionDiff::apply))
    }
    @org.junit.Test
    fun acceptingOneEditKeepsIndependentSuggestions() {
        val original = "We recieved teh mesage."
        val accepted = SuggestionDiff.Edit(3, 11, "recieved", "got")
        val updated = SuggestionDiff.apply(original, accepted)
        val second = SuggestionDiff.Edit(12, 15, "teh", "the")
        val third = SuggestionDiff.Edit(16, 22, "mesage", "message")
        org.junit.Assert.assertEquals(second.copy(start = 7, end = 10),
            SuggestionDiff.afterAccepting(second, accepted, updated))
        org.junit.Assert.assertEquals(third.copy(start = 11, end = 17),
            SuggestionDiff.afterAccepting(third, accepted, updated))
        org.junit.Assert.assertNull(SuggestionDiff.afterAccepting(accepted, accepted, updated))
    }
}
