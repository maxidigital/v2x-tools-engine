package engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The engine's only interface to the world: HTTP. Content-addressed: load returns an {@code engineId}
 * (hash of tree+metadata); convert/generate take it via the {@code X-Engine-Id} header. No users, no mid.
 */
@RestController
@RequestMapping("/engine")
public class EngineController {

    private final EngineService engine;
    private final ObjectMapper mapper;

    public EngineController(EngineService engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    /** Load a definition. Body = {tree, metadata:{description, fixups}}. Returns {engineId}. */
    @PostMapping("/load")
    public ResponseEntity<?> load(@RequestBody LoadRequest req) throws Exception {
        if (req == null || req.tree == null)
            return ResponseEntity.badRequest().body(Map.of("error", "missing 'tree'"));
        String treeJson = mapper.writeValueAsString(req.tree);
        String metaJson = mapper.writeValueAsString(req.metadata == null ? new LoadMetadata() : req.metadata);
        try {
            String engineId = engine.load(treeJson, metaJson);
            return ResponseEntity.ok(Map.of("engineId", engineId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Convert a payload (binary UPER via octet-stream, or hex/json/xml text). Typed result. */
    @PostMapping("/convert")
    public ResponseEntity<?> convert(
            @RequestHeader("X-Engine-Id") String engineId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Accept", required = false) String accept,
            @RequestBody byte[] payload) {
        boolean binaryIn = contentType != null && contentType.toLowerCase().contains("octet-stream");
        EngineResult r = engine.convert(engineId, payload, binaryIn, from, to);
        return respond(r, to, wantsBinary(accept));
    }

    /** Generate a sample. Body = GenerateOptions (format/minimal/size/overrides). Typed result. */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestHeader("X-Engine-Id") String engineId,
            @RequestHeader(value = "Accept", required = false) String accept,
            @RequestBody(required = false) GenerateOptions opts) {
        if (opts == null)
            opts = new GenerateOptions();
        EngineResult r = engine.generate(engineId, opts);
        return respond(r, opts.format, wantsBinary(accept));
    }

    /** List loaded definitions: [{engineId, description}]. */
    @GetMapping("/loaded")
    public ResponseEntity<?> loaded() {
        return ResponseEntity.ok(engine.loaded());
    }

    /** Evict one loaded definition. */
    @DeleteMapping("/loaded/{engineId}")
    public ResponseEntity<?> evict(@PathVariable String engineId) {
        return engine.evict(engineId) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ---- output representation: binary (octet-stream) on success for UPER/WER, else the typed JSON ----

    private ResponseEntity<?> respond(EngineResult r, String toFormat, boolean binaryOut) {
        if (binaryOut && r.isOk() && isBinaryFormat(toFormat)) {
            String data = r.getData();
            String hex = data.contains(":") ? data.substring(data.indexOf(':') + 1) : data;
            byte[] bytes = a.tools.Tools.hexStringToBytes(hex);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
        }
        return ResponseEntity.ok(r);
    }

    private static boolean isBinaryFormat(String f) {
        if (f == null) return false;
        String u = f.toUpperCase().trim();
        return u.equals("UPER") || u.equals("WER");
    }

    private static boolean wantsBinary(String accept) {
        return accept != null && accept.toLowerCase().contains("octet-stream");
    }
}
