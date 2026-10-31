package ide.core;

import java.util.List;

/** Line diff (longest common subsequence) printed as unified hunks without context. */
public final class TextDiff {

    private TextDiff() {
    }

    /** Empty if equal, else {@code --- path / +++ path} and one {@code @@} hunk per changed run of lines. */
    public static String unified(String path, String before, String after) {
        if (before.equals(after)) return "";
        var a = before.lines().toList();
        var b = after.lines().toList();
        var lcs = new int[a.size() + 1][b.size() + 1];
        for (var i = a.size() - 1; i >= 0; i--) {
            for (var j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        var out = new StringBuilder("--- ").append(path).append("\n+++ ").append(path).append('\n');
        int i = 0, j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                i++;
                j++;
                continue;
            }
            int i0 = i, j0 = j;
            while ((i < a.size() || j < b.size()) && !(i < a.size() && j < b.size() && a.get(i).equals(b.get(j)))) {
                if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) j++;
                else i++;
            }
            out.append("@@ -").append(range(i0, i - i0)).append(" +").append(range(j0, j - j0)).append(" @@\n");
            lines(out, '-', a.subList(i0, i));
            lines(out, '+', b.subList(j0, j));
        }
        return out.toString();
    }

    private static String range(int start, int count) {
        if (count == 1) return String.valueOf(start + 1);
        return (count == 0 ? start : start + 1) + "," + count;
    }

    private static void lines(StringBuilder out, char sign, List<String> lines) {
        lines.forEach(line -> out.append(sign).append(line).append('\n'));
    }
}
