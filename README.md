# TP HMI/HRI – Patrullaje nocturno en depósito (Unitree Go2)

Trabajo práctico grupal de Desarrollo de Aplicaciones I (UADE, Facultad de Ingeniería y Cs. Exactas): interfaz HMI/HRI para un robot de servicio, con prototipo en Figma y un APK Android que funciona con un robot simulado.

## Caso de uso

Ronda de seguridad nocturna en un depósito logístico con un Unitree Go2. Las rondas a pie son caras y las cámaras fijas tienen puntos ciegos entre estanterías. Un operador supervisa la ronda desde la app, ve la telemetría y da órdenes discretas.

- **Variables propias de telemetría:** puntos de control verificados, puertas o portones abiertos detectados y nivel de ruido ambiente.
- **Escenario propio:** el robot detecta a una persona en una zona restringida y el operador elige entre emitir un aviso por parlante, mantener la distancia o volver a la base. No se guardan imágenes ni rostros: el historial registra solo el evento.

## Contenido

| Ruta | Qué contiene |
| --- | --- |
| [`docs/preentrega.md`](docs/preentrega.md) | Documento de preentrega: problema, entorno, actores, misión, elección del robot, fuentes, recorrido, telemetría, comandos, riesgos y escenario propio |

## Stack previsto

- Kotlin, Jetpack Compose, MVVM, ViewModel y StateFlow.
- Repository que desacopla telemetría, comandos y eventos de su implementación simulada.
- Room para el historial de misiones y eventos.
- Inyección de dependencias manual.
- Funciona sin robot, sin servidor y sin internet.

ROS2 y DDS se usan solo como referencia de diseño (telemetría como tópicos, comandos como servicios, misiones como acciones). El contrato de mensajes es propio y no es el protocolo oficial de Unitree.

## Estado

- [x] Documento de preentrega (borrador)
- [ ] Prototipo navegable en Figma
- [ ] APK con simulador
- [ ] Entrega final: APK, código, diagrama de arquitectura, ejemplos JSON, evidencias de los 5 escenarios y video
