import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Set;
import java.util.zip.*;

/** Offline fixture preparation using Minecraft's own NBT implementation; never loads terrain. */
class FreshEvaluationWorld {
    private static final Set<String> CONFIGURATION = Set.of("DataVersion", "version", "Version", "WorldGenSettings",
        "DataPacks", "GameRules", "Difficulty", "DifficultyLocked", "GameType", "hardcore", "allowCommands");

    private static NbtCompound read(Path archive) throws Exception {
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("level.dat");
            if (entry == null) throw new IllegalArgumentException("Archive has no root level.dat");
            try (var input = zip.getInputStream(entry)) {
                return NbtIo.readCompressed(input, NbtSizeTracker.of(16 * 1024 * 1024)).getCompound("Data").orElseThrow();
            }
        }
    }

    private static void verify(Path archive, long seed) throws Exception {
        try (var zip = new ZipFile(archive.toFile())) {
            if (zip.size() != 1 || zip.getEntry("level.dat") == null) throw new IllegalStateException("Fresh archive must contain only level.dat");
        }
        var data = read(archive);
        if (data.getBoolean("initialized").orElseThrow() || data.getCompound("Player").isPresent()
            || data.getCompound("WorldGenSettings").orElseThrow().getLong("seed").orElseThrow() != seed) {
            throw new IllegalStateException("Fresh seed/initialization/player contract failed");
        }
        for (var key : data.getKeys()) {
            if (!CONFIGURATION.contains(key) && !Set.of("initialized", "LevelName", "Time", "DayTime", "LastPlayed").contains(key)) {
                throw new IllegalStateException("Unexpected persisted world state: " + key);
            }
        }
        System.out.println("Verified fresh configuration: seed=" + seed + " difficulty=" + data.getByte("Difficulty", (byte)-1) + " entries=1 initialized=false player=false");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--verify")) { verify(Path.of(args[1]), Long.parseLong(args[2])); return; }
        if (args.length != 4) throw new IllegalArgumentException("Expected template.zip output.zip seed title, or --verify archive.zip seed");
        var output = Path.of(args[1]); long seed = Long.parseLong(args[2]);
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to replace a frozen archive: " + output);
        var template = read(Path.of(args[0])); var data = new NbtCompound();
        for (var key : CONFIGURATION) {
            var value = template.get(key);
            if (value == null) throw new IllegalArgumentException("Template configuration missing: " + key);
            data.put(key, value.copy());
        }
        var generation = data.getCompound("WorldGenSettings").orElseThrow();
        generation.putLong("seed", seed); generation.putBoolean("bonus_chest", false);
        data.putBoolean("initialized", false); data.putString("LevelName", args[3]);
        data.putLong("Time", 0); data.putLong("DayTime", 0); data.putLong("LastPlayed", 0);
        var root = new NbtCompound(); root.put("Data", data);
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(root, bytes);
        var temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".fresh-world-", ".zip");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                var entry = new ZipEntry("level.dat"); entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry); zip.write(bytes.toByteArray()); zip.closeEntry();
            }
            verify(temporary, seed);
            Files.move(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
    }
}
