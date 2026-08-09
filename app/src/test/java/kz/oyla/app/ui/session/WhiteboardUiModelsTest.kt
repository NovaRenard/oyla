package kz.oyla.app.ui.session

import kz.oyla.app.data.remote.dto.WhiteboardBrushSize
import kz.oyla.app.data.remote.dto.WhiteboardColor
import kz.oyla.app.data.remote.dto.WhiteboardPointDto
import kz.oyla.app.data.remote.dto.WhiteboardStateSnapshotDto
import kz.oyla.app.data.remote.dto.WhiteboardStrokeDto
import kz.oyla.app.data.remote.dto.WhiteboardTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardUiModelsTest {
    @Test fun `reconnect snapshot keeps server sequence order and child permission`() {
        val state = WhiteboardStateSnapshotDto("exercise", false, 8, 2, listOf(
            stroke("later", 2), stroke("first", 1)
        )).toUi()
        assertEquals(listOf("first", "later"), state.strokes.map { it.id })
        assertTrue(!state.childDrawingEnabled)
        assertEquals(8, state.boardRevision)
    }

    @Test fun `pointer coordinates are normalized and clamped independently of tablet size`() {
        assertEquals(WhiteboardPointDto(.25f, .75f), normalizeWhiteboardPoint(50f, 75f, 200f, 100f))
        assertEquals(WhiteboardPointDto(0f, 1f), normalizeWhiteboardPoint(-5f, 120f, 100f, 100f))
        assertEquals(WhiteboardPointDto(1f, 1f), normalizeWhiteboardPoint(3f, 3f, 0f, 0f))
    }

    private fun stroke(id: String, sequence: Int) = WhiteboardStrokeDto(
        id, "exercise", "CHILD", "device", sequence, WhiteboardTool.PEN, WhiteboardColor.BLUE,
        WhiteboardBrushSize.MEDIUM, listOf(WhiteboardPointDto(.1f, .1f), WhiteboardPointDto(.2f, .2f)), "2026-08-09T00:00:00Z"
    )
}
