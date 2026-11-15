package com.byyourside.talkandcut.command

/** Only one complete, known function is accepted. Arbitrary model text is never executed. */
data class Team4Call(val function: Int, val value: Int) {
    companion object {
        private val pattern = Regex("^\\s*<maum_([0-6])>\\((count|relation|concept|mode|action)=(-?\\d+)\\)(?:<maum_end>)?\\s*$")
        fun parse(text: String): Team4Call? {
            val match = pattern.matchEntire(text) ?: return null
            val function = match.groupValues[1].toInt()
            val key = match.groupValues[2]
            val value = match.groupValues[3].toIntOrNull() ?: return null
            val valid = when (function) {
                0 -> key == "count" && value in -1..4
                1 -> key == "relation" && value in 0..7
                2 -> key == "concept" && value in 0..9
                3, 4, 5 -> key == "mode" && value in 1..2
                6 -> key == "action" && value in 0..2
                else -> false
            }
            return if (valid) Team4Call(function, value) else null
        }
    }
}
