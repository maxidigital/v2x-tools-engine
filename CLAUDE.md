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
| `POST /engine/convert?from=&to=` (`X-Engine-Id`) | Convierte el payload entre formatos (UPER/WER/**XER**/**JER**). Si falla un decode UPER, el error lo explica el tracer (campo + bit); ningún error lleva nombres de clases Java. |
| `POST /engine/generate` (`X-Engine-Id`) | Genera un mensaje de muestra (`{format, size, minimal, overrides}`). |
| `POST /engine/diagnose?from=UPER` (`X-Engine-Id`) | Desglose **bit a bit** de un UPER (`PERDecoder.trace`): `data` = JSON `{payloadBits, decodedBits, trailingBits, error?, failurePath?, failureBit?, failureFieldStart?, paddingBits?, padding?, paddingNonZero?, extraBytes?, extraHex?, constraintViolations, entries[{start,end,path,kind,bits,value,type,violation?}], decoded?}`. `violation` = `OUT_OF_RANGE` (valor/cantidad fuera del `(min..max)` declarado: UPER lo deja pasar) o `INVALID_INDEX` (ENUMERATED sin valor definido: en modo desglose se marca y **se sigue**; `decoded` se omite). Padding = hasta el borde del octeto; lo que sigue = `extraBytes`. Un payload roto igual es `ok` (desglose parcial + error). Solo UPER. |
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
- **`wind.connector.jar` ya NO trae `wind.lib`** (2026-11-19): se le quitaron las 285 clases de `wind.lib` (estaban
  idénticas a `libs/wind.lib-4.4.jar`). Un cambio en `wind.lib` = **reemplazar solo `libs/wind.lib-*.jar`**, sin
  parchear el connector. El connector conserva lo suyo: `wind/connector`, `commons/translators` (`MessagesApp`),
  `portshub`, serializadores y `org.json`. Respaldo del jar viejo: fuera del repo.
- ⚠️ **Contrato engine↔hub** (envelope tipado, `engineNotFound`): deployar **ambos juntos**.
- **`metadata.compat = "asn1c"`** (load): `Asn1cCompat` reescribe el árbol antes de compilar para codificar como
  asn1c/Vanetza: (1) `WITH COMPONENT` aplicado en serie (tipo o campo con tag `withComponents`) saca la
  extensibilidad del SIZE; (2) las extension additions se suman a la raíz (`SIZE(1..16,...,17..40)` → `(1..40,...)`).
  Default = X.691. Verificado 2026-11-18: modo asn1c = Vanetza stock; estándar = Vanetza con las restricciones corregidas.

