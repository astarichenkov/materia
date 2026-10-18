package com.lectureprocessor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Small line-based diff used by the "compare versions" view. It is deterministic
 * and bounded so a very long document cannot exhaust memory.
 */
public final class LineDiff {

    public enum Type { SAME, ADDED, REMOVED }

    public record Line(Type type, String text) {
    }

    private static final int MAX_LINES = 4000;

    private LineDiff() {
    }

    public static List<Line> compare(String before, String after) {
        String[] left = lines(before);
        String[] right = lines(after);
        int[][] lcs = new int[left.length + 1][right.length + 1];
        for (int i = left.length - 1; i >= 0; i--) {
            for (int j = right.length - 1; j >= 0; j--) {
                lcs[i][j] = left[i].equals(right[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.length && j < right.length) {
            if (left[i].equals(right[j])) {
                result.add(new Line(Type.SAME, left[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Line(Type.REMOVED, left[i++]));
            } else {
                result.add(new Line(Type.ADDED, right[j++]));
            }
        }
        while (i < left.length) {
            result.add(new Line(Type.REMOVED, left[i++]));
        }
        while (j < right.length) {
            result.add(new Line(Type.ADDED, right[j++]));
        }
        return result;
    }

    private static String[] lines(String value) {
        if (value == null) {
            return new String[0];
        }
        String[] all = value.replace("\r\n", "\n").split("\n", -1);
        if (all.length <= MAX_LINES) {
            return all;
        }
        String[] truncated = new String[MAX_LINES + 1];
        System.arraycopy(all, 0, truncated, 0, MAX_LINES);
        truncated[MAX_LINES] = "… (документ усечён для сравнения)";
        return truncated;
    }
}
