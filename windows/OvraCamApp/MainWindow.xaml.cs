using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;

namespace OvraCam
{
    public sealed class PhoneEntry
    {
        public string Label { get; set; }
        public string Key { get; set; }      // "usb:<serial>" or "wifi:<ip>"
        public string Serial { get; set; }
        public string State { get; set; }
        public string Ip { get; set; }
        public int Port { get; set; }
        public bool IsUsb => Serial != null;
        public override string ToString() => Label;
    }

    public partial class MainWindow : Window
    {
        private readonly Settings _settings = Settings.Load();
        private readonly Discovery _discovery = new Discovery();
        private SharedFrameWriter _writer;
        private PhoneConnection _conn;
        private string _connKey;
        private bool _connecting;
        private bool _camRunning;
        private bool _exiting;
        private System.Windows.Forms.NotifyIcon _tray;
        private readonly DispatcherTimer _scanTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        private readonly DispatcherTimer _uiTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        private EventWaitHandle _showEvent;
        private bool _loadingUi;
        private bool _scanning;
        private PhoneStatus _phoneStatus;
        private readonly HashSet<string> _autoLaunched = new HashSet<string>();

        // video pipeline: the network thread queues H.264 packets, the decoder thread decodes them in order
        private sealed class Packet { public byte[] Data; public int Rotation; public int Flags; }
        private readonly ConcurrentQueue<Packet> _packets = new ConcurrentQueue<Packet>();
        private readonly AutoResetEvent _packetEvent = new AutoResetEvent(false);
        private volatile bool _resetDecoder;
        private Thread _decoder;
        private volatile bool _decoderRun = true;
        private byte[] _pixels = Array.Empty<byte>();
        private byte[] _previewPixels = Array.Empty<byte>();
        private WriteableBitmap _previewBitmap;
        private int _previewPending;
        private int _frameCount;
        private readonly Stopwatch _fpsWatch = Stopwatch.StartNew();
        private double _fps;
        private DateTime _lastFrameUtc = DateTime.MinValue;
        private string _videoSize = "";
        private bool _focusSliderUpdating;
        private DateTime _focusSliderTouched = DateTime.MinValue;

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
        }

        // ---------------- startup / shutdown ----------------

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            _loadingUi = true;
            AutoConnectBox.IsChecked = _settings.AutoConnect;
            MirrorBox.IsChecked = _settings.Mirror;
            StartupBox.IsChecked = _settings.StartWithWindows;
            IpBox.Text = _settings.LastIp;
            QualityBtn.Content = "Quality: " + QualityLabel(_settings.Quality);
            _loadingUi = false;

            SetupTray();
            SetupShowEvent();

            if (Environment.GetCommandLineArgs().Any(a => a.Equals("--tray", StringComparison.OrdinalIgnoreCase)))
            {
                Hide();
            }

            try
            {
                _writer = new SharedFrameWriter();
            }
            catch (Exception ex)
            {
                SetCamStatus(false, "Can't write video frames: " + ex.Message);
            }

            _ = StartVirtualCameraAsync();

            _discovery.Start();
            _decoder = new Thread(DecoderLoop) { IsBackground = true, Name = "OvraCam decoder" };
            _decoder.SetApartmentState(ApartmentState.MTA);
            _decoder.Start();

