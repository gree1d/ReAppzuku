package com.northmendo.Appzuku;

final class ForegroundAppResolver {
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private ForegroundAppResolver() {
    }

    static String resolveKillableForegroundPackage(ShellManager shellManager, String ownPackageName) {
        String packageName = resolveForegroundPackage(shellManager);
        if (!isKillablePackage(packageName, ownPackageName)) {
            return null;
        }
        return packageName;
    }

    private static String resolveForegroundPackage(ShellManager shellManager) {
        String dumpOutput = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        String packageName = extractPackageFromActivityDump(dumpOutput);
        if (packageName != null) {
            return packageName;
        }

        String windowOutput = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys window | grep mCurrentFocus");
        packageName = extractPackageFromWindowDump(windowOutput);
        if (packageName != null) {
            return packageName;
        }

        String topOutput = shellManager.runShellCommandAndGetFullOutput("cmd activity get-top-activity");
        return extractPackageFromTopActivity(topOutput);
    }

    private static boolean isKillablePackage(String packageName, String ownPackageName) {
        return isValidPackageName(packageName)
                && !SYSTEM_UI_PACKAGE.equals(packageName)
                && ownPackageName != null
                && !ownPackageName.equals(packageName);
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.contains(".")
                && !packageName.isEmpty()
                && Character.isLetter(packageName.charAt(0));
    }

    private static String extractPackageFromActivityDump(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }

        for (String line : output.split("\n")) {
            if (line.contains(SYSTEM_UI_PACKAGE)) {
                continue;
            }

            String[] parts = line.trim().split("\\s+");
            for (String part : parts) {
                if (!part.contains("/")) {
                    continue;
                }

                String potentialPkg = part.split("/")[0];
                if (isValidPackageName(potentialPkg)) {
                    return potentialPkg;
                }
            }
        }
        return null;
    }

    private static String extractPackageFromWindowDump(String output) {
        if (output == null || output.isEmpty() || output.contains(SYSTEM_UI_PACKAGE)) {
            return null;
        }

        int slashIndex = output.indexOf("/");
        if (slashIndex <= 0) {
            return null;
        }

        int start = slashIndex - 1;
        while (start > 0 && (Character.isLetterOrDigit(output.charAt(start - 1))
                || output.charAt(start - 1) == '.')) {
            start--;
        }

        String potentialPkg = output.substring(start, slashIndex);
        if (isValidPackageName(potentialPkg)) {
            return potentialPkg;
        }
        return null;
    }

    private static String extractPackageFromTopActivity(String output) {
        if (output == null || !output.contains("ActivityRecord")) {
            return null;
        }

        int start = output.indexOf("u0 ");
        if (start == -1) {
            return null;
        }

        String sub = output.substring(start + 3);
        int slash = sub.indexOf("/");
        if (slash == -1) {
            return null;
        }

        String potentialPkg = sub.substring(0, slash).trim();
        if (isValidPackageName(potentialPkg)) {
            return potentialPkg;
        }
        return null;
    }
}