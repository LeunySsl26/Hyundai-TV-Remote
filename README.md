# Hyundai TV Remote (Open Source)

Control remoto de código abierto para televisores **Hyundai Smart TV con webOS** (probado en el modelo **HYLED4026WiM**, webOS 4.1.0, compatible con televisores Hyundai/LG webOS mediante protocolo SSAP).

Incluye:
1. **Aplicación Android nativa (`HyundaiRemoteApp/`)**:
   - Control por **Infrarrojo (IR)** y por **Wi-Fi** (WebSockets seguros `wss://IP:3001` / SSAP).
   - Descubrimiento automático de TV en la red local (SSDP).
   - Teclado para enviar texto a la TV y respuesta háptica.
   - **Servidor Web integrado (puerto 8080)** para controlar la TV desde cualquier navegador en la misma red Wi-Fi.
2. **Servidor Web en Python (`tv_remote_web.py` + `start_tv_remote.sh`)**:
   - Permite controlar la TV desde cualquier navegador (PC, iPhone/Safari, Android, tablet) sin necesidad de instalar la app Android, actuando como puente local HTTP ↔ WSS hacia el televisor.

---

## Estructura del proyecto

```text
Hyundai-TV-Remote/
├── HyundaiRemoteApp/          # Código fuente completo de la app Android
│   ├── AndroidManifest.xml
│   ├── build.sh               # Script de compilación sin Gradle (aapt2 + javac + d8 + apksigner)
│   ├── libs/                  # Java-WebSocket & slf4j-api
│   ├── res/                   # Layouts, drawables, valores y UI web (res/raw/remote_web.html)
│   └── src/com/hyundai/remote/
│       ├── MainActivity.java
│       ├── WebOsController.java
│       ├── IrTransmitter.java
│       ├── TvDiscovery.java
│       └── RemoteWebServer.java
├── tv_remote_web.py           # Servidor web puente en Python (asyncio + websockets)
├── start_tv_remote.sh         # Script de inicio rápido (instala dependencias si faltan)
├── requirements.txt
└── LICENSE                    # Licencia MIT
```

---

## Uso rápido del Servidor Web por Wi-Fi (`.sh` + Python)

Cualquier persona en su red local puede ejecutar el servidor en Linux, macOS, Termux (Android) o Windows (WSL/Git Bash):

```bash
git clone https://github.com/LeunySsl26/Hyundai-TV-Remote.git
cd Hyundai-TV-Remote
chmod +x start_tv_remote.sh
./start_tv_remote.sh --tv 192.168.1.3 --port 8080
```

> Cambia `192.168.1.3` por la dirección IP local de tu televisor.

Luego abre en el navegador de cualquier dispositivo conectado al mismo Wi-Fi:
- En el mismo dispositivo: `http://localhost:8080`
- En otro móvil, tablet o PC: `http://<IP_DEL_DISPOSITIVO_SERVIDOR>:8080`

### Atajos de teclado en PC
- **Flechas**: Navegación (D-Pad)
- **Enter**: OK / Seleccionar
- **Escape / Backspace**: Atrás
- **H**: Inicio (Home)
- **M**: Silenciar (Mute)
- **`+` / `-`**: Subir / Bajar volumen
- **Espacio**: Play / Pausa
- **0–9**: Números / Canales

---

## Compilar la App Android desde código fuente

Requisitos: `aapt2`, `javac` (JDK), `d8`, `zip`, `apksigner`, Android SDK (`android-34`).

```bash
cd HyundaiRemoteApp
chmod +x build.sh
./build.sh
```

Puedes personalizar las rutas con variables de entorno:
```bash
ANDROID_JAR=/ruta/a/android.jar KEYSTORE=/ruta/a/debug.keystore ./build.sh
```

También puedes descargar el APK precompilado desde la sección **Releases** de este repositorio.

---

## Notas sobre uso en la nube vs. red local Wi-Fi

El televisor webOS escucha únicamente en la **red local (LAN)** en el puerto `3001` con un certificado SSL autofirmado (`wss://<IP_TV>:3001`). Por ello:
- Un servidor en la nube no puede conectarse directamente a `192.168.x.x` detrás del router de cada usuario.
- Al alojar este repositorio en GitHub, cualquier usuario puede descargar o ejecutar `start_tv_remote.sh` en su propia red local (en un teléfono con Termux, una Raspberry Pi o un PC) para habilitar el control web por Wi-Fi para todos los dispositivos de su casa (incluyendo iPhone/Safari).

---

## Licencia

Distribuido bajo la licencia **MIT**. Siéntete libre de modificar, mejorar y compartir este proyecto.
