package com.protore.game;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imported Mesa Turnip drivers in the usual AdrenoTools package format: a ZIP whose package directory
 * holds meta.json plus the main Vulkan library named by its libraryName field (normally
 * libvulkan_freedreno.so), and optionally dependency .so files next to it.
 *
 * The installed copy deliberately lives in the app's internal files directory. libadrenotools' custom
 * driver loader dlopens the library and Android refuses to do that from /sdcard storage; internal
 * app-private storage is the location its documentation requires.
 */
final class TurnipDriverPackage {

    static final String META_NAME = "meta.json";
    static final String DEFAULT_LIBRARY = "libvulkan_freedreno.so";

    private static final String DIR_NAME = "turnip-driver";
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_META_BYTES = 128L * 1024L;

    private TurnipDriverPackage() {
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    static final class Meta {
        String name = "Imported Turnip driver";
        String author = "";
        String packageVersion = "";
        String vendor = "Mesa";
        String driverVersion = "";
        String libraryName = DEFAULT_LIBRARY;
        int minApi = 0;
        boolean legacy = false;

        String label() {
            StringBuilder s = new StringBuilder(name);
            if (!packageVersion.isEmpty()) s.append(" - package ").append(packageVersion);
            if (!driverVersion.isEmpty()) s.append(" - ").append(driverVersion);
            if (legacy) s.append(" - legacy ZIP without meta.json");
            return s.toString();
        }
    }

    /**
     * Validates and installs a driver ZIP. Existing installed files are replaced only after the new
     * package has been unpacked and checked, so a bad archive cannot wipe a working imported driver.
     */
    static Meta install(InputStream zipInput, File targetDir, int sdkInt) throws IOException {
        if (zipInput == null) throw new IOException("cannot open the picked ZIP");
        File parent = targetDir.getParentFile();
        if (parent == null) throw new IOException("no internal directory for the driver");
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }

        File staging = new File(parent, targetDir.getName() + ".importing");
        deleteRecursive(staging);
        File unpacked = new File(staging, "unpacked");
        File prepared = new File(staging, "prepared");
        try {
            extractAllowed(zipInput, unpacked);
            Meta meta = prepare(unpacked, prepared, sdkInt);
            swapIntoPlace(prepared, targetDir);
            return meta;
        } finally {
            deleteRecursive(staging);
        }
    }

    /** Metadata for a usable installed driver, or null when there is none. Broken metadata throws. */
    static Meta readInstalled(File dir) throws IOException {
        if (dir == null || !dir.isDirectory()) return null;
        File metaFile = new File(dir, META_NAME);
        Meta meta;
        if (metaFile.isFile()) {
            meta = parseMeta(metaFile);
        } else {
            meta = new Meta();
            meta.legacy = true;
        }
        File main = mainLibrary(dir, meta);
        if (main == null) return null;
        meta.libraryName = main.getName();
        validateElf(main);
        return looksLikeTurnip(meta) ? meta : null;
    }

    static File mainLibrary(File dir, Meta meta) {
        if (dir == null || meta == null) return null;
        File main = findFileIgnoreCase(dir, meta.libraryName);
        if (main != null) return main;
        List<File> libraries = listLibraries(dir);
        return libraries.size() == 1 ? libraries.get(0) : null;
    }

    static void deleteRecursive(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---- unpacking ----

    private static void extractAllowed(InputStream input, File root) throws IOException {
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("cannot create " + root);
        String rootPrefix = root.getCanonicalPath() + File.separator;
        Set<String> seen = new HashSet<>();
        long totalBytes = 0;
        int entries = 0;
        int keptFiles = 0;

        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input));
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            entries++;
            if (entries > MAX_ENTRIES) throw new IOException("the ZIP has too many entries");
            String rawName = entry.getName();
            if (rawName == null) {
                zip.closeEntry();
                continue;
            }
            String normalized = rawName.replace('\\', '/');
            if (normalized.startsWith("/") || normalized.contains(":")) {
                throw new IOException("unsafe path in the ZIP: " + rawName);
            }
            List<String> parts = safeParts(normalized, rawName);
            if (parts.isEmpty()) {
                zip.closeEntry();
                continue;
            }
            String fileName = parts.get(parts.size() - 1);
            String lower = fileName.toLowerCase(Locale.US);
            boolean directory = entry.isDirectory() || normalized.endsWith("/");
            if (directory || !(lower.equals(META_NAME) || lower.endsWith(".so"))) {
                zip.closeEntry();
                continue;  // licenses/readmes etc. are not needed by the loader
            }
            if (parts.size() > 4) throw new IOException("the driver package is nested too deeply");
            String relative = String.join("/", parts);
            if (!seen.add(relative.toLowerCase(Locale.US))) {
                throw new IOException("duplicate file in the ZIP: " + relative);
            }

            File dest = root;
            for (String part : parts) dest = new File(dest, part);
            if (!dest.getCanonicalPath().startsWith(rootPrefix)) {
                throw new IOException("unsafe path in the ZIP: " + rawName);
            }
            File destParent = dest.getParentFile();
            if (destParent == null || (!destParent.mkdirs() && !destParent.isDirectory())) {
                throw new IOException("cannot create " + destParent);
            }

