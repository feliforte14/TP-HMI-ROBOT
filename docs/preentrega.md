# Preentrega TP HMI/HRI – Patrullaje nocturno en depósito (Go2)

8 de noviembre de 2026

## 1. Problema

Las rondas nocturnas de seguridad en un depósito logístico son caras y dejan zonas sin cobertura. Hoy se resuelven con un guardia que recorre el depósito a pie y con cámaras fijas que otra persona mira desde un puesto.

- **Costo:** una ronda a pie exige personal durante toda la noche.
- **Puntos ciegos:** las cámaras fijas no ven entre las estanterías.
- **Detección tardía:** un portón abierto o una persona en una zona restringida pueden pasar desapercibidos hasta la ronda siguiente.

El servicio que proponemos es una ronda robotizada con un Unitree Go2, supervisada por un operador desde una app Android.

## 2. Entorno

El robot opera de noche en un depósito de una sola planta, con poca luz y sin personal en la mayor parte del recorrido.

- **Zonas:** muelle de carga con portones, pasillos entre estanterías, zona restringida y base de carga.
- **Condiciones:** piso liso, maquinaria apagada o en espera, y obstáculos temporales como pallets mal ubicados.
- **Conectividad:** Wi-Fi irregular entre estanterías metálicas, por lo que la app debe tolerar datos viejos y desconexiones.

## 3. Actores

Hay cuatro actores, y el supervisor es el único que opera la app.

| Actor | Rol | Necesidad | Implicancia para la HMI |
| --- | --- | --- | --- |
| Supervisor de seguridad | Opera la app | Ver el estado, iniciar y cancelar rondas, resolver incidentes | Estado y acción inmediata visibles sin explorar menús |
| Personal nocturno ocasional | Comparte el espacio con el robot | Entender qué hace el robot y que no es un peligro | Intención del movimiento legible: aviso sonoro y luminoso |
| Persona no autorizada | Puede ingresar en una zona restringida | Es el evento a detectar | Detección sin grabar su rostro |
| Responsable del depósito | Consulta el historial | Evidencia de cada ronda sin datos personales | Registro de eventos, no de imágenes |

## 4. Misión

El robot hace una ronda por una lista de puntos de control y en cada uno verifica el estado del entorno: portones, ruido y presencia de personas.

1. El operador inicia la ronda desde la app y el robot sale de la base.
2. En cada punto de control toma la lectura y lo marca como verificado.
3. Si detecta una anomalía, emite una alerta; si es un incidente, pausa la ronda y espera una decisión del operador.
4. Al terminar, o si el operador lo pide, vuelve a la base de carga.

El robot detecta y avisa; no abre portones ni interviene físicamente.

## 5. Elección del robot

Elegimos el Unitree Go2 porque la tarea es recorrer y observar, no manipular objetos, y un cuadrúpedo cubre pasillos angostos de forma estable.

| Alternativa | A favor | En contra |
| --- | --- | --- |
| Sin robot (guardia y cámaras fijas) | Sin desarrollo ni equipamiento nuevo | Costo de personal nocturno y puntos ciegos entre estanterías |
| Unitree Go2 (cuadrúpedo) | Estable, de bajo perfil, recorre pasillos y terreno irregular | No tiene brazos: no abre ni cierra portones |
| Unitree G1 (humanoide) | Puede manipular objetos | Más complejo y costoso para una tarea que no requiere manos |

**Capacidades a confirmar en la ficha oficial:** LiDAR, cámara, IMU, parlante, autonomía de batería de aproximadamente 1 a 2 horas y marcha sobre terreno irregular.

**Lo que requeriría desarrollo adicional:** navegación autónoma con mapa del depósito, detección de personas y detección de portones abiertos. En el TP estas capacidades se simulan.

## 6. Fuentes técnicas

Las fuentes están listadas por nombre; falta abrirlas para citar enlaces y confirmar las cifras del Go2.

- Documentación oficial de Unitree: Go2, `unitree_sdk2` y `unitree_ros2`.
- Documentación de ROS2 y DDS, usada solo como referencia de diseño: telemetría como tópicos, comandos como servicios y misiones como acciones.
- Teoría de la cátedra: *Interfaces HMI para robots de servicio* y *Robótica de Servicio*.
- ISO 9241-210, diseño centrado en las personas, citada en la teoría.
- Documentación de Android: Jetpack Compose, ViewModel, StateFlow y Room.

## 7. Esquema del recorrido

