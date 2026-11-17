using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace OvraCam
{
    internal sealed class WifiPhone
    {
        public string Name;
        public string Ip;
        public int Port;
        public DateTime LastSeen;
    }

    /// <summary>Listens for the phone app's UDP beacons ("OVRACAM|1|name|port") on port 5151.</summary>
    internal sealed class Discovery : IDisposable
    {
        public const int BeaconPort = 5151;
        private readonly ConcurrentDictionary<string, WifiPhone> _phones = new ConcurrentDictionary<string, WifiPhone>();
        private UdpClient _udp;
        private CancellationTokenSource _cts;

        public string Error { get; private set; }

        public void Start()
        {
            try
            {
                _udp = new UdpClient();
                _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _udp.Client.Bind(new IPEndPoint(IPAddress.Any, BeaconPort));
                _cts = new CancellationTokenSource();
                _ = Task.Run(() => Loop(_cts.Token));
            }
            catch (Exception ex)
            {
                Error = ex.Message;
            }
        }

        private async Task Loop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var r = await _udp.ReceiveAsync(ct);
                    var text = Encoding.UTF8.GetString(r.Buffer);
                    var parts = text.Split('|');
                    if (parts.Length >= 4 && parts[0] == "OVRACAM" && int.TryParse(parts[3], out var port))
                    {
                        var ip = r.RemoteEndPoint.Address.MapToIPv4().ToString();
                        _phones[ip] = new WifiPhone { Name = parts[2], Ip = ip, Port = port, LastSeen = DateTime.UtcNow };
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (ObjectDisposedException) { break; }
                catch { await Task.Delay(200); }
            }
        }

        public List<WifiPhone> Phones
        {
            get
            {
                var cutoff = DateTime.UtcNow.AddSeconds(-5);
                return _phones.Values.Where(p => p.LastSeen > cutoff).OrderBy(p => p.Name).ToList();
            }
        }

        public void Dispose()
        {
            try { _cts?.Cancel(); } catch { }
            try { _udp?.Dispose(); } catch { }
        }
    }
}
