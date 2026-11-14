package app.foyer.system

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizingTest {
    @Test fun targetSmallerThanContentUsesEnoughRows() {
        assertEquals(2, initialWidgetCells(target = 1, minPx = 180, cellPx = 120f, insetPx = 8f, max = 20))
    }

    @Test fun insetCountsAgainstAvailableContentSpace() {
        assertEquals(2, initialWidgetCells(target = 1, minPx = 108, cellPx = 120f, insetPx = 8f, max = 20))
    }

    @Test fun largerTargetIsPreserved() {
        assertEquals(3, initialWidgetCells(target = 3, minPx = 80, cellPx = 120f, insetPx = 8f, max = 20))
    }

    @Test fun missingTargetUsesProviderMinimum() {
        assertEquals(2, initialWidgetCells(target = 0, minPx = 180, cellPx = 120f, insetPx = 8f, max = 20))
    }

    @Test fun widthCannotExceedGrid() {
        assertEquals(5, initialWidgetCells(target = 1, minPx = 600, cellPx = 100f, insetPx = 8f, max = 5))
    }
}
