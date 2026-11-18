# RondaGo

Aplicación Android para supervisar **rondas simuladas** en un depósito logístico. Proyecto académico de Desarrollo de Aplicaciones I, UADE.

Incluye app Kotlin/Compose, ViewModel/StateFlow, Room, Retrofit, DataStore, controlador Python, gateway HTTP y comunicación **ROS 2 Jazzy con Fast DDS**. No requiere un Go2 para la demostración. No controla hardware real.

## Elegir cómo ejecutarlo

| Modo | Requisitos | Comunicación | Uso |
| --- | --- | --- | --- |
| **Demo local Android** | APK instalado, Android 8 o superior | Dentro del APK; **sin ROS/DDS** | Probar pantallas, ronda, alertas y persistencia sin servidor ni Internet |
| **Android + gateway + ROS 2** | App, Ubuntu 24.04, ROS 2 Jazzy | Android → HTTP → gateway ROS 2 → Fast DDS → controlador simulado | Demostrar la integración exigida |
| **Gateway `--demo`** | Python 3.10+ | HTTP + motor Python local; **sin DDS** | Probar API y reglas sin instalar ROS |

La app inicia en modo local. Los modos tienen rótulos explícitos y sus registros se guardan separados por origen. La conexión al Go2 físico, la navegación real y la detección mediante sensores no forman parte de esta versión.

## App Android y APK

1. Abrir la carpeta **`android/`** en Android Studio.
2. Usar JDK 17 o compatible con Gradle 8.9; instalar Android SDK 35 si se solicita.
3. Sincronizar Gradle y ejecutar `app` en un emulador o teléfono Android 8+.
4. En **Ronda**, pulsar **Iniciar ronda**. Cada cinco ticks se verifica un punto; después de cuatro puntos vuelve a base.
5. En **Escenarios de prueba**, inyectar presencia, obstáculo, batería baja, acceso abierto o ruido.
6. Abrir **Alertas**, tocar una tarjeta y reconocer, resolver o responder. Resolver requiere una justificación.
7. Consultar **Historial** aun sin conexión. Se puede filtrar por alertas, órdenes, rondas o texto/fecha.

El APK de depuración se genera con:

```bash
cd android
./gradlew :app:assembleDebug
```

En Windows: `gradlew.bat :app:assembleDebug`. Resultado: `android/app/build/outputs/apk/debug/app-debug.apk`.

