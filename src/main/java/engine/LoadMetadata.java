package engine;

import java.util.ArrayList;
import java.util.List;

/** Metadata envelope passed alongside the tree at load: human description + sticky fixups. */
public class LoadMetadata {
    public String description;
    public List<PathValue> fixups = new ArrayList<>();
    /** null/"standard" = X.691 (default); "asn1c" = encode like asn1c/Vanetza (see {@link Asn1cCompat}). */
    public String compat;
}
