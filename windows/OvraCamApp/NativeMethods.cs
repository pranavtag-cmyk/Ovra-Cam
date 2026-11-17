using System;
using System.Runtime.InteropServices;

namespace OvraCam
{
    /// <summary>Calls into OvraCamSource.dll (virtual camera + H.264 decoder).</summary>
    internal static class NativeMethods
    {
        private const string Dll = "OvraCamSource.dll";

        [DllImport(Dll, CharSet = CharSet.Unicode, ExactSpelling = true)]
        public static extern int OvraCam_Start(string friendlyName);

        [DllImport(Dll, ExactSpelling = true)]
        public static extern int OvraCam_Stop();

        [DllImport(Dll, ExactSpelling = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool OvraCam_IsRegistered();

        [DllImport(Dll, ExactSpelling = true)]
        public static extern IntPtr OvraDec_Create();

        [DllImport(Dll, ExactSpelling = true)]
        public static extern void OvraDec_Destroy(IntPtr decoder);

        [DllImport(Dll, ExactSpelling = true)]
        public static extern int OvraDec_Decode(IntPtr decoder, byte[] data, int length, long timestamp);

        [DllImport(Dll, ExactSpelling = true)]
        public static extern int OvraDec_Render(IntPtr decoder, byte[] bgra, int capacity, int rotation, int mirror, int maxWidth, int maxHeight, out int width, out int height);
    }
}
