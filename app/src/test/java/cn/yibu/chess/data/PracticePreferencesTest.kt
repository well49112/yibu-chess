package cn.yibu.chess.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PracticePreferencesTest {
    @Test fun completedPracticeSurvivesReopeningAndDeletedGamesLoseOnlyTheirOwnRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PracticePreferences(context)
        val first = PracticeProgress("1:5", "h5f3", completed = 3, independentCorrect = 2, streak = 1, nextDueAt = 1000, lastReviewedAt = 300)
        val second = first.copy(key = "2:6")
        prefs.save(mapOf(first.key to first, second.key to second))
        val restored = PracticePreferences(context)
        assertEquals(first, restored.read().getValue(first.key))
        assertEquals(mapOf(first.key to first), restored.retainGames(setOf(1L)))
        assertEquals(mapOf(first.key to first), PracticePreferences(context).read())
        assertTrue(restored.retainGames(emptySet()).isEmpty())
    }
}
