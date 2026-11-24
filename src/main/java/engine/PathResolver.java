package engine;

import java.util.ArrayList;
import java.util.List;

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
        navigate(root, parse(path));
    }

    /** Navigate the path (with its side effects) and set the leaf to {@code value}. */
    static void apply(i.Element root, String path, Object value) {
        i.Element leaf = navigate(root, parse(path));
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

    private static i.Element navigate(i.Element root, List<Step> steps) {
        i.Element cur = root;
        for (Step step : steps) {
            cur = descend(cur, step.name);
            if (step.index >= 0) {
                if (!(cur instanceof i.SequenceOf))
                    throw new PathException("index [" + step.index + "] on non-SEQUENCE OF at '" + step.name + "'");
                cur = ((i.SequenceOf<?, ?>) cur).getElement(step.index);
            }
        }
        return cur;
    }

    /** Descend one named level: SEQUENCE field by name, or CHOICE alternative by name (selecting it). */
    private static i.Element descend(i.Element cur, String name) {
        if (cur instanceof i.Choice) {
            i.Choice<?> cho = (i.Choice<?>) cur;
            i.Field[] fields = cho.getFields();
            for (int j = 0; j < fields.length; j++) {
                if (name.equals(fields[j].getFieldName())) {
                    cho.setChoiceIndex(j);          // select this alternative
                    return cho.getElement();
                }
            }
            throw new PathException("CHOICE alternative '" + name + "' not found");
        }
        if (cur instanceof i.Sequence) {
            i.Sequence<?> seq = (i.Sequence<?>) cur;
            for (int j = 0; j < seq.length(); j++) {
                if (name.equals(seq.field(j).getFieldName())) {
                    return seq.field(j).getElement();   // forces present (lazy create) for optional fields
                }
            }
            throw new PathException("field '" + name + "' not found");
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
     * The framework's artificial REAL (an INTEGER scaled by its {@code @unit}): an integer is the encoded value, as
     * JER shows it (latitude {@code 413875000}); a number with a decimal point is the value in the unit.
     */
    private static void setReal(i.Double<?> d, Object value, String path) {
        String v = value.toString().trim();
        try {
            if (v.contains(".") || v.contains("e") || v.contains("E")) d.setValue(Double.parseDouble(v));
            else ((i.Value) d).longValue(Long.parseLong(v));
        } catch (NumberFormatException ex) {
            throw new PathException("expected a number for '" + path + "', got '" + v + "'");
        }
    }

    /** '0'/'1' characters, as JER shows them ({@code "10"}), optionally as ASN.1 {@code '10'B}. */
    private static void setBits(i.BitString<?> b, String v, String path) {
        String bits = v.matches("'[01]*'B") ? v.substring(1, v.length() - 2) : v;
        if (!bits.matches("[01]*"))
            throw new PathException("expected bits (e.g. \"0110\") for '" + path + "', got '" + v + "'");
        checkLength(bits.length(), b.getLength(), b.min(), b.max(), b.indefiniteLength(), "bits", path);
        for (int k = 0; k < bits.length(); k++)
            b.set(k, bits.charAt(k) == '1');
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
