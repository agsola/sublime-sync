# sublime-sync 0.2 — Edición desde el móvil (diseño)

[English](design-v0.2.md) · **Español** · Base: [design.es.md](design.es.md) (v0.1)

Fecha: 2026-10-30

## 1. Intención

En la 0.1 el móvil solo lee. En la 0.2 el usuario quiere:

- **Editar** cualquier pestaña en el móvil, con un editor básico pero muy funcional y la letra
  de Sublime. Lo editado **no se puede perder** aunque se cierre la app: se guarda solo en el
  móvil y se sincroniza cada X segundos.
- **Crear** pestañas nuevas desde el móvil.
- **Ocultar** pestañas (y desocultarlas), con un aviso para deshacer durante unos segundos.
- **Cerrar de verdad** una pestaña en Sublime, sin perder nada.
- Marcar pestañas como **privadas**: en las listas se ven como asteriscos; dentro y al copiar,
  normal.

## 2. Decisiones del usuario

- Al sincronizar una edición, Sublime abre **una pestaña nueva "✎ título"** junto a la
  original, y las sincronizaciones siguientes **actualizan esa misma pestaña**. La original
  nunca se toca.
- Si esa pestaña "✎" se ha cambiado en el Mac desde la última sincronización, **no se pisa**:
  se abre otra "✎ título (2)" con lo del móvil.
- Ocultas y privadas se **sincronizan** (cifradas en el servidor), para respetarse tras
  reinstalar o en otro móvil.
- Cerrar de verdad: solo si la pestaña está guardada en disco o es una "✎" ya sincronizada.
  Si no, se rechaza y la app lo dice.

## 3. Anti-objetivos

- Perder texto editado: por cerrar la app, por quedarse sin red, por un conflicto o por cerrar
  una pestaña.
- Tocar la pestaña original de Sublime al editar.
- Que el servidor vea texto, títulos o qué pestañas están ocultas o son privadas.
- Que el editor vaya lento con textos largos.

## 4. Identidad estable de las pestañas

Los ids de vista de Sublime cambian al reiniciarlo, y ocultas, privadas y borradores necesitan
una identidad estable.

- El plugin guarda en cada vista un ajuste `sublime_sync_uid` (UUID). Los ajustes de vista se
  conservan en la sesión de Sublime, así que el uid sobrevive a reinicios. **No modifica el
  texto ni marca la pestaña como cambiada.**
- `Tab` gana dos campos: `uid` y `draft` (id del borrador del móvil si es una pestaña "✎").

## 5. Comandos móvil → Mac

Canal nuevo, cifrado igual que todo lo demás (AAD `command:<id>`):

| Tipo | Contenido | Efecto en Sublime |
|---|---|---|
| `draft` | `draftId`, `sourceUid?`, `title`, `text`, `rev` | Crea o actualiza la pestaña "✎" de ese borrador |
| `close` | `uid` | Cierra la pestaña si es seguro; si no, la rechaza |

- El móvil envía los comandos al **Mac por Tailscale** si está accesible y, si no, al
  **servidor** (según el ajuste "Leer desde"). El Mac los recoge del servidor al instante por
  WebSocket, o con un sondeo cada 30 s.
- Cada comando lleva un **id único**: aplicarlo dos veces no hace nada.
- El Mac devuelve un **resultado cifrado** por comando (`applied` / `conflict` / `refused`,
  con un motivo). La app lo muestra.
- El plugin recoge los comandos del agente aprovechando la conexión por la que ya envía las
  instantáneas, más un sondeo ligero cada 3 s.
- Conflicto: el plugin guarda en la vista el hash del último texto aplicado
  (`sublime_sync_applied`). Si el texto actual no coincide, la pestaña ha cambiado en el Mac:
  esa pestaña deja de estar ligada al borrador y se abre "✎ título (2)" con lo del móvil.

## 6. Estado del móvil sincronizado

Un documento cifrado `phone-state` en el servidor (y copia local) con:
`hidden: {uid: hiddenAt}` y `private: {uid: true}`.

- Escritura con **versión** (compare-and-swap). Si dos escrituras chocan, se hace la unión de
  los dos estados y se reintenta.
- Si el servidor no está disponible (solo Mac, o sin red), se guarda en local y se sube cuando
  se pueda.

## 7. Editor (Android)

- Botón **Editar** en la vista de texto y botón **Nueva pestaña** en la pantalla principal.
- Letra JetBrains Mono; tamaño según los ajustes.
- Funciones: deshacer y rehacer (historial propio), buscar y reemplazar, sangría automática al
  pulsar Intro, ajuste de línea, contador de líneas y caracteres, y botón "Copiar todo".
- **Autoguardado local:** cada cambio se guarda cifrado en el móvil (en 0,5 s y al salir de la
  app), con la clave de contenido. Al volver a abrir, el borrador sigue ahí.
