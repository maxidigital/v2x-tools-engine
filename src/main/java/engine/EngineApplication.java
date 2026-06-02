package engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The V2X engine service. Self-contained, HTTP-only: it loads digested message definitions and
 * converts/generates payloads. It knows nothing about repos, users (beyond an id) or auth.
 */
@SpringBootApplication
public class EngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngineApplication.class, args);
    }
}
