package com.example.writeassist

object LocalCorrections {
    fun correct(text: String): String {
        var result = text
        val dictionary = mapOf(
            "teh" to "the", "adn" to "and", "recieve" to "receive",
            "recieved" to "received", "definately" to "definitely",
            "seperate" to "separate", "occured" to "occurred",
            "grammer" to "grammar", "dont" to "don't", "cant" to "can't",
            "wont" to "won't", "doesnt" to "doesn't", "isnt" to "isn't",
            "wasnt" to "wasn't", "im" to "I'm", "ive" to "I've",
            "youre" to "you're", "theyre" to "they're"
        )
        for ((wrong, right) in dictionary) {
            result = result.replace(Regex("\\b" + Regex.escape(wrong) + "\\b", RegexOption.IGNORE_CASE)) {
                if (it.value.firstOrNull()?.isUpperCase() == true)
                    right.replaceFirstChar { ch -> ch.uppercase() }
                else right
            }
        }
        result = result.replace(Regex(" {2,}"), " ")
            .replace(Regex("\\b([A-Za-z]{2,})\\s+\\1\\b", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("\\bi\\b"), "I")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
        return result
    }
}
