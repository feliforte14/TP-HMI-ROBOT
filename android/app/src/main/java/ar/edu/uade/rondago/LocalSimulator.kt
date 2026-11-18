package ar.edu.uade.rondago

/** Simulador dentro del APK. No utiliza ROS ni DDS; modo separado y rotulado. */
class LocalSimulator(initial: State = State(), private val clock: () -> Double = ::now) {
    var state = initial.copy(fresh = true, transport = "LOCAL", status = if (initial.status in listOf("PATROLLING", "RETURNING")) "PAUSED" else initial.status)
        private set
    private var step = 0
    private var high: Double? = null
    private var low: Double? = null
    private var speakerUntil = 0.0
    private fun active() = state.status in listOf("PATROLLING", "PAUSED", "RETURNING")
    private fun unresolved(type: String) = state.events.any { it.type == type && it.status != "RESOLVED" && it.roundId == state.roundId }
    private fun alert(type: String, message: String) {
        if (!unresolved(type)) state = state.copy(events = state.events + Alert(roundId = state.roundId, type = type, message = message, sector = state.sector, observedAt = clock()))
    }
    private fun finish(status: String) { state = state.copy(status = status, rounds = state.rounds.map { if (it.id == state.roundId) it.copy(status = status, endedAt = clock(), verified = state.verified) else it }) }
    fun command(id: String, action: String, eventId: String = "", note: String = "", expectedVersion: Int = state.version): Command {
        state.commands[id]?.let { return it }
        var reason = ""
        try {
            require(expectedVersion == state.version) { "Estado cambió: actualizar" }
            when (action) {
                "START" -> {
                    require(!active() && state.battery >= 20 && state.sector == "Base") { "Inicio requiere base, batería suficiente y ninguna ronda activa" }
                    require("navigation" in state.capabilities) { "Navegación no disponible" }
                    val round = Round(id = newId(), startedAt = clock())
                    state = state.copy(roundId = round.id, status = "PATROLLING", verified = 0, rounds = state.rounds + round); step = 0
                }
                "PAUSE", "HOLD", "STOP" -> { require(active()) { "No hay ronda activa" }; state = state.copy(status = "PAUSED") }
                "RESUME" -> {
                    require(state.status == "PAUSED" && !unresolved("OBSTACLE") && !unresolved("PRESENCE") && state.battery >= 20) { "Resolver incidentes y revisar batería antes de reanudar" }
                    state = state.copy(status = "PATROLLING")
                }
                "CANCEL" -> { require(active()) { "No hay ronda activa" }; finish("CANCELLED") }
                "RETURN" -> { require(state.sector != "Base" && "navigation" in state.capabilities) { "Regreso no disponible" }; state = state.copy(status = "RETURNING"); step = 0 }
                "ACK", "ANNOUNCE", "RESOLVE" -> {
                    val e = state.events.find { it.id == eventId }
                    require(e != null && e.status != "RESOLVED") { "Alerta inexistente o resuelta" }
                    if (action == "ANNOUNCE") {
                        require(e.type == "PRESENCE" && "speaker" in state.capabilities) { "Aviso no disponible" }; speakerUntil = clock() + 3
                    }
                    if (action == "RESOLVE") {
                        require(note.trim().length >= 3) { "Ingresar motivo de resolución" }
                        require(!(e.type == "ACCESS" && state.accessOpen)) { "Cerrar acceso primero" }
                        require(!(e.type == "NOISE" && state.noise >= 65)) { "Reducir ruido primero" }
                        require(!(e.type == "BATTERY" && state.battery < 20)) { "Recargar primero" }
                    }
                    state = state.copy(events = state.events.map { if (it.id == eventId) it.copy(status = if (action == "RESOLVE") "RESOLVED" else "ACKNOWLEDGED", note = note) else it })
                }
                "DEMO_PRESENCE", "DEMO_OBSTACLE" -> { alert(if (action == "DEMO_PRESENCE") "PRESENCE" else "OBSTACLE", "Incidente sintético: revisar sector"); if (active()) state = state.copy(status = "PAUSED") }
                "DEMO_ACCESS" -> { state = state.copy(accessOpen = true); alert("ACCESS", "Portón abierto (simulado)") }
                "DEMO_CLOSE_ACCESS" -> state = state.copy(accessOpen = false)
                "DEMO_NOISE" -> state = state.copy(noise = 78.0)
                "DEMO_QUIET" -> state = state.copy(noise = 45.0)
                "DEMO_LOW_BATTERY" -> { state = state.copy(battery = 15.0); alert("BATTERY", "Batería baja: solicitar regreso"); if (state.status == "PATROLLING") state = state.copy(status = "PAUSED") }
                "DEMO_RECHARGE" -> { require(state.sector == "Base" && !active()) { "Recargar solo en base sin ronda activa" }; state = state.copy(battery = 95.0) }
                else -> error("Acción no permitida")
            }
            if (eventId.isNotEmpty() && action in listOf("HOLD", "RETURN")) state = state.copy(events = state.events.map { if (it.id == eventId) it.copy(status = "ACKNOWLEDGED") else it })
            state = state.copy(version = state.version + 1)
        } catch (e: IllegalArgumentException) { reason = e.message ?: "Rechazada" }
          catch (e: IllegalStateException) { reason = e.message ?: "Rechazada" }
        val result = Command(id, action, eventId, state.roundId, if (reason.isEmpty()) "EXECUTED" else "REJECTED", reason, clock())
        state = state.copy(commands = state.commands + (id to result), observedAt = clock(), rounds = state.rounds.map { if (it.id == state.roundId) it.copy(status = state.status, verified = state.verified) else it })
        return result
    }
    fun tick(): State {
        val before = state
        val t = clock()
        if (t >= speakerUntil && state.noise > 70) {
            low = null; if (high == null) high = t
            if (t - high!! >= 3) alert("NOISE", "Ruido sintético >70 dB(A) durante 3 s")
        } else if (state.noise < 65) {
            high = null; if (low == null) low = t
            if (t - low!! >= 5) state = state.copy(events = state.events.map { if (it.type == "NOISE" && it.status != "RESOLVED") it.copy(status = "RESOLVED", note = "Ruido bajo 65 durante 5 s") else it })
        } else { high = null; low = null }
        if (state.status in listOf("PATROLLING", "RETURNING")) {
            step++
            if (step >= 5) {
                step = 0
                if (state.status == "RETURNING" || state.verified == 4) {
                    val status = if (state.status == "RETURNING") "RETURNED" else "COMPLETED"
                    state = state.copy(sector = "Base"); finish(status)
                } else {
                    val n = state.verified + 1
                    state = state.copy(verified = n, sector = listOf("Base", "Recepción", "Pasillo A", "Pasillo B", "Portón de carga")[n], battery = (state.battery - 1).coerceAtLeast(0.0))
                }
            }
        }
        if (state.battery < 20 && state.status == "PATROLLING") { alert("BATTERY", "Batería baja: solicitar regreso"); state = state.copy(status = "PAUSED") }
        if (state != before) state = state.copy(version = state.version + 1)
        state = state.copy(observedAt = t, receivedAt = t, fresh = true, rounds = state.rounds.map { if (it.id == state.roundId) it.copy(status = state.status, verified = state.verified) else it })
        return state
    }
}
