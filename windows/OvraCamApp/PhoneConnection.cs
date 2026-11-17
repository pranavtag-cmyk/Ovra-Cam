using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace OvraCam
{
    internal sealed class PhoneStatus
    {
        public string device { get; set; }
        public string camera { get; set; }   // "back" / "front"
        public int width { get; set; }
        public int height { get; set; }
        public bool torch { get; set; }
        public bool hasTorch { get; set; }
        public string res { get; set; }
        public int battery { get; set; } = -1;
    }

    /// <summary>
    /// TCP link to the phone. Phone -> PC packets: [type:1][len:4 BE][payload].
    ///   type 1 = video frame: [rotation:2 BE][flags:1][jpeg bytes]
    ///   type 2 = status JSON (UTF-8)
    /// PC -> phone: one JSON command per line, e.g. {"cmd":"switch"}.
    /// </summary>
    internal sealed class PhoneConnection : IDisposable
    {
        public const int DefaultPort = 5150;

        private TcpClient _client;
        private NetworkStream _stream;
        private CancellationTokenSource _cts;
        private readonly object _writeLock = new object();

        public string Host { get; }
        public int Port { get; }
        public string Via { get; }   // "USB" or "Wi-Fi"
        public bool Connected => _client?.Connected == true;

        public event Action<byte[], int, int> FrameReceived;   // jpeg, rotation, flags
        public event Action<PhoneStatus> StatusReceived;
        public event Action<string> Disconnected;

        public PhoneConnection(string host, int port, string via)
        {
            Host = host;
            Port = port;
            Via = via;
        }

        public async Task ConnectAsync(int timeoutMs = 4000)
        {
            _client = new TcpClient { NoDelay = true, ReceiveBufferSize = 4 * 1024 * 1024 };
            using (var cts = new CancellationTokenSource(timeoutMs))
            {
                await _client.ConnectAsync(Host, Port, cts.Token);
            }
            _stream = _client.GetStream();
            _cts = new CancellationTokenSource();
            // over USB, adb accepts the local connection even if the phone app isn't listening:
            // the read loop will then end quickly and report a disconnect.
            _ = Task.Run(() => ReadLoop(_cts.Token));
            Send(new { cmd = "hello", client = "Ovra Cam for Windows", version = 1 });
        }

        private static async Task ReadExactAsync(Stream s, byte[] buf, int count, CancellationToken ct)
        {
            int off = 0;
            while (off < count)
            {
                int n = await s.ReadAsync(buf.AsMemory(off, count - off), ct);
                if (n <= 0) throw new EndOfStreamException("The phone closed the connection");
                off += n;
            }
        }

        private async Task ReadLoop(CancellationToken ct)
        {
            string reason = "Disconnected";
            var header = new byte[5];
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    await ReadExactAsync(_stream, header, 5, ct);
                    int type = header[0];
                    int len = (header[1] << 24) | (header[2] << 16) | (header[3] << 8) | header[4];
                    if (len < 0 || len > 32 * 1024 * 1024) throw new InvalidDataException("Bad packet from phone");
                    var payload = new byte[len];
                    await ReadExactAsync(_stream, payload, len, ct);

                    if (type == 1 && len > 3)
                    {
                        int rotation = (payload[0] << 8) | payload[1];
                        int flags = payload[2];
                        var jpeg = new byte[len - 3];
                        Buffer.BlockCopy(payload, 3, jpeg, 0, jpeg.Length);
                        FrameReceived?.Invoke(jpeg, rotation, flags);
                    }
                    else if (type == 2)
                    {
                        try
                        {
                            var st = JsonSerializer.Deserialize<PhoneStatus>(Encoding.UTF8.GetString(payload));
                            if (st != null) StatusReceived?.Invoke(st);
                        }
                        catch { }
                    }
                }
            }
            catch (OperationCanceledException) { reason = null; }
            catch (Exception ex) { reason = ex.Message; }

            if (reason != null && !ct.IsCancellationRequested)
            {
                Disconnected?.Invoke(reason);
            }
        }

        public void Send(object command)
        {
            try
            {
                var line = JsonSerializer.Serialize(command) + "\n";
                var bytes = Encoding.UTF8.GetBytes(line);
                lock (_writeLock)
                {
                    _stream?.Write(bytes, 0, bytes.Length);
                }
            }
            catch { }
        }

        public void Dispose()
        {
            try { _cts?.Cancel(); } catch { }
            try { _stream?.Dispose(); } catch { }
            try { _client?.Dispose(); } catch { }
        }
    }
}
