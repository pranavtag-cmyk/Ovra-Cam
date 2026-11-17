using System;
using System.Runtime.InteropServices;

namespace OvraCam
{
    /// <summary>Calls into OvraCamSource.dll (the Windows 11 virtual camera media source).</summary>
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
    }
}
