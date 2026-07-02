# v2x-tools-engine

The V2X **core engine** as its own service. Self-contained and **HTTP-only**: loads digested message
definitions and converts/generates payloads over them. Owns all the "wind" libraries (codecs,
conversion) and knows nothing about repos, auth or users. No persistence — a loaded definition stays
in memory until restart or evict.

Spring Boot · Java 17 · the wind jars are committed under `libs/` (system scope, no registry).

> 📖 **La doc del servicio (endpoints, config, gotchas) está en [`CLAUDE.md`](CLAUDE.md).** El modelo es
> **content-addressed**: `POST /engine/load {tree, metadata}` → `engineId` (hash); `convert`/`generate`
> toman el `engineId` por header `X-Engine-Id`. Lo transversal, en el repo `v2x-tools-docs`.

## Build / Run

```bash
mvn package                                    # los jars wind ya están en libs/
java -jar target/v2x-tools-engine-1.0.jar      # puerto 8090 (o $PORT)
```

Railway: buildea con Maven y corre el jar; honra el `PORT` inyectado (`server.port=${PORT:8090}`) y
bindea IPv6 (`server.address=::`, ver `CLAUDE.md` §Gotchas).
