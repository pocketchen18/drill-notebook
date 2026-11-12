using System;
using System.IO;
using System.Threading;

// A tiny real Windows process used only in workspace-local updater integration tests.
class PortableUpdateFixture {
    static void Main(string[] arguments) {
        var root = Environment.GetEnvironmentVariable("APP_ROOT");
        if (String.IsNullOrEmpty(root)) throw new Exception("APP_ROOT is required");
        Directory.CreateDirectory(Path.Combine(root, "runtime"));
        if (arguments.Length == 2 && arguments[0] == "hold") {
            File.WriteAllText(Path.Combine(root, "runtime", arguments[1] + ".ready"), "ready");
            while (!File.Exists(Path.Combine(root, "runtime", arguments[1] + ".stop"))) Thread.Sleep(20);
            return;
        }
#if NEW_BUILD
        var id = Environment.GetEnvironmentVariable("DRILL_UPDATE_TRANSACTION");
        if (String.IsNullOrEmpty(id)) throw new Exception("Update transaction is required");
        var work = Path.Combine(root, "runtime", "updater", id);
        var failed = File.Exists(Path.Combine(root, "config", "fixture-fail-new-boot"));
        File.WriteAllText(Path.Combine(root, "data", "study.db"), "new-schema-data");
        var pid = System.Diagnostics.Process.GetCurrentProcess().Id;
        File.WriteAllText(Path.Combine(work, "health.json"), "{\"phase\":\"" + (failed ? "failed" : "healthy") + "\",\"version\":\"0.8.0\",\"pid\":" + pid + ",\"backendPid\":0,\"error\":\"fixture startup failure\"}");
        File.AppendAllText(Path.Combine(root, "runtime", "new-starts.txt"), "new\n");
        Thread.Sleep(500);
#else
        File.AppendAllText(Path.Combine(root, "runtime", "old-starts.txt"), "old\n");
#endif
    }
}
