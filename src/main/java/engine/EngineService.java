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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    /** Evict a loaded definition after this many minutes with no convert/generate touching it. */
    @Value("${engine.cache.ttl-minutes:60}")
    private long ttlMinutes;

    private static final class CachedDef {
        final Supplier<i.Sequence> factory;   // builds a fresh generic instance
        final String description;
        final List<PathValue> fixups;
        volatile long lastAccessMs = System.currentTimeMillis();   // reset on each access; drives the idle TTL
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
        if (meta.compat != null && !meta.compat.isBlank() && !"standard".equalsIgnoreCase(meta.compat)) {
            if (!Asn1cCompat.ASN1C.equalsIgnoreCase(meta.compat.trim()))
                throw new IllegalArgumentException("unknown compat '" + meta.compat + "' (standard | asn1c)");
            treeJson = Asn1cCompat.apply(treeJson, mapper);
        }
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

    /** Fetches a loaded def and marks it just-used (resets its idle TTL). Null if not loaded. */
    private CachedDef access(String engineId) {
        CachedDef cd = cache.get(engineId);
        if (cd != null) cd.lastAccessMs = System.currentTimeMillis();
        return cd;
    }

    /**
     * Evicts definitions idle longer than the TTL, freeing memory. Safe: a later request for an evicted
     * engineId yields {@code engineNotFound}, and the hub reloads the same (deterministic) engineId and retries.
     */
    @Scheduled(fixedDelayString = "${engine.cache.sweep-ms:600000}")
    void sweepIdle() {
        long cutoff = System.currentTimeMillis() - ttlMinutes * 60_000L;
        cache.entrySet().removeIf(e -> e.getValue().lastAccessMs < cutoff);
    }

    // ---------------- convert ----------------

    /** Converts a payload using the definition identified by engineId. The caller says which (no routing). */
    public EngineResult convert(String engineId, byte[] payload, boolean binaryIn, String from, String to) {
        CachedDef cd = access(engineId);
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
            try {
                seq = mapp.get().decode(seq, in);
            } catch (WindException | RuntimeException e) {
                return EngineResult.decodeError(decodeFailure(cd, in, fromEnc, e));
            }
            Payload out = mapp.get().encode(seq, toEnc);
            return EngineResult.ok(format(out, toEnc));
        } catch (WindException | RuntimeException e) {
            return EngineResult.decodeError(clean(e));
        }
    }

    /**
     * A failed decode, explained. For UPER the bit-level tracer re-reads the payload and says which field broke and
     * why ("invalid value while decoding X (field starts at bit S): ..."), instead of a bare Java exception.
     */
    private String decodeFailure(CachedDef cd, Payload in, Encoding fromEnc, Exception e) {
        if (fromEnc == Encoding.UPER) {
            try {
                byte[] bytes = in.getBytes();
                if (bytes != null && bytes.length > 0) {
                    String why = a.codecs.uper.PERDecoder.trace(cd.factory.get(), bytes).error();
                    if (why != null && !why.isBlank()) return why;
                }
            } catch (RuntimeException ignored) { /* fall back to the codec's own message */ }
        }
        String m = clean(e);
        boolean jdk = e.getClass().getName().startsWith("java.") || String.valueOf(e.getMessage()).startsWith("java.");
        return jdk ? "invalid " + fromEnc + " payload: it does not match the message definition (" + m + ")" : m;
    }

    /** The exception's message without a leading Java class name ("java.lang.Foo: msg" → "msg"). */
    static String clean(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return "invalid payload (" + e.getClass().getSimpleName() + ")";
        String c = m.replaceFirst("^(?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)(?::\\s*|$)", "").trim();
        return c.isEmpty() ? "invalid payload" : c;
    }

    // ---------------- diagnose ----------------

    /**
     * Bit-level breakdown of a UPER payload: every read of the decoder with its absolute bit range, field path,
     * kind and value. A malformed payload is still {@code ok}: the partial breakdown plus the error (and where it
     * happened) is exactly what diagnosing needs. {@code data} = JSON
     * {@code {payloadBits, decodedBits, trailingBits, error?, failurePath?, failureBit?, entries[], decoded?}}.
     */
    public EngineResult diagnose(String engineId, byte[] payload, boolean binaryIn, String from) {
        CachedDef cd = access(engineId);
        if (cd == null)
            return EngineResult.engineNotFound(engineId);
        if (from != null && !from.isBlank() && !"UPER".equalsIgnoreCase(from.trim()))
            return EngineResult.decodeError("diagnose supports UPER only (got " + from + ")");
        try {
            byte[] bytes = binaryIn
                    ? payload
                    : Payload.create(new String(payload, StandardCharsets.UTF_8).trim(), Encoding.UPER).getBytes();
            if (bytes == null || bytes.length == 0)
                return EngineResult.decodeError("empty payload");
            i.Sequence seq = cd.factory.get();
            a.codecs.uper.PerTrace t = a.codecs.uper.PERDecoder.trace(seq, bytes);
            return EngineResult.ok(mapper.writeValueAsString(traceJson(t, seq)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return EngineResult.decodeError(clean(e));
        }
    }

    private Map<String, Object> traceJson(a.codecs.uper.PerTrace t, i.Sequence decoded) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("payloadBits", t.payloadBits());
        out.put("decodedBits", t.decodedBits());
        out.put("trailingBits", t.payloadBits() - t.decodedBits());
        if (t.error() != null) {
            out.put("error", t.error());
            out.put("failurePath", t.failurePath());
            out.put("failureBit", t.failureBit());
            out.put("failureFieldStart", t.failureFieldStart());
        } else {
            // Padding = up to the next octet boundary (UPER fills it with 0s; non-zero = broken encoder).
            // Anything after that boundary is extra data the message does not account for.
            int padEnd = Math.min(t.payloadBits(), (t.decodedBits() + 7) / 8 * 8);
            String pad = t.bits(t.decodedBits(), padEnd);
            out.put("paddingBits", pad.length());
            out.put("padding", pad);
            if (pad.indexOf('1') >= 0) out.put("paddingNonZero", true);
            int extra = t.payloadBits() - padEnd;
            if (extra > 0) {
                out.put("extraBytes", extra / 8);
                out.put("extraHex", hexOf(t.bits(padEnd, t.payloadBits())));
            }
        }
        int violations = 0;
        boolean invalidIndex = false;
        java.util.Set<String> extended = new java.util.HashSet<>();   // paths whose extension bit was 1
        List<Map<String, Object>> entries = new ArrayList<>();
        for (a.codecs.uper.PerTrace.Entry e : t.entries()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("start", e.startBit);
            m.put("end", e.endBit);
            m.put("path", e.path);
            m.put("kind", e.kind.name());
            m.put("bits", t.bits(e.startBit, e.endBit));
            if (e.value != null) m.put("value", e.value);
            if (e.type != null) m.put("type", e.type);
            if (e.kind == a.codecs.uper.PerTrace.Kind.EXT_BIT && "true".equals(e.value)) extended.add(e.path);
            String v = e.violation != null ? e.violation : extended.contains(e.path) ? null : rangeViolation(e);
            if (e.violation != null && e.violation.startsWith("INVALID_INDEX")) invalidIndex = true;
            if (v != null) {
                m.put("violation", v);
                violations++;
            }
            entries.add(m);
        }
        out.put("constraintViolations", violations);
        out.put("entries", entries);
        if (t.error() == null && !invalidIndex) {   // with an invalid index the JER would show a default, not what was sent
            try {
                out.put("decoded", mapper.readTree(mapp.get().encode(decoded, Encoding.JSON).toText()));
            } catch (Exception ignored) { /* breakdown is what matters; JER is a convenience */ }
        }
        return out;
    }

    private static String hexOf(String bits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 8 <= bits.length(); i += 8)
            sb.append(String.format("%02x", Integer.parseInt(bits.substring(i, i + 8), 2)));
        return sb.toString();
    }

    private static final java.util.regex.Pattern RANGE =
            java.util.regex.Pattern.compile("\\((-?\\d+)\\.\\.(-?\\d+)(,\\.\\.\\.)?\\)");

    /**
     * A constrained value/count outside its declared range. UPER packs {@code (min..max)} into the bits needed
     * for the span, so e.g. 6 bits for {@code (1..62)} can still carry 63 or 64: the decoder accepts it, but no
     * valid encoder produces it. Values read through the extension (unconstrained) path carry no range here.
     */
    static String rangeViolation(a.codecs.uper.PerTrace.Entry e) {
        if (e.type == null || e.value == null) return null;
        if (e.kind != a.codecs.uper.PerTrace.Kind.VALUE && e.kind != a.codecs.uper.PerTrace.Kind.LENGTH) return null;
        java.util.regex.Matcher m = RANGE.matcher(e.type);
        if (!m.find()) return null;
        try {
            long min = Long.parseLong(m.group(1)), max = Long.parseLong(m.group(2));
            long val = Long.parseLong(e.value.trim().split("\\s+")[0]);
            if (val < min || val > max)
                return "OUT_OF_RANGE: " + val + " not in (" + min + ".." + max + ")";
        } catch (NumberFormatException ignored) { /* non-numeric value: nothing to check */ }
        return null;
    }

    // ---------------- generate ----------------

    /** Generates a sample; applies sticky fixups, then one-shot overrides (overrides win on conflict). */
    public EngineResult generate(String engineId, GenerateOptions opts) {
        CachedDef cd = access(engineId);
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
            return EngineResult.decodeError(clean(e));
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
            case "XER":  return Encoding.XML;   // XER (X.693) = el nombre público; XML es el enum interno de wind
            case "JER":  return Encoding.JSON;  // JER (X.697) = el nombre público; JSON es el enum interno de wind
            default:     return null;
        }
    }
}
