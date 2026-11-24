package engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Resolves a path-language expression against a built message instance and sets a leaf value.
 *
 * Path = steps separated by ':' (or '.', the APL spelling: ASN.1 identifiers never contain either). Each step names a SEQUENCE field or a CHOICE alternative, with an
 * optional [n] to index into a SEQUENCE OF. Descending has side effects (this is why it's a "language",
 * not a plain navigator): a CHOICE step selects that alternative ({@code setChoiceIndex}); an OPTIONAL
 * field is forced present (its element is lazily created on access).
 *
 *   header:messageId
 *   container:sensorInformationContainer:sensorId
 *   cpmContainers:perceivedObjectContainer:perceivedObjects[0]:objectId
 *
 * Used for both load fixups (validated against a sample at load) and generate overrides (applied per call).
 */
final class PathResolver {

    /** Thrown when a path cannot be resolved against the structure. */
    static class PathException extends RuntimeException {
        PathException(String msg) { super(msg); }
    }

    private PathResolver() {}

    private static final class Step {
        final String name;
        final int index;   // -1 if no [n]
        Step(String name, int index) { this.name = name; this.index = index; }
    }

    /** Navigate the path and throw if it cannot be resolved (no value set). */
    static void validate(i.Element root, String path) {
        navigate(root, parse(path), e -> { });
    }

    /** Navigate the path (with its side effects) and set the leaf to {@code value}. */
    /**
     * {@code init} receives every element the path brings into existence (an OPTIONAL made present, new SEQUENCE OF
     * items, a newly chosen CHOICE alternative), so it is not left empty: e.g. a new lane must get its mandatory
     * node list at its minimum size, or the message does not encode.
     */
    static void apply(i.Element root, String path, Object value, Consumer<i.Element> init) {
        i.Element leaf = navigate(root, parse(path), init);
        setLeaf(leaf, value, path);
    }

    private static List<Step> parse(String path) {
        if (path == null || path.isBlank())
            throw new PathException("empty path");
        List<Step> steps = new ArrayList<>();
        for (String raw : path.split("[:.]")) {
            String seg = raw.trim();
            if (seg.isEmpty())
                throw new PathException("empty step in path '" + path + "'");
            int index = -1;
            int br = seg.indexOf('[');
            if (br >= 0) {
                if (!seg.endsWith("]"))
                    throw new PathException("malformed index in '" + seg + "'");
                try {
                    index = Integer.parseInt(seg.substring(br + 1, seg.length() - 1).trim());
                } catch (NumberFormatException e) {
                    throw new PathException("non-numeric index in '" + seg + "'");
                }
                seg = seg.substring(0, br).trim();
            }
            steps.add(new Step(seg, index));
        }
        return steps;
    }

    private static i.Element navigate(i.Element root, List<Step> steps, Consumer<i.Element> init) {
        i.Element cur = root;
        for (Step step : steps) {
            cur = descend(cur, step.name, init);
            if (step.index >= 0) {
                if (!(cur instanceof i.SequenceOf))
                    throw new PathException("index [" + step.index + "] on non-SEQUENCE OF at '" + step.name + "'");
                i.SequenceOf<?, ?> list = (i.SequenceOf<?, ?>) cur;
                if (step.index >= list.max())
                    throw new PathException("index [" + step.index + "] out of range at '" + step.name
                            + "' (at most " + list.max() + " items)");
                int before = list.getSize();
                cur = list.getElement(step.index);            // creates the items up to index
                for (int k = before; k <= step.index; k++)
                    init.accept(list.getElement(k));
            }
        }
        return cur;
    }

    /** Descend one named level: SEQUENCE field by name, or CHOICE alternative by name (selecting it). */
    private static i.Element descend(i.Element cur, String name, Consumer<i.Element> init) {
        if (cur instanceof i.Choice) {
            i.Choice<?> cho = (i.Choice<?>) cur;
            i.Field[] fields = cho.getFields();
            for (int j = 0; j < fields.length; j++) {
                if (name.equals(fields[j].getFieldName())) {
                    if (cho.getChoiceIndex() == j)
                        return cho.getElement();
                    cho.setChoiceIndex(j);          // select this alternative
                    init.accept(cho.getElement());
                    return cho.getElement();
                }
            }
            throw new PathException("CHOICE alternative '" + name + "' not found; alternatives: " + names(fields));
        }
        if (cur instanceof i.Sequence) {
            i.Sequence<?> seq = (i.Sequence<?>) cur;
            for (int j = 0; j < seq.length(); j++) {
                if (name.equals(seq.field(j).getFieldName())) {
                    i.Field f = seq.field(j);
                    boolean present = f.isPresent();
                    i.Element e = f.getElement();       // forces present (lazy create) for optional fields
                    if (!present)
                        init.accept(e);
                    return e;
                }
            }
            i.Field[] fs = new i.Field[seq.length()];
            for (int j = 0; j < fs.length; j++) fs[j] = seq.field(j);
            throw new PathException("field '" + name + "' not found; fields here: " + names(fs));
        }
        throw new PathException("cannot descend '" + name + "' on a non-SEQUENCE/CHOICE element");
    }

