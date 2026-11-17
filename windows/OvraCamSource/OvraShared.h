#pragma once

// Shared frame file written by the Ovra Cam desktop app (OvraCam.exe) and read by this media source.
// A plain file is used (instead of a named mapping) because the media source runs inside
// Windows' Frame Server service (session 0), which cannot see the user's session objects.
// Layout must match windows/OvraCamApp/SharedFrameWriter.cs
#define OVRA_SHARED_FOLDER L"C:\\ProgramData\\OvraCam"
#define OVRA_SHARED_FILE L"C:\\ProgramData\\OvraCam\\frame.bin"
#define OVRA_MAGIC 0x4352564F // 'OVRC'
#define OVRA_HEADER_SIZE 4096
#define OVRA_MAX_WIDTH 1920
#define OVRA_MAX_HEIGHT 1080
#define OVRA_FILE_SIZE (OVRA_HEADER_SIZE + OVRA_MAX_WIDTH * OVRA_MAX_HEIGHT * 4)
#define OVRA_STALE_MS 1500 // after this without new frames, show the placeholder

#pragma pack(push, 1)
struct OvraSharedHeader
{
	UINT32 magic;
	UINT32 version;
	UINT32 width;   // BGRA32, top-down
	UINT32 height;
	UINT32 stride;
	UINT32 flags;
	volatile LONGLONG seq; // odd while the writer is writing
	LONGLONG writeTime;    // FILETIME (UTC) of the last frame
	UINT32 writerPid;
};
#pragma pack(pop)
