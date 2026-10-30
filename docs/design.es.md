# sublime-sync — Diseño

[English](design.md) · **Español**

Fecha: 2026-10-30

## 1. Intención

El usuario usa Sublime Text 4 en macOS como "caché": muchas pestañas abiertas sin guardar.
Quiere poder **leerlas desde su móvil Android** en cualquier momento y lugar, de forma
**segura**, y tener un **histórico tipo Time Machine** como backup.

El miedo principal: **cerrar el portátil y perder lo escrito sin que se haya sincronizado**.

## 2. Objetivos

- Leer en el móvil todas las pestañas abiertas en Sublime (guardadas y sin guardar), de todas las ventanas.
- Latencia aceptable: **≤ 10 min** (objetivo real: segundos).
- Funciona aunque el móvil esté fuera de casa y el Mac tras NAT.
- Redundancia: si el Mac está apagado/dormido, se lee la última copia del servidor.
- Histórico navegable en el tiempo (Time Machine).
- Copiar fácilmente el texto completo de una pestaña.
- Seguridad: cifrado extremo a extremo; el servidor nunca ve texto en claro.
- App protegida con huella; clave guardada en el almacén seguro del SO.
- Interfaz sencilla e intuitiva: la lista de pestañas debe ser fácil de entender, ver, leer y copiar.

## 3. No-objetivos (v1)

- Editar desde el móvil. **Pero** el diseño debe dejar preparado el camino (ver §10).
- iOS, web, varios usuarios, varios Macs.
- Publicar en Google Play (se instala por APK).

## 4. Anti-objetivos (sería un fracaso aunque "funcione")

- Que el servidor (o quien lo comprometa, o un proxy/CDN intermedio) pueda leer el contenido.
- Que se pierda contenido por cerrar la tapa / apagar el Mac antes de sincronizar.
- Que el plugin modifique, guarde o cierre pestañas de Sublime, o lo ralentice al escribir.
- Que la clave quede en claro en el móvil o en el Mac.
- Una UI confusa en la que no se sepa de dónde vienen los datos ni de cuándo son.

## 5. Arquitectura

```
Sublime Text ──(plugin Python, API oficial)──► agente macOS (Go, launchd)
                                                  │  comprime (Brotli) + cifra (E2E)
                         Tailscale (directo) ◄────┤────► Servidor Linux (Go, systemd)
                                │                 │        detrás de nginx + HTTPS
                                ▼                 │        (opcionalmente Cloudflare proxy)
                         App Android (Kotlin) ◄───┘        guarda solo blobs cifrados + histórico
```

### 5.1 Lectura en el Mac — plugin de Sublime

- Archivo Python en `~/Library/Application Support/Sublime Text/Packages/User/` (o paquete propio).
- Usa la API: recorre `sublime.windows()` → `window.views()`; por vista: id, nombre/título,
  ruta de archivo si existe, sintaxis, si está sucia (`is_dirty`), contenido completo.
- Eventos: `on_modified_async` (con debounce ~2 s), `on_new_async`, `on_close`,
  `on_load_async`, `on_post_save_async`, activación/desactivación de ventana. Además un
  barrido periódico de respaldo (p. ej. cada 60 s) por si se escapa algún evento.
- El plugin **solo lee**; envía la instantánea al agente local por socket Unix. No contiene
  criptografía (el Python de Sublime no trae librerías de cifrado modernas).
- Se eligió plugin y no solo leer `Auto Save Session.sublime_session` porque: (a) la edición
  futura necesita plugin igualmente, (b) es inmediato. Medido: Sublime reescribe el archivo de
  sesión segundos (≤ ~1 min) tras dejar de escribir, así que sirve como **fuente de respaldo**.

### 5.2 Agente macOS (Go, LaunchAgent)

- Recibe instantáneas del plugin; calcula diferencias por pestaña (hash del contenido).
- Comprime, cifra y **sube al servidor en cuanto hay cambio** (tras el debounce), no en lotes.
  Así el riesgo de "cerré la tapa y no subió" se reduce a los ~2 s de debounce.
- Al detectar suspensión del sistema / cierre de Sublime, fuerza un envío inmediato (best effort).
- Si no hay red, encola en disco (cifrado) y reintenta al recuperar conexión.
- Sirve la API de lectura al móvil **solo por la interfaz de Tailscale** (no escucha en LAN/0.0.0.0).
- Muestra el QR de emparejamiento.

### 5.3 Servidor (Go, binario único, systemd)

- Ubicación configurable (p. ej. `sync.example.com`), detrás de nginx como proxy inverso a
  `http://127.0.0.1:8787`, con HTTPS. Compatible con Cloudflare en modo proxy.
