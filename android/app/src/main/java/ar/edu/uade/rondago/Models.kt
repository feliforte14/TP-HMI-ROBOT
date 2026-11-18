package ar.edu.uade.rondago

import java.util.UUID

fun now() = System.currentTimeMillis() / 1000.0
fun newId() = UUID.randomUUID().toString()
data class Alert(val id: String = newId(), val roundId: String = "", val type: String = "", val message: String = "", val sector: String = "", val status: String = "PENDING", val observedAt: Double = now(), val source: String = "SIMULATION", val note: String = "")
data class Round(val id: String = "", val startedAt: Double = now(), val endedAt: Double? = null, val status: String = "PATROLLING", val verified: Int = 0)
data class Command(val id: String = newId(), val action: String = "", val eventId: String = "", val roundId: String = "", val status: String = "REQUESTED", val reason: String = "", val updatedAt: Double = now(), val source: String = "SIMULATION")
data class State(
    val source: String = "SIMULATION", val robotId: String = "GO2-SIM-01", val roundId: String = "", val version: Int = 0,
    val status: String = "IDLE", val sector: String = "Base", val verified: Int = 0, val total: Int = 4, val battery: Double = 95.0,
    val accessOpen: Boolean = false, val noise: Double = 45.0, val events: List<Alert> = emptyList(),
    val commands: Map<String, Command> = emptyMap(), val rounds: List<Round> = emptyList(),
    val capabilities: List<String> = listOf("navigation", "speaker"), val observedAt: Double = now(),
    val hasTelemetry: Boolean = true, val fresh: Boolean = true, val transport: String = "LOCAL", val receivedAt: Double = now()
)
data class Order(val id: String, val action: String, val eventId: String, val note: String, val expectedVersion: Int, val sessionId: String)
data class Session(val id: String, val clientId: String, val expiresAt: Double)
data class EventPage(val items: List<Alert>, val cursor: Long)
val labels = mapOf("IDLE" to "En base", "PATROLLING" to "En ronda", "PAUSED" to "Pausada", "RETURNING" to "Volviendo a base", "COMPLETED" to "Finalizada", "CANCELLED" to "Cancelada", "RETURNED" to "Regresó a base", "PENDING" to "Pendiente", "ACKNOWLEDGED" to "Atendida", "RESOLVED" to "Resuelta", "REQUESTED" to "Solicitada", "ACCEPTED" to "Aceptada · aún sin ejecutar", "EXECUTED" to "Ejecutada", "REJECTED" to "Rechazada", "UNKNOWN" to "Sin respuesta · conciliando", "START" to "Iniciar ronda", "PAUSE" to "Pausar", "RESUME" to "Reanudar", "RETURN" to "Volver a base", "CANCEL" to "Cancelar ronda", "STOP" to "Solicitar detención", "ACK" to "Reconocer", "RESOLVE" to "Resolver", "ANNOUNCE" to "Emitir aviso", "HOLD" to "Mantener posición", "PRESENCE" to "Presencia", "OBSTACLE" to "Obstáculo", "ACCESS" to "Acceso", "NOISE" to "Ruido", "BATTERY" to "Batería")
fun label(key: String) = labels[key] ?: key
