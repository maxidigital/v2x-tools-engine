package engine;

import a.MessageId;
import a.enums.Encoding;
import a.messages.Payload;
import de.dlr.ts.v2x.commons.translators.MessagesApp;
import de.dlr.ts.v2x.wind_generic.WindGeneric;
import de.dlr.ts.v2x.wind_model.MessageDefinition;
import de.dlr.ts.v2x.wind_model.WindMessageCodec;
import i.Sequence;
import i.WindException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import wind_parser.i.ParserSequence;

/**
 * The V2X core. Holds, per user, an in-memory MessagesApp with the loaded message definitions,
 * and converts / generates payloads over it. No TTL: a loaded message stays until restart or evict.
 * This is the only place that touches "wind".
 */
@Service
public class EngineService {

    private final WindMessageCodec codec = new WindMessageCodec();
    private final Map<Long, MessagesApp> engines = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> loadedMids = new ConcurrentHashMap<>();    // "id:prot"
    private final Map<Long, Set<String>> loadedAliases = new ConcurrentHashMap<>();

    private MessagesApp app(Long userId) {
        return engines.computeIfAbsent(userId, id -> MessagesApp.create());
    }

    private static String key(MessageId mid) {
        return mid.getId() + ":" + mid.getProtocolVersion();
    }

    // ---------------- load ----------------

    /** Parses a digested definition (eager) and registers it for the user, keyed by its messageId. */
    public void load(Long userId, String definitionJson) {
        MessageDefinition def = codec.parse(definitionJson);
        ParserSequence root = def.rootSequence();
        MessageId mid = MessageId.create(def.getMessageId(), def.getProtocolVersion());
        app(userId).registerMessage(mid, () -> WindGeneric.build(root));
        loadedMids.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(key(mid));
        if (def.getAlias() != null)
            loadedAliases.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(def.getAlias());
    }

    public boolean isLoaded(Long userId, MessageId mid) {
        return loadedMids.getOrDefault(userId, Set.of()).contains(key(mid));
    }

    public List<String> loaded(Long userId) {
        return List.copyOf(loadedAliases.getOrDefault(userId, Set.of()));
    }

    public void evict(Long userId) {
        engines.remove(userId);
        loadedMids.remove(userId);
        loadedAliases.remove(userId);
    }

    // ---------------- convert ----------------

    /**
     * Converts a payload between formats. If the message isn't loaded, returns notFound with the
     * messageId so the caller can fetch + load it and retry. The messageId is taken from the
     * optional header, else read from the payload (extractMessageId handles UPER/WER/JSON/XML).
     */
    public EngineResult convert(Long userId, String input, String from, String to, String messageIdHeader) {
        Encoding fromEnc = encoding(from);
        Encoding toEnc = encoding(to);
        if (fromEnc == null || toEnc == null)
            return EngineResult.decodeError("unsupported format conversion: " + from + " -> " + to);

        try {
            MessagesApp mapp = app(userId);
            Payload payloadIn = Payload.create(input, fromEnc);
            MessageId mid = (messageIdHeader != null && !messageIdHeader.isBlank())
                    ? MessageId.createFromStringId(messageIdHeader)
                    : mapp.extractMessageId(payloadIn.getBytes(), fromEnc);

            if (mid == null || mid.isUnknown() || !isLoaded(userId, mid))
                return EngineResult.notFound(mid == null ? 0 : mid.getId(),
                                             mid == null ? 0 : mid.getProtocolVersion());

            Sequence sequence = mapp.createEmptyMessage(mid);
            sequence = mapp.decode(sequence, payloadIn);
            Payload payloadOut = mapp.encode(sequence, toEnc);
            String data = (toEnc.isUPER() || toEnc.isWER()) ? payloadOut.getHexWithEncoding() : payloadOut.toText();
            return EngineResult.ok(data);
        } catch (WindException | RuntimeException e) {
            return EngineResult.decodeError(String.valueOf(e.getMessage()));
        }
    }

    // ---------------- generate ----------------

    /**
     * Generates a sample payload (minimal or random). Symmetric with convert: if the message isn't
     * loaded it returns notFound with the messageId so the caller can fetch + load it and retry.
     */
    public EngineResult generate(Long userId, String messageIdStr, String format, boolean minimal) {
        try {
            MessageId mid = MessageId.createFromStringId(messageIdStr);
            if (mid == null || mid.isUnknown() || !isLoaded(userId, mid))
                return EngineResult.notFound(mid == null ? 0 : mid.getId(),
                                             mid == null ? 0 : mid.getProtocolVersion());

            MessagesApp mapp = app(userId);
            Sequence seq = mapp.createEmptyMessage(mid);
            seq = minimal ? mapp.initialize(seq) : mapp.randomize(seq);
            // header: field(0)=protocolVersion, field(1)=messageID — set explicitly to the requested mid.
            i.Sequence header = (i.Sequence) seq.field(0).getElement();
            ((i.Integer) header.field(0).getElement()).setValue(mid.getProtocolVersion());
            ((i.Integer) header.field(1).getElement()).setValue(mid.getId());
            Encoding enc = encoding(format);
            if (enc == null)
                enc = Encoding.UPER;
            Payload payload = mapp.encode(seq, enc);
            String data = (enc.isXML() || enc.isJSON()) ? payload.toText() : payload.getHexWithEncoding();
            return EngineResult.ok(data);
        } catch (WindException | RuntimeException e) {
            return EngineResult.decodeError(String.valueOf(e.getMessage()));
        }
    }

    private static Encoding encoding(String format) {
        if (format == null)
            return null;
        switch (format.toUpperCase().trim()) {
            case "UPER": return Encoding.UPER;
            case "WER":  return Encoding.WER;
            case "XML":  return Encoding.XML;
            case "JSON": return Encoding.JSON;
            default:     return null;
        }
    }
}