- Almacena **solo datos cifrados** en SQLite (+ blobs). No puede descifrar.
- Funciones: recibir subidas del Mac; servir la última versión y el histórico al móvil;
  relé en vivo (WebSocket) cuando el Mac está conectado; retención Time Machine.
- WebSocket con ping < 100 s (timeout de inactividad de Cloudflare); vhost nginx con `Upgrade`.

### 5.4 App Android (Kotlin + Jetpack Compose, APK)

- Al abrir: desbloqueo biométrico (BiometricPrompt, con fallback a PIN/patrón del dispositivo).
- Lanza **en paralelo** petición al Mac (Tailscale) y al servidor. Usa la respuesta más reciente
  disponible, **prefiriendo el Mac** si responde dentro de un margen corto (p. ej. 1,5 s).
- Caché local de la última versión conocida (cifrada) para abrir al instante y funcionar sin red.
- Indicador de origen siempre visible:
  - **"Mac · Tailscale · en vivo"**
  - **"Servidor · en vivo"** (Mac conectado al servidor pero no accesible por Tailscale)
  - **"Servidor · copia de hace X"** (Mac desconectado)
  - **"Sin conexión · caché de hace X"**
- La fecha/hora de los datos mostrados siempre visible.

## 6. Seguridad

- **Emparejamiento**: el agente del Mac genera una clave maestra aleatoria de 256 bits y la
  muestra como **QR** (junto con la URL del servidor, la dirección Tailscale del Mac y un id de
  dispositivo). El móvil la escanea una vez. (Se descartó que la generase el móvil: habría que
  trasladarla al Mac a mano; mismo nivel de seguridad.)
- De la clave maestra se derivan (HKDF) claves separadas:
  - clave de cifrado de contenido (AEAD: XChaCha20-Poly1305 o AES-256-GCM),
  - token de autenticación ante el servidor (el servidor solo guarda su hash),
  - credencial para la API del agente del Mac por Tailscale.
- Todo contenido viaja y se almacena cifrado; metadatos mínimos en claro (ids opacos, tamaño,
  timestamp). **Los títulos de pestaña también van cifrados.**
- Anti-rollback: cada instantánea lleva un contador monotónico dentro del texto cifrado.
- Android: clave maestra en **Android Keystore** (envuelta por una clave del Keystore que exige
  autenticación biométrica). Caché local cifrada con esa clave.
- Mac: clave en el **Llavero de macOS** (Keychain).
- Transporte: HTTPS además del cifrado E2E; Tailscale ya cifra.
- Revocación: poder regenerar la clave (re-emparejar) e invalidar la anterior.
- Limitación conocida: el agente guarda la clave mediante la herramienta `security` de macOS,
  así que cualquier proceso que corra con el mismo usuario de macOS puede leerla sin aviso.
  Protegerla de tu propia cuenta exigiría un agente nativo firmado; fuera del alcance de v1.

## 7. Histórico (Time Machine)

- Instantánea completa (todas las pestañas) como mucho cada 5 min, solo si hubo cambios.
  Deduplicación por pestaña (si una pestaña no cambió, se reutiliza su blob cifrado).
- Retención: todas las de las últimas 24 h; una por hora durante 30 días; una por día después
  (indefinido salvo límite de espacio configurado).
- La poda la hace el servidor por metadatos (no necesita descifrar).
- En la app: una línea de tiempo/deslizador para elegir momento y ver las pestañas tal como
  estaban; desde ahí también se puede copiar.

## 8. Interfaz Android

Prioridad: **leer y copiar sin pensar**.

- **Pantalla principal (lista)**: barra superior con indicador de origen + "actualizado hace X".
  Cada pestaña es una tarjeta con:
  - título claro: nombre de archivo si existe; si no, la primera línea no vacía;
  - marca si está sin guardar y a qué ventana pertenece (agrupado por ventana);
  - 3–4 líneas de vista previa en letra legible;
  - botón **"Copiar"** visible directamente en la tarjeta (copia el texto completo).
- **Detalle**: texto completo, letra grande, fuente monoespaciada opcional, seleccionable;
  botón "Copiar todo" fijo; búsqueda dentro del texto.
- Búsqueda global por contenido en la lista.
- **Histórico**: botón de reloj → línea de tiempo; al elegir un momento, la lista entra en modo
  "viendo copia del dd/mm hh:mm" claramente marcado, con botón para volver a "ahora".
- Respeta tema claro/oscuro del sistema.

## 9. Restricciones y entorno

- Mac: macOS, Sublime Text 4, Python del plugin host de Sublime (3.8+).
- Servidor: Linux con nginx (p. ej. CloudPanel) como proxy inverso hacia `127.0.0.1:8787` con
  HTTPS. Instalación con usuario sudo: servicio systemd con usuario propio sin privilegios.
  Sin Docker.
