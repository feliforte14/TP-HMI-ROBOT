package ar.edu.uade.rondago

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

 data class UiState(val state: State? = null, val mode: String = "LOCAL", val url: String = "http://10.0.2.2:8080/", val connected: Boolean = false, val control: Boolean = false, val working: Boolean = false, val cut: Boolean = false, val error: String = "", val command: Command? = null, val pending: Boolean = false, val lastSeen: Double = 0.0)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val history = repo.records.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutable = MutableStateFlow(UiState())
    val ui = mutable.asStateFlow()
    private val mutex = Mutex()
    private var sim = LocalSimulator()
    private var api: Api? = null
    private var clientId = ""
    private var session: Session? = null
    private var foreground = true
    private var ready = false
    private var lastRenew = 0.0
    private fun scope() = if (mutable.value.mode == "LOCAL") "LOCAL" else mutable.value.url
    init {
        viewModelScope.launch {
            val (mode, url, client) = repo.preferences()
            clientId = client
            mutable.update { it.copy(mode = mode, url = url) }
            val local = repo.saved("LOCAL")
            sim = LocalSimulator(local ?: State())
            repo.unresolved("LOCAL").forEach { c -> repo.command("LOCAL", sim.state.commands[c.id] ?: c.copy(status = "REJECTED", reason = "Demo interrumpida antes de confirmar ejecución")) }
            val saved = repo.saved(scope())
            mutable.update { it.copy(state = saved?.copy(fresh = false), error = if (mode == "GATEWAY") "Ingresá el token en Conexión para reconectar" else "") }
            ready = true
            while (isActive) {
                if (foreground) mutex.withLock { poll() }
                delay(1000)
            }
        }
    }
    fun active(value: Boolean) { foreground = value; if (!value) mutable.update { it.copy(connected = false, control = false) } }
    fun cut(value: Boolean) { mutable.update { it.copy(cut = value, connected = if (value) false else it.connected, control = if (value) false else it.control) } }
    private suspend fun poll() {
        val u = mutable.value
        if (u.cut) { mutable.update { it.copy(connected = false, control = false) }; return }
        try {
            val state: State
            var control = u.mode == "LOCAL"
            if (u.mode == "LOCAL") { state = sim.tick() }
            else {
                val a = api ?: return
                state = a.state()
                if (!state.hasTelemetry) { mutable.update { it.copy(connected = false, control = false, error = "Gateway sin telemetría. Iniciar el controlador ROS 2.") }; return }
                require(state.source == "SIMULATION" && state.robotId == "GO2-SIM-01") { "Gateway incompatible: solo se permite simulación" }
                if (session == null || now() - lastRenew > 20) {
                    try { session = a.session(mapOf("clientId" to clientId)); lastRenew = now() }
                    catch (_: HttpException) { session = null }
                }
                control = session != null && session!!.expiresAt > now()
                for (c in repo.unresolved(scope())) {
                    try { val result = a.result(c.id); repo.command(scope(), result); mutable.update { it.copy(command = result) } }
                    catch (_: HttpException) { /* 404 no prueba que una orden se haya ejecutado; conservar UNKNOWN. */ }
                }
            }
            repo.save(scope(), state)
            val pending = repo.unresolved(scope()).isNotEmpty()
            mutable.update { it.copy(state = state, connected = state.fresh, control = control && state.fresh, pending = pending, lastSeen = if (state.fresh) now() else it.lastSeen, error = if (!state.fresh) "Datos vencidos: órdenes bloqueadas" else if (!control) "Solo lectura: otro operador controla o sesión vencida" else "") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(connected = false, control = false, error = "Sin conexión. Historial disponible. ${readable(e)}") }
        }
    }
    fun configure(mode: String, url: String, token: String) {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val normalized = url.trim().trimEnd('/') + "/"
                    val nextApi = if (mode == "GATEWAY") { require(token.length >= 16) { "Token: al menos 16 caracteres" }; repo.api(normalized, token) } else null
                    repo.configure(mode, normalized)
                    api = nextApi; session = null
                    val cached = repo.saved(if (mode == "LOCAL") "LOCAL" else normalized)?.copy(fresh = false)
                    mutable.update { UiState(mode = mode, url = normalized, state = cached) }
                    poll()
                } catch (e: Exception) { mutable.update { it.copy(error = readable(e)) } }
            }
        }
    }
    fun send(action: String, eventId: String = "", note: String = "") {
        if (!ready || mutable.value.working) return
        mutable.update { it.copy(working = true) }
        viewModelScope.launch {
            mutex.withLock {
                val u = mutable.value
                if (!u.connected || !u.control || u.pending || u.cut || u.state == null || now() - u.lastSeen > 5) {
                    mutable.update { it.copy(working = false, error = "Orden bloqueada: revisar enlace, control y pendientes") }
                    return@withLock
                }
                val scope = scope()
                var cmd = Command(action = action, eventId = eventId, roundId = u.state.roundId)
                repo.command(scope, cmd)
                mutable.update { it.copy(command = cmd, error = "") }
                try {
                    if (u.mode == "LOCAL") {
                        cmd = cmd.copy(status = "ACCEPTED"); repo.command(scope, cmd); mutable.update { it.copy(command = cmd) }
                        delay(400)
                        cmd = sim.command(cmd.id, action, eventId, note, u.state.version)
                        repo.save(scope, sim.state)
                    } else {
                        cmd = api!!.command(Order(cmd.id, action, eventId, note, u.state.version, session!!.id))
                    }
                    repo.command(scope, cmd)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    cmd = cmd.copy(status = if (e is HttpException && e.code() in 400..499) "REJECTED" else "UNKNOWN", reason = readable(e))
                    repo.command(scope, cmd)
                }
                mutable.update { it.copy(command = cmd, working = false, pending = cmd.status in listOf("ACCEPTED", "UNKNOWN")) }
                poll()
            }
        }
    }
    private fun readable(e: Exception): String = if (e is HttpException) {
        try { val text = e.response()?.errorBody()?.string(); repo.gson.fromJson(text, com.google.gson.JsonObject::class.java)?.get("error")?.asString ?: "HTTP ${e.code()}" } catch (_: Exception) { "HTTP ${e.code()}" }
    } else e.message ?: "No se pudo completar la operación"
}
