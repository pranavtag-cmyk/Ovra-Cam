using System;
using System.IO;
using System.Text.Json;

namespace OvraCam
{
    internal sealed class Settings
    {
        public int ExtraRotation { get; set; }          // 0, 90, 180, 270 (on top of the phone's own)
        public bool Mirror { get; set; }
        public string Quality { get; set; } = "720p";   // 720p or 1080p
        public string LastIp { get; set; } = "";
        public bool AutoConnect { get; set; } = true;
        public bool StartWithWindows { get; set; }
        public bool ShownTrayTip { get; set; }

        private static string PathName => System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OvraCam", "settings.json");

        public static Settings Load()
        {
            try
            {
                if (File.Exists(PathName))
                    return JsonSerializer.Deserialize<Settings>(File.ReadAllText(PathName)) ?? new Settings();
            }
            catch { }
            return new Settings();
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(PathName));
                File.WriteAllText(PathName, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch { }
        }
    }
}
