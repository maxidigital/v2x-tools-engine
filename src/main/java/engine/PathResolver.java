package engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a path-language expression against a built message instance and sets a leaf value.
 *
 * Path = steps separated by ':'. Each step names a SEQUENCE field or a CHOICE alternative, with an
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
        for (String raw : path.split(":")) {
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
            ((i.Enumerated) leaf).setValue((int) asLong(value, path));
        } else if (leaf instanceof i.Text) {
            ((i.Text) leaf).setValue(value.toString());
        } else {
            throw new PathException("unsupported leaf type for '" + path + "': "
                    + leaf.getClass().getSimpleName());
        }
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