    private static void setLeaf(i.Element leaf, Object value, String path) {
        if (value == null)
            throw new PathException("null value for '" + path + "'");
        if (leaf instanceof i.Integer) {
            ((i.Integer<?>) leaf).setValue(asLong(value, path));
        } else if (leaf instanceof i.Boolean) {
            ((i.Boolean) leaf).setValue(value instanceof java.lang.Boolean ? (java.lang.Boolean) value
                    : java.lang.Boolean.parseBoolean(value.toString()));
        } else if (leaf instanceof i.Enumerated) {
            setEnumerated((i.Enumerated<?>) leaf, value, path);
        } else if (leaf instanceof i.Text) {
            ((i.Text) leaf).setValue(value.toString());
        } else if (leaf instanceof i.Double && leaf instanceof i.Value) {
            setReal((i.Double<?>) leaf, value, path);
        } else if (leaf instanceof i.BitString) {
            setBits((i.BitString<?>) leaf, value.toString().trim(), path);
        } else if (leaf instanceof i.OctetString) {
            setOctets((i.OctetString<?>) leaf, value.toString().trim(), path);
        } else {
            throw new PathException("unsupported leaf type for '" + path + "': "
                    + leaf.getClass().getSimpleName());
        }
    }

    /** By identifier (as JER shows it, e.g. {@code passengerCar}) or by number. */
    private static void setEnumerated(i.Enumerated<?> e, Object value, String path) {
        String v = value.toString().trim();
        String[] labels = e.labels();
        if (labels != null)
            for (int k = 0; k < labels.length; k++)
                if (v.equals(labels[k])) {
                    e.setValue(e.values()[k]);
                    return;
                }
        try {
            e.setValue(Integer.parseInt(v));
        } catch (NumberFormatException ex) {
            throw new PathException("'" + v + "' is not a value of '" + path + "'"
                    + (labels == null ? "" : "; one of " + String.join(", ", labels)));
        }
    }

    /**
     * The framework's artificial REAL (an INTEGER scaled by its {@code @unit}): set the encoded integer, as JER
     * shows it (latitude {@code 413875000} = 41.3875°). Decimals are refused: the scale is not applied here.
     */
    private static void setReal(i.Double<?> d, Object value, String path) {
        String v = value.toString().trim();
        try {
            ((i.Value) d).longValue(Long.parseLong(v));
        } catch (NumberFormatException ex) {
            throw new PathException("expected the encoded integer for '" + path + "' (as JER shows it, e.g. "
                    + "413875000 for 41.3875°), got '" + v + "'");
        }
    }

    private static String names(i.Field[] fields) {
        StringBuilder sb = new StringBuilder();
        for (i.Field f : fields) sb.append(sb.length() == 0 ? "" : ", ").append(f.getFieldName());
        return sb.toString();
    }

    /**
     * '0'/'1' characters, as JER shows them ({@code "10"}), optionally as ASN.1 {@code '10'B}; or the named bits to
     * set, {@code {ingressPath,egressPath}} (the others 0).
     */
    private static void setBits(i.BitString<?> b, String v, String path) {
        String bits = v.matches("'[01]*'B") ? v.substring(1, v.length() - 2) : v;
        if (v.startsWith("{") && v.endsWith("}")) bits = namedBits(b, v.substring(1, v.length() - 1), path);
        if (!bits.matches("[01]*"))
            throw new PathException("expected bits (e.g. \"0110\") for '" + path + "', got '" + v + "'");
        checkLength(bits.length(), b.getLength(), b.min(), b.max(), b.indefiniteLength(), "bits", path);
        for (int k = 0; k < bits.length(); k++)
            b.set(k, bits.charAt(k) == '1');
    }

    private static String namedBits(i.BitString<?> b, String list, String path) {
        String[] labels = b.labels();
        if (labels == null || labels.length == 0)
            throw new PathException("'" + path + "' has no named bits: give them as '0'/'1' characters");
        java.util.List<Integer> on = new ArrayList<>();
        for (String raw : list.split(",")) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            int k = java.util.Arrays.asList(labels).indexOf(n);
            if (k < 0)
                throw new PathException("'" + n + "' is not a bit of '" + path + "'; bits: " + String.join(", ", labels));
            on.add(k);
        }
        int len = Math.max(Math.max(b.min(), b.getLength()), on.stream().mapToInt(x -> x + 1).max().orElse(0));
        char[] c = new char[len];
        java.util.Arrays.fill(c, '0');
        for (int k : on) c[k] = '1';
        return new String(c);
    }

    /** Hex, as JER shows it ({@code "0a1b"}), optionally as ASN.1 {@code '0A1B'H}. */
    private static void setOctets(i.OctetString<?> o, String v, String path) {
        String hex = v.matches("'[0-9A-Fa-f]*'H") ? v.substring(1, v.length() - 2) : v;
        if (!hex.matches("([0-9A-Fa-f]{2})*"))
            throw new PathException("expected hex bytes (e.g. \"0a1b\") for '" + path + "', got '" + v + "'");
        int n = hex.length() / 2;
        checkLength(n, o.getLength(), o.min(), o.max(), o.indefiniteLength(), "bytes", path);
        for (int k = 0; k < n; k++)
            o.setValue(k, (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16));
    }

    /** Within SIZE, and not shorter than what is there (the framework can grow a string, not shrink it). */
    private static void checkLength(int n, int current, int min, int max, boolean unbounded, String unit, String path) {
        if (n < min || (!unbounded && n > max))
            throw new PathException("'" + path + "' takes " + (min == max ? min : min + ".." + max) + " " + unit
                    + ", got " + n);
        if (n < current)
            throw new PathException("'" + path + "' already has " + current + " " + unit
                    + " and cannot be shortened: generate with minimal");
    }

    private static long asLong(Object value, String path) {
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new PathException("expected a number for '" + path + "', got '" + value + "'");
        }
    }
}
