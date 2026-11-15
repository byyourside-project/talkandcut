package com.byyourside.talkandcut.command

import org.junit.Assert.*
import org.junit.Test

class Team4CallTest {
    @Test fun acceptsNativeOutputWithoutEosText() {
        assertEquals(Team4Call(6, 1), Team4Call.parse("<maum_6>(action=1)"))
        assertEquals(Team4Call(0, 3), Team4Call.parse("<maum_0>(count=3)<maum_end>"))
    }
    @Test fun rejectsMultipleCallsAndUnknownValues() {
        assertNull(Team4Call.parse("<maum_6>(action=1)<maum_6>(action=1)"))
        assertNull(Team4Call.parse("<maum_6>(action=9)"))
        assertNull(Team4Call.parse("<maum_6>(count=1)"))
        assertNull(Team4Call.parse("촬영할게요 <maum_6>(action=1)"))
    }
    @Test fun preservesNoCaptureAndConfirmationDecisions() {
        assertEquals(Team4Call(6, 0), Team4Call.parse("<maum_6>(action=0)"))
        assertEquals(Team4Call(6, 2), Team4Call.parse("<maum_6>(action=2)"))
    }
}
