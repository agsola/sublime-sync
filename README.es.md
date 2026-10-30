# sublime-sync

[English](README.md) · **Español**

Lee desde tu **móvil Android** todas las pestañas que tienes abiertas en **Sublime Text**
(también las no guardadas), de forma segura y desde cualquier sitio. Con un histórico tipo
Time Machine como copia de seguridad.

> **Estado:** v0.2 — lectura y edición. Instalación: [docs/install.es.md](docs/install.es.md).

## El problema

Mucha gente usa Sublime Text como bloc de notas: decenas de pestañas sin título que nunca se
guardan. Ese texto solo existe en un ordenador. Si no estás delante no puedes leerlo, y si el
portátil se estropea puedes perderlo.

## Qué hace

- Refleja en el móvil todas las pestañas abiertas (guardadas y sin guardar, de todas las
  ventanas) en segundos.
- Funciona tras NAT: se conecta directamente al Mac por **Tailscale** cuando puede y, si no, a
  través de un pequeño **servidor** de relé que alojas tú.
- Pregunta a ambos a la vez y prefiere el Mac; la app siempre indica de dónde vienen los datos y
  de cuándo son (`Mac · Tailscale · en vivo`, `Servidor · en vivo`, `Servidor · copia de hace 2 h`,
  `Sin conexión · caché`).
- Guarda un **histórico** cifrado en el servidor (todas las copias de 24 h, una por hora durante
  30 días y una por día después) y permite volver atrás en el tiempo.
- Se lee como Sublime: **barra de pestañas** arriba y el texto completo debajo; se desliza
  para cambiar de pestaña. Cada pestaña muestra **cuándo se modificó**; el orden puede ser el de
  Sublime o el de la última modificación.
- Recuerda dónde estabas: al volver a abrir la app vuelve a la misma pestaña, o a su ventana si
  la pestaña ya no existe.
- **Copiar el texto completo** de cualquier pestaña con un toque.
- **Editar desde el móvil** con un editor de texto plano (letra monoespaciada como Sublime,
  deshacer y rehacer, buscar y reemplazar, sangría automática). Todo lo que escribes se guarda
  al momento en el móvil y se envía cada pocos segundos; en Sublime aparece como una pestaña
  nueva "✎ título", sin tocar la original. Si esa pestaña cambió en el Mac entretanto, se abre
  una copia numerada.
- Crear pestañas nuevas, ocultar pestañas (con deshacer), marcarlas como privadas (con puntos
  en las listas) y cerrarlas en Sublime cuando no se pierde nada.

## Modelo de seguridad

- **Cifrado extremo a extremo.** El contenido se comprime (Brotli) y se cifra en el Mac. El
  servidor y cualquier proxy/CDN delante solo ven datos cifrados, ni siquiera los títulos.
- **Emparejamiento por QR.** El Mac genera una clave aleatoria de 256 bits y la muestra como
  QR; el móvil la escanea una vez. De ella se derivan claves distintas para cifrar y para
  autenticarse ante el servidor.
- **Claves en el almacén del sistema.** Llavero de macOS en el Mac; Android Keystore en el
  móvil, desbloqueado con la huella (o el PIN del dispositivo). La caché del móvil también va
  cifrada.
- **Anti-rollback.** Cada instantánea lleva un contador monotónico dentro del cifrado.

## Componentes

```
Sublime Text ──(plugin)──► agente macOS ──(Tailscale)──► app Android
                                │                            ▲
                                └──► servidor de relé ───────┘
                                     (guarda datos cifrados + histórico)
```

| Carpeta    | Qué                                       | Tecnología                |
|------------|-------------------------------------------|---------------------------|
| `plugin/`  | Plugin de Sublime Text, solo lectura      | Python (sin dependencias) |
| `agent/`   | Agente en segundo plano (LaunchAgent)     | Go                        |
| `server/`  | Relé + histórico cifrado (systemd)        | Go + SQLite               |
| `android/` | App del móvil                             | Kotlin + Jetpack Compose  |

## Requisitos

- macOS con Sublime Text 4
- Android 11+
- Tailscale en Mac y móvil (opcional, para la vía directa)
- Un servidor Linux con nginx y HTTPS para el relé (opcional pero recomendado; funciona detrás de Cloudflare)

## Documentación

- Diseño: [docs/design.es.md](docs/design.es.md) · [docs/design.md](docs/design.md); edición (v0.2): [docs/design-v0.2.es.md](docs/design-v0.2.es.md) · [docs/design-v0.2.md](docs/design-v0.2.md)
- Instalación: [docs/install.es.md](docs/install.es.md) · [docs/install.md](docs/install.md)
- Limpiar después de compilar: [docs/cleanup.es.md](docs/cleanup.es.md) · [docs/cleanup.md](docs/cleanup.md)

## Mantener tu despliegue privado

Este repositorio no contiene datos de ningún despliegue. Pon tu dominio, servidores y
credenciales en archivos ignorados por git (`local/`, `*.local.*`, `.env`); ver [.gitignore](.gitignore).

Antes de publicar, ejecuta `scripts/check-leaks.sh`. Busca secretos, archivos privados,
correos, IPs, tu ruta personal y tus propias cadenas privadas, una expresión regular por
línea en `local/private-patterns.txt` (ignorado por git). `scripts/check-leaks.sh --install-hook`
lo ejecuta antes de cada commit, y `--history` revisa también los commits anteriores.

## Licencia

[MIT](LICENSE). Las tipografías incluidas (JetBrains Mono, Atkinson Hyperlegible) tienen
licencia SIL Open Font License; ver `android/fonts-OFL-*.txt`.
