using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Threading;

namespace OvraCam
{
    /// <summary>
    /// Writes BGRA frames into C:\ProgramData\OvraCam\frame.bin, which the virtual camera
    /// (running inside Windows' Frame Server service) reads. Layout must match OvraShared.h.
    /// </summary>
    internal sealed unsafe class SharedFrameWriter : IDisposable
    {
        public const string Folder = @"C:\ProgramData\OvraCam";
        public const string FilePath = @"C:\ProgramData\OvraCam\frame.bin";
        public const uint Magic = 0x4352564F; // 'OVRC'
        public const int HeaderSize = 4096;
        public const int MaxWidth = 3840;
        public const int MaxHeight = 2160;
        public const long FileSize = HeaderSize + (long)MaxWidth * MaxHeight * 4;

        // header offsets (see OvraShared.h)
        private const int OffMagic = 0, OffVersion = 4, OffWidth = 8, OffHeight = 12, OffStride = 16, OffFlags = 20, OffSeq = 24, OffTime = 32, OffPid = 40, OffReqW = 44, OffReqH = 48, OffReqTime = 56;

        private FileStream _stream;
        private MemoryMappedFile _mmf;
        private MemoryMappedViewAccessor _view;
        private byte* _ptr;
        private long _seq;
        private readonly object _lock = new object();

        public SharedFrameWriter()
        {
            Directory.CreateDirectory(Folder);
            _stream = new FileStream(FilePath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
            if (_stream.Length < FileSize)
            {
                _stream.SetLength(FileSize);
            }

            _mmf = MemoryMappedFile.CreateFromFile(_stream, null, FileSize, MemoryMappedFileAccess.ReadWrite, HandleInheritability.None, true);
            _view = _mmf.CreateViewAccessor(0, FileSize, MemoryMappedFileAccess.ReadWrite);
            byte* p = null;
            _view.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
            _ptr = p + _view.PointerOffset;

            _seq = *(long*)(_ptr + OffSeq);
            if ((_seq & 1) != 0) _seq++;
            *(uint*)(_ptr + OffMagic) = Magic;
            *(uint*)(_ptr + OffVersion) = 1;
            *(uint*)(_ptr + OffPid) = (uint)Environment.ProcessId;
        }

        /// <summary>Writes a top-down BGRA32 frame (stride = width * 4).</summary>
        public void Write(byte[] bgra, int width, int height)
        {
            if (width <= 0 || height <= 0 || width > MaxWidth || height > MaxHeight) return;
            int stride = width * 4;
            long size = (long)stride * height;
            if (bgra.Length < size) return;

            lock (_lock)
            {
                if (_ptr == null) return;
                long* seqPtr = (long*)(_ptr + OffSeq);
                Interlocked.Exchange(ref *seqPtr, ++_seq); // odd => writing
                *(uint*)(_ptr + OffWidth) = (uint)width;
                *(uint*)(_ptr + OffHeight) = (uint)height;
                *(uint*)(_ptr + OffStride) = (uint)stride;
                *(uint*)(_ptr + OffFlags) = 0;
                fixed (byte* src = bgra)
                {
                    Buffer.MemoryCopy(src, _ptr + HeaderSize, FileSize - HeaderSize, size);
                }
                *(long*)(_ptr + OffTime) = DateTime.UtcNow.ToFileTimeUtc();
                Interlocked.Exchange(ref *seqPtr, ++_seq); // even => done
            }
        }

        /// <summary>The size an app (Teams, OBS...) is currently pulling from the camera, or null if none.</summary>
        public (int width, int height)? GetRequestedSize()
        {
            lock (_lock)
            {
                if (_ptr == null) return null;
                int w = (int)*(uint*)(_ptr + OffReqW);
                int h = (int)*(uint*)(_ptr + OffReqH);
                long t = *(long*)(_ptr + OffReqTime);
                if (w <= 0 || h <= 0 || t <= 0) return null;
                try
                {
                    var age = DateTime.UtcNow - DateTime.FromFileTimeUtc(t);
                    if (age > TimeSpan.FromSeconds(3) || age < TimeSpan.FromSeconds(-60)) return null;
                }
                catch { return null; }
                return (w, h);
            }
        }

        public void Dispose()
        {
            lock (_lock)
            {
                if (_ptr != null)
                {
                    _view.SafeMemoryMappedViewHandle.ReleasePointer();
                    _ptr = null;
                }
                _view?.Dispose(); _view = null;
                _mmf?.Dispose(); _mmf = null;
                _stream?.Dispose(); _stream = null;
            }
        }
    }
}
