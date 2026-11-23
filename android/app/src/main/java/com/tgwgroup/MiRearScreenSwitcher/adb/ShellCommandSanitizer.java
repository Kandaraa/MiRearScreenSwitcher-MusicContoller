package com.tgwgroup.MiRearScreenSwitcher.adb;

import java.util.regex.Pattern;

/**
 * Security hardening utilities for privileged shell commands executed by TaskService / MrssShellDaemon.
 * Prevents command injection via external mrss:// URIs (Audit F5) and notification payloads (Audit F6).
 */
public final class ShellCommandSanitizer {
    private static final Pattern PACKAGE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private static final Pattern COMPONENT_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.]*/\\.?[a-zA-Z0-9_.$]+$");

    private ShellCommandSanitizer() {}

    /**
     * Strictly validates an Android package name against a safe whitelist regex.
     */
    public static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255) {
            return false;
        }
        return PACKAGE_NAME_PATTERN.matcher(pkg).matches();
    }

    /**
     * Strictly validates an Android flattened ComponentName ("pkg/cls" or "pkg/.cls").
     */
    public static boolean isValidComponentName(String component) {
        if (component == null || component.isEmpty() || component.length() > 512) {
            return false;
        }
        return COMPONENT_NAME_PATTERN.matcher(component).matches();
    }

    /**
     * Escapes an arbitrary untrusted string as a single-quoted POSIX shell literal ('...').
     * Inside POSIX single quotes, $, `, ", \, ;, |, &, >, <, and newlines are 100% literal.
     * Any embedded single quote ' is replaced with '\'' and null bytes are stripped.
     */
    public static String escapeShellSingleQuoted(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "''";
        }
        String cleaned = raw.replace("\0", "");
        return "'" + cleaned.replace("'", "'\\''") + "'";
    }
}

