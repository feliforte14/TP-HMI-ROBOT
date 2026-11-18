# Validación

## Ejecutado durante la implementación

- Backend Python: 22 pruebas automáticas pasan (dominio, HTTP real local, persistencia, sesiones, caducidad, duplicados, pérdida de respuesta y reconciliación).
- Prueba anterior del equipo: talker/listener ROS 2 con Fast DDS en Ubuntu 24.04/WSL 2, confirmada por captura. Esta evidencia no equivale a validar la integración nueva.

## Verificación automatizada del commit

Consultar [GitHub Actions](https://github.com/feliforte14/TP-HMI-ROBOT/actions) para el commit actual. Los jobs separados comprueban:

| Job | Evidencia |
| --- | --- |
| backend | unittest de reglas, almacenamiento y API |
| ros-dds | Dos procesos ROS reales, flujo HTTP/DDS, presencia, batería, regreso y caída del controlador |
| android | APK, pruebas Kotlin de dominio y Android Lint |
| android-device | UI de inicio/pausa e historial y reapertura de Room en emulador |

Los resultados pendientes o fallidos no se consideran aprobados. Los informes y el APK se publican como artefactos de la ejecución.

## No verificado por estas pruebas

Integración con Go2 físico, red particular Windows/WSL/teléfono del equipo, accesibilidad con TalkBack y fuentes al 200 %, rendimiento p95 de 100 eventos en dispositivo real, alertas en segundo plano (no implementadas), Figma y aceptación por el docente.
