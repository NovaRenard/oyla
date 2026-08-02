package kz.oyla.app.ui.session

import androidx.annotation.DrawableRes
import kz.oyla.app.R

@DrawableRes
fun exerciseDrawableFor(assetKey: String): Int = when (assetKey) {
    "exercise_rocket" -> R.drawable.exercise_rocket
    "exercise_cat" -> R.drawable.exercise_cat
    "exercise_house" -> R.drawable.exercise_house
    "exercise_fox" -> R.drawable.exercise_fox
    else -> R.drawable.exercise_placeholder
}
