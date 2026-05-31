package com.gradwahl.rs254.cache;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a Jagfile archive.
 *
 * Format (after the 6-byte outer header):
 *   g2  file_count
 *   for each file:
 *     g4s  name_hash
 *     g3   unpacked_size
 *     g3   packed_size
 *   then packed file bytes (BZip2 if packed != unpacked)
 *
 * When the whole archive is BZip2-compressed (outer unpacked != packed) the
 * data after byte 6 is already the inflated body — handled by DiskCache before
 * this class is called.
 */
public final class JagFile {
    /** name_hash → decompressed bytes */
    private final Map<Integer, byte[]> files = new HashMap<>();

    public static JagFile load(byte[] raw) throws IOException {
        JagFile jag = new JagFile();
        jag.parse(raw);
        return jag;
    }

    private void parse(byte[] raw) throws IOException {
        int unpackedSize = g3(raw, 0);
        int packedSize   = g3(raw, 3);

        byte[] body;
        if (unpackedSize == packedSize) {
            // Not whole-file compressed — skip 6-byte header
            body = raw;
        } else {
            // Whole-file BZip2 compressed
            body = bzip2Decompress(raw, 6, raw.length - 6, unpackedSize);
        }

        // body layout (for non-whole-compressed, body starts at offset 6)
        int pos       = (unpackedSize == packedSize) ? 6 : 0;
        int fileCount = g2(body, pos); pos += 2;

        int[] hashes   = new int[fileCount];
        int[] unpacked = new int[fileCount];
        int[] packed   = new int[fileCount];

        for (int i = 0; i < fileCount; i++) {
            hashes[i]   = g4s(body, pos);       pos += 4;
            unpacked[i] = g3(body, pos);         pos += 3;
            packed[i]   = g3(body, pos);         pos += 3;
        }

        for (int i = 0; i < fileCount; i++) {
            byte[] fileData;
            if (unpackedSize != packedSize) {
                // whole-file decompressed — files are raw
                fileData = new byte[unpacked[i]];
                System.arraycopy(body, pos, fileData, 0, unpacked[i]);
                pos += unpacked[i];
            } else if (unpacked[i] == packed[i]) {
                // per-file uncompressed
                fileData = new byte[unpacked[i]];
                System.arraycopy(body, pos, fileData, 0, unpacked[i]);
                pos += packed[i];
            } else {
                // per-file BZip2 compressed
                fileData = bzip2Decompress(body, pos, packed[i], unpacked[i]);
                pos += packed[i];
            }
            files.put(hashes[i], fileData);
        }
    }

    public byte[] get(String name) {
        return files.get(jagHash(name));
    }

    // -------------------------------------------------------------------------

    /** BZip2 decompress. Jagex strips the "BZh9" magic — we prepend it back. */
    private static byte[] bzip2Decompress(byte[] src, int off, int len, int unpackedSize) throws IOException {
        // Jagex strips the first 4 bytes of the BZip2 stream ("BZh9")
        byte[] prefixed = new byte[4 + len];
        prefixed[0] = 'B'; prefixed[1] = 'Z'; prefixed[2] = 'h'; prefixed[3] = '9';
        System.arraycopy(src, off, prefixed, 4, len);

        try (BZip2CompressorInputStream bz = new BZip2CompressorInputStream(new ByteArrayInputStream(prefixed))) {
            byte[] out = new byte[unpackedSize];
            int read = 0;
            while (read < unpackedSize) {
                int n = bz.read(out, read, unpackedSize - read);
                if (n == -1) break;
                read += n;
            }
            return out;
        }
    }

    static int jagHash(String name) {
        int hash = 0;
        String upper = name.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            hash = (hash * 61 + upper.charAt(i) - 32);
        }
        return hash;
    }

    private static int g2(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
    }

    private static int g3(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 16) | ((b[pos + 1] & 0xff) << 8) | (b[pos + 2] & 0xff);
    }

    private static int g4s(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16)
             | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
    }
}