También se genera en [GitHub Actions](https://github.com/feliforte14/TP-HMI-ROBOT/actions): abrir una ejecución exitosa de **Verificación RondaGo** y descargar el artefacto **RondaGo-debug-apk** (GitHub puede pedir iniciar sesión). Es un APK de demostración firmado con clave de debug, no una publicación de Play Store.

## Preparar Windows 10 y Ubuntu

Windows 10 versión 2004/compilación 19041 o posterior admite la instalación simplificada. El entorno usado por el equipo es Windows 10 Pro 22H2 con Ubuntu 24.04 en WSL 2.

En **PowerShell como administrador**:

```powershell
wsl --install -d Ubuntu-24.04
```

Reiniciar si se solicita. Abrir Ubuntu y crear usuario/contraseña de Linux; la contraseña no se muestra al escribir. Verificar desde PowerShell:

```powershell
wsl -l -v
```

`Ubuntu-24.04` debe indicar `VERSION 2`. Si indica 1:

```powershell
wsl --set-version Ubuntu-24.04 2
```

## Instalar ROS 2 Jazzy

Los comandos siguientes se ejecutan en **Ubuntu**, uno por uno. Si ROS ya está instalado, saltar a la ejecución del backend.

```bash
sudo apt update
sudo apt install -y locales curl software-properties-common git
sudo locale-gen en_US.UTF-8
sudo update-locale LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
export LANG=en_US.UTF-8
sudo add-apt-repository -y universe
```

Comprobar `locale -a` y `locale charmap`: debe existir `en_US.utf8` y devolverse `UTF-8`. Si aparece una advertencia en la sesión vieja, abrir una nueva terminal Ubuntu.

```bash
export ROS_APT_SOURCE_VERSION=$(curl -fsSL https://api.github.com/repos/ros-infrastructure/ros-apt-source/releases/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
curl -fL -o /tmp/ros2-apt-source.deb "https://github.com/ros-infrastructure/ros-apt-source/releases/download/${ROS_APT_SOURCE_VERSION}/ros2-apt-source_${ROS_APT_SOURCE_VERSION}.noble_all.deb"
sudo dpkg -i /tmp/ros2-apt-source.deb
sudo apt update
sudo apt upgrade -y
sudo apt install -y ros-jazzy-desktop ros-dev-tools
source /opt/ros/jazzy/setup.bash
ros2 --help
```

## Ejecutar el backend ROS 2 + DDS

En Ubuntu:

```bash
cd ~
git clone https://github.com/feliforte14/TP-HMI-ROBOT.git
cd TP-HMI-ROBOT
```

Si ya está clonado, entrar a la carpeta y usar `git pull --ff-only`.

Crear un token de laboratorio y mantenerlo fuera de Git. La app y el gateway deben usar el mismo token:

```bash
export RONDAGO_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
printf '%s\n' "$RONDAGO_TOKEN"
bash scripts/run_ros.sh
```

El script carga Jazzy, selecciona **`rmw_fastrtps_cpp`**, usa `ROS_DOMAIN_ID=42` y ejecuta **dos procesos**: controlador simulado y gateway. Ctrl+C termina ambos. No ejecutar simultáneamente la demo antigua `simulator/rondago_sim.py`: publica en el mismo topic con otro contrato.

En otra terminal Ubuntu se puede inspeccionar DDS:

```bash
source /opt/ros/jazzy/setup.bash
export RMW_IMPLEMENTATION=rmw_fastrtps_cpp
export ROS_DOMAIN_ID=42
ros2 node list
ros2 topic echo /rondago/sim/state
```

En Android, ir a **Conexión**, escribir URL y token, y elegir **Conectar gateway**. El token se conserva solo en memoria; volver a ingresarlo tras cerrar el proceso. La URL y el modo se conservan mediante DataStore.

### Red con WSL, emulador y teléfono

- Gateway por defecto: `127.0.0.1:8080`, accesible desde Ubuntu.
- Para escuchar en interfaces de WSL, antes de iniciar: `export RONDAGO_HOST=0.0.0.0`.
- Emulador Android: `http://10.0.2.2:8080/` apunta al host Windows, **no directamente a WSL**. Requiere que el reenvío localhost de WSL llegue al gateway; probar primero `http://localhost:8080/v1/state` desde Windows (sin token responderá 401 si hay conexión).
- Teléfono físico: misma red local, usar IP LAN de Windows y configurar reenvío TCP hacia WSL si usa NAT. La IP de WSL puede cambiar al reiniciar. Consultar [la guía de red WSL de Microsoft](https://learn.microsoft.com/windows/wsl/networking). No desactivar todo el firewall: permitir solo el puerto y la red de laboratorio necesarios.
- Esta versión usa HTTP y token compartido **solo en laboratorio confiable**. No publicar el puerto en Internet. El dominio DDS no es autenticación; SROS 2/TLS y cuentas por operador quedan para un despliegue real.

## Reglas implementadas

- Una ronda activa; cuatro controles únicos, vuelta a base y resultado final.
- Iniciar requiere base, batería >=20 %, capacidad de navegación y enlace vigente.
- Pausar, reanudar, cancelar, volver, mantener posición y detener son solicitudes simuladas.
- Presencia y obstáculo pausan la ronda; reconocer no equivale a resolver. Se requiere resolución antes de reanudar.
- Batería baja pausa la ronda y permite regreso; recarga solo en base sin ronda activa.
- Ruido sintético >70 durante 3 s activa alerta; <65 durante 5 s la resuelve. No son límites normativos. El aviso por parlante tiene una ventana de exclusión acústica.
- Portón abierto genera una alerta por episodio; se debe cerrar antes de resolverla.
- Los comandos pasan por solicitado, aceptado y resultado confirmado. HTTP 202 **no significa ejecutado**.
- UUID por orden, versión esperada del estado y TTL de 3 s. La reentrega conserva ID y TTL; el controlador persiste resultado y estado de forma atómica.
- Sin confirmación durante 5 s: resultado desconocido y bloqueo de nuevas órdenes hasta conciliar.
- Telemetría ausente o mayor a 5 s: bloqueo en app **y gateway**. No se encolan órdenes offline.
- Una sesión de control por gateway, renovación y vencimiento a los 60 s. Otro operador queda en lectura.
- Historial Room en Android y SQLite en backend. Los eventos se actualizan por ID sin duplicarlos.

La supervisión Android se realiza **en primer plano**. No se implementaron alertas de fondo, notificaciones push, sensores reales, audio real, integración Unitree ni emergencia certificada. En demo local, el progreso pausa al dejar de actualizarse la app; el backend ROS continúa independientemente.

## Estructura

| Ruta | Contenido |
| --- | --- |
| `android/` | App completa de demo: Compose, ViewModel, Repository, Room, Retrofit, DataStore |
| `backend/engine.py` | Máquina de estados y persistencia del controlador |
| `backend/ros_nodes.py` | Nodos ROS 2, topics y QoS DDS |
| `backend/gateway.py` | API HTTP, sesiones, diario y conciliación |
| `scripts/run_ros.sh` | Arranque de ambos procesos ROS |
| `tests/` | Pruebas unitarias, HTTP y prueba integrada real DDS |
| `docs/IMPLEMENTACION.md` | Arquitectura, contrato API y límites |
| `docs/VALIDACION.md` | Evidencias y estado de validación |
| `docs/preentrega.md` | Propuesta inicial histórica; algunas decisiones fueron reemplazadas |
| `simulator/rondago_sim.py` | Primera demo histórica de telemetría, no ejecutar junto al controlador actual |

## Pruebas

Sin instalar dependencias Python externas:

```bash
python3 -m unittest discover -s tests -v
```

Con ROS 2 Jazzy cargado:

```bash
source /opt/ros/jazzy/setup.bash
python3 tests/ros_integration.py
```

Android:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

El último comando necesita un dispositivo o emulador. GitHub Actions incluye jobs de backend, DDS real, compilación Android y prueba de UI/Room en emulador. Consultar el resultado de cada ejecución; que un test esté escrito no significa que haya pasado.

## Fuentes técnicas

- [Instalar WSL](https://learn.microsoft.com/windows/wsl/install)
- [ROS 2 Jazzy en Ubuntu](https://docs.ros.org/en/jazzy/Installation/Ubuntu-Install-Debs.html)
- [QoS de ROS 2](https://docs.ros.org/en/jazzy/Concepts/Intermediate/About-Quality-of-Service-Settings.html)
- [Android Gradle Plugin 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
