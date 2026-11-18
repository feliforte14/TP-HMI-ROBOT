package ar.edu.uade.rondago
import org.junit.Assert.*
import org.junit.Test
class SimulatorTest {
    @Test fun normalRoundCompletesOnce() {
        val s = LocalSimulator(); s.command("start", "START")
        repeat(25) { s.tick() }
        assertEquals("COMPLETED", s.state.status); assertEquals(4, s.state.verified); assertEquals("Base", s.state.sector)
        s.command("start", "START"); assertEquals(1, s.state.rounds.size)
    }
    @Test fun incidentBlocksResumeUntilResolution() {
        val s = LocalSimulator(); s.command("a", "START"); s.command("b", "DEMO_OBSTACLE")
        assertEquals("REJECTED", s.command("c", "RESUME").status)
        val e = s.state.events.first()
        s.command("d", "RESOLVE", e.id, "Sector revisado")
        assertEquals("EXECUTED", s.command("e", "RESUME").status)
    }
    @Test fun lowBatteryNeedsReturnAndRecharge() {
        val s = LocalSimulator(); s.command("a", "START"); repeat(5) { s.tick() }; s.command("b", "DEMO_LOW_BATTERY")
        assertEquals("PAUSED", s.state.status); assertEquals("REJECTED", s.command("c", "START").status)
        s.command("d", "RETURN"); repeat(5) { s.tick() }; s.command("e", "DEMO_RECHARGE")
        assertEquals("EXECUTED", s.command("f", "START").status)
    }
    @Test fun staleVersionRejected() { val s = LocalSimulator(); s.command("a", "START"); assertEquals("REJECTED", s.command("b", "PAUSE", expectedVersion = 0).status) }
    @Test fun noiseRequiresDurationAndRecovery() {
        var t = 1000.0; val s = LocalSimulator(clock = { t }); s.command("a", "DEMO_NOISE")
        repeat(3) { t++; s.tick() }; assertTrue(s.state.events.isEmpty())
        t++; s.tick(); assertEquals("PENDING", s.state.events.first().status)
        s.command("b", "DEMO_QUIET"); repeat(6) { t++; s.tick() }; assertEquals("RESOLVED", s.state.events.first().status)
    }
    @Test fun accessAlertsDeduplicated() { val s = LocalSimulator(); s.command("a", "DEMO_ACCESS"); s.command("b", "DEMO_ACCESS"); assertEquals(1, s.state.events.size) }
}
