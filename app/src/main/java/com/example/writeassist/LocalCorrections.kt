package com.example.writeassist

/**
 * Deterministic offline proofreading. Only applies high-confidence transformations.
 * AI proofreading is optional and never required for these suggestions.
 */
object LocalCorrections {
    private val dictionary = mapOf(
        "teh" to "the", "adn" to "and", "recieve" to "receive",
        "recieved" to "received", "definately" to "definitely",
        "seperate" to "separate", "occured" to "occurred",
        "grammer" to "grammar", "dont" to "don't", "cant" to "can't",
        "wont" to "won't", "doesnt" to "doesn't", "isnt" to "isn't",
        "wasnt" to "wasn't", "im" to "I'm", "ive" to "I've",
        "youre" to "you're", "theyre" to "they're",
        "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
        "couldnt" to "couldn't", "didnt" to "didn't",
        "hasnt" to "hasn't", "havent" to "haven't",
        "werent" to "weren't", "arent" to "aren't",
        "alot" to "a lot", "becuase" to "because",
        "beleive" to "believe", "freind" to "friend",
        "wierd" to "weird", "untill" to "until",
        "thier" to "their", "adress" to "address",
        "tomorow" to "tomorrow", "tommorow" to "tomorrow",
        "neccessary" to "necessary", "enviroment" to "environment",
        "goverment" to "government", "occurence" to "occurrence",
        "begining" to "beginning", "comming" to "coming",
        "accomodate" to "accommodate", "acheive" to "achieve",
        "succesful" to "successful", "wich" to "which",
        "writting" to "writing", "sentance" to "sentence",
        "definatly" to "definitely", "realy" to "really"
    )

    private val protectedText = Regex(
        """(?i)\b(?:[a-z][a-z0-9+.-]*://|www\.)\S+|\b[\w.%+-]+@[\w.-]+\.[a-z]{2,}\b|(?<!\w)[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+\b"""
    )
    private val word = Regex("""[\p{L}]+(?:['’][\p{L}]+)*""")
    private val agreement = Regex("""\b(he|she|it)[ \t]+(are|have|do|don't)\b""", RegexOption.IGNORE_CASE)
    private val pluralAgreement = Regex("""\b(we|they|you)[ \t]+(is|has|does|doesn't)\b""", RegexOption.IGNORE_CASE)
    private val modal = Regex("""\b(could|should|would|might|must)[ \t]+of(?=[ \t]+\p{L})""", RegexOption.IGNORE_CASE)
    private val duplicate = Regex("""\b(the|a|an|to|of|in|at|and|for)[ \t]+\1\b""", RegexOption.IGNORE_CASE)
    private val repeatedSpace = Regex("""(?<=\S)[ \t]{2,}(?=\S)""")
    private val spaceBefore = Regex("""(?<=\p{L})[ \t]+(?=[,.!?;:])""")
    private val spaceAfter = Regex("""(?<=\p{L}[;,!?])(?=\p{L})""")
    private val sentenceStart = Regex("""(?:\A[ \t]*|[!?][ \t]+|(?<![\p{L}.]\p{L})\.[ \t]{2,})([a-z])""")

    fun suggestions(text: String): List<SuggestionDiff.Edit> {
        val edits = mutableListOf<SuggestionDiff.Edit>()
        val protected = protectedText.findAll(text).map { it.range }.toList()

        fun add(start: Int, end: Int, replacement: String) {
            val before = text.substring(start, end)
            if (before == replacement || protected.any { start < it.last + 1 && end > it.first } ||
                edits.any { start < it.end && end > it.start || start == it.start }) return
            edits += SuggestionDiff.Edit(start, end, before, replacement)
        }

        fun replaceGroup(match: MatchResult, group: Int, replacement: String) {
            val part = match.groups[group] ?: return
            add(part.range.first, part.range.last + 1, preserveCase(part.value, replacement))
        }

        for (match in duplicate.findAll(text)) {
            val first = match.groups[1] ?: continue
            add(first.range.last + 1, match.range.last + 1, "")
        }
        for (match in agreement.findAll(text)) {
            val replacement = when (match.groupValues[2].lowercase()) {
                "are" -> "is"
                "have" -> "has"
                "do" -> "does"
                else -> "doesn't"
            }
            replaceGroup(match, 2, replacement)
        }
        for (match in pluralAgreement.findAll(text)) {
            val replacement = when (match.groupValues[2].lowercase()) {
                "is" -> "are"
                "has" -> "have"
                "does" -> "do"
                else -> "don't"
            }
            replaceGroup(match, 2, replacement)
        }
        for (match in modal.findAll(text)) {
            add(match.range.last - 1, match.range.last + 1, "have")
        }
        for (match in word.findAll(text)) {
            val replacement = if (match.value == "i") "I" else dictionary[match.value.lowercase()]
            if (replacement != null) replaceGroup(match, 0, replacement)
        }
        for (match in spaceBefore.findAll(text)) add(match.range.first, match.range.last + 1, "")
        for (match in repeatedSpace.findAll(text)) add(match.range.first, match.range.last + 1, " ")
        for (match in spaceAfter.findAll(text)) add(match.range.first, match.range.first, " ")
        for (match in sentenceStart.findAll(text)) {
            replaceGroup(match, 1, match.groupValues[1].uppercase())
        }
        return edits.sortedWith(compareBy({ it.start }, { it.end }))
    }

    fun correct(text: String): String =
        suggestions(text).asReversed().fold(text, SuggestionDiff::apply)

    private fun preserveCase(original: String, replacement: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> replacement.uppercase()
        original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }
}
