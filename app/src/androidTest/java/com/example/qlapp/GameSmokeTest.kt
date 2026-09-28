package com.example.qlapp

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.qlapp.ui.GameActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

class GameSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun exercise(kind: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch<GameActivity>(Intent(context, GameActivity::class.java).putExtra("game", kind)).use { scenario ->
            compose.onNodeWithText("开始冒险 →").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("暂停").assertExists()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "$kind-game.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("暂停").performClick()
            compose.onNodeWithText("休息一下").assertIsDisplayed()
            compose.onNodeWithText("继续游戏 →").performClick()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("休息一下").assertIsDisplayed()
            compose.onNodeWithText("返回游乐场").performClick()
        }
    }

    @Test fun airRendersAndPauses() = exercise("air")
    @Test fun pianoRendersAndPauses() = exercise("piano")
    @Test fun runnerRendersAndPauses() = exercise("runner")
}
