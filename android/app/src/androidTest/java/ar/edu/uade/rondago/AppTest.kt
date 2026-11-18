package ar.edu.uade.rondago

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun localDemoStartsAndPauses() {
        compose.onNodeWithText("SIMULACIÓN LOCAL · SIN ROS/DDS").assertExists()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Iniciar ronda").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Iniciar ronda").performScrollTo()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Iniciar ronda") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Iniciar ronda").performClick()
        compose.onNodeWithTag("dashboard").performScrollToIndex(0)
        compose.waitUntil(15000) { compose.onAllNodesWithText("En ronda").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pausar", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("dashboard").performScrollToIndex(0)
        compose.waitUntil(15000) { compose.onAllNodesWithText("Pausada").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Historial").performClick()
        compose.onNodeWithText("Guardado en este dispositivo · disponible sin conexión").assertExists()
    }
    @Test fun roomSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-persistence.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        val record = Record("TEST", "event", "e1", "{\"source\":\"SIMULATION\"}", 1.0)
        db.records().put(record); db.close()
        db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        assertEquals(record, db.records().find("TEST", "event", "e1"))
        db.close(); context.deleteDatabase(name)
        Unit
    }
}
