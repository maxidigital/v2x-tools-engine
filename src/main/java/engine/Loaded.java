package engine;

/** One entry of GET /engine/loaded. */
public class Loaded {
    public String engineId;
    public String description;

    public Loaded(String engineId, String description) {
        this.engineId = engineId;
        this.description = description;
    }
}
