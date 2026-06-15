# v2x-tools-engine

The V2X **core engine**, as its own service. Self-contained and **HTTP-only**: it loads digested
message definitions and converts/generates payloads over them. It owns all the "wind" libraries
(codecs, `MessagesApp`, conversion) and knows nothing about repos, auth or users (beyond an id to
separate in-memory state). No persistence, no TTL — a loaded message stays in memory until restart
or evict.

Spring Boot · Java 17 · the wind jars are committed under `libs/` (system scope, no registry).

## Endpoints (`X-User-Id` header on all)

| Method | Path | Request | Result |
|---|---|---|---|
| `POST` | `/engine/load` | `X-Message-Id` + body = definition JSON | registers the definition |
| `POST` | `/engine/convert` | `?from=&to=` + `[X-Message-Id]` + body = payload | `{status:"ok",data}` \| `{status:"notLoaded",messageId,protocolVersion}` \| `{status:"decodeError",error}` (always HTTP 200) |
| `GET` | `/engine/generate` | `?mid=2:2&format=UPER&minimal=` | `{status:"ok",data}` \| `{status:"notFound",messageId,protocolVersion}` \| `{status:"decodeError",error}` (always HTTP 200) |
| `GET` | `/engine/messages` | — | list of loaded messages |
| `DELETE` | `/engine/messages` | — | evict (clear) |

The message type for `/convert` comes from the optional `X-Message-Id` header, otherwise it is read
from the payload itself (`extractMessageId`, works for UPER/WER/JSON/XML).

## Run

```
mvn package
java -jar target/v2x-tools-engine-1.0.jar      # port 8090 (or $PORT)
```

Railway: builds with Maven and runs the jar; honors the injected `PORT` (`server.port=${PORT:8090}`).
