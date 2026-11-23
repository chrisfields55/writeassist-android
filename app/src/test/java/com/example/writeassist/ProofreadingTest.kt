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
}
