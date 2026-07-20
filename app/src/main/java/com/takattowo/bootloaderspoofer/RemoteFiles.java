package com.takattowo.bootloaderspoofer;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;

/**
 * Helper for reading module-shared files via {@link XposedInterface#openRemoteFile(String)}.
 * Only used in the injected target process. Module-process code must not touch this class.
 */
final class RemoteFiles {

    static String read(XposedInterface xposed, String name) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = xposed.openRemoteFile(name);
            if (pfd == null) return null;
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > KeyboxLoader.MAX_XML_BYTES) return null;
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
        }
    }

    private RemoteFiles() {}
}
