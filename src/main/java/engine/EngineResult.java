package engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Typed result of a convert/generate, serialized as the response body. Carries no wind types, so the
 * caller (the hub) reacts without knowing V2X.
 *
 * {"status":"ok","data":...} | {"status":"engineNotFound","engineId":"..."} | {"status":"decodeError","error":...}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EngineResult {

    private String status;
    private String data;
    private String engineId;
    private String error;

    public static EngineResult ok(String data) {
        EngineResult r = new EngineResult();
        r.status = "ok";
        r.data = data;
        return r;
    }

    /** The engineId is not loaded (never loaded, or evicted): the hub must re-supply and retry. */
    public static EngineResult engineNotFound(String engineId) {
        EngineResult r = new EngineResult();
        r.status = "engineNotFound";
        r.engineId = engineId;
        return r;
    }

    public static EngineResult decodeError(String error) {
        EngineResult r = new EngineResult();
        r.status = "decodeError";
        r.error = error;
        return r;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOk() { return "ok".equals(status); }

    public String getStatus() { return status; }
    public String getData() { return data; }
    public String getEngineId() { return engineId; }
    public String getError() { return error; }
}
