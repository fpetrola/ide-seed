package ide.ui;

import javax.swing.UIManager;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How big everything is written, the whole window at once: one factor over the look's own sizes, the code's included,
 * changed by steps and remembered in {@code ~/.ideseed/zoom.txt}.
 */
final class Zoom {

    /** The factor a window starts with when none is remembered. */
    static final float USUAL = 0.55f;

    private static final float STEP = 1.1f;
    private static final Path REMEMBERED = Path.of(System.getProperty("user.home"), ".ideseed", "zoom.txt");

    private static float factor = USUAL;
    private static Font base;

    private Zoom() {
    }

    /** Takes the look's own font as what the factor is over, and applies the remembered factor. */
    static void start() {
        base = UIManager.getFont("defaultFont");
        try {
            if (Files.isRegularFile(REMEMBERED)) factor = Float.parseFloat(Files.readString(REMEMBERED).strip());
        } catch (IOException | NumberFormatException unreadable) {
            factor = USUAL;
        }
        apply();
    }

    /** Bigger ({@code steps} > 0), smaller, or as usual ({@code 0}). */
    static void by(int steps) {
        factor = steps == 0 ? USUAL : Math.max(0.3f, Math.min(3f, factor * (float) Math.pow(STEP, steps)));
        apply();
        try {
            Files.createDirectories(REMEMBERED.getParent());
            Files.writeString(REMEMBERED, Float.toString(factor));
        } catch (IOException unwritable) {
            // the next window starts as usual
        }
    }

    /** A size of the look's, at the current factor. */
    static float of(float size) {
        return Math.max(6f, size * factor);
    }

    /** The code's font at a size of the look's. */
    static Font code(float size) {
        return new Font("JetBrains Mono", Font.PLAIN, Math.round(of(size)));
    }

    private static void apply() {
        if (base != null) UIManager.put("defaultFont", base.deriveFont(of(base.getSize2D())));
    }
}
