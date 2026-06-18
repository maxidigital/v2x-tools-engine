package engine;

/** A path + value, used by load fixups (sticky) and generate overrides (one-shot). */
public class PathValue {
    public String path;
    public Object value;

    public PathValue() {}
    public PathValue(String path, Object value) { this.path = path; this.value = value; }
}
