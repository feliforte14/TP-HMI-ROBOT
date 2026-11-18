package ar.edu.uade.rondago

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeNotNull

/** Contrato Retrofit desde el emulador hacia el gateway de CI. */
class GatewayTest {
    @Test fun emulatorReceivesConfirmedCommandsThroughHttp() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("gatewayToken")
        assumeNotNull(token)
        val repo = Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        val api = repo.api("http://10.0.2.2:8080/", token!!)
        val state = api.state()
        assertTrue(state.fresh)
        assertEquals("SIMULATION", state.source)
        val session = api.session(mapOf("clientId" to "android-integration"))
        val id = newId()
        val order = Order(id, "START", "", "", state.version, session.id)
        assertEquals("ACCEPTED", api.command(order).status)
        var result = api.result(id)
        repeat(30) { if (result.status == "ACCEPTED") { delay(200); result = api.result(id) } }
        assertEquals("EXECUTED", result.status)
        assertEquals("EXECUTED", api.command(order).status)
        assertEquals(1, api.state().rounds.size)
        repo.db.close()
    }
}
