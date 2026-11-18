package engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

/**
 * {@code compat: "asn1c"} (load metadata): encode the way asn1c (Vanetza and other asn1c-based stacks) does, where
 * it departs from X.691. By default the engine follows the standard (as ObjSys and asn1tools do). Rewrites the
 * tree's constraints before compiling it:
 * <ol>
 * <li><b>Serially applied constraint</b> ({@code withComponents} tag, e.g. CPM {@code ConstraintWrappedCpmContainers},
 * and a field constrained in place like {@code PathPredicted2.pathPredicted}, which gets its own copy of the type):
 * asn1c lets the last (non-extensible, not PER-visible) constraint remove
 * the extensibility of the SIZE/range → no extension bit, root only.</li>
 * <li><b>Extension additions</b> ({@code SIZE(1..16,...,17..40)}, {@code (1..32767,...,8388607)}): asn1c merges
 * them into the root → {@code (1..40,...)}, still extensible.</li>
 * </ol>
 */
final class Asn1cCompat {

    static final String ASN1C = "asn1c";
    private static final String EXTBIT_TAG = "EXTBIT";

    private Asn1cCompat() {
    }

    static String apply(String treeJson, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(treeJson);
            JsonNode t0 = root.path("tree").path("types");
            final JsonNode types = t0.isObject() ? t0 : root.path("types");
            if (!types.isObject()) return treeJson;
            ObjectNode typesObj = (ObjectNode) types;
            // a field constrained in place (PathPredicted2.pathPredicted PathPredicted (WITH COMPONENT ...)) gets its
            // own copy of the type, from the original (unmerged) constraint, without the extension bit
            java.util.List<String[]> serialFields = new java.util.ArrayList<>();
            types.fields().forEachRemaining(e -> e.getValue().path("fields").forEach(f -> {
                if (onElements(f.path("tags").path("withComponents").asText(null)) && f.hasNonNull("type")
                        && types.path(f.get("type").asText()).get("range") instanceof ObjectNode)
                    serialFields.add(new String[]{e.getKey(), f.path("name").asText()});
            }));
            for (String[] sf : serialFields) {
                for (JsonNode f : types.get(sf[0]).path("fields")) {
                    if (!f.path("name").asText().equals(sf[1])) continue;
                    String orig = f.get("type").asText(), copy = orig + "~asn1c";
                    if (!typesObj.has(copy)) {
                        ObjectNode c = ((ObjectNode) types.get(orig)).deepCopy();
                        ObjectNode ct = c.get("tags") instanceof ObjectNode o ? o : c.putObject("tags");
                        ct.put("withComponents", f.path("tags").path("withComponents").asText());
                        typesObj.set(copy, c);
                    }
                    ((ObjectNode) f).put("type", copy);
                }
            }
            Iterator<Map.Entry<String, JsonNode>> it = types.fields();
            while (it.hasNext()) {
                JsonNode t = it.next().getValue();
                if (t.isObject()) adjust((ObjectNode) t);
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("asn1c compat: cannot rewrite tree: " + e.getMessage());
        }
    }

    private static void adjust(ObjectNode t) {
        if (!(t.get("range") instanceof ObjectNode r)) return;
        JsonNode tags = t.get("tags");
        boolean serial = tags != null && onElements(tags.path("withComponents").asText(null));
        if (serial) {
            r.put("extBit", false);
            r.put("extendedRange", false);
            r.remove("extLower");
            r.remove("extUpper");
            if (tags instanceof ObjectNode o) o.remove(EXTBIT_TAG);
        } else if (r.path("extendedRange").asBoolean(false) && r.has("extLower") && r.has("extUpper")) {
            BigInteger lo = num(r, "lower"), hi = num(r, "upper"), elo = num(r, "extLower"), ehi = num(r, "extUpper");
            if (lo == null || hi == null || elo == null || ehi == null) return;
            r.put("lower", lo.min(elo).toString());
            r.put("upper", hi.max(ehi).toString());
            r.put("extendedRange", false);
            r.remove("extLower");
            r.remove("extUpper");
        }
    }

    /**
     * A serially applied constraint on a list's elements: {@code WITH COMPONENT} (singular). {@code WITH COMPONENTS}
     * alone constrains a SEQUENCE's fields (the parser may attach a SEQUENCE-level one to its last field, e.g.
     * {@code MapemElementReference}); asn1c keeps the extensibility there.
     */
    private static final java.util.regex.Pattern ON_ELEMENTS = java.util.regex.Pattern.compile("WITH\\s+COMPONENT(?!S)");

    static boolean onElements(String withComponents) {
        return withComponents != null && ON_ELEMENTS.matcher(withComponents).find();
    }

    private static BigInteger num(JsonNode r, String f) {
        try {
            return new BigInteger(r.path(f).asText().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
