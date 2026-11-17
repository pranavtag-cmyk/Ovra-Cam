using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace OvraCam
{
    internal sealed class UsbDevice
    {
        public string Serial;
        public string State;   // device, unauthorized, offline
        public string Model;
    }

    /// <summary>Thin wrapper over the bundled adb.exe (Android platform-tools) for USB connections.</summary>
    internal static class Adb
    {
        public static string AdbPath
        {
            get
            {
                var dir = AppContext.BaseDirectory;
                var p = Path.Combine(dir, "adb", "adb.exe");
                if (File.Exists(p)) return p;
                p = Path.Combine(dir, "adb.exe");
                return File.Exists(p) ? p : null;
            }
        }

        public static bool Available => AdbPath != null;

        public static async Task<(int code, string output)> RunAsync(string args, int timeoutMs = 8000)
        {
            var path = AdbPath;
            if (path == null) return (-1, "adb not found");
            var psi = new ProcessStartInfo(path, args)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WorkingDirectory = Path.GetDirectoryName(path),
            };
            try
            {
                using var p = Process.Start(psi);
                var stdout = p.StandardOutput.ReadToEndAsync();
                var stderr = p.StandardError.ReadToEndAsync();
                var exited = await Task.Run(() => p.WaitForExit(timeoutMs));
                if (!exited)
                {
                    try { p.Kill(true); } catch { }
                    return (-2, "adb timed out");
                }
                return (p.ExitCode, (await stdout) + (await stderr));
            }
            catch (Exception ex)
            {
                return (-3, ex.Message);
            }
        }

        public static async Task<List<UsbDevice>> ListDevicesAsync()
        {
            var list = new List<UsbDevice>();
            var (code, output) = await RunAsync("devices -l", 15000);
            if (code != 0) return list;
            foreach (var raw in output.Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith("*")) continue;
                var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 2) continue;
                // skip adb-over-wifi entries (ip:port), we handle Wi-Fi ourselves
                if (parts[0].Contains(':') && parts[0].Contains('.')) continue;
                var dev = new UsbDevice { Serial = parts[0], State = parts[1] };
                var model = parts.FirstOrDefault(x => x.StartsWith("model:"));
                dev.Model = model != null ? model.Substring(6).Replace('_', ' ') : "Android phone";
                list.Add(dev);
            }
            return list;
        }

        public static async Task<bool> ForwardAsync(string serial, int localPort, int remotePort)
        {
            var (code, _) = await RunAsync($"-s {serial} forward tcp:{localPort} tcp:{remotePort}");
            return code == 0;
        }

        public static async Task RemoveForwardAsync(int localPort)
        {
            await RunAsync($"forward --remove tcp:{localPort}", 3000);
        }

        /// <summary>Asks the phone to open the Ovra Cam app (works once USB debugging is allowed).</summary>
        public static async Task LaunchAppAsync(string serial)
        {
            await RunAsync($"-s {serial} shell am start -n com.ovra.cam/.MainActivity", 5000);
        }

        public static void KillServer()
        {
            try
            {
                var path = AdbPath;
                if (path == null) return;
                using var p = Process.Start(new ProcessStartInfo(path, "kill-server") { UseShellExecute = false, CreateNoWindow = true });
                p?.WaitForExit(3000);
            }
            catch { }
        }
    }
}
