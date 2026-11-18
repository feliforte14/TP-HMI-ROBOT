# Validación ejecutada

**Fecha:** 18 de noviembre de 2026. **Código:** [`3fd9dad`](https://github.com/feliforte14/TP-HMI-ROBOT/commit/3fd9dadcd8ab6534f1478fe025fc5ef62f7a5239).

**[Ejecución completa aprobada en GitHub Actions](https://github.com/feliforte14/TP-HMI-ROBOT/actions/runs/44384429382)**. Los cuatro jobs terminaron con `success`. Las actualizaciones documentales posteriores no cambian este código validado.

| Verificación | Resultado observado |
| --- | --- |
| Backend Python | **24 pruebas aprobadas**: ronda completa, pausa, presencia, obstáculo, acceso, histéresis de ruido, batería baja por escenario y consumo, idempotencia, TTL, versión, persistencia, sesiones, pérdida de respuesta, conciliación, autenticación HTTP y cursores |
| ROS 2 / Fast DDS | **Aprobada**: procesos separados en contenedor Jazzy, HTTP → gateway → DDS → controlador → DDS → HTTP; inicio, reentrega sin duplicar ejecución, presencia, resolución, batería, regreso y telemetría vencida al terminar el controlador |
| Android: compilación | **APK debug generado** con Gradle 8.9 y AGP 8.7.3 |
| Android: dominio Kotlin | **7 pruebas aprobadas**, 0 fallos, 0 omitidas |
| Android Lint | **Sin errores**. Advertencias de versiones más recientes, target SDK anterior al más reciente, reglas de extracción de datos e icono predeterminado. No se presenta como aplicación publicada en Play Store |
| Android: emulador API 29 | **3 pruebas aprobadas**: interfaz de inicio/pausa e historial, reapertura de Room conservando registros, y Retrofit desde el emulador hacia el gateway HTTP con confirmación e idempotencia |

## Artefactos

En la ejecución enlazada:

- `RondaGo-debug-apk`: ZIP con `app-debug.apk`, firmado para depuración.
- `informes-android`: informe Lint y resultados de pruebas unitarias.
- `pruebas-emulador`: informe de pruebas instrumentadas.

La prueba instrumentada de HTTP utiliza el modo `--demo` del gateway para comprobar Android/Retrofit. La prueba ROS/DDS es independiente y usa el controlador ROS real. No se afirma haber probado las tres piezas juntas en un teléfono físico del equipo.

El equipo había confirmado previamente `talker/listener` con Fast DDS en Ubuntu 24.04/WSL 2 mediante captura. La validación actual se ejecutó automáticamente en GitHub, sin requerir nuevas pruebas manuales del usuario.

## Límites pendientes

- Go2 físico, su SDK, motores, sensores, percepción y navegación real: no implementados ni probados.
- Red específica Windows/WSL/teléfono del equipo: no probada remotamente. El README explica la configuración necesaria.
- Accesibilidad manual con TalkBack/texto al 200 % y p95 de 100 eventos en dispositivo físico: no medidos.
- Alertas en segundo plano: no implementadas; la supervisión de la app es en primer plano.
- Figma navegable, datos académicos del grupo y aprobación del alcance por el docente: no forman parte del código ni de estas pruebas.

Este resultado acredita un prototipo académico de simulación y comunicación, no un sistema de vigilancia listo para producción ni una función de emergencia física certificada.
