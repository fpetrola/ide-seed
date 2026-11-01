package ide.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.Map;

/**
 * The window's icons, drawn as IntelliJ draws them: a colored disc with the letter of a kind of element (C, I, E, m, f,
 * v, p), a bulb for a quick fix, a wrench for a refactoring, marks for problems and for the steps of a change.
 */
final class Icons {

    /** Drawn on a 16-pixel square, shown at the size the zoom gives the text, a little larger. */
    private static int size() {
        return Math.max(10, Math.round(Zoom.of(13) * 1.6f));
    }

    private static final Map<String, Icon> KINDS = Map.ofEntries(
            Map.entry("CLASS", letter("C", new Color(0x3F8BD8))),
            Map.entry("INTERFACE", letter("I", new Color(0x4FA85C))),
            Map.entry("ENUM", letter("E", new Color(0xD6893C))),
            Map.entry("METHOD", letter("m", new Color(0xD8586E))),
            Map.entry("CONSTRUCTOR", letter("m", new Color(0xD8586E))),
            Map.entry("FIELD", letter("f", new Color(0xC79A2E))),
            Map.entry("PARAM", letter("p", new Color(0x8C8C8C))),
            Map.entry("LOCAL", letter("v", new Color(0x8C8C8C))),
            Map.entry("PACKAGE", folder()),
            Map.entry("FIX", bulb()),
            Map.entry("REFACTORING", wrench()),
            Map.entry("ERROR", mark(new Color(0xE05555), "!")),
            Map.entry("STEP", dot(new Color(0x6A9FD8))),
            Map.entry("PRIMITIVE", dot(new Color(0x5FB865))),
            Map.entry("QUESTION", mark(new Color(0x6A9FD8), "?")),
            Map.entry("ANSWER", mark(new Color(0x5FB865), "✓")),
            Map.entry("DROPPED", mark(new Color(0xE05555), "×")),
            Map.entry("ASSUMED", mark(new Color(0xC79A2E), "!")),
            Map.entry("NOTE", dot(new Color(0x8C8C8C))));

    private Icons() {
    }

    /** The icon of a kind of element, or of a thing the window shows; a gray dot for what it does not know. */
    static Icon of(String kind) {
        return KINDS.getOrDefault(kind, dot(new Color(0x8C8C8C)));
    }

    private interface Painting {
        void paint(Graphics2D g);
    }

    private static Icon icon(Painting painting) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.translate(x, y);
                g2.scale(size() / 16.0, size() / 16.0);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                painting.paint(g2);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size();
            }

            @Override
            public int getIconHeight() {
                return size();
            }
        };
    }

    private static Icon letter(String letter, Color color) {
        return icon(g -> {
            g.setColor(color);
            g.fillOval(1, 1, 14, 14);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            var metrics = g.getFontMetrics();
            g.drawString(letter, 8 - metrics.stringWidth(letter) / 2, 12);
        });
    }

    private static Icon mark(Color color, String sign) {
        return icon(g -> {
            g.setColor(color);
            g.fillOval(2, 2, 12, 12);
            g.setColor(new Color(0x2B2B2B));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            var metrics = g.getFontMetrics();
            g.drawString(sign, 8 - metrics.stringWidth(sign) / 2, 12);
        });
    }

    private static Icon dot(Color color) {
        return icon(g -> {
            g.setColor(color);
            g.fillOval(5, 5, 6, 6);
        });
    }

    private static Icon folder() {
        return icon(g -> {
            g.setColor(new Color(0x87939A));
            g.fillRoundRect(1, 3, 6, 3, 2, 2);
            g.fillRoundRect(1, 5, 14, 9, 3, 3);
        });
    }

    private static Icon bulb() {
        return icon(g -> {
            g.setColor(new Color(0xF2C55C));
            g.fillOval(3, 1, 10, 10);
            g.fillRect(6, 10, 4, 2);
            g.setColor(new Color(0xA0A0A0));
            g.fillRect(6, 12, 4, 2);
        });
    }

    private static Icon wrench() {
        return icon(g -> {
            g.setColor(new Color(0x9AA7B0));
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(4, 12, 10, 6);
            var head = new Path2D.Double();
            head.append(new java.awt.geom.Arc2D.Double(8, 2, 7, 7, 200, 290, java.awt.geom.Arc2D.OPEN), false);
            g.draw(head);
        });
    }
}
