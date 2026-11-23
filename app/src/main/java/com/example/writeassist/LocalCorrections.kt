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

    fun correct(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for ((wrong, right) in dictionary) {
            val regex = Regex("\\b" + Regex.escape(wrong) + "\\b", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { match ->
                // Don't correct an unfinished word while the user is typing.
                if (match.range.last == result.lastIndex && !result.last().isWhitespace()) {
                    match.value
                } else if (match.value.first().isUpperCase() && right != "I'm" && right != "I've") {
                    right.replaceFirstChar { it.uppercase() }
                } else right
            }
        }
        result = result.replace(Regex(" {2,}"), " ")
            .replace(Regex("\\b([A-Za-z]{2,})\\s+\\1\\b", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,;:!?])(?=[A-Za-z])"), "$1 ")
            .replace(Regex("\\bi\\b"), "I")
        return result
    }
}
