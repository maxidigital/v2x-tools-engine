package engine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Physical units in {@code set} values: the spellings people (and AIs) write for the units ETSI schemas declare in
 * {@code @unit}, and the conversion between units of the same quantity ({@code 50 km/h} into a field in m/s).
 */
final class Units {

    private record U(String quantity, double toBase) {
    }

    private static final Map<String, U> UNITS = new HashMap<>();

    private static void add(String quantity, double toBase, String... names) {
        for (String n : names) UNITS.put(n, new U(quantity, toBase));
    }

    static {
        add("angle", 1, "degree", "degrees", "deg", "°");
        add("angle", 180 / Math.PI, "radian", "radians", "rad");
        add("speed", 1, "m/s", "metre per second", "meter per second", "metres per second", "meters per second", "mps", "m s-1");
        add("speed", 0.01, "cm/s", "centimetre per second", "centimeter per second", "centimetres per second");
        add("speed", 1 / 3.6, "km/h", "kmh", "kph", "kilometre per hour", "kilometer per hour", "kilometres per hour");
        add("accel", 1, "m/s2", "m/s^2", "m/s²", "metre per second squared", "meter per second squared", "m s-2");
        add("accel", 0.01, "cm/s2", "cm/s^2", "cm/s²", "centimetre per second squared");
        add("angspeed", 1, "degree per second", "degrees per second", "deg/s", "degree/s", "°/s");
        add("length", 1, "m", "metre", "meter", "metres", "meters");
        add("length", 0.1, "dm", "decimetre", "decimeter", "decimetres");
        add("length", 0.01, "cm", "centimetre", "centimeter", "centimetres", "centimeters");
        add("length", 0.001, "mm", "millimetre", "millimeter", "millimetres", "millimeters");
        add("length", 1000, "km", "kilometre", "kilometer", "kilometres", "kilometers");
        add("time", 1, "s", "second", "seconds", "sec");
        add("time", 0.001, "ms", "millisecond", "milliseconds");
        add("time", 60, "min", "minute", "minutes");
        add("time", 3600, "h", "hour", "hours");
    }

    private Units() {
    }

    private static U lookup(String unit) {
        String n = unit.trim().replaceAll("[.;,:]+$", "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return UNITS.get(n);
    }

    /** Multiply a value in {@code from} by this to get it in {@code to}; null when they are not the same quantity. */
    static Double factor(String from, String to) {
        U f = lookup(from), t = lookup(to);
        if (f == null || t == null || !f.quantity.equals(t.quantity)) return null;
        return f.toBase / t.toBase;
    }
}
