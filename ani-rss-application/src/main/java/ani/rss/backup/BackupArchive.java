package ani.rss.backup;

import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.persistence.DatabaseManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.UnixStat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

/** Safe, manifest-backed archive creation and extraction. */
public final class BackupArchive {
    public static final long MAX_COMPRESSED_BYTES = 50L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    public static final int MAX_ENTRIES = 50_000;
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:([/\\\\].*)?$");
    private static final Pattern APPLICATION_VERSION = Pattern.compile(
            "[0-9A-Za-z][0-9A-Za-z._+\\-]{0,127}");
    private static final long MAX_CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1000;
    private static final Set<String> ROOT_FILES = Set.of(
            "config.v2.json", "ani.v2.json", "database.db", "auth-state.v2.json");
    private static final Set<String> ROOT_DIRECTORIES = Set.of("files", "torrents");

    private BackupArchive() {
    }

    public static void create(OutputStream output, Path configDir, String applicationVersion) throws IOException {
        validateApplicationVersion(applicationVersion);
        Path root = configDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Map<String, Path> sources = new LinkedHashMap<>();
        addRequired(sources, root.resolve("config.v2.json"), "config.v2.json");
        addRequired(sources, root.resolve("ani.v2.json"), "ani.v2.json");
        addOptional(sources, root.resolve("auth-state.v2.json"), "auth-state.v2.json");
        Path database = root.resolve("database.db");
        Path databaseSnapshot = null;
        if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(database) || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("database.db is not a regular file");
            }
            databaseSnapshot = root.resolve(".ani-rss-db-" + UUID.randomUUID() + ".snapshot");
            DatabaseManager.backupTo(databaseSnapshot);
            sources.put("database.db", databaseSnapshot);
        }
        addDirectory(sources, root.resolve("files"), "files");
        addDirectory(sources, root.resolve("torrents"), "torrents");

        List<BackupManifest.Entry> entries = new ArrayList<>();
        try {
            ZipArchiveOutputStream zip = new ZipArchiveOutputStream(new NonClosingOutputStream(output));
            zip.setUseZip64(Zip64Mode.AsNeeded);
            for (Map.Entry<String, Path> source : sources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                BackupManifest.Entry manifestEntry = writeEntry(zip, source.getKey(), source.getValue());
                entries.add(manifestEntry);
            }
            BackupManifest manifest = new BackupManifest(
                    BackupManifest.CURRENT_FORMAT,
                    applicationVersion,
                    System.currentTimeMillis(),
                    entries);
            byte[] manifestBytes = GsonStatic.toJson(manifest).getBytes(StandardCharsets.UTF_8);
            ZipArchiveEntry manifestEntry = new ZipArchiveEntry("manifest.json");
            manifestEntry.setSize(manifestBytes.length);
            zip.putArchiveEntry(manifestEntry);
            zip.write(manifestBytes);
            zip.closeArchiveEntry();
            zip.finish();
            zip.close();
        } finally {
            if (databaseSnapshot != null) {
                Files.deleteIfExists(databaseSnapshot);
            }
        }
    }

    public static BackupValidation validateAndExtract(Path archive, Path destination) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("backup archive is not a regular file");
        }
        if (Files.size(source) > MAX_COMPRESSED_BYTES) {
            throw new IOException("compressed archive exceeds 50 MiB");
        }
        validateArchiveStructure(source);
        Path targetRoot = destination.toAbsolutePath().normalize();
        if (Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) {
            boolean nonEmpty;
            try (var children = Files.list(targetRoot)) {
                nonEmpty = children.findAny().isPresent();
            }
            if (Files.isSymbolicLink(targetRoot) || nonEmpty) {
                throw new IOException("staging directory must be empty and non-symlink");
            }
        } else {
            Files.createDirectories(targetRoot);
        }

        Map<String, BackupManifest.Entry> actual = new LinkedHashMap<>();
        long expanded = 0;
        int count = 0;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(source));
             ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, "UTF-8", true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextZipEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("archive contains too many entries");
                }
                String name = normalizeEntryName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                validateEntryType(entry);
                if (entry.isDirectory()) {
                    validateAllowed(name + "/");
                    continue;
                }
                validateAllowed(name);
                if (actual.containsKey(name)) {
                    throw new IOException("duplicate archive entry: " + name);
                }
                Path target = targetRoot.resolve(name).normalize();
                if (!target.startsWith(targetRoot) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("archive target conflict: " + name);
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    if (Files.isSymbolicLink(parent) || !PathPolicy.realPathWithin(targetRoot, parent)
                            .startsWith(targetRoot.toRealPath())) {
                        throw new IOException("archive parent is a symbolic link: " + name);
                    }
                }
                MessageDigest digest = sha256();
                long size = 0;
                try (FileChannel channel = FileChannel.open(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        size += read;
                        expanded += read;
                        if (size > MAX_ENTRY_BYTES || expanded > MAX_EXPANDED_BYTES) {
                            throw new IOException("expanded archive exceeds configured limits");
                        }
                        digest.update(buffer, 0, read);
                        ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                        while (bytes.hasRemaining()) {
                            channel.write(bytes);
                        }
                    }
                    channel.force(true);
                }
                actual.put(name, new BackupManifest.Entry(name, size, hex(digest.digest())));
            }
        } catch (ZipException e) {
            throw new IOException("invalid ZIP archive", e);
        }

        BackupManifest manifest = null;
        Path manifestPath = targetRoot.resolve("manifest.json");
        if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(manifestPath) > MAX_ENTRY_BYTES) {
                throw new IOException("manifest is too large");
            }
            String json = Files.readString(manifestPath);
            try {
                manifest = GsonStatic.GSON.fromJson(json, BackupManifest.class);
            } catch (RuntimeException e) {
                throw new IOException("invalid backup manifest", e);
            }
            if (manifest == null || manifest.formatVersion() != BackupManifest.CURRENT_FORMAT) {
                throw new IOException("unsupported backup manifest version");
            }
            validateManifestMetadata(manifest);
            actual.remove("manifest.json");
            verifyManifest(manifest, actual);
        }

        validateDocuments(targetRoot, actual.keySet());
        if (actual.isEmpty()) {
            throw new IOException("backup contains no files");
        }
        List<String> warnings = new ArrayList<>();
        boolean legacy = manifest == null;
        if (legacy) {
            warnings.add("legacy backup has no manifest; hashes were calculated during validation");
        }
        String applicationVersion = manifest == null ? null : manifest.applicationVersion();
        Set<String> topLevels = actual.keySet().stream()
                .map(name -> name.substring(0, name.indexOf('/') >= 0 ? name.indexOf('/') : name.length()))
                .collect(java.util.stream.Collectors.toSet());
        return new BackupValidation(true, legacy, applicationVersion,
                List.copyOf(actual.values()), topLevels, warnings);
    }

    private static void addRequired(Map<String, Path> sources, Path path, String archiveName) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("required backup file is missing: " + archiveName);
        }
        sources.put(archiveName, path);
    }

    private static void addOptional(Map<String, Path> sources, Path path, String archiveName) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        addRequired(sources, path, archiveName);
    }

    private static void addDirectory(Map<String, Path> sources, Path root, String prefix) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("backup directory is not a regular directory: " + prefix);
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("symbolic links are not allowed in backup: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("special files are not allowed in backup: " + path);
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".") || relative.contains("/.")) {
                    continue;
                }
                sources.put(prefix + "/" + relative, path);
            }
        }
    }

    private static BackupManifest.Entry writeEntry(ZipArchiveOutputStream zip, String name, Path source)
            throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        zip.putArchiveEntry(entry);
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                digest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
            }
        } finally {
            zip.closeArchiveEntry();
        }
        return new BackupManifest.Entry(name, size, hex(digest.digest()));
    }

    private static void verifyManifest(BackupManifest manifest,
                                       Map<String, BackupManifest.Entry> actual) throws IOException {
        Map<String, BackupManifest.Entry> declared = new HashMap<>();
        for (BackupManifest.Entry entry : manifest.files()) {
            if (entry == null || entry.path() == null || !declaredValue(entry.path(), entry)) {
                throw new IOException("invalid manifest entry");
            }
            String name = normalizeEntryName(entry.path());
            validateAllowed(name);
            if (declared.put(name, new BackupManifest.Entry(name, entry.size(), entry.sha256())) != null) {
                throw new IOException("duplicate manifest entry");
            }
        }
        if (!declared.keySet().equals(actual.keySet())) {
            throw new IOException("manifest does not match archive contents");
        }
        for (Map.Entry<String, BackupManifest.Entry> item : declared.entrySet()) {
            BackupManifest.Entry actualEntry = actual.get(item.getKey());
            BackupManifest.Entry declaredEntry = item.getValue();
            if (declaredEntry.size() != actualEntry.size() ||
                    !declaredEntry.sha256().equalsIgnoreCase(actualEntry.sha256())) {
                throw new IOException("manifest hash mismatch: " + item.getKey());
            }
        }
    }

    private static void validateArchiveStructure(Path source) throws IOException {
        try (ZipFile zip = ZipFile.builder()
                .setPath(source)
                .setUseUnicodeExtraFields(true)
                .get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("archive contains too many entries");
                }
                String name = normalizeEntryName(entry.getName());
                if (!name.isEmpty()) {
                    validateAllowed(entry.isDirectory() ? name + "/" : name);
                }
                validateEntryType(entry);
                if (!zip.canReadEntryData(entry)) {
                    throw new IOException("unsupported or encrypted ZIP entry: " + name);
                }
            }
            if (count == 0) {
                throw new IOException("backup archive is empty");
            }
        } catch (ZipException e) {
            throw new IOException("invalid ZIP archive", e);
        }
    }

    private static void validateEntryType(ZipArchiveEntry entry) throws IOException {
        if (entry.isUnixSymlink()) {
            throw new IOException("symbolic links and special files are not allowed");
        }
        if (entry.getPlatform() != ZipArchiveEntry.PLATFORM_UNIX) {
            return;
        }
        int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (type == 0) {
            return;
        }
        boolean regular = type == UnixStat.FILE_FLAG && !entry.isDirectory();
        boolean directory = type == UnixStat.DIR_FLAG && entry.isDirectory();
        if (!regular && !directory) {
            throw new IOException("symbolic links and special files are not allowed");
        }
    }

    private static void validateManifestMetadata(BackupManifest manifest) throws IOException {
        validateApplicationVersion(manifest.applicationVersion());
        long now = System.currentTimeMillis();
        if (manifest.createdAt() <= 0 || manifest.createdAt() > now + MAX_CLOCK_SKEW_MILLIS) {
            throw new IOException("invalid backup manifest creation time");
        }
    }

    private static void validateApplicationVersion(String applicationVersion) throws IOException {
        if (applicationVersion == null || !APPLICATION_VERSION.matcher(applicationVersion).matches()) {
            throw new IOException("invalid backup application version");
        }
    }

    private static boolean declaredValue(String path, BackupManifest.Entry entry) {
        return entry.size() >= 0 && entry.size() <= MAX_ENTRY_BYTES &&
                entry.sha256() != null && entry.sha256().matches("(?i)[0-9a-f]{64}");
    }

    private static void validateDocuments(Path root, Set<String> names) throws IOException {
        if (!names.contains("config.v2.json") || !names.contains("ani.v2.json")) {
            throw new IOException("backup must contain config.v2.json and ani.v2.json");
        }
        try {
            JsonElement config = JsonParser.parseString(Files.readString(root.resolve("config.v2.json")));
            if (!config.isJsonObject()) {
                throw new IOException("config document is not an object");
            }
            JsonElement subscriptions = JsonParser.parseString(Files.readString(root.resolve("ani.v2.json")));
            if (!subscriptions.isJsonArray()) {
                throw new IOException("subscription document is not an array");
            }
        } catch (RuntimeException e) {
            throw new IOException("backup JSON validation failed", e);
        }
        Path database = root.resolve("database.db");
        if (Files.exists(database, LinkOption.NOFOLLOW_LINKS) && !DatabaseManager.integrityCheck(database)) {
            throw new IOException("backup SQLite integrity check failed");
        }
    }

    private static String normalizeEntryName(String raw) throws IOException {
        if (raw == null || raw.indexOf('\0') >= 0) {
            throw new IOException("invalid archive path");
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || DRIVE_PATH.matcher(value).matches()) {
            throw new IOException("absolute archive path is not allowed");
        }
        Path normalized = Path.of(value).normalize();
        String result = normalized.toString().replace('\\', '/');
        if (result.isBlank() || result.equals(".") || result.equals("..") || result.startsWith("../") ||
                result.contains("/../") || result.endsWith("/..")) {
            throw new IOException("archive path escapes staging directory");
        }
        return result;
    }

    private static void validateAllowed(String name) throws IOException {
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (clean.equals("manifest.json") || ROOT_FILES.contains(clean)) {
            return;
        }
        if (ROOT_DIRECTORIES.contains(clean)) {
            return;
        }
        for (String directory : ROOT_DIRECTORIES) {
            if (clean.startsWith(directory + "/") && clean.length() > directory.length() + 1) {
                return;
            }
        }
        throw new IOException("undeclared archive path: " + name);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        private NonClosingOutputStream(OutputStream output) {
            super(new BufferedOutputStream(output));
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
