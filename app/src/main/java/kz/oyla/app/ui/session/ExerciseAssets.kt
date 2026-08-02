package kz.oyla.app.ui.session

import androidx.annotation.DrawableRes
import kz.oyla.app.R

@DrawableRes
fun exerciseDrawableFor(assetKey: String): Int = when (assetKey) {
    "exercise_rocket" -> R.drawable.exercise_rocket
    "exercise_cat" -> R.drawable.exercise_cat
    "exercise_house" -> R.drawable.exercise_house
    "exercise_fox" -> R.drawable.exercise_fox
    // Temporary local placeholders until the corresponding illustration files arrive.
    "exercise_fish" -> R.drawable.exercise_fish
    "exercise_apple" -> R.drawable.exercise_apple
    "exercise_duck" -> R.drawable.exercise_duck
    "exercise_elephant" -> R.drawable.exercise_elephant
    "exercise_lamp" -> R.drawable.exercise_lamp
    "exercise_dog" -> R.drawable.exercise_dog
    "exercise_ball" -> R.drawable.exercise_ball
    else -> R.drawable.exercise_placeholder
}
