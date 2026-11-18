package ar.edu.uade.rondago

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF00796B), secondary = Color(0xFF435F70), background = Color(0xFFF3F6F5))) {
                val vm: MainViewModel = viewModel()
                RondaGo(vm)
            }
        }
    }
}
fun date(t: Double) = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date((t * 1000).toLong()))
data class Confirmation(val action: String, val eventId: String = "")
@Composable
fun RondaGo(vm: MainViewModel) {
    val u by vm.ui.collectAsStateWithLifecycle()
    val records by vm.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.active(true) else if (e == Lifecycle.Event.ON_PAUSE) vm.active(false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val canSend = u.connected && u.control && !u.working && !u.pending && !u.cut
    val send: (String, String) -> Unit = { action, event ->
        if (action in listOf("CANCEL", "RETURN", "STOP", "ANNOUNCE", "HOLD", "RESOLVE")) { reason = ""; confirmation = Confirmation(action, event) }
        else vm.send(action, event)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Ronda", "Alertas", "Historial", "Conexión").forEachIndexed { index, title ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "!", "≡", "⚙")[index]) }, label = { Text(title) })
            }
        }
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("RondaGo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (u.mode == "LOCAL") "SIMULACIÓN LOCAL · SIN ROS/DDS" else "SIMULACIÓN · ${u.state?.transport ?: "Gateway"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (u.connected) "● Datos vigentes · ${if (u.control) "Con control" else "Solo lectura"}" else "○ Sin enlace · órdenes bloqueadas", style = MaterialTheme.typography.bodySmall)
            }
            if (u.error.isNotEmpty()) Text(u.error, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            u.command?.let { c ->
                Text("${label(c.action)}: ${label(c.status)}${if (c.reason.isNotEmpty()) " · ${c.reason}" else ""}", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (u.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> Dashboard(u, canSend, send, vm::cut)
                1 -> Alerts(u, canSend, send)
                2 -> History(records, vm.repo)
                else -> Connection(u, vm::configure)
            }
        }
    }
    confirmation?.let { c ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(label(c.action)) },
            text = { Column {
                Text(if (c.action == "STOP") "Solicitar detención de software simulada. No es una parada física de emergencia." else "Se enviará una solicitud al controlador simulado. Aceptar no significa ejecutar.")
                if (c.action == "RESOLVE") OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Motivo de resolución") })
            } },
            confirmButton = { TextButton(enabled = canSend && (c.action != "RESOLVE" || reason.trim().length >= 3), onClick = { vm.send(c.action, c.eventId, reason); confirmation = null }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Volver") } })
    }
}
@Composable
fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() } }
}
@Composable
fun Dashboard(u: UiState, enabled: Boolean, send: (String, String) -> Unit, cut: (Boolean) -> Unit) {
    val s = u.state
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel("Supervisión de ronda") {
                if (s == null) Text("Sin datos. Usá la demo local o conectá el gateway.") else {
                    Text(label(s.status), style = MaterialTheme.typography.headlineSmall)
                    Text("${s.sector} · ${s.verified}/${s.total} controles verificados")
                    LinearProgressIndicator(progress = { s.verified.toFloat() / s.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("Batería: ${s.battery.toInt()} %${if (s.battery < 20) " · BAJA" else ""}")
                    Text("Actualización: ${date(s.observedAt)}${if (!u.connected) " · dato anterior" else ""}", style = MaterialTheme.typography.bodySmall)
                    Text("Ruta: Base → Recepción → Pasillo A → Pasillo B → Portón → Base", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Panel("Órdenes") {
                val active = s?.status in listOf("PATROLLING", "PAUSED", "RETURNING")
                Action("START", enabled && s != null && !active && s.battery >= 20 && s.sector == "Base", send)
                Action("PAUSE", enabled && s?.status == "PATROLLING", send)
                Action("RESUME", enabled && s?.status == "PAUSED", send)
                Action("RETURN", enabled && s != null && s.sector != "Base" && "navigation" in s.capabilities, send)
                Action("CANCEL", enabled && active, send)
                Action("STOP", enabled && active, send)
                Text("Detención de software simulada; no reemplaza una parada física.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Panel("Variables del depósito") { Text("Accesos abiertos: ${s?.let { if (it.accessOpen) "1" else "0" } ?: "Sin datos"}"); Text("Ruido sintético: ${s?.noise?.toInt()?.toString() ?: "Sin datos"} dB(A)"); Text("Umbrales de demo: >70 durante 3 s; recuperación <65 durante 5 s.", style = MaterialTheme.typography.bodySmall) } }
        item {
            Panel("Escenarios de prueba") {
                Text("Datos ficticios. No hay sensores ni movimiento real.", style = MaterialTheme.typography.bodySmall)
                listOf("DEMO_PRESENCE" to "Inyectar presencia", "DEMO_OBSTACLE" to "Inyectar obstáculo", "DEMO_ACCESS" to "Abrir portón", "DEMO_CLOSE_ACCESS" to "Cerrar portón", "DEMO_NOISE" to "Elevar ruido", "DEMO_QUIET" to "Reducir ruido", "DEMO_LOW_BATTERY" to "Bajar batería a 15 %", "DEMO_RECHARGE" to "Recargar en base").forEach { (a, text) -> OutlinedButton(onClick = { send(a, "") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) } }
                Row { Switch(checked = u.cut, onCheckedChange = cut); Text("Simular corte de enlace en esta app", Modifier.padding(12.dp)) }
            }
        }
    }
}
@Composable
fun Action(action: String, enabled: Boolean, send: (String, String) -> Unit, event: String = "") { Button(onClick = { send(action, event) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label(action)) } }
@Composable
fun Alerts(u: UiState, enabled: Boolean, send: (String, String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val events = u.state?.events.orEmpty().sortedByDescending { it.observedAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (events.isEmpty()) item { Text("Sin alertas registradas. Podés inyectar un escenario desde Ronda.") }
        items(events, key = { it.id }) { e ->
            Card(onClick = { selected = if (selected == e.id) null else e.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${label(e.type)} · ${label(e.status)}", fontWeight = FontWeight.Bold)
                    Text("${e.sector} · ${date(e.observedAt)}")
                    Text(e.message)
                    if (selected == e.id) {
                        Text("SIMULACIÓN · ${e.id}", style = MaterialTheme.typography.bodySmall)
                        if (e.note.isNotEmpty()) Text("Resolución: ${e.note}")
                        if (e.status != "RESOLVED") {
                            Action("ACK", enabled && e.status == "PENDING", send, e.id)
                            if (e.type == "PRESENCE") {
                                Action("ANNOUNCE", enabled && u.state?.capabilities?.contains("speaker") == true, send, e.id)
                                Action("HOLD", enabled && u.state?.status in listOf("PATROLLING", "PAUSED", "RETURNING"), send, e.id)
                                Action("RETURN", enabled && u.state?.sector != "Base", send, e.id)
                            }
                            Action("RESOLVE", enabled, send, e.id)
                        }
                    } else Text("Tocar para ver detalle y acciones", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
@Composable
fun History(records: List<Record>, repo: Repository) {
    var filter by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("all") }
    val rows = records.filter { it.kind != "state" && (kind == "all" || it.kind == kind) && (filter.isBlank() || (it.json + date(it.at) + it.scope).contains(filter, true)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Buscar tipo, estado o fecha dd/MM") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "Todo", "event" to "Alertas", "command" to "Órdenes", "round" to "Rondas").forEach { (k, text) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(text) }) }
        }
        Text("Guardado en este dispositivo · disponible sin conexión", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (rows.isEmpty()) item { Text("No hay registros para este filtro.") }
            items(rows, key = { "${it.scope}:${it.kind}:${it.id}" }) { r ->
                Panel("${date(r.at)} · ${if (r.scope == "LOCAL") "Demo local" else "Gateway"}") {
                    when (r.kind) {
                        "event" -> { val e = repo.gson.fromJson(r.json, Alert::class.java); Text("${label(e.type)} · ${label(e.status)}"); Text(e.message); if (e.note.isNotEmpty()) Text(e.note) }
                        "command" -> { val c = repo.gson.fromJson(r.json, Command::class.java); Text("${label(c.action)} · ${label(c.status)}"); if (c.reason.isNotEmpty()) Text(c.reason); Text("ID: ${c.id}", style = MaterialTheme.typography.bodySmall) }
                        "round" -> { val round = repo.gson.fromJson(r.json, Round::class.java); Text("Ronda · ${label(round.status)} · ${round.verified}/4"); Text(round.id, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
@Composable
fun Connection(u: UiState, configure: (String, String, String) -> Unit) {
    var url by rememberSaveable(u.url) { mutableStateOf(u.url) }
    var token by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Panel("Demo sin servidor") {
            Text("La app simula rondas y guarda historial en Room. Funciona sin Internet. Este modo no utiliza ROS 2 ni DDS.")
            Button(onClick = { configure("LOCAL", url, "") }, enabled = !u.working) { Text("Usar demo local") }
        } }
        item { Panel("Gateway ROS 2 / DDS") {
            Text("Conectá la app al gateway de tu PC. El gateway intercambia datos con el controlador simulado mediante DDS.")
            OutlinedTextField(url, { url = it }, label = { Text("URL del gateway") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Token de laboratorio") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { configure("GATEWAY", url, token) }, enabled = !u.working && token.length >= 16) { Text("Conectar gateway") }
            Text("El token queda solo en memoria. Reingresalo al abrir de nuevo. HTTP está habilitado para una red local de laboratorio; no publicar este servidor en Internet.", style = MaterialTheme.typography.bodySmall)
            Text("Emulador: 10.0.2.2 llega al host Windows. WSL y un teléfono físico pueden requerir configurar red y firewall; ver README.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel("Alcance") { Text("Prototipo académico. Solo supervisión en primer plano; al salir de la app no se garantizan alertas. No hay integración con Go2 físico ni parada de emergencia certificada.") } }
    }
}
