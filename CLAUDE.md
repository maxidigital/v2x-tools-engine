# `v2x-tools-engine` — el dueño de los codecs

> Doc por-servicio (se auto-carga al entrar). Build/run en `README.md`. Lo transversal vive en
> **`v2x-tools-docs/`** (`SYSTEM.md`, `ADDRESSING.md`).

**Propósito**: el **motor de codecs wind**. Carga una definición digerida y hace **convert / generate** sobre
ella. **Content-addressed**: `POST /engine/load {tree, metadata}` → `engineId = hash(tree+metadata)`; después
convert/generate toman el `engineId` por header. Sin persistencia, sin auth, sin V2X en el path.
**Tecnología**: Spring Boot · Java 17 · jars wind vendorizados en `libs/` (system scope, no registry).

## Qué hace / qué NO hace
- ✅ `load` (árbol+metadata → `engineId`, cache in-mem) · `convert` (UPER/WER/XML/JSON) · `generate` ·
  render del `EngineResult` tipado.
- ❌ **NO** parsea ASN.1 (eso es el **repo**) · **NO** sabe de refs, aliases, users ni auth (eso es el **hub**).
  Solo un `X-User-Id` para separar estado en memoria.

## Endpoints (`/engine`)
| Método + path | Qué hace |
|---|---|
| `POST /engine/load` `{tree, metadata}` | Carga la definición → devuelve `{engineId}` (idempotente por hash). |
| `POST /engine/convert?from=&to=` (`X-Engine-Id`) | Convierte el payload entre formatos. |
| `POST /engine/generate` (`X-Engine-Id`) | Genera un mensaje de muestra. |
| `GET /engine/loaded` · `DELETE /engine/loaded/{engineId}` | Lista / evicta definiciones cargadas. |

`EngineResult` tipado: `{status: ok | engineNotFound | decodeError}`, **siempre HTTP 200** (el hub reacciona al
status; ante `engineNotFound` recarga y reintenta).

## Parámetros / config (env vars)
| Variable | Para qué |
|---|---|
| `PORT` | Puerto que bindea Tomcat (8090). |
| `server.address=::` | **Bindea IPv6** (la red privada Railway entre servicios es IPv6-only; ver gotcha). |
| `engine.cache.ttl-minutes` | TTL **idle** de cada def cargada (default `60`). Se resetea en cada convert/generate. |
| `engine.cache.sweep-ms` | Cada cuánto corre el sweeper que evicta las idle (default `600000` = 10 min). |

Sin DB. Estado en memoria (cache `engineId → definición cargada`) con **TTL idle** — ver
[§Cache de definiciones](#cache-de-definiciones--ciclo-de-vida-content-addressed--ttl-idle).

## Cache de definiciones — ciclo de vida (content-addressed + TTL idle)

El engine mantiene **un cache en memoria** de definiciones compiladas: `Map<engineId, CachedDef>`
(`EngineService.cache`, un `ConcurrentHashMap`). No hay DB ni disco — al reiniciar arranca vacío.

**Content-addressed.** `POST /engine/load {tree, metadata}` calcula `engineId = sha256(tree + "\n" + metadata)`
(primeros 32 hex) y compila la def bajo esa key con `computeIfAbsent`. Es **idempotente**: el mismo árbol+metadata
→ el mismo engineId → no recompila. Compilar (`compile`) también **valida los fixups fail-fast** contra una
instancia de muestra, así un path inválido explota en el `load`, no en el `generate`.

**TTL idle (evict tras N minutos sin uso).** Cada `CachedDef` lleva un `volatile long lastAccessMs`:
- **Se resetea en cada `convert` y `generate`** — ambos pasan por el helper `access(engineId)`, que hace el
  `cache.get` y pisa `lastAccessMs = System.currentTimeMillis()`. Una def recién compilada ya nace "tocada"
  (init del constructor), así no la barren antes de usarla.
- **NO** resetean el TTL: `GET /engine/loaded` (solo lista) ni `DELETE /engine/loaded/{engineId}` (evicta ya).
- Un **`@Scheduled sweepIdle()`** corre cada `engine.cache.sweep-ms` (default `600000` = 10 min) y hace
  `cache.entrySet().removeIf(now - lastAccessMs > ttl)`, con `ttl = engine.cache.ttl-minutes` (default `60`).
  Habilitado por `@EnableScheduling` en `EngineApplication`.

**Por qué es seguro evictar (no se pierde nada).** Si el sweeper saca un engineId y luego llega un
`convert`/`generate` para él → `cache.get` da null → el engine responde `EngineResult.engineNotFound(engineId)`
(HTTP 200, status tipado, **no** un error). El **hub** (`ResolutionService`) detecta `isEngineNotFound()` →
`reload()` → re-trae el árbol del repo → `engine.load` → **mismo engineId** (el hash es determinístico) →
**reintenta**. Transparente para el llamador. O sea: el TTL solo **libera memoria**; la corrección la garantiza
el handshake `engineNotFound` que ya existía. El costo de un miss = 1 fetch al repo + 1 recompilación.

**Tuning.**
- `engine.cache.ttl-minutes` — subir si hay defs grandes muy reusadas y sobra RAM; bajar para liberar antes.
- `engine.cache.sweep-ms` — cada cuánto barre. Con TTL 60 y sweep 10, una def idle se evicta entre los 60 y 70 min.

> **El otro cache vive en el hub.** El hub tiene su propio `refToEngineId` (`userId|ref → engineId`), que **sigue
> unbounded** (deuda separada, no la toca esto). No hay problema si el engine evicta: su entrada apunta a un
> engineId reproducible y, si el engine ya no lo tiene, el `reload()` lo regenera. Ver `v2x-tools-hub/CLAUDE.md`.

## Deploy
Railway — push a `git@github.com:maxidigital/v2x-tools-engine.git` (auto-deploy). Root dir del engine.

## Gotchas
- ⚠️ **`server.address=::`** obligatorio: la red privada de Railway (`*.railway.internal`) es **IPv6-only** entre
  servicios; con el default `0.0.0.0` (IPv4) el hub falla con `engine request failed: null`. `::` acepta también
  IPv4-mapped, así el proxy público sigue andando.
- **`jackson-dataformat-yaml`** obligatoria (el `WindMessageCodec` referencia `YAMLFactory`; runtime, el build
  `-DskipTests` no lo detecta).
- Si cambia un **jar wind** que el engine vendoriza: rebuild la cadena (`wind.connector`) y re-copiar a `libs/`
  + commit (no usa GitHub Packages). Ver `README.md`.
- ⚠️ **Cambios de contrato engine↔hub** (envelope tipado, `engineNotFound`): deployar **ambos juntos**.

## Ver también
`v2x-tools-docs/SYSTEM.md` · `README.md` (run) · `v2x-tools-hub/CLAUDE.md` (el orquestador que lo llama).
