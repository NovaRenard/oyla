package kz.oyla.app.ui.play

import kz.oyla.app.ui.session.ExerciseUiState

/** Hint-hand gestures for the generated games, or null when [state] is another game. */
fun newGameHintPlan(state: ExerciseUiState): List<HintStep>? {
    val exercise = state.exercise ?: return null
    val options = exercise.options
    val config = exercise.gameConfig
    return when (exercise.activityType) {
        "MATH" -> listOf(HintStep.Sweep(200f, 300f, 1080f, 300f))
        "PARTS" -> listOf(HintStep.Sweep(260f, 275f, 1020f, 275f))
        "MISSING" -> listOf(HintStep.Sweep(330f, 320f, 950f, 320f))
        "PAIRS" -> {
            val layout = pairsLayout((config?.operandA ?: 2).coerceIn(1, 6), (config?.operandB ?: 2).coerceIn(1, 6))
            listOf(
                HintStep.Tap(layout.glassX[0] + layout.glassW / 2f, PairsLayout.GLASS_TOP + PairsLayout.GLASS_H / 2f),
                HintStep.Tap(layout.plateX[0] + layout.plateW / 2f, PairsLayout.PLATE_TOP + PairsLayout.PLATE_H / 2f)
            )
        }
        "TYPE_WORD" -> {
            val first = config?.word?.trim()?.firstOrNull()?.toString() ?: return emptyList()
            val keys = typeWordKeys(state.sessionExerciseId, options)
            val index = keys.indexOfFirst { it.label.equals(first, ignoreCase = true) }.takeIf { it >= 0 } ?: return emptyList()
            val keyboard = typeWordKeyboard(keys.size)
            val c = keyboard.corners[index]
            listOf(HintStep.Tap(c.x + keyboard.side / 2f, c.y + keyboard.side / 2f))
        }
        "PHONETICS" -> {
            val palette = config?.groupLabels.orEmpty().ifEmpty { listOf("V", "C") }
            val letters = options.sortedBy { it.position }
            val index = letters.indexOfFirst { it.groupKey in palette }.takeIf { it >= 0 } ?: return emptyList()
            val layout = phoneticsLayout(letters.size, palette.size)
            val color = palette.indexOf(letters[index].groupKey)
            val half = PhoneticsLayout.PALETTE_SIDE / 2f
            listOf(HintStep.Drag(layout.paletteX[color] + half, PhoneticsLayout.PALETTE_TOP + half,
                layout.cellX[index] + layout.cellW / 2f, PhoneticsLayout.CELL_TOP + layout.cellH / 2f))
        }
        "LABEL_SCENE" -> {
            val chips = shuffledFor(state.sessionExerciseId, options)
            val chip = chips.firstOrNull() ?: return emptyList()
            val objects = options.sortedBy { it.position }
            val rect = LabelScene.cellRect(LabelScene.cellOf(chip, objects.indexOfFirst { it.id == chip.id }.coerceAtLeast(0)))
            val home = LabelScene.chipHome(0, chips.size)
            listOf(HintStep.Drag(home.x + LabelScene.CHIP_W / 2f, home.y + LabelScene.CHIP_H / 2f, rect.center.x, rect.top + LabelScene.PICTURE / 2f))
        }
        "BASKET", "CATEGORY_HOUSE" -> {
            val items = shuffledFor(state.sessionExerciseId, options)
            val needs = config?.requirements.orEmpty()
            val index = items.indexOfFirst { item ->
                if (exercise.activityType == "BASKET") needs.any { it.imageAssetKey == item.imageAssetKey } else item.isTarget == true
            }.takeIf { it >= 0 } ?: return emptyList()
            val side = scatterSide(items.size)
            val (x, y) = scatterSlots(items.size, state.sessionExerciseId, side)[index]
            val target = if (exercise.activityType == "BASKET") basketRect(config?.container).center else HouseRect.center
            listOf(HintStep.Drag(x + side / 2f, y + side / 2f, target.x, target.y))
        }
        "PARKING" -> {
            val cars = shuffledFor(state.sessionExerciseId, options)
            val car = cars.firstOrNull() ?: return emptyList()
            val bays = parkingBays(config?.groupLabels.orEmpty(), options)
            val bay = bays.firstOrNull { it.color == car.groupKey } ?: return emptyList()
            val (bx, bw) = ParkingLot.bayX(bay.index, bays.size)
            val home = ParkingLot.carHome(0, cars.size)
            listOf(HintStep.Drag(home.x + ParkingLot.CAR_W / 2f, home.y + ParkingLot.CAR_H / 2f, bx + bw / 2f, ParkingLot.BAY_TOP + ParkingLot.BAY_H / 2f))
        }
        "ARROWS" -> {
            val n = (config?.gridSize ?: 4).coerceIn(3, 8)
            val start = ArrowsBoard.center((config?.startCell ?: 0).coerceIn(0, n * n - 1), n)
            val button = ArrowsBoard.button(ArrowDir.RIGHT)
            val half = ArrowsBoard.BUTTON / 2f
            listOf(
                HintStep.Tap(button.x + half, button.y + half),
                HintStep.Tap(start.x, start.y),
                HintStep.Tap(ArrowsBoard.PALETTE_LEFT + 179f, ArrowsBoard.GO_TOP + 50f)
            )
        }
        else -> null
    }
}
