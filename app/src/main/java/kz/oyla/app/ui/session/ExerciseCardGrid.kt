package kz.oyla.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaNavy

@Composable
fun ExerciseCardGrid(
    exercise: ExerciseUiModel,
    selectedAnswer: AnswerUiModel?,
    enabled: Boolean,
    dimmed: Boolean = false,
    showCorrectMarker: Boolean = false,
    pendingOptionId: String? = null,
    onOptionClick: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        exercise.options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
                row.forEach { option ->
                    val selected = selectedAnswer?.selectedOptionId == option.id
                    val border = when {
                        selected && selectedAnswer.isCorrect -> BorderStroke(3.dp, androidx.compose.ui.graphics.Color(0xFF38B76A))
                        selected && !selectedAnswer.isCorrect -> BorderStroke(3.dp, androidx.compose.ui.graphics.Color(0xFFE7A83A))
                        else -> BorderStroke(2.dp, OylaBlue)
                    }
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        border = border,
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .alpha(if (dimmed) 0.58f else 1f)
                            .clickable(enabled = enabled) { onOptionClick(option.id) }
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                                Image(
                                    painter = painterResource(exerciseDrawableFor(option.imageAssetKey)),
                                    contentDescription = option.label,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)
                                )
                                Text(option.label, color = OylaNavy, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                            }
                            if (showCorrectMarker && option.id == exercise.correctOptionId) {
                                Text(
                                    "✓  Правильный ответ", color = androidx.compose.ui.graphics.Color(0xFF228B4E),
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                            if (selected && selectedAnswer.isCorrect) Icon(
                                Icons.Outlined.CheckCircle, contentDescription = "Верный ответ",
                                tint = androidx.compose.ui.graphics.Color(0xFF38B76A), modifier = Modifier.align(Alignment.TopEnd)
                            )
                            if (pendingOptionId == option.id) CircularProgressIndicator(
                                color = OylaBlue, modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
