using System;
using System.Threading;
using System.Windows;

namespace OvraCam
{
    public partial class App : Application
    {
        private static Mutex _single;

        protected override void OnStartup(StartupEventArgs e)
        {
            _single = new Mutex(true, "OvraCam.SingleInstance", out bool created);
            if (!created)
            {
                // already running: ask the other instance to show itself
                NativeShow.SignalExisting();
                Shutdown();
                return;
            }
            base.OnStartup(e);
        }
    }

    internal static class NativeShow
    {
        public const string EventName = "OvraCam.ShowWindow";

        public static void SignalExisting()
        {
            try
            {
                if (EventWaitHandle.TryOpenExisting(EventName, out var ev))
                {
                    ev.Set();
                    ev.Dispose();
                }
            }
            catch { }
        }
    }
}
