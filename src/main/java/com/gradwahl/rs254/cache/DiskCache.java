package com.gradwahl.rs254.cache;

import com.gradwahl.rs254.io.PacketBuffer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public final class DiskCache {
    private final Path dir;

    public DiskCache(String dir) {
        this.dir = Path.of(dir);
    }

    /**
     * Returns a persistent random UID from uid.dat, creating the file if absent.
     * Matches signlink.getuid() in the reference client.
     */
    public int getUid() {
        Path uidPath = dir.resolve("uid.dat");
        try {
            if (!Files.isRegularFile(uidPath) || Files.size(uidPath) < 4) {
                int fresh = (int) (new Random().nextDouble() * 9.9999999E7);
                try (DataOutputStream out = new DataOutputStream(new FileOutputStream(uidPath.toFile()))) {
                    out.writeInt(fresh);
                }
            }
            try (DataInputStream in = new DataInputStream(new FileInputStream(uidPath.toFile()))) {
                return in.readInt() + 1;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    public boolean exists() {
        return Files.isRegularFile(dir.resolve("main_file_cache.dat"))
            && Files.isRegularFile(dir.resolve("main_file_cache.idx0"));
    }

    public int[] crcsForIndex0() throws IOException {
        int count = count(0);
        int[] crcs = new int[9];

        for (int i = 0; i < crcs.length; i++) {
            byte[] data = i < count ? read(0, i) : null;
            crcs[i] = data == null ? 0 : PacketBuffer.crc32(data, 0, data.length);
        }

        return crcs;
    }

    private int count(int index) throws IOException {
        Path idx = dir.resolve("main_file_cache.idx" + index);
        if (!Files.isRegularFile(idx)) {
            return 0;
        }
        return (int) (Files.size(idx) / 6L);
    }

    public byte[] read(int archive, int file) throws IOException {
        Path datPath = dir.resolve("main_file_cache.dat");
        Path idxPath = dir.resolve("main_file_cache.idx" + archive);

        try (RandomAccessFile dat = new RandomAccessFile(datPath.toFile(), "r");
             RandomAccessFile idx = new RandomAccessFile(idxPath.toFile(), "r")) {

            if (file < 0 || ((long) file + 1L) * 6L > idx.length()) {
                return null;
            }

            idx.seek((long) file * 6L);
            int size = g3(idx);
            int sector = g3(idx);

            if (size < 0 || size > 2_000_000 || sector <= 0 || (long) sector * 520L > dat.length()) {
                return null;
            }

            byte[] out = new byte[size];
            int pos = 0;
            int part = 0;

            while (pos < size) {
                if (sector == 0) return null;

                dat.seek((long) sector * 520L);
                int sectorFile = g2(dat);
                int sectorPart = g2(dat);
                int nextSector = g3(dat);
                int sectorIndex = dat.readUnsignedByte();

                if (sectorFile != file || sectorPart != part || sectorIndex != archive + 1) {
                    return null;
                }

                int remaining = size - pos;
                if (remaining > 512) remaining = 512;
                dat.readFully(out, pos, remaining);
                pos += remaining;
                sector = nextSector;
                part++;
            }

            return out;
        }
    }

    private static int g2(RandomAccessFile file) throws IOException {
        return (file.readUnsignedByte() << 8) | file.readUnsignedByte();
    }

    private static int g3(RandomAccessFile file) throws IOException {
        return (file.readUnsignedByte() << 16) | (file.readUnsignedByte() << 8) | file.readUnsignedByte();
    }
}
