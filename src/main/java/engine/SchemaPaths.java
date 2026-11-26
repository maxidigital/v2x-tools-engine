package engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code set} paths of a message that match a search ("pedestrian", "speed limit", "cause"): walks a scratch
 * instance through every field, every CHOICE alternative and the first item of each list, so it finds what a FULL
 * sample does not show (a FULL sample picks one alternative per CHOICE). Each hit is one line: path, type, and its
 * values (range, unit, named numbers, labels, alternatives).
 */
final class SchemaPaths {

    static final int MAX_HITS = 40;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_NODES = 60_000;

    private final String[] terms;
    private final List<String> hits = new ArrayList<>();
    private final Deque<String> types = new ArrayDeque<>();
    private int total;
    private int nodes;

    private SchemaPaths(String query) {
        this.terms = query.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim().split("\\s+");
    }

    static String find(i.Element root, String query) {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query is required (a word to look for, e.g. 'pedestrian')");
        SchemaPaths sp = new SchemaPaths(query);
        sp.walk(root, "", 0);
        if (sp.hits.isEmpty())
            return "No field, alternative or value of this message matches '" + query.trim() + "'.";
        StringBuilder sb = new StringBuilder();
        sb.append(sp.total).append(sp.total == 1 ? " match" : " matches").append(" for '").append(query.trim()).append("'");
        if (sp.total > sp.hits.size()) sb.append(" (first ").append(sp.hits.size()).append("; refine the query for others)");
        sb.append(". Paths for generate's set (path=value):\n");
        for (String h : sp.hits) sb.append(h).append('\n');
        if (sp.nodes >= MAX_NODES) sb.append("(search stopped early: the message is very large)\n");
        return sb.toString().strip();
    }

    private void walk(i.Element e, String path, int depth) {
        if (e == null || depth > MAX_DEPTH || ++nodes > MAX_NODES) return;
        String type = e.name();
        boolean recursive = type != null && types.contains(type) && (e instanceof i.Sequence || e instanceof i.Choice);
        String detail = describe(e);
        if (!path.isEmpty()) consider(path, type, detail);
        if (recursive) return;
        types.push(type == null ? "" : type);
        try {
            if (e instanceof i.Choice) {
                i.Choice<?> c = (i.Choice<?>) e;
                i.Field[] fs = c.getFields();
                for (int j = 0; j < fs.length; j++) {
                    try {
                        c.setChoiceIndex(j);
                        walk(c.getElement(), join(path, fs[j].getFieldName()), depth + 1);
                    } catch (RuntimeException ignored) {
                        // an alternative that cannot be built: skip it
                    }
                }
            } else if (e instanceof i.Sequence) {
                i.Sequence<?> s = (i.Sequence<?>) e;
                for (int j = 0; j < s.length(); j++) {
                    i.Field f = s.field(j);
                    try {
                        walk(f.getElement(), join(path, f.getFieldName()), depth + 1);
                    } catch (RuntimeException ignored) {
                        // skip
                    }
                }
            } else if (e instanceof i.SequenceOf) {
                i.SequenceOf<?, ?> l = (i.SequenceOf<?, ?>) e;
                if (l.max() > 0) {
                    try {
                        walk(l.getElement(0), path + "[0]", depth + 1);
                    } catch (RuntimeException ignored) {
                        // skip
                    }
                }
            }
        } finally {
            types.pop();
        }
    }

    private void consider(String path, String type, String detail) {
        String hay = (path + " " + type + " " + detail).toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        String compact = hay.replace(" ", "");
        for (String t : terms)
            if (!hay.contains(t) && !compact.contains(t)) return;
        total++;
        if (hits.size() < MAX_HITS) hits.add(path + "  " + (type == null ? "" : type + " ") + detail);
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    static String describe(i.Element e) {
        if (e instanceof a.generic.GenericDouble g) {
            String u = g.unitName() == null ? "" : ", unit " + g.unitStep() + " " + g.unitName().trim();
            return "number (encoded integer" + u + ")";
        }
        if (e instanceof i.Integer<?> n) {
            StringBuilder sb = new StringBuilder("INTEGER");
            if (n instanceof a.generic.GenericInteger gi) {
                sb.append(" (").append(gi.min()).append("..").append(gi.max()).append(")");
                Map<String, Long> named = gi.namedValues();
                if (!named.isEmpty()) sb.append(" named: ").append(clip(String.join(", ", named.keySet())));
            }
            return sb.toString();
        }
        if (e instanceof i.Enumerated<?> en)
            return "ENUMERATED: " + (en.labels() == null ? "" : clip(String.join(", ", en.labels())));
        if (e instanceof i.Boolean) return "BOOLEAN";
        if (e instanceof i.BitString) {
            String[] names = e instanceof a.generic.GenericBitString gb ? gb.bitNames() : ((i.BitString<?>) e).labels();
            return "BIT STRING" + (names == null || names.length == 0 ? "" : " bits: " + clip(String.join(", ", names)));
        }
        if (e instanceof i.OctetString) return "OCTET STRING (hex)";
        if (e instanceof i.Text) return "string";
        if (e instanceof i.Choice<?> c) {
            StringBuilder sb = new StringBuilder();
            for (i.Field f : c.getFields()) sb.append(sb.length() == 0 ? "" : " | ").append(f.getFieldName());
            return "CHOICE: " + clip(sb.toString());
        }
        if (e instanceof i.SequenceOf<?, ?> l) return "list (" + l.min() + ".." + l.max() + " items)";
        if (e instanceof i.Sequence) return "SEQUENCE";
        return "";
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
