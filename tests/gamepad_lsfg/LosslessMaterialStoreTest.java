import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.yuzu.yuzu_emu.utils.LosslessMaterialStore;

public class LosslessMaterialStoreTest {
    static int assertions;
    static void expect(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("lsfg-test");
        final File live = root.resolve("lossless").toFile();
        final File source = root.resolve("Lossless.dll").toFile();
        int preparations;
        final LosslessMaterialStore store = new LosslessMaterialStore(live,
            stage -> {
                preparations++;
                String dll = Files.readString(stage.toPath().resolve("Lossless.dll"));
                // Stand-in for the native PE-to-SPIR-V translator boundary only.
                if (!dll.startsWith("valid:")) return 3;
                Files.writeString(stage.toPath().resolve("lsfg_spirv.cache"), dll);
                return 0;
            }, dir -> {
                Path dll = dir.toPath().resolve("Lossless.dll"), cache = dir.toPath().resolve("lsfg_spirv.cache");
                return Files.isRegularFile(dll) && Files.isRegularFile(cache) &&
                    Files.readString(dll).equals(Files.readString(cache));
            });
        Fixture() throws Exception { live.mkdirs(); seed("valid:old"); }
        void seed(String value) throws Exception {
            Files.writeString(live.toPath().resolve("Lossless.dll"), value);
            Files.writeString(live.toPath().resolve("lsfg_spirv.cache"), value);
        }
        int install(String value) throws Exception {
            Files.writeString(source.toPath(), value);
            return store.install(() -> new FileInputStream(source));
        }
        String dll() throws Exception { return Files.readString(live.toPath().resolve("Lossless.dll")); }
        String cache() throws Exception { return Files.readString(live.toPath().resolve("lsfg_spirv.cache")); }
        public void close() throws Exception {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    public static void main(String[] args) throws Exception {
        try (var f = new Fixture()) {
            var timestamp = Files.getLastModifiedTime(f.live.toPath().resolve("Lossless.dll"));
            expect(f.install("valid:old") == 0, "unchanged valid input succeeds");
            expect(f.preparations == 0, "unchanged input must skip translation");
            expect(timestamp.equals(Files.getLastModifiedTime(f.live.toPath().resolve("Lossless.dll"))), "unchanged live DLL must not be rewritten");
            expect(f.install("valid:new") == 0, "changed valid input commits");
            expect(f.dll().equals("valid:new") && f.cache().equals("valid:new"), "DLL and cache switch together");
            expect(f.preparations == 1, "one changed input translates once");
            expect(f.install("bad DLL") == 3, "invalid DLL reports parser error");
            expect(f.dll().equals("valid:new") && f.cache().equals("valid:new"), "invalid DLL preserves both previous files");
            expect(f.store.install(() -> { throw new FileNotFoundException(); }) != 0, "missing input reported");
            expect(f.dll().equals("valid:new") && f.cache().equals("valid:new"), "missing input keeps safe fallback");
            int[] opens = {0};
            expect(f.store.install(() -> {
                if (opens[0]++ == 0) return new ByteArrayInputStream("valid:copy".getBytes());
                return new InputStream() {
                    int i;
                    public int read() throws IOException { if (i++ < 3) return 'x'; throw new IOException("copy interrupted"); }
                };
            }) != 0, "interrupted staging copy reports error");
            expect(opens[0] == 2, "copy failure happens after the source was successfully fingerprinted");
            expect(!Files.exists(f.root.resolve("lossless.staging")), "partial staging file is removed");
            expect(f.dll().equals("valid:new") && f.cache().equals("valid:new"), "copy failure preserves old DLL and cache");
            int[] changedOpens = {0};
            expect(f.store.install(() -> new ByteArrayInputStream(
                (changedOpens[0]++ == 0 ? "valid:first" : "valid:second").getBytes())) == 2,
                "source changed during copy is rejected");
            expect(f.dll().equals("valid:new") && f.cache().equals("valid:new"), "changed source preserves committed generation");
            Files.writeString(f.live.toPath().resolve("lsfg_spirv.cache"), "broken");
            expect(f.install("valid:new") == 0 && f.cache().equals("valid:new"), "unchanged DLL repairs corrupt cache independently");
        }
        try (var f = new Fixture()) {
            // Process death between directory renames: recover committed backup before import.
            Files.move(f.live.toPath(), f.root.resolve("lossless.backup"));
            f.store.install(() -> { throw new FileNotFoundException(); });
            expect(f.dll().equals("valid:old") && f.cache().equals("valid:old"), "interrupted replacement restores backup on restart");
        }
        try (var f = new Fixture()) {
            var store = new LosslessMaterialStore(f.live, stage -> { throw new IOException("translator failure"); }, dir -> false);
            Files.writeString(f.source.toPath(), "valid:new");
            expect(store.install(() -> new FileInputStream(f.source)) != 0, "preparation exception is safe");
            expect(f.dll().equals("valid:old") && f.cache().equals("valid:old"), "preparation exception preserves old cache");
        }
        System.out.println("PASS " + assertions + " material-store assertions");
    }
}
