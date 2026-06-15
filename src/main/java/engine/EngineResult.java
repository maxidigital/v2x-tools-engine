package engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Typed result of a conversion, serialized as the /engine/convert response body. Carries no
 * wind types, so the caller (the backend hub) reacts to it without knowing anything about V2X.
 *
 * {"status":"ok","data":...} | {"status":"notFound","messageId":2,"protocolVersion":5} | {"status":"decodeError","error":...}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EngineResult {

    private String status;
    private String data;
    private Integer messageId;
    private Integer protocolVersion;
    private String error;

    public static EngineResult ok(String data) {
        EngineResult r = new EngineResult();
        r.status = "ok";
        r.data = data;
        return r;
    }

    public static EngineResult notFound(int messageId, int protocolVersion) {
        EngineResult r = new EngineResult();
        r.status = "notFound";
        r.messageId = messageId;
        r.protocolVersion = protocolVersion;
        return r;
    }

    public static EngineResult decodeError(String error) {
        EngineResult r = new EngineResult();
        r.status = "decodeError";
        r.error = error;
        return r;
    }

    public String getStatus() { return status; }
    public String getData() { return data; }
    public Integer getMessageId() { return messageId; }
    public Integer getProtocolVersion() { return protocolVersion; }
    public String getError() { return error; }
}