            _scanTimer.Tick += async (s, a) => await ScanAsync();
            _scanTimer.Start();
            _uiTimer.Tick += (s, a) => UpdateLiveUi();
            _uiTimer.Start();
            _ = ScanAsync();
        }

        private async Task StartVirtualCameraAsync()
        {
            SetCamStatus(null, "Starting virtual camera...");
            string error = null;
            int hr = 0;
            bool registered = true;
            await Task.Run(() =>
            {
                try
                {
                    registered = NativeMethods.OvraCam_IsRegistered();
                    if (registered) hr = NativeMethods.OvraCam_Start("Ovra Cam");
                }
                catch (DllNotFoundException) { error = "OvraCamSource.dll is missing - please reinstall Ovra Cam."; }
                catch (Exception ex) { error = ex.Message; }
            });

            if (error != null) { SetCamStatus(false, error); return; }
            if (!registered) { SetCamStatus(false, "The Ovra Cam camera isn't installed. Please run the Ovra Cam installer again."); return; }
            if (hr < 0)
            {
                SetCamStatus(false, $"Couldn't start the virtual camera (0x{hr:X8}). Windows 11 is required.");
                return;
            }
            _camRunning = true;
            SetCamStatus(true, "\"Ovra Cam\" webcam is on - pick it in Teams, Zoom, Meet or OBS");
        }

        private void SetCamStatus(bool? ok, string text)
        {
            CamStatus.Text = text;
            CamDot.Fill = (Brush)FindResource(ok == true ? "Good" : ok == false ? "Warn" : "Dim");
        }

        private void MainWindow_Closing(object sender, CancelEventArgs e)
        {
            if (!_exiting)
            {
                e.Cancel = true;
                Hide();
                if (!_settings.ShownTrayTip)
                {
                    _settings.ShownTrayTip = true;
                    _settings.Save();
                    _tray.ShowBalloonTip(4000, "Ovra Cam is still running", "Your phone webcam stays available. Right-click the tray icon to exit.", System.Windows.Forms.ToolTipIcon.Info);
                }
            }
        }

        private void ExitApp()
        {
            _exiting = true;
            _scanTimer.Stop();
            _uiTimer.Stop();
            Disconnect(null);
            _decoderRun = false;
            _packetEvent.Set();
            _discovery.Dispose();
            if (_camRunning)
            {
                try { NativeMethods.OvraCam_Stop(); } catch { }
            }
            _writer?.Dispose();
            _ = Task.Run(() => Adb.KillServer());
            _tray.Visible = false;
            _tray.Dispose();
            _settings.Save();
            Application.Current.Shutdown();
        }

        private void SetupTray()
        {
            _tray = new System.Windows.Forms.NotifyIcon
            {
                Text = "Ovra Cam",
                Visible = true,
            };
            try
            {
                var res = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/ovracam.ico"));
                if (res != null) _tray.Icon = new System.Drawing.Icon(res.Stream);
            }
            catch { }
            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Open Ovra Cam", null, (s, a) => ShowFromTray());
            menu.Items.Add("Switch phone camera", null, (s, a) => _conn?.Send(new { cmd = "switch" }));
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (s, a) => ExitApp());
            _tray.ContextMenuStrip = menu;
            _tray.DoubleClick += (s, a) => ShowFromTray();
        }

        private void SetupShowEvent()
        {
            _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, NativeShow.EventName);
            var t = new Thread(() =>
            {
                while (true)
                {
                    try { _showEvent.WaitOne(); } catch { return; }
                    Dispatcher.BeginInvoke(new Action(ShowFromTray));
                }
            }) { IsBackground = true };
            t.Start();
        }

        private void ShowFromTray()
        {
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
        }

        // ---------------- finding phones ----------------

        private async Task ScanAsync()
        {
            if (_scanning || _exiting) return;
            _scanning = true;
            try
            {
                var entries = new List<PhoneEntry>();
                if (Adb.Available)
                {
                    foreach (var d in await Adb.ListDevicesAsync())
                    {
                        string label = d.State switch
                        {
                            "device" => $"{d.Model}  (USB)",
                            "unauthorized" => $"{d.Model}  (USB - tap Allow on phone)",
                            _ => $"{d.Model}  (USB - {d.State})",
                        };
                        entries.Add(new PhoneEntry { Label = label, Key = "usb:" + d.Serial, Serial = d.Serial, State = d.State });
                    }
                }
                foreach (var w in _discovery.Phones)
                {
                    entries.Add(new PhoneEntry { Label = $"{w.Name}  (Wi-Fi {w.Ip})", Key = "wifi:" + w.Ip, Ip = w.Ip, Port = w.Port, State = "device" });
                }

                var selectedKey = (PhoneList.SelectedItem as PhoneEntry)?.Key;
                PhoneList.ItemsSource = entries;
                var sel = entries.FirstOrDefault(x => x.Key == selectedKey) ?? entries.FirstOrDefault(x => x.Key == _connKey) ?? entries.FirstOrDefault();
                PhoneList.SelectedItem = sel;

                if (_conn == null && !_connecting)
                {
                    if (entries.Count == 0)
                    {
                        SetPhoneStatus(null, Adb.Available ? "Looking for your phone (USB or Wi-Fi)..." : "Looking for your phone on Wi-Fi...");
                    }
                    else if (entries.All(x => x.State == "unauthorized"))
                    {
                        SetPhoneStatus(null, "On your phone, tap \"Allow\" on the USB debugging prompt.");
                    }
                    else
                    {
                        SetPhoneStatus(null, "Phone found - press Connect.");
                    }

                    if (_settings.AutoConnect)
                    {
                        var target = entries.FirstOrDefault(x => x.IsUsb && x.State == "device") ?? entries.FirstOrDefault(x => !x.IsUsb);
                        if (target != null) await ConnectAsync(target, auto: true);
                    }
                }
            }
            finally
            {
                _scanning = false;
            }
        }

        // ---------------- connecting ----------------

        private async void ConnectBtn_Click(object sender, RoutedEventArgs e)
        {
            if (PhoneList.SelectedItem is PhoneEntry p)
            {
                await ConnectAsync(p, auto: false);
            }
            else if (!string.IsNullOrWhiteSpace(IpBox.Text))
            {
                ConnectIp_Click(sender, e);
            }
            else
            {
                SetPhoneStatus(false, "No phone found yet. Open Ovra Cam on your phone first.");
            }
        }

        private async void ConnectIp_Click(object sender, RoutedEventArgs e)
        {
            var text = IpBox.Text.Trim();
            if (text.Length == 0) { SetPhoneStatus(false, "Type the IP address shown in the phone app."); return; }
            int port = PhoneConnection.DefaultPort;
            var host = text;
            var colon = text.LastIndexOf(':');
            if (colon > 0 && int.TryParse(text.Substring(colon + 1), out var p)) { host = text.Substring(0, colon); port = p; }
            _settings.LastIp = text;
            _settings.Save();
            await ConnectAsync(new PhoneEntry { Label = host, Key = "wifi:" + host, Ip = host, Port = port, State = "device" }, auto: false);
        }

        private async Task ConnectAsync(PhoneEntry p, bool auto)
        {
            if (_connecting) return;
            if (_conn != null)
            {
                if (_connKey == p.Key) return;
                Disconnect(null);
            }
            if (p.IsUsb && p.State != "device")
            {
                if (!auto) SetPhoneStatus(false, "On your phone, tap \"Allow\" on the USB debugging prompt, then try again.");
                return;
            }

            _connecting = true;
            ConnectBtn.IsEnabled = false;
            try
            {
                string host; int port; string via;
                if (p.IsUsb)
                {
                    SetPhoneStatus(null, "Connecting over USB...");
                    if (!await Adb.ForwardAsync(p.Serial, PhoneConnection.DefaultPort, PhoneConnection.DefaultPort))
                    {
                        SetPhoneStatus(false, "USB connection failed. Unplug and plug the phone back in.");
                        return;
                    }
                    host = "127.0.0.1"; port = PhoneConnection.DefaultPort; via = "USB";
                }
                else
                {
                    SetPhoneStatus(null, $"Connecting over Wi-Fi to {p.Ip}...");
                    host = p.Ip; port = p.Port > 0 ? p.Port : PhoneConnection.DefaultPort; via = "Wi-Fi";
                }

                var conn = new PhoneConnection(host, port, via);
                conn.FrameReceived += OnFrame;
                conn.StatusReceived += st => Dispatcher.BeginInvoke(new Action(() => OnStatus(st)));
                conn.Disconnected += reason => Dispatcher.BeginInvoke(new Action(() => { if (_conn == conn) Disconnect(reason); }));
                try
                {
                    await conn.ConnectAsync();
                }
                catch (Exception ex)
                {
                    conn.Dispose();
                    if (!auto) SetPhoneStatus(false, $"Couldn't reach the phone ({ex.Message}). Is Ovra Cam open on it?");
                    return;
                }

                ClearPackets();
                _resetDecoder = true;
                _conn = conn;
                _connKey = p.Key;
                _lastFrameUtc = DateTime.UtcNow;
                SendSettingsToPhone();
                SetPhoneStatus(true, $"Connected over {via}");
                DisconnectBtn.IsEnabled = true;
                SwitchBtn.IsEnabled = true;

                // if the phone app wasn't open, USB lets us start it for the user
                if (p.IsUsb && !_autoLaunched.Contains(p.Serial))
                {
                    _autoLaunched.Add(p.Serial);
                    _ = Task.Run(async () =>
                    {
                        await Task.Delay(1500);
                        if ((DateTime.UtcNow - _lastFrameUtc).TotalSeconds > 1.2 && _phoneStatus == null)
                            await Adb.LaunchAppAsync(p.Serial);
                    });
                }
            }
            finally
            {
                _connecting = false;
                ConnectBtn.IsEnabled = true;
            }
        }

        private void DisconnectBtn_Click(object sender, RoutedEventArgs e)
        {
            Disconnect(null);
            // stop auto-connect from immediately re-connecting the same phone
            _loadingUi = true;
            AutoConnectBox.IsChecked = false;
            _loadingUi = false;
            _settings.AutoConnect = false;
            _settings.Save();
        }

        private void Disconnect(string reason)
        {
            var hadStatus = _phoneStatus != null;
            var c = _conn;
            _conn = null;
            _connKey = null;
            _phoneStatus = null;
            c?.Dispose();
            DisconnectBtn.IsEnabled = false;
            SwitchBtn.IsEnabled = false;
            TorchBtn.IsEnabled = false;
            TorchBtn.Content = "Light: off";
            FocusBtn.IsEnabled = false;
            RefocusBtn.IsEnabled = false;
            FocusBtn.Content = "Focus: auto";
            FocusPanel.Visibility = Visibility.Collapsed;
            FpsBadge.Visibility = Visibility.Collapsed;
            Preview.Source = null;
            _previewBitmap = null;
            PreviewHint.Visibility = Visibility.Visible;
            if (reason != null && !hadStatus)
            {
                SetPhoneStatus(null, "Phone found - open Ovra Cam on your phone.");
            }
            else if (reason != null)
            {
                SetPhoneStatus(false, "Phone disconnected. " + (reason.Length < 80 ? reason : ""));
            }
            else
            {
                SetPhoneStatus(null, "Not connected");
            }
        }

        private void SetPhoneStatus(bool? ok, string text)
        {
            PhoneStatusText.Text = text;
            PhoneDot.Fill = (Brush)FindResource(ok == true ? "Good" : ok == false ? "Warn" : "Dim");
        }

        private void OnStatus(PhoneStatus st)
        {
            _phoneStatus = st;
            var cam = st.camera == "front" ? "front camera" : "back camera";
            var batt = st.battery >= 0 ? $" · battery {st.battery}%" : "";
            SetPhoneStatus(true, $"{st.device} · {cam} · over {_conn?.Via}{batt}");
            TorchBtn.IsEnabled = st.hasTorch;
            TorchBtn.Content = st.torch ? "Light: on" : "Light: off";

            FocusBtn.IsEnabled = true;
            RefocusBtn.IsEnabled = true;
            FocusBtn.Content = st.focusLocked ? "Focus: locked" : "Focus: auto";
            FocusPanel.Visibility = st.focusLocked && st.canManualFocus ? Visibility.Visible : Visibility.Collapsed;
            if ((DateTime.UtcNow - _focusSliderTouched).TotalSeconds > 2)
            {
                _focusSliderUpdating = true;
                FocusSlider.Value = Math.Max(0, Math.Min(1, st.focus));
                _focusSliderUpdating = false;
            }

            // keep the quality the user picked, if this phone supports it
            if (st.resolutions != null && st.resolutions.Length > 0 && Array.IndexOf(st.resolutions, _settings.Quality) < 0)
            {
                _settings.Quality = st.resolutions.Contains("1080p") ? "1080p" : st.resolutions[0];
                QualityBtn.Content = "Quality: " + QualityLabel(_settings.Quality);
            }
            if (!string.IsNullOrEmpty(st.res) && st.res != _settings.Quality)
            {
                SendSettingsToPhone();
            }
        }

        private void SendSettingsToPhone()
        {
            _conn?.Send(new { cmd = "res", value = _settings.Quality });
        }

        // ---------------- camera controls ----------------

        private void SwitchBtn_Click(object sender, RoutedEventArgs e) => _conn?.Send(new { cmd = "switch" });

        private void TorchBtn_Click(object sender, RoutedEventArgs e)
        {
            bool on = _phoneStatus == null || !_phoneStatus.torch;
            _conn?.Send(new { cmd = "torch", on });
        }

        private static string QualityLabel(string q) => q == "4k" ? "4K" : q;

        private void QualityBtn_Click(object sender, RoutedEventArgs e)
        {
            var options = _phoneStatus?.resolutions;
            if (options == null || options.Length == 0) options = new[] { "720p", "1080p" };
            int i = Array.IndexOf(options, _settings.Quality);
            _settings.Quality = options[(i + 1) % options.Length];
            QualityBtn.Content = "Quality: " + QualityLabel(_settings.Quality);
            _settings.Save();
            SendSettingsToPhone();
        }

        private void FocusBtn_Click(object sender, RoutedEventArgs e)
        {
            bool locked = _phoneStatus?.focusLocked == true;
            _conn?.Send(new { cmd = "focus", mode = locked ? "auto" : "lock" });
        }

        private void RefocusBtn_Click(object sender, RoutedEventArgs e)
        {
            _conn?.Send(new { cmd = "focus", mode = "refocus" });
        }

        private void FocusSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_focusSliderUpdating) return;
            _focusSliderTouched = DateTime.UtcNow;
            _conn?.Send(new { cmd = "focus", mode = "manual", value = Math.Round(FocusSlider.Value, 3) });
        }

        private void RotateBtn_Click(object sender, RoutedEventArgs e)
        {
            _settings.ExtraRotation = (_settings.ExtraRotation + 90) % 360;
            _settings.Save();
        }

        private void Option_Changed(object sender, RoutedEventArgs e)
        {
            if (_loadingUi) return;
            _settings.AutoConnect = AutoConnectBox.IsChecked == true;
            _settings.Mirror = MirrorBox.IsChecked == true;
            var startup = StartupBox.IsChecked == true;
            if (startup != _settings.StartWithWindows)
            {
                _settings.StartWithWindows = startup;
                SetStartWithWindows(startup);
            }
            _settings.Save();
        }

        private static void SetStartWithWindows(bool on)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
                if (key == null) return;
                if (on) key.SetValue("OvraCam", $"\"{Environment.ProcessPath}\" --tray");
                else key.DeleteValue("OvraCam", false);
            }
            catch { }
        }

        // ---------------- video pipeline ----------------

        private void ClearPackets()
        {
            while (_packets.TryDequeue(out _)) { }
        }

        private void OnFrame(int type, byte[] data, int rotation, int flags)
        {
            if (type != 3) return; // only H.264 (phone app v2+)
            if (_packets.Count > 120)
            {
                // the PC can't keep up: drop the backlog and restart from the next keyframe
                ClearPackets();
                _resetDecoder = true;
                _conn?.Send(new { cmd = "keyframe" });
            }
            _packets.Enqueue(new Packet { Data = data, Rotation = rotation, Flags = flags });
            _packetEvent.Set();
        }

        private void DecoderLoop()
        {
            IntPtr dec = IntPtr.Zero;
            byte[] config = null;
            bool waitKey = true;
            int errors = 0;
            long ts = 0;
            DateTime lastKeyRequest = DateTime.MinValue;

            void RequestKey()
            {
                if ((DateTime.UtcNow - lastKeyRequest).TotalSeconds > 1)
                {
                    lastKeyRequest = DateTime.UtcNow;
                    _conn?.Send(new { cmd = "keyframe" });
                }
            }

            while (_decoderRun)
            {
                _packetEvent.WaitOne(500);
                if (!_decoderRun) break;

                try
                {
                    if (_resetDecoder)
                    {
                        _resetDecoder = false;
                        if (dec != IntPtr.Zero) { NativeMethods.OvraDec_Destroy(dec); dec = IntPtr.Zero; }
                        waitKey = true;
                        errors = 0;
                        if (_conn != null) RequestKey();
                    }

                    int produced = 0;
                    int rotation = 0;
                    while (_packets.TryDequeue(out var p))
                    {
                        if ((p.Flags & 4) != 0)
                        {
                            // SPS/PPS: (re)start the decoder with the new stream settings
                            config = p.Data;
                            if (dec != IntPtr.Zero) NativeMethods.OvraDec_Destroy(dec);
                            dec = NativeMethods.OvraDec_Create();
                            if (dec != IntPtr.Zero) NativeMethods.OvraDec_Decode(dec, config, config.Length, ts);
                            waitKey = true;
                            continue;
                        }

                        if (dec == IntPtr.Zero)
                        {
                            if (config == null) { RequestKey(); continue; }
                            dec = NativeMethods.OvraDec_Create();
                            if (dec == IntPtr.Zero) continue;
                            NativeMethods.OvraDec_Decode(dec, config, config.Length, ts);
                            waitKey = true;
                        }

                        bool key = (p.Flags & 2) != 0;
                        if (waitKey && !key) { RequestKey(); continue; }
                        waitKey = false;

                        ts += 333333;
                        int n = NativeMethods.OvraDec_Decode(dec, p.Data, p.Data.Length, ts);
                        if (n > 0) { produced += n; errors = 0; rotation = p.Rotation; }
                        else if (n < 0 && ++errors > 30) { errors = 0; waitKey = true; RequestKey(); }
                    }

                    if (produced > 0)
                    {
                        Interlocked.Add(ref _frameCount, produced);
                        RenderOutput(dec, rotation);
                    }
                }
                catch (DllNotFoundException) { break; }
                catch (EntryPointNotFoundException) { break; }
                catch (Exception ex)
                {
                    Debug.WriteLine("decode error: " + ex.Message);
                }
            }

            if (dec != IntPtr.Zero) NativeMethods.OvraDec_Destroy(dec);
        }

        private void RenderOutput(IntPtr dec, int phoneRotation)
        {
            int rot = ((phoneRotation + _settings.ExtraRotation) % 360 + 360) % 360;
            int mirror = _settings.Mirror ? 1 : 0;

            // render exactly the size the meeting app / OBS is using (or 1080p if nobody is using the camera yet)
            var req = _writer?.GetRequestedSize();
            int tw = req?.width ?? 1920, th = req?.height ?? 1080;
            int cap = SharedFrameWriter.MaxWidth * SharedFrameWriter.MaxHeight * 4;
            if (_pixels.Length != cap) _pixels = new byte[cap];
            if (NativeMethods.OvraDec_Render(dec, _pixels, _pixels.Length, rot, mirror, tw, th, out int w, out int h) == 1)
            {
                _writer?.Write(_pixels, w, h);
                _lastFrameUtc = DateTime.UtcNow;
            }

            // small preview for this window
            if (Interlocked.CompareExchange(ref _previewPending, 1, 0) != 0) return;
            const int pw = 1280, ph = 720;
            if (_previewPixels.Length != pw * ph * 4) _previewPixels = new byte[pw * ph * 4];
            if (NativeMethods.OvraDec_Render(dec, _previewPixels, _previewPixels.Length, rot, mirror, pw, ph, out int vw, out int vh) != 1)
            {
                Interlocked.Exchange(ref _previewPending, 0);
                return;
            }
            var copy = new byte[vw * vh * 4];
            Buffer.BlockCopy(_previewPixels, 0, copy, 0, copy.Length);
            Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    if (IsVisible && _conn != null)
                    {
                        if (_previewBitmap == null || _previewBitmap.PixelWidth != vw || _previewBitmap.PixelHeight != vh)
                        {
                            _previewBitmap = new WriteableBitmap(vw, vh, 96, 96, PixelFormats.Bgra32, null);
                            Preview.Source = _previewBitmap;
                        }
                        _previewBitmap.WritePixels(new Int32Rect(0, 0, vw, vh), copy, vw * 4, 0);
                        PreviewHint.Visibility = Visibility.Collapsed;
                    }
                }
                finally
                {
                    Interlocked.Exchange(ref _previewPending, 0);
                }
            }), DispatcherPriority.Render);
        }

        private void UpdateLiveUi()
        {
            var elapsed = _fpsWatch.Elapsed.TotalSeconds;
            if (elapsed >= 1)
            {
                _fps = Interlocked.Exchange(ref _frameCount, 0) / elapsed;
                _fpsWatch.Restart();
            }

            if (_conn != null && (DateTime.UtcNow - _lastFrameUtc).TotalSeconds < 2)
            {
                FpsBadge.Visibility = Visibility.Visible;
                FpsText.Text = $"LIVE · {QualityLabel(_phoneStatus?.res ?? _settings.Quality)} · {_fps:0} fps · {_conn.Via}";
            }
            else
            {
                FpsBadge.Visibility = Visibility.Collapsed;
                if (_conn != null)
                {
                    PreviewHint.Visibility = Visibility.Visible;
                    HintTitle.Text = "Connected - waiting for video";
                    HintText.Text = "Make sure Ovra Cam is open on your phone and the screen is on.";
                }
                else
                {
                    HintTitle.Text = "Connect your phone";
                    HintText.Text = "Open Ovra Cam on your phone. Plug it in with USB (USB debugging on), or join the same Wi-Fi - it will appear on the right.";
                }
            }
        }
    }
}
