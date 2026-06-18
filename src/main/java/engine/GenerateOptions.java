package engine;

import a.enums.RandomSize;
import java.util.List;

/** Per-request generation options (the body of POST /engine/generate). Extensible. */
public class GenerateOptions {
    public String format = "UPER";
    public boolean minimal = false;
    public RandomSize size = RandomSize.SMALL;
    /** One-shot overrides applied to this single generated message (on top of the load fixups). */
    public List<PathValue> overrides;
}
