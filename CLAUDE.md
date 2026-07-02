# `v2x-tools-engine` — cheat-sheet

> Quick-ref operativo (se auto-carga al entrar). **Arquitectura / diseño / decisiones / caching →
> [`v2x-tools-docs/services/engine/`](../v2x-tools-docs/services/engine/README.md)** (la fuente durable).
> Cross-service → `v2x-tools-docs/SYSTEM.md`. Este archivo solo tiene lo que necesitás para operar el repo.

**Qué es**: el **motor de codecs wind** — carga un árbol digerido (`load` → `engineId` content-addressed) y hace
**convert / generate**. Sin DB, sin auth, sin refs (eso lo resuelve el hub; el ASN.1 lo parsea el repo).
Spring Boot · Java 17 · jars wind en `libs/` (system scope).

## Endpoints (`/engine`)
| Método + path | Qué |
|---|---|
| `POST /engine/load` `{tree, metadata}` | → `{engineId}` (idempotente por hash). |
| `POST /engine/convert?from=&to=` (`X-Engine-Id`) | Convierte el payload entre formatos (UPER/WER/**XER**/**JER**). |
| `POST /engine/generate` (`X-Engine-Id`) | Genera un mensaje de muestra (`{format, size, minimal, overrides}`). |
| `GET /engine/loaded` · `DELETE /engine/loaded/{engineId}` | Lista / evicta defs cargadas. |

`EngineResult` tipado `{status: ok | engineNotFound | decodeError}`, **siempre HTTP 200**. El cache tiene **TTL
idle** (evict tras `engine.cache.ttl-minutes`, default 60) — el porqué/handshake en el doc de arquitectura.

## Config (env vars)
| Variable | Para qué |
|---|---|
| `PORT` | Puerto (8090). |
| `server.address=::` | **Bindea IPv6** (red interna Railway IPv6-only — ver gotcha). |
| `engine.cache.ttl-minutes` / `engine.cache.sweep-ms` | TTL idle del cache (60) / intervalo del sweeper (10 min). |

Sin DB (estado en memoria).

## Build / Deploy
```bash
mvn clean package -DskipTests        # local, sin credenciales
```
Push a `git@github.com:maxidigital/v2x-tools-engine.git` → Railway auto-deploya. Detalle de build/vendor en `README.md`.

## Gotchas
- ⚠️ **`server.address=::`** obligatorio (Railway interno es IPv6-only; si no → `engine request failed: null`).
- **`jackson-dataformat-yaml`** obligatoria en runtime (el `WindMessageCodec` usa `YAMLFactory`; `-DskipTests` no lo detecta).
- **Jars wind en `libs/`**: si cambian, rebuild `wind.connector` + re-copiar + commit (no registry). Ver `README.md`.
- ⚠️ **Contrato engine↔hub** (envelope tipado, `engineNotFound`): deployar **ambos juntos**.
