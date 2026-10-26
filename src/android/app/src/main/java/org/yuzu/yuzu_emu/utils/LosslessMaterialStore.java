// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Owns only the native lossless directory. Never writes the external source or game materials. */
public final class LosslessMaterialStore {
    public interface Source { InputStream open() throws IOException; }
    public interface Prepare { int run(File directory) throws Exception; }
    public interface Validate { boolean run(File directory) throws Exception; }

    private final File live;
    private final File staging;
    private final File backup;
    private final Prepare prepare;
    private final Validate validate;

    public LosslessMaterialStore(File directory, Prepare prepare, Validate validate) {
        live = directory;
        staging = new File(directory.getPath() + ".staging");
        backup = new File(directory.getPath() + ".backup");
        this.prepare = prepare;
        this.validate = validate;
    }

    /** Caller serializes import/removal and excludes a running emulator. */
    public int install(Source source) {
        try {
            recover();
            // Hash before staging: unchanged DLL/cache entails no writes or translation.
            byte[] incoming;
            try (InputStream input = source.open()) { incoming = hash(input); }
            File installedDll = new File(live, "Lossless.dll");
            if (installedDll.isFile()) {
                byte[] current;
                try (InputStream input = Files.newInputStream(installedDll.toPath())) {
                    current = hash(input);
                }
                if (Arrays.equals(incoming, current) && validate.run(live)) return 0;
            }
            deleteTree(staging);
            Files.createDirectories(staging.toPath());
            File candidate = new File(staging, "Lossless.dll");
            try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(candidate)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            // Reject an external source changed during the copy.
            try (InputStream input = Files.newInputStream(candidate.toPath())) {
                if (!Arrays.equals(incoming, hash(input))) return 2;
            }
            int result = prepare.run(staging);
            if (result != 0) return result;
            if (!validate.run(staging)) return 6;
            if (live.exists()) move(live, backup);
            try {
                move(staging, live);
            } catch (IOException error) {
                if (backup.exists()) move(backup, live);
                throw error;
            }
            // Commit is complete. A cleanup error must not report a failed import.
            try { deleteTree(backup); } catch (IOException ignored) {}
            return 0;
        } catch (Exception error) {
            return 2;
        } finally {
            try { deleteTree(staging); } catch (IOException ignored) {}
        }
    }

    private void recover() throws IOException {
        if (!backup.exists()) return;
        if (!live.exists()) move(backup, live);
        else deleteTree(backup); // New complete generation is already committed.
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        return digest.digest();
    }

    private static void deleteTree(File file) throws IOException {
        // Do not follow symlinks, including in interrupted staging directories.
        if (Files.isDirectory(file.toPath()) && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list " + file);
            for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
