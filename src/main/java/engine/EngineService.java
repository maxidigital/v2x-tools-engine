package engine;

import a.enums.Encoding;
import a.enums.RandomSize;
import a.messages.Payload;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.dlr.ts.v2x.commons.translators.MessagesApp;
import de.dlr.ts.v2x.wind_generic.WindGeneric;
import de.dlr.ts.v2x.wind_model.MessageDefinition;
import de.dlr.ts.v2x.wind_model.WindMessageCodec;
import i.WindException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import wind_parser.i.ParserSequence;

/**
 * The V2X core, as a universal codec. Holds a content-addressed cache of compiled definitions keyed by
 * {@code engineId = hash(tree + metadata)} (no users, no messageId routing). Each loaded definition keeps
 * a factory (build a fresh generic instance), a human description, and its sticky fixups. convert/generate
 * take the engineId explicitly. This is the only place that touches "wind".
 */
@Service
public class EngineService {

    private final WindMessageCodec codec = new WindMessageCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    // MessagesApp holds mutable per-operation state (randomizers, etc.) -> one per thread, reused.
    private final ThreadLocal<MessagesApp> mapp = ThreadLocal.withInitial(MessagesApp::create);

    private final Map<String, CachedDef> cache = new ConcurrentHashMap<>();

    private static final class CachedDef {
        final Supplier<i.Sequence> factory;   // builds a fresh generic instance
        final String description;
        final List<PathValue> fixups;
        CachedDef(Supplier<i.Sequence> factory, String description, List<PathValue> fixups) {
            this.factory = factory;
            this.description = description;
            this.fixups = fixups;
        }
    }

    // ---------------- load ----------------

    /** Compiles (tree + metadata) and caches it under engineId = hash(tree+metadata); returns engineId. */
    public String load(String treeJson, String metadataJson) {
        LoadMetadata meta = parseMetadata(metadataJson);
        String engineId = sha256(treeJson + "\n" + (metadataJson == null ? "" : metadataJson));
        cache.computeIfAbsent(engineId, k -> compile(treeJson, meta));
        return engineId;
    }

    private CachedDef compile(String treeJson, LoadMetadata meta) {
        MessageDefinition def = codec.parse(treeJson);
        final ParserSequence root = def.rootSequence();
        Supplier<i.Sequence> factory = () -> WindGeneric.build(root);

        List<PathValue> fixups = meta.fixups != null ? meta.fixups : List.of();
        if (!fixups.isEmpty()) {
            // fail-fast: validate every fixup path against a sample instance now, not at generate time.
            i.Sequence sample = factory.get();
            for (PathValue f : fixups) {
                try {
                    PathResolver.validate(sample, f.path);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("invalid fixup path '" + f.path + "': " + e.getMessage());
                }
            }
        }
        return new CachedDef(factory, meta.description, fixups);
    }

    public List<Loaded> loaded() {
        List<Loaded> out = new ArrayList<>();
        cache.forEach((id, cd) -> out.add(new Loaded(id, cd.description)));
        return out;
    }

    public boolean evict(String engineId) {
        return cache.remove(engineId) != null;
    }

    // ---------------- convert ----------------

    /** Converts a payload using the definition identified by engineId. The caller says which (no routing). */
    public EngineResult convert(String engineId, byte[] payload, boolean binaryIn, String from, String to) {
        CachedDef cd = cache.get(engineId);
        if (cd == null)
            return EngineResult.engineNotFound(engineId);
        Encoding fromEnc = encoding(from);
        Encoding toEnc = encoding(to);
        if (fromEnc == null || toEnc == null)
            return EngineResult.decodeError("unsupported format conversion: " + from + " -> " + to);
        try {
            Payload in = binaryIn
                    ? Payload.create(payload, fromEnc)
                    : Payload.create(new String(payload, StandardCharsets.UTF_8), fromEnc);
            i.Sequence seq = cd.factory.get();
            seq = mapp.get().decode(seq, in);
            Payload out = mapp.get().encode(seq, toEnc);
            return EngineResult.ok(format(out, toEnc));
        } catch (WindException | RuntimeException e) {
            return EngineResult.decodeError(String.valueOf(e.getMessage()));
        }
    }

    // ---------------- generate ----------------

    /** Generates a sample; applies sticky fixups, then one-shot overrides (overrides win on conflict). */
    public EngineResult generate(String engineId, GenerateOptions opts) {
        CachedDef cd = cache.get(engineId);
        if (cd == null)
            return EngineResult.engineNotFound(engineId);
        try {
            Encoding enc = encoding(opts.format);
            if (enc == null)
                enc = Encoding.UPER;
            i.Sequence seq = cd.factory.get();
            RandomSize size = opts.size != null ? opts.size : RandomSize.SMALL;
            seq = opts.minimal ? mapp.get().initialize(seq) : mapp.get().randomize(seq, size);
            applyAll(seq, cd.fixups);            // sticky
            applyAll(seq, opts.overrides);       // one-shot (override the fixups if same path)
            Payload payload = mapp.get().encode(seq, enc);
            return EngineResult.ok(format(payload, enc));
        } catch (WindException | RuntimeException e) {
            return EngineResult.decodeError(String.valueOf(e.getMessage()));
        }
    }

    private static void applyAll(i.Sequence seq, List<PathValue> pvs) {
        if (pvs == null)
            return;
        for (PathValue pv : pvs)
            PathResolver.apply(seq, pv.path, pv.value);
    }

    // ---------------- helpers ----------------

    private static String format(Payload p, Encoding enc) {
        return (enc.isUPER() || enc.isWER()) ? p.getHexWithEncoding() : p.toText();
    }

    private LoadMetadata parseMetadata(String json) {
        if (json == null || json.isBlank())
            return new LoadMetadata();
        try {
            return mapper.readValue(json, LoadMetadata.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid metadata: " + e.getMessage());
        }
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 32);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
