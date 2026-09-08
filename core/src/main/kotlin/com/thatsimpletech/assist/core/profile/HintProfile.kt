package com.thatsimpletech.assist.core.profile

import com.thatsimpletech.assist.core.grammar.HintCodec

/** Settings/profile word for the hint codec (C5). One codec per run; Numeric is the default. */
enum class HintProfile {
    NUMERIC,
    LETTERS,
    ;

    val codec: HintCodec
        get() = when (this) {
            NUMERIC -> HintCodec.Numeric
            LETTERS -> HintCodec.Letters
        }

    val word: String get() = codec.word

    companion object {
        fun of(codec: HintCodec): HintProfile = when (codec) {
            is HintCodec.Letters -> LETTERS
            else -> NUMERIC
        }

        /** Unknown or empty words become Numeric, same as [HintCodec.parse]. */
        fun parse(word: String): HintProfile = of(HintCodec.parse(word))
    }
}
