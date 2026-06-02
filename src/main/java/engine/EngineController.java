package engine;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The engine's only interface to the world: HTTP. */
@RestController
@RequestMapping("/engine")
public class EngineController {

    private final EngineService engine;

    public EngineController(EngineService engine) {
        this.engine = engine;
    }

    /** Load a digested definition for a user. Body = the definition JSON. */
    @PostMapping("/load")
    public ResponseEntity<?> load(
            @RequestHeader(value = "X-User-Id", defaultValue = "0") Long userId,
            @RequestHeader(value = "X-Message-Id", required = false) String messageId,
            @RequestBody String definitionJson) {
        engine.load(userId, definitionJson);
        return ResponseEntity.ok().build();
    }

    /** Convert a payload. Returns a typed result (always HTTP 200; outcome in the body). */
    @PostMapping("/convert")
    public EngineResult convert(
            @RequestHeader(value = "X-User-Id", defaultValue = "0") Long userId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestHeader(value = "X-Message-Id", required = false) String messageId,
            @RequestBody String payload) {
        return engine.convert(userId, payload, from, to, messageId);
    }

    /** Generate a sample payload (minimal or random) for a loaded message. */
    @GetMapping("/generate")
    public ResponseEntity<String> generate(
            @RequestHeader(value = "X-User-Id", defaultValue = "0") Long userId,
            @RequestParam String mid,
            @RequestParam(defaultValue = "UPER") String format,
            @RequestParam(defaultValue = "false") boolean minimal) {
        try {
            return ResponseEntity.ok(engine.generate(userId, mid, format, minimal));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("generate failed: " + e.getMessage());
        }
    }

    /** List the messages loaded for a user. */
    @GetMapping("/messages")
    public ResponseEntity<?> messages(
            @RequestHeader(value = "X-User-Id", defaultValue = "0") Long userId) {
        return ResponseEntity.ok(Map.of("userId", userId, "loaded", engine.loaded(userId)));
    }

    /** Evict the user's engine (clear everything loaded). */
    @DeleteMapping("/messages")
    public ResponseEntity<?> evict(
            @RequestHeader(value = "X-User-Id", defaultValue = "0") Long userId) {
        engine.evict(userId);
        return ResponseEntity.ok().build();
    }
}