- Con Cloudflare en modo proxy: límites de tamaño de petición y timeout de WebSocket inactivo.
- Un solo usuario, un solo Mac, un móvil (el diseño no debe impedir añadir un segundo móvil).

## 10. Preparación para edición futura

- Las ediciones desde el móvil se enviarían como **pestaña nueva** en Sublime, sin tocar la
  original (p. ej. título "Editado desde móvil — <título original>").
- El protocolo debe tener un canal móvil → Mac (cifrado igual) aunque v1 no lo use; el plugin es
  el que abrirá la pestaña nueva (`window.new_file()` + insertar texto).

## 11. Decisiones de implementación

- Lenguajes: Go para agente y servidor (código compartido de protocolo/cripto); Kotlin+Compose
  para Android.
- Biblioteca criptográfica concreta, formato de mensajes, esquema SQLite, debounce exacto,
  margen de preferencia del Mac e intervalo de barrido: a decidir en el plan.
- Estructura del repo: monorepo con `plugin/`, `agent/`, `server/`, `android/`.

### 11.1 Concreciones

- **Tailscale en el móvil**: el Android tiene la app de Tailscale en la misma tailnet que el Mac.
  El agente escucha HTTP plano solo en la IP/MagicDNS de Tailscale (WireGuard ya cifra y el
  contenido va además cifrado E2E); la app permite cleartext únicamente para ese host. Si
  Tailscale está apagado, la petición falla rápido y manda el servidor.
- **Transporte**: el agente mantiene un WebSocket con el servidor (subidas + latido de
  presencia cada ~30 s). "Servidor · en vivo" = hay latido del Mac en los últimos ~90 s. El móvil
  se suscribe por WebSocket al servidor (y al agente por Tailscale) para recibir cambios en vivo;
  si no, hace fetch al abrir y al tirar hacia abajo. El mismo canal servirá de móvil → Mac para
  las ediciones futuras.
- **Pérdida al cerrar la tapa**: el contenido nunca se pierde (sigue en Sublime y en su archivo
  de sesión); lo único que puede quedar pendiente son los últimos ~2 s, que se suben al
  despertar. El agente guarda una cola local cifrada de lo no subido. **Se implementa el
  fallback** de leer `Auto Save Session.sublime_session` (cada ~60 s) si el plugin no envía nada.
- **Claves**: una sola clave maestra compartida (Mac + móviles). El primer registro en el
  servidor se autoriza con un token de instalación de un solo uso configurado al instalar el
  servidor; el agente registra con él el hash del token de autenticación. Re-emparejar genera
  clave nueva; la app y el agente conservan las claves antiguas (llavero de claves con id) para
  seguir leyendo el histórico antiguo. Un segundo móvil escanea el mismo QR.
- **QR**: se muestra bajo demanda (`syncsublime pair`); no hace falta un solo uso. Clave en el
  Llavero como ítem propio del agente.
- **Tamaños** (desde la 0.2.1): una pestaña solo se recorta si no cabe en el servidor una vez
  comprimida y cifrada (16 MB por pestaña), con un tope de seguridad de 32 millones de
  caracteres leídos de Sublime. Las recortadas muestran un aviso en la app. Medido en un Pixel 10
  Pro: una pestaña de 32 millones de caracteres abre en ~3,5 s y se desplaza con fluidez; con ~60
  millones de caracteres abiertos la app usa ~380 MB de sus 512 MB, así que una pestaña que no
  quepa en memoria muestra un aviso en lugar de cerrar la app. El editor del móvil admite hasta 5
  millones de caracteres.
- **Servidor**: script de instalación idempotente + unit systemd + snippet nginx para
  WebSocket. Endpoint `/health`; la app muestra "servidor sin respuesta" si falla.
- **Android**: minSdk 30 (Android 11), target el último. Biometría con fallback a PIN/patrón del
  dispositivo. Pruebas en emulador y en móvil real.
- **Pestañas cerradas**: desaparecen de "ahora" pero siguen en el histórico.
- **Plugin**: sin dependencias externas, compatible con el host Python de Sublime (3.8+).

## 12. Compresión

- El contenido se comprime con **Brotli antes de cifrar** (después de cifrar ya no se puede
  comprimir). Aplica a subidas, histórico, relé y caché. Riesgo de fuga por longitud (tipo
  CRIME) descartado: nadie externo puede inyectar texto en las pestañas. No se usa
  `Content-Encoding` HTTP sobre los blobs cifrados (no ganaría nada).

## 13. Repositorio público

- El repo es público y reutilizable por terceros: **nada privado** en él (dominios, IPs,
  usuarios, claves, rutas personales). Todo lo específico de un despliegue va en archivos de
  configuración locales ignorados por git (`local/`, `*.local.*`, `.env`).
