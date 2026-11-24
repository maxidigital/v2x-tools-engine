package engine;

import java.util.List;

/** Per-request generation options (the body of POST /engine/generate). Extensible. */
public class GenerateOptions {
    public String format = "UPER";
    /**
     * MINIMAL = only mandatory fields, minimal values (the base for overrides) · RANDOM = a typical message (random
     * optionals, short lists and strings) · FULL = every optional field present once, one item per list (every field
     * of the schema appears). Default MINIMAL.
     */
    public String content = "MINIMAL";
    /** One-shot overrides applied to this single generated message (on top of the load fixups). */
    public List<PathValue> overrides;
}
