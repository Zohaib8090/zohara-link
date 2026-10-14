package com.example

import com.example.ui.zl.accelerate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMathTest {
    @Test
    fun slowMovesStayPreciseAndFastMovesCarryFurther() {
        val (sx, _) = accelerate(1f, 0f)
        val (fx, _) = accelerate(40f, 0f)
        assertTrue("a slow move is about 1:1", sx in 1.0f..1.4f)
        assertTrue("a fast flick goes much further per pixel", fx / 40f > sx / 1f * 2f)
    }

    @Test
    fun gainIsCappedAndKeepsDirection() {
        val (x, y) = accelerate(-500f, 250f)
        assertEquals(-500f * 4f, x, 0.001f)
        assertEquals(250f * 4f, y, 0.001f)
    }
}
