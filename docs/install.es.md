# Instalar sublime-sync

[English](install.md) · **Español**

Necesitas: un Mac con Sublime Text 4 y Go 1.23+, un móvil con Android 11+ y
(recomendado) un servidor Linux con systemd, nginx y HTTPS. Tailscale en el Mac y en el
móvil es opcional, pero da la vía más rápida y privada.

Sustituye `sync.example.com` por tu propio dominio en todo lo que sigue.

## 1. Servidor de relé (Linux)

1. Apunta un (sub)dominio al servidor y sírvelo por HTTPS con nginx, como proxy hacia
   `http://127.0.0.1:8787` con soporte de WebSocket.
   - **CloudPanel:** crea un sitio *Reverse Proxy* con URL `http://127.0.0.1:8787` y emite
     un certificado de Let's Encrypt. Su vhost por defecto ya deja pasar WebSockets.
   - **nginx a mano:** ver [deploy/server/nginx-sublime-sync.conf](../deploy/server/nginx-sublime-sync.conf).
   - **Proxy de Cloudflare:** funciona. Usa el modo SSL *Full (strict)*; los WebSockets
     vienen activados.
2. Compila el servidor para la CPU de tu servidor y cópialo con los archivos de despliegue:
   ```sh
   GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -o sublime-sync-server ./cmd/sublime-sync-server   # o GOARCH=amd64
   scp sublime-sync-server deploy/server/install.sh deploy/server/sublime-sync.service tu@servidor:
   ```
3. En el servidor:
   ```sh
   sudo ./install.sh ./sublime-sync-server
   ```
   Crea un usuario sin privilegios `sublime-sync` y un servicio systemd endurecido, e
   imprime una sola vez un **token de instalación** (también queda en
   `/etc/sublime-sync/env`). Volver a ejecutarlo actualiza el binario y conserva los datos
   y el token.
4. Comprueba: `curl https://sync.example.com/health` → `ok`.

## 2. Agente del Mac y plugin de Sublime

Desde la raíz del repositorio:

```sh
deploy/mac/install.sh                      # compila el agente e instala el plugin
~/.local/bin/sublime-sync-agent init --server https://sync.example.com --install-token <token>
deploy/mac/install.sh                      # ahora además arranca el agente (LaunchAgent)
~/.local/bin/sublime-sync-agent status
```

- `init` crea una clave aleatoria de 256 bits en el Llavero de macOS y se registra en el
  servidor. Tailscale debe estar activo (o pasa `--mac-url`).
- El plugin se copia en `~/Library/Application Support/Sublime Text/Packages/sublime-sync/`.
  Solo lee las pestañas; nunca las modifica.
- Registro: `~/Library/Application Support/sublime-sync/agent.log`.

## 3. App Android

1. Compílala (o usa un APK publicado):
   ```sh
   cd android && ./gradlew :app:assembleRelease
   ```
   Para una versión firmada, crea `android/keystore.local.properties` (ignorado por git)
   con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`.
2. Instala el APK en el móvil (permite instalar desde ese origen cuando te lo pida).
3. Ábrela, desbloquéala con la huella o el PIN y pulsa **Escanear código QR**.
4. En el Mac ejecuta `~/.local/bin/sublime-sync-agent pair` y escanea el QR. Contiene la
   clave secreta: no le hagas fotos ni lo compartas. Pulsa Ctrl-C al terminar para
   borrarlo de la pantalla.

## Qué muestra la app

| Indicador | Significado |
|---|---|
| 🟢 Mac · Tailscale · en vivo | Leído directamente del Mac por Tailscale |
| 🔵 Servidor · en vivo | El Mac está conectado; leído a través del servidor |
| 🟡 Servidor · copia de hace X | El Mac está apagado o dormido; última copia del servidor |
| ⚪ Sin conexión · caché de hace X | Sin red; última copia guardada (cifrada) en el móvil |

El icono del reloj abre el histórico. Toca una hora para ver las pestañas como estaban.

## Mantenimiento

- **Cambiar la clave:** `sublime-sync-agent rotate`, reinicia el agente
  (`deploy/mac/install.sh`), ejecuta `pair` y pulsa **Escanear QR nuevo** al final de la
  lista de pestañas de la app. El histórico anterior sigue siendo legible.
  (**Desemparejar** borra todas las claves del móvil.)
- **Desinstalar (Mac):** `launchctl bootout gui/$(id -u)/io.github.agsola.sublime-sync.agent`,
  borra `~/.local/bin/sublime-sync-agent`, la carpeta del plugin, la carpeta de datos y el
  elemento del Llavero `io.github.agsola.sublime-sync`.
- **Desinstalar (servidor):** `sudo systemctl disable --now sublime-sync` y borra
  `/opt/sublime-sync`, `/var/lib/sublime-sync` y `/etc/sublime-sync`.