```
  Base de carga ──▶ P1 Muelle de carga ──▶ P2 Pasillo A
        ▲                                       │
        │                                       ▼
  P5 Zona restringida ◀── P4 Pasillo C ◀── P3 Pasillo B
  (pausa si detecta
   a una persona)
```

La ronda sale de la base, pasa por los cinco puntos de control en orden y vuelve a la base. En la zona restringida, la detección de una persona pausa la ronda hasta que el operador decide.

## 8. Telemetría

La app muestra las ocho variables que pide la consigna más tres propias del servicio. Todo dato lleva su hora de última actualización y se marca como viejo si la conexión se pierde.

| Variable | Qué muestra | Origen |
| --- | --- | --- |
| Conectividad | Conectado, degradado o desconectado | Consigna |
| Batería | Porcentaje y advertencia de nivel bajo | Consigna |
| Estado operativo | En base, patrullando, pausado o incidente | Consigna |
| Misión y progreso | Ronda en curso y puntos completados | Consigna |
| Ubicación | Punto de control actual o zona | Consigna |
| Movimiento | Quieto, caminando o girando | Consigna |
| Alertas | Lista de alertas activas y reconocidas | Consigna |
| Última actualización | Hora de la última lectura recibida | Consigna |
| Puntos de control verificados | Cantidad verificada sobre el total de la ronda | Propia |
| Puertas o portones abiertos detectados | Cantidad y ubicación | Propia |
| Nivel de ruido ambiente | Nivel actual y aviso si supera un umbral | Propia |

## 9. Comandos

La app ofrece siete órdenes discretas, sin joystick. Cada una tiene una condición de habilitación, una respuesta simulada y un estado visible: pendiente, confirmada, rechazada o sin respuesta.

| Comando | Se habilita cuando | Confirmación previa |
| --- | --- | --- |
| Iniciar misión | El robot está en base, conectado y con batería suficiente | No |
| Pausar misión | Hay una misión en curso | No |
| Reanudar misión | La misión está pausada y no hay incidente sin resolver | No |
| Cancelar misión | Hay una misión en curso o pausada | Sí, por su impacto |
| Solicitar regreso a punto seguro | El robot está fuera de la base | No |
| Solicitar detención operativa | El robot está en movimiento | Sí, por su impacto |
| Reconocer alertas | Hay una alerta activa | No |

La detención es un comando de software simulado y no reemplaza una parada física de emergencia. Una orden enviada tampoco equivale a una orden ejecutada: la app muestra su estado hasta que el robot responde.

## 10. Riesgos

Identificamos cinco riesgos operativos y de interacción, cada uno con una medida de interfaz, de lógica o de procedimiento.

| # | Riesgo | Medida |
| --- | --- | --- |
| 1 | Persona cerca del robot en movimiento | Aviso sonoro y luminoso antes de moverse; comando de mantener distancia; la parada física de emergencia sigue siendo el mecanismo de seguridad real |
| 2 | Orden accidental, por ejemplo cancelar la ronda | Confirmación solo en acciones de alto impacto y etiquetas de acción explícitas |
| 3 | Pérdida de conexión | Datos viejos marcados como tales, órdenes sin confirmar visibles y recuperación guiada al reconectar |
| 4 | Privacidad: grabar a personas | No guardar imágenes ni rostros; registrar solo el evento (hora, zona y decisión del operador) |
| 5 | Batería baja en plena ronda | Advertencia anticipada y acción disponible de volver a la base |

## 11. Escenario propio: persona en zona restringida

El robot detecta a una persona en una zona restringida, pausa la ronda y espera una decisión del operador. Este caso suma interacción con personas (HRI) y privacidad.

| Decisión del operador | Qué hace el robot |
| --- | --- |
| Emitir aviso por parlante | Reproduce un aviso y queda a la espera |
| Mantener la distancia | Se detiene a una distancia segura y sigue observando |
| Volver a la base | Abandona la ronda y regresa a la base de carga |

**Privacidad:** no se guardan imágenes ni rostros. El historial registra solo el evento: hora, zona y la decisión tomada. La app muestra el estado de la orden hasta que el robot responde.

## 12. Figma navegable

Enlace al prototipo: *pendiente de completar*.

El prototipo cubre selección del robot, panel de supervisión, telemetría, misiones, control, alertas, historial y desconexión, con los estados normal, deshabilitado, advertencia y error.

- **Flujo normal:** el operador elige el robot, inicia la ronda, sigue el progreso y la ronda termina con el regreso a la base.
- **Flujo con falla:** aparece una persona en la zona restringida, la ronda se pausa y el operador elige entre aviso por parlante, mantener la distancia o volver a la base.