- **Sincronización:** cada X segundos (ajuste, 10 s por defecto) si hubo cambios. También al
  salir del editor.
- Estado visible en el editor: "Guardado en el móvil", "Enviado hace 5 s", "En Sublime ✓",
  "Conflicto: abierto como (2)" o "Sin conexión: se enviará después".
- Lista de borradores en curso, para retomarlos.
- Si la pestaña que se edita es ya una "✎" del móvil, se sigue editando el mismo borrador.
- Las pestañas de más de 5 millones de caracteres, o recortadas para caber en el servidor, se
  pueden leer y copiar, pero no editar en el móvil (la app explica por qué).

## 8. Ocultar, privadas y cerrar (Android)

- **Ocultar:** acción en cada pestaña. Desaparece de la lista y de las barras. Un aviso abajo
  durante unos 5 s ofrece "Deshacer". Ajustes → "Pestañas ocultas (N)" para desocultarlas.
- **Privada:** botón de candado por pestaña. En listas, barras y búsquedas, título y vista
  previa se ven como `••••••`. Al entrar se ve normal y "Copiar" copia el texto real.
- **Cerrar en Sublime:** acción aparte, con confirmación. Muestra el resultado ("Cerrada" o
  "No se cerró: tiene cambios sin guardar").

## 9. Servidor

Tablas nuevas, todas con contenido cifrado:

- `commands(id, created, env, result_env)`: `POST /v1/commands`, `GET /v1/commands?pending=1`
  (Mac), `PUT /v1/commands/{id}/result` (Mac) y `GET /v1/commands/{id}` (móvil). Se borran a los
  7 días de tener resultado.
- `state(name, version, env)`: `GET`/`PUT /v1/state/{name}` con `If-Match: <version>`.
- WebSocket: el evento `{"type":"command"}` avisa al Mac y `{"type":"result","id"}` avisa al
  móvil.
- El agente expone las mismas rutas por Tailscale.

## 10. Concreciones tras la revisión (decisiones de Claude)

- **Tras un conflicto, el borrador se reengancha a la "(2)":** las siguientes sincronizaciones
  actualizan la "(2)" y la "✎" original queda como una pestaña normal del Mac. Si vuelve a haber
  conflicto con la "(2)", se abre la "(3)", y así; nunca se pisa ni se pierde nada.
- **Pestaña nueva desde el móvil:** en Sublime se titula "✎ " más su primera línea, y se abre en
  la ventana activa. Si la pestaña editada viene de otra, se abre en la ventana de la original.
- **Sublime cerrado o plugin parado:** los comandos esperan en el servidor y en el agente hasta
  que el plugin los recoge. La app muestra "Pendiente: Sublime no está abierto" si pasan más de
  30 s sin aplicarse.
- **Regla para cerrar en Sublime:** solo se cierra si (a) la pestaña no tiene cambios sin
  guardar y tiene archivo, o (b) es una "✎" cuyo texto no ha cambiado desde la última
  sincronización, o (c) está vacía. En cualquier otro caso se rechaza.
- **El plugin deja de ser solo de lectura, pero solo por comandos:** crea, actualiza y cierra
  pestañas únicamente al aplicar un comando, y guarda el uid en los ajustes de cada vista. Los
  comandos los descifra y valida el agente; el plugin nunca ve la clave.
- **Privadas en la búsqueda:** la búsqueda también encuentra texto de pestañas privadas, pero el
  resultado se muestra con asteriscos.
- **Fusión del estado del móvil:** cada entrada lleva su marca de tiempo (`{uid: {hidden,
  private, at}}`); al fusionar gana la más reciente. Así, desocultar en un móvil se respeta en
  otro.
- **Repetición de comandos:** el agente guarda los ids de comandos ya aplicados y rechaza los
  que tengan más de 7 días. Así el servidor no puede reenviar uno antiguo.
- **Autoguardado y bloqueo:** las claves están en memoria mientras la app está desbloqueada; al
  pasar a segundo plano se guarda y se envía al momento, antes del rebloqueo de 5 minutos.
- **Plataformas:** las mismas que la 0.1 (Sublime Text 4, macOS, Android 11 o superior).

## 11. Decisiones del constructor

- Formato: lo que cifra el móvil usa la versión 2 del sobre (sin Brotli, porque Android solo
  trae el descompresor); el Mac y el servidor leen ambas versiones.
- Esquema exacto de los mensajes, sondeos e intervalos.
- Cómo se implementa el deshacer del editor.
- Diseño visual del editor, dentro del sistema actual (Mariana, JetBrains Mono, Atkinson).
- Versión: app 0.2.0, protocolo compatible hacia atrás con la 0.1 (los campos nuevos son
  opcionales).
