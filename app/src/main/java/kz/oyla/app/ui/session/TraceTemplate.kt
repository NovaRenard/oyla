package kz.oyla.app.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Templates the server can name in `WhiteboardExerciseConfigDto.traceTemplate`. */
val TRACE_TEMPLATES = listOf("SUN", "HOUSE", "FISH", "STAR", "FLOWER", "HEART")

/**
 * Dotted outline the child traces over. Drawn in its own layer under the strokes, so the
 * eraser never removes it. Coordinates are relative to the board, so the child's board and the
 * specialist's copy (same 1150×800 proportions) show it in the same place.
 */
@Composable
fun TraceTemplateLayer(template: String?, modifier: Modifier) {
    val key = template?.uppercase() ?: return
    if (key !in TRACE_TEMPLATES) return
    Canvas(modifier) {
        // A square box in the free middle of the board (the task bubble is on top, paints below).
        val side = size.height * 0.62f
        val box = Rect(Offset((size.width - side) / 2f, size.height * 0.2f), androidx.compose.ui.geometry.Size(side, side))
        fun p(x: Float, y: Float) = Offset(box.left + x * side, box.top + y * side)
        val path = Path()
        fun circle(cx: Float, cy: Float, r: Float) = path.addOval(Rect(p(cx - r, cy - r), p(cx + r, cy + r)))
        fun poly(vararg pts: Float, closed: Boolean = true) {
            path.moveTo(p(pts[0], pts[1]).x, p(pts[0], pts[1]).y)
            var i = 2
            while (i < pts.size) { val o = p(pts[i], pts[i + 1]); path.lineTo(o.x, o.y); i += 2 }
            if (closed) path.close()
        }
        when (key) {
            "SUN" -> {
                circle(.5f, .5f, .24f)
                repeat(8) { i ->
                    val a = i * PI.toFloat() / 4f
                    poly(.5f + cos(a) * .32f, .5f + sin(a) * .32f, .5f + cos(a) * .47f, .5f + sin(a) * .47f, closed = false)
                }
            }
            "HOUSE" -> {
                poly(.2f, .46f, .8f, .46f, .8f, .95f, .2f, .95f)
                // Roof sits on the wall's top line, so no second parallel row of dots.
                poly(.12f, .46f, .5f, .08f, .88f, .46f, closed = false)
                poly(.42f, .95f, .42f, .68f, .58f, .68f, .58f, .95f, closed = false)
                poly(.26f, .56f, .37f, .56f, .37f, .67f, .26f, .67f)
            }
            "FISH" -> {
                path.addOval(Rect(p(.08f, .28f), p(.72f, .72f)))
                poly(.7f, .5f, .95f, .28f, .95f, .72f)
                circle(.24f, .44f, .035f)
            }
            "STAR" -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) .48f else .2f
                    val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
                    pts[i * 2] = .5f + cos(a) * r; pts[i * 2 + 1] = .54f + sin(a) * r
                }
                poly(*pts)
            }
            "FLOWER" -> {
                circle(.5f, .32f, .09f)
                repeat(6) { i ->
                    val a = i * PI.toFloat() / 3f
                    circle(.5f + cos(a) * .18f, .32f + sin(a) * .18f, .09f)
                }
                poly(.5f, .59f, .5f, .98f, closed = false)
                path.moveTo(p(.5f, .82f).x, p(.5f, .82f).y)
                path.quadraticTo(p(.66f, .66f).x, p(.66f, .66f).y, p(.76f, .7f).x, p(.76f, .7f).y)
                path.quadraticTo(p(.66f, .84f).x, p(.66f, .84f).y, p(.5f, .82f).x, p(.5f, .82f).y)
            }
            "HEART" -> {
                path.moveTo(p(.5f, .92f).x, p(.5f, .92f).y)
                path.cubicTo(p(.1f, .62f).x, p(.1f, .62f).y, p(.02f, .3f).x, p(.02f, .3f).y, p(.25f, .18f).x, p(.25f, .18f).y)
                path.cubicTo(p(.38f, .11f).x, p(.38f, .11f).y, p(.48f, .2f).x, p(.48f, .2f).y, p(.5f, .3f).x, p(.5f, .3f).y)
                path.cubicTo(p(.52f, .2f).x, p(.52f, .2f).y, p(.62f, .11f).x, p(.62f, .11f).y, p(.75f, .18f).x, p(.75f, .18f).y)
                path.cubicTo(p(.98f, .3f).x, p(.98f, .3f).y, p(.9f, .62f).x, p(.9f, .62f).y, p(.5f, .92f).x, p(.5f, .92f).y)
            }
        }
        val w = minOf(size.width, size.height) * 0.014f
        drawPath(
            path, Color(0xFFB9C3D8),
            style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.1f, w * 2.2f)))
        )
    }
}
