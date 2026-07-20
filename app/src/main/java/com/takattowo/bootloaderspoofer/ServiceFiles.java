package com.takattowo.bootloaderspoofer;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.github.libxposed.service.XposedService;

final class ServiceFiles {

    static boolean exists(XposedService service, String name) {
        return Arrays.asList(service.listRemoteFiles()).contains(name);
    }

    static String readString(XposedService service, String name, int maxBytes) throws IOException {
        byte[] bytes = readBytes(service, name, maxBytes);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    static void replaceString(XposedService service, String name, String content,
                              int maxBytes) throws IOException {
        byte[] replacement = content.getBytes(StandardCharsets.UTF_8);
        if (replacement.length > maxBytes) throw new IOException("File is too large");

        byte[] previous = readBytes(service, name, maxBytes);
        try {
            service.deleteRemoteFile(name);
            writeNew(service, name, replacement);
            byte[] stored = readBytes(service, name, maxBytes);
            if (!Arrays.equals(replacement, stored)) throw new IOException("Stored file did not verify");
        } catch (Throwable failure) {
            try {
                service.deleteRemoteFile(name);
                if (previous != null) writeNew(service, name, previous);
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw asIOException(failure);
        }
    }

    private static byte[] readBytes(XposedService service, String name,
                                    int maxBytes) throws IOException {
        try {
            if (!exists(service, name)) return null;
            try (ParcelFileDescriptor pfd = service.openRemoteFile(name);
                 FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IOException("File is too large");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } catch (Throwable t) {
            throw asIOException(t);
        }
    }

    private static void writeNew(XposedService service, String name, byte[] content)
            throws IOException {
        try (ParcelFileDescriptor pfd = service.openRemoteFile(name);
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write(content);
            out.getFD().sync();
        } catch (Throwable t) {
            throw asIOException(t);
        }
    }

    private static IOException asIOException(Throwable t) {
        return t instanceof IOException e ? e : new IOException(t.getMessage(), t);
    }

    private ServiceFiles() {}
}
