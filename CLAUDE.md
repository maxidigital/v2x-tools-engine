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

Sin DB. Estado en memoria (cache `engineId → definición cargada`).

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
