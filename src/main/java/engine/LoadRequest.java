package engine;

import com.fasterxml.jackson.databind.JsonNode;

/** Body of POST /engine/load: the structural tree (from the repo) + the metadata envelope. */
public class LoadRequest {
    public JsonNode tree;
    public LoadMetadata metadata;
}
