package com.byyourside.talkandcut.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanTranscriptTest {
    @Test fun removesModelTagsButPreservesCommand() {
        assertEquals("3초 뒤에 찍어줘", KoreanTranscript.clean("<|ko|><|NEUTRAL|><|Speech|>3초 뒤에 찍어줘."))
    }
    @Test fun preservesNumbersAndInternalPunctuation() {
        assertEquals("두 번째 컷 다시 찍고, 밝기는 0.5로", KoreanTranscript.clean("두 번째 컷 다시 찍고, 밝기는 0.5로!"))
    }
    @Test fun nonSpeechTagsDoNotBecomeCommands() {
        assertEquals("", KoreanTranscript.clean("<|ko|><|BGM|><|withitn|>"))
    }
}
