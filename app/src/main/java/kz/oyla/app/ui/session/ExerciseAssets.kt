package kz.oyla.app.ui.session

import androidx.annotation.DrawableRes
import kz.oyla.app.R

@DrawableRes
fun exerciseDrawableFor(assetKey: String): Int = when (assetKey) {
    "exercise_rocket" -> R.drawable.exercise_rocket
    "exercise_cat" -> R.drawable.exercise_cat
    "exercise_house" -> R.drawable.exercise_house
    "exercise_fox" -> R.drawable.exercise_fox
    "exercise_ball" -> R.drawable.exercise_ball
    "exercise_duck" -> R.drawable.exercise_duck
    "exercise_apple" -> R.drawable.exercise_apple
    "exercise_dog" -> R.drawable.exercise_dog
    "exercise_lamp" -> R.drawable.exercise_lamp
    "exercise_elephant" -> R.drawable.exercise_elephant
    "exercise_fish" -> R.drawable.exercise_fish
    else -> R.drawable.exercise_placeholder
}