            File part = new File(destParent, fileName + ".part");
            long limit = lower.equals(META_NAME) ? MAX_META_BYTES : MAX_FILE_BYTES;
            long fileBytes = 0;
            try (FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    fileBytes += n;
                    totalBytes += n;
                    if (fileBytes > limit) {
                        throw new IOException(fileName + " is unexpectedly large for a driver package");
                    }
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("the unpacked driver package is too large");
                    }
                    out.write(buffer, 0, n);
                }
            }
            if (!part.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("cannot unpack " + fileName);
            }
            keptFiles++;
            zip.closeEntry();
        }
        if (keptFiles == 0) {
            throw new IOException("no meta.json or .so driver library in the ZIP");
        }
    }

    private static List<String> safeParts(String normalized, String rawName) throws IOException {
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0) {
                throw new IOException("unsafe path in the ZIP: " + rawName);
            }
            parts.add(part);
        }
        return parts;
    }

    // ---- package selection and validation ----

    private static Meta prepare(File unpacked, File prepared, int sdkInt) throws IOException {
        List<File> metaFiles = new ArrayList<>();
        collectNamed(unpacked, META_NAME, 0, metaFiles);
        sortPackageFiles(unpacked, metaFiles);

        IOException firstError = null;
        for (File metaFile : metaFiles) {
            try {
                Meta meta = parseMeta(metaFile);
                validateAndPrepare(metaFile.getParentFile(), meta, prepared, sdkInt, metaFile);
                return meta;
            } catch (IOException e) {
                if (firstError == null) firstError = e;
                deleteRecursive(prepared);
            }
        }

        // Some older Turnip ZIPs contain only libvulkan_freedreno.so. Accept that one-file layout,
        // including the same file below a single package folder or an android/ folder.
        List<File> legacyLibraries = new ArrayList<>();
        collectNamed(unpacked, DEFAULT_LIBRARY, 0, legacyLibraries);
        sortPackageFiles(unpacked, legacyLibraries);
        for (File library : legacyLibraries) {
            try {
                Meta meta = new Meta();
                meta.legacy = true;
                meta.libraryName = library.getName();
                validateAndPrepare(library.getParentFile(), meta, prepared, sdkInt, null);
                return meta;
            } catch (IOException e) {
                if (firstError == null) firstError = e;
                deleteRecursive(prepared);
            }
        }

        if (firstError != null) throw firstError;
        throw new IOException("not an AdrenoTools Turnip ZIP: no usable meta.json and no "
                + DEFAULT_LIBRARY);
    }

    private static void validateAndPrepare(File packageDir, Meta meta, File prepared, int sdkInt,
                                            File metaFile) throws IOException {
        if (packageDir == null) throw new IOException("driver package directory is missing");
        if (meta.minApi > sdkInt) {
            throw new IOException("this driver requires Android API " + meta.minApi
                    + ", this phone reports API " + sdkInt);
        }

        File main = findFileIgnoreCase(packageDir, meta.libraryName);
        if (main == null) {
            List<File> libraries = listLibraries(packageDir);
            if (libraries.size() == 1) main = libraries.get(0);
        }
        if (main == null) {
            throw new IOException("meta.json names " + meta.libraryName
                    + ", but that library is not in the ZIP");
        }
        meta.libraryName = main.getName();
        if (!looksLikeTurnip(meta)) {
            throw new IOException("this does not look like a Mesa Turnip package (vendor/name/library: "
                    + meta.vendor + " / " + meta.name + " / " + meta.libraryName + ")");
        }
        validateElf(main);

        if (!prepared.mkdirs() && !prepared.isDirectory()) {
            throw new IOException("cannot create " + prepared);
        }
        Set<String> copied = new HashSet<>();
        for (File library : listLibraries(packageDir)) {
            String lower = library.getName().toLowerCase(Locale.US);
            if (!copied.add(lower)) throw new IOException("duplicate library " + library.getName());
            validateElf(library);
            copyFile(library, new File(prepared, library.getName()));
        }
        if (metaFile != null) {
            copyFile(metaFile, new File(prepared, META_NAME));
        } else {
            writeGeneratedMeta(prepared, meta);
        }
    }

    private static Meta parseMeta(File metaFile) throws IOException {
        byte[] bytes = Files.readAllBytes(metaFile.toPath());
        if (bytes.length > MAX_META_BYTES) throw new IOException("meta.json is too large");
        JSONObject json;
        try {
            json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("meta.json is not valid JSON: " + e.getMessage(), e);
        }

        int schemaVersion = intValue(json, "schemaVersion", 1);
        if (schemaVersion != 1) {
            throw new IOException("unsupported AdrenoTools package schemaVersion " + schemaVersion);
        }
        Meta meta = new Meta();
        String name = stringValue(json, "name");
        if (!name.isEmpty()) meta.name = name;
        meta.author = stringValue(json, "author");
        meta.packageVersion = stringValue(json, "packageVersion");
        String vendor = stringValue(json, "vendor");
        if (!vendor.isEmpty()) meta.vendor = vendor;
        meta.driverVersion = stringValue(json, "driverVersion");
        meta.minApi = intValue(json, "minApi", 0);

        String library = stringValue(json, "libraryName");
        if (library.isEmpty()) library = DEFAULT_LIBRARY;
        library = library.replace('\\', '/');
        if (library.startsWith("./")) library = library.substring(2);
        int slash = library.lastIndexOf('/');
        if (slash >= 0) library = library.substring(slash + 1);
        if (!safeLibraryName(library)) {
            throw new IOException("meta.json has an unsafe libraryName: " + library);
        }
        meta.libraryName = library;
        return meta;
    }

    private static boolean looksLikeTurnip(Meta meta) {
        String text = (meta.vendor + " " + meta.name + " " + meta.libraryName)
                .toLowerCase(Locale.US);
        if (text.contains("mesa") || text.contains("turnip") || text.contains("freedreno")) {
            return true;
        }
        return false;
    }

    private static boolean safeLibraryName(String name) {
        return !name.isEmpty()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.equals(".")
                && !name.equals("..")
                && name.toLowerCase(Locale.US).endsWith(".so");
    }

    private static void validateElf(File file) throws IOException {
        byte[] header = new byte[20];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < header.length) {
                int n = in.read(header, offset, header.length - offset);
                if (n < 0) break;
                offset += n;
            }
        }
        if (offset < header.length
                || (header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || (header[4] & 0xff) != 2       // ELFCLASS64
                || (header[5] & 0xff) != 1       // little endian
                || littleShort(header, 16) != 3  // ET_DYN
                || littleShort(header, 18) != 183) {  // EM_AARCH64
            throw new IOException(file.getName() + " is not an AArch64 Android shared library");
        }
    }

    private static int littleShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void writeGeneratedMeta(File prepared, Meta meta) throws IOException {
        String json = "{\"schemaVersion\":1"
                + ",\"name\":" + JSONObject.quote(meta.name)
                + ",\"author\":\"Unknown\""
                + ",\"packageVersion\":\"1\""
                + ",\"vendor\":\"Mesa\""
                + ",\"driverVersion\":\"0.0.0\""
                + ",\"minApi\":28"
                + ",\"libraryName\":" + JSONObject.quote(meta.libraryName)
                + "}\n";
        Files.write(new File(prepared, META_NAME).toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static void swapIntoPlace(File prepared, File targetDir) throws IOException {
        File parent = targetDir.getParentFile();
        File backup = new File(parent, targetDir.getName() + ".old");
        deleteRecursive(backup);
        boolean hadTarget = targetDir.exists();
        if (hadTarget && !targetDir.renameTo(backup)) {
            throw new IOException("cannot replace the currently imported driver");
        }
        if (!prepared.renameTo(targetDir)) {
            if (hadTarget) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(targetDir);
            }
            throw new IOException("cannot install the imported driver");
        }
        deleteRecursive(backup);
    }

    // ---- small file/JSON helpers ----

    private static void collectNamed(File dir, String name, int depth, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (depth < 3) collectNamed(child, name, depth + 1, out);
            } else if (child.isFile() && name.equalsIgnoreCase(child.getName())) {
                out.add(child);
            }
        }
    }

    private static void sortPackageFiles(final File root, List<File> files) {
        files.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int pa = packagePriority(relativeDir(root, a.getParentFile()));
                int pb = packagePriority(relativeDir(root, b.getParentFile()));
                if (pa != pb) return Integer.compare(pa, pb);
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });
    }

    private static int packagePriority(String relativeDir) {
        if (relativeDir.isEmpty()) return 0;
        if (relativeDir.equalsIgnoreCase("android")) return 1;
        return 10 + relativeDir.split("/").length;
    }

    private static String relativeDir(File root, File dir) {
        if (root == null || dir == null) return "";
        String rootPath = root.getAbsolutePath();
        String dirPath = dir.getAbsolutePath();
        if (dirPath.equals(rootPath)) return "";
        String prefix = rootPath + File.separator;
        if (dirPath.startsWith(prefix)) {
            return dirPath.substring(prefix.length()).replace(File.separatorChar, '/');
        }
        return dirPath;
    }

    private static File findFileIgnoreCase(File dir, String name) {
        if (dir == null || name == null) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && name.equalsIgnoreCase(child.getName())) return child;
        }
        return null;
    }

    private static List<File> listLibraries(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return out;
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(Locale.US).endsWith(".so")) {
                out.add(child);
            }
        }
        return out;
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static String stringValue(JSONObject json, String key) {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        return String.valueOf(value).trim();
    }

    private static int intValue(JSONObject json, String key, int fallback) {
        Object value = json.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            String text = ((String) value).trim();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                try {
                    return (int) Double.parseDouble(text);
                } catch (NumberFormatException ignoredAgain) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
