package com.lectureprocessor.codereview.code;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal glob matcher supporting {@code **}, {@code *} and {@code ?}. Used to
 * exclude secrets, build output and data directories from source indexing.
 */
public final class PathGlob {

    private PathGlob() {
    }

    public static boolean matchesAny(String path, List<String> patterns) {
        if (path == null || patterns == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && matches(normalized, pattern.strip())) {
                return true;
            }
        }
        return false;
    }

    public static boolean startsWithAny(String path, List<String> prefixes) {
        if (path == null || prefixes == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && normalized.startsWith(prefix.strip().replace('\\', '/'))) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(String path, String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            // `**/` matches zero or more leading directories.
                            regex.append("(?:.*/)?");
                            i++;
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(path).matches();
    }
}
