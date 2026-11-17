// Ovra Cam: H.264 decoding for the desktop app (OvraCam.exe).
// The phone sends hardware-encoded H.264 (up to 4K). We decode it with Windows' built-in
// Media Foundation H.264 decoder and convert NV12 -> BGRA with rotation, mirroring and scaling.
#include "pch.h"
#include "Tools.h"
#include "OvraShared.h"
#include <codecapi.h>
#include <ppl.h>

// {62CE7E72-4C71-4D20-B15D-452831A87D9D} CLSID_MSH264DecoderMFT (wmcodecdsp.h)
static const GUID CLSID_OvraH264Decoder = { 0x62ce7e72, 0x4c71, 0x4d20, { 0xb1, 0x5d, 0x45, 0x28, 0x31, 0xa8, 0x7d, 0x9d } };

struct OvraDecoder
{
	wil::com_ptr_nothrow<IMFTransform> mft;
	UINT codedWidth = 0;
	UINT codedHeight = 0;
	UINT width = 0;   // display size (cropped)
	UINT height = 0;
	UINT stride = 0;
	std::vector<BYTE> nv12;
	bool hasFrame = false;
	bool needOutputType = true;
};

static HRESULT SetNV12Output(OvraDecoder* d)
{
	for (DWORD i = 0;; i++)
	{
		wil::com_ptr_nothrow<IMFMediaType> type;
		auto hr = d->mft->GetOutputAvailableType(0, i, &type);
		if (FAILED(hr))
			return hr;

		GUID subtype{};
		if (SUCCEEDED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) && subtype == MFVideoFormat_NV12)
		{
			RETURN_IF_FAILED(d->mft->SetOutputType(0, type.get(), 0));
			UINT32 w = 0, h = 0;
			MFGetAttributeSize(type.get(), MF_MT_FRAME_SIZE, &w, &h);
			d->codedWidth = w;
			d->codedHeight = h;
			d->width = w;
			d->height = h;

			MFVideoArea area{};
			if (SUCCEEDED(type->GetBlob(MF_MT_MINIMUM_DISPLAY_APERTURE, (UINT8*)&area, sizeof(area), nullptr)))
			{
				if (area.Area.cx > 0 && area.Area.cy > 0 && (UINT)area.Area.cx <= w && (UINT)area.Area.cy <= h)
				{
					d->width = area.Area.cx;
					d->height = area.Area.cy;
				}
			}

			UINT32 stride = 0;
			if (FAILED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &stride)) || (INT32)stride <= 0)
			{
				stride = w;
			}
			d->stride = stride;
			d->needOutputType = false;
			return S_OK;
		}
	}
}

extern "C" __declspec(dllexport) void* __stdcall OvraDec_Create()
{
	std::ignore = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	std::ignore = MFStartup(MF_VERSION, MFSTARTUP_LITE);

	auto d = new (std::nothrow) OvraDecoder();
	if (!d)
		return nullptr;

	if (FAILED(CoCreateInstance(CLSID_OvraH264Decoder, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&d->mft))))
	{
		delete d;
		return nullptr;
	}

	wil::com_ptr_nothrow<IMFAttributes> atts;
	if (SUCCEEDED(d->mft->GetAttributes(&atts)))
	{
		atts->SetUINT32(CODECAPI_AVLowLatencyMode, TRUE);
	}

	wil::com_ptr_nothrow<IMFMediaType> input;
	if (FAILED(MFCreateMediaType(&input)))
	{
		delete d;
		return nullptr;
	}
	input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
	input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
	MFSetAttributeRatio(input.get(), MF_MT_FRAME_RATE, 30, 1);
	if (FAILED(d->mft->SetInputType(0, input.get(), 0)))
	{
		delete d;
		return nullptr;
	}

	std::ignore = SetNV12Output(d);
	d->needOutputType = false;
	d->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
	d->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
	return d;
}

extern "C" __declspec(dllexport) void __stdcall OvraDec_Destroy(void* handle)
{
	auto d = (OvraDecoder*)handle;
	if (!d)
		return;

	if (d->mft)
	{
		d->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
		d->mft->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
	}
	delete d;
}

// pulls every available decoded frame, keeps the latest one. returns number of frames
static int DrainOutput(OvraDecoder* d)
{
	int frames = 0;
	for (int guard = 0; guard < 64; guard++)
	{
		MFT_OUTPUT_STREAM_INFO info{};
		if (FAILED(d->mft->GetOutputStreamInfo(0, &info)))
			break;

		wil::com_ptr_nothrow<IMFSample> sample;
		bool provides = (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
		if (!provides)
		{
			wil::com_ptr_nothrow<IMFMediaBuffer> buffer;
			if (FAILED(MFCreateMemoryBuffer(max(info.cbSize, (DWORD)(d->codedWidth * d->codedHeight * 3 / 2)), &buffer)))
				break;
			if (FAILED(MFCreateSample(&sample)))
				break;
			sample->AddBuffer(buffer.get());
		}

		MFT_OUTPUT_DATA_BUFFER ob{};
		ob.dwStreamID = 0;
		ob.pSample = sample.get();
		DWORD status = 0;
		auto hr = d->mft->ProcessOutput(0, 1, &ob, &status);
		if (ob.pEvents)
		{
			ob.pEvents->Release();
		}

		if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT)
			break;

		if (hr == MF_E_TRANSFORM_STREAM_CHANGE)
		{
			if (FAILED(SetNV12Output(d)))
				break;
			continue;
		}

		if (FAILED(hr))
			break;

		IMFSample* out = ob.pSample;
		if (!out)
			continue;

		wil::com_ptr_nothrow<IMFMediaBuffer> buf;
		if (SUCCEEDED(out->ConvertToContiguousBuffer(&buf)))
		{
			auto cw = d->codedWidth;
			auto ch = d->codedHeight;
			auto size = (size_t)cw * ch * 3 / 2;
			if (d->nv12.size() != size)
			{
				d->nv12.resize(size);
			}

			wil::com_ptr_nothrow<IMF2DBuffer> buf2d;
			BYTE* scan = nullptr;
			LONG pitch = 0;
			bool locked2d = false;
			if (SUCCEEDED(buf.query_to(&buf2d)) && SUCCEEDED(buf2d->Lock2D(&scan, &pitch)) && pitch > 0)
			{
				locked2d = true;
			}
			else
			{
				DWORD maxLen = 0, curLen = 0;
				if (SUCCEEDED(buf->Lock(&scan, &maxLen, &curLen)))
				{
					pitch = (LONG)d->stride;
				}
				else
				{
					scan = nullptr;
				}
			}

			if (scan)
			{
				// Y then interleaved UV, copied to a compact buffer (stride = coded width)
				for (UINT y = 0; y < ch; y++)
				{
					CopyMemory(d->nv12.data() + (size_t)y * cw, scan + (size_t)y * pitch, cw);
				}
				auto uvSrc = scan + (size_t)pitch * ch;
				auto uvDst = d->nv12.data() + (size_t)cw * ch;
				for (UINT y = 0; y < ch / 2; y++)
				{
					CopyMemory(uvDst + (size_t)y * cw, uvSrc + (size_t)y * pitch, cw);
				}
				d->hasFrame = true;
				frames++;

				if (locked2d)
				{
					buf2d->Unlock2D();
				}
				else
				{
					buf->Unlock();
				}
			}
		}

		if (provides && out)
		{
			out->Release();
		}
	}
	return frames;
}

// Feed one H.264 access unit (Annex B, with start codes). Returns frames decoded (>= 0) or -1 on error.
extern "C" __declspec(dllexport) int __stdcall OvraDec_Decode(void* handle, const BYTE* data, int length, LONGLONG timestamp)
{
	auto d = (OvraDecoder*)handle;
	if (!d || !d->mft || !data || length <= 0)
		return -1;

	wil::com_ptr_nothrow<IMFMediaBuffer> buffer;
	if (FAILED(MFCreateMemoryBuffer(length, &buffer)))
		return -1;

	BYTE* p = nullptr;
	if (FAILED(buffer->Lock(&p, nullptr, nullptr)))
		return -1;
	CopyMemory(p, data, length);
	buffer->Unlock();
	buffer->SetCurrentLength(length);

	wil::com_ptr_nothrow<IMFSample> sample;
	if (FAILED(MFCreateSample(&sample)))
		return -1;
	sample->AddBuffer(buffer.get());
	sample->SetSampleTime(timestamp);
	sample->SetSampleDuration(333333);

	int frames = 0;
	auto hr = d->mft->ProcessInput(0, sample.get(), 0);
	if (hr == MF_E_NOTACCEPTING)
	{
		frames += DrainOutput(d);
		hr = d->mft->ProcessInput(0, sample.get(), 0);
	}
	if (FAILED(hr))
	{
		return frames > 0 ? frames : -1;
	}

	frames += DrainOutput(d);
	return frames;
}

static inline BYTE Clamp255(int v) { return (BYTE)(v < 0 ? 0 : (v > 255 ? 255 : v)); }

// Converts the latest decoded frame to top-down BGRA.
// rotation: clockwise 0/90/180/270. mirror: flip horizontally (after rotation).
// The result fits inside maxWidth x maxHeight (keeping the aspect ratio, never upscaling).
extern "C" __declspec(dllexport) int __stdcall OvraDec_Render(void* handle, BYTE* bgra, int capacity, int rotation, int mirror, int maxWidth, int maxHeight, int* outWidth, int* outHeight)
{
	auto d = (OvraDecoder*)handle;
	if (!d || !d->hasFrame || !bgra || !outWidth || !outHeight)
		return 0;

	int sw = (int)d->width;
	int sh = (int)d->height;
	int cw = (int)d->codedWidth;
	if (sw <= 0 || sh <= 0)
		return 0;

	rotation = ((rotation % 360) + 360) % 360;
	bool swap = rotation == 90 || rotation == 270;
	int rw = swap ? sh : sw;
	int rh = swap ? sw : sh;

	if (maxWidth <= 0 || maxWidth > OVRA_MAX_WIDTH) maxWidth = OVRA_MAX_WIDTH;
	if (maxHeight <= 0 || maxHeight > OVRA_MAX_HEIGHT) maxHeight = OVRA_MAX_HEIGHT;
	double scale = min(1.0, min((double)maxWidth / rw, (double)maxHeight / rh));
	int ow = max(2, ((int)(rw * scale)) & ~1);
	int oh = max(2, ((int)(rh * scale)) & ~1);
	if ((long long)ow * oh * 4 > capacity)
		return 0;

	const BYTE* Y = d->nv12.data();
	const BYTE* UV = Y + (size_t)cw * d->codedHeight;
	double inv = (double)rw / ow; // output -> rotated-space step (same for both axes, aspect kept)

	concurrency::parallel_for(0, oh, [&](int oy)
		{
			BYTE* dst = bgra + (size_t)oy * ow * 4;
			for (int ox = 0; ox < ow; ox++)
			{
				// position in the rotated (upright) image
				double fx = (ox + 0.5) * inv - 0.5;
				double fy = (oy + 0.5) * inv - 0.5;
				if (mirror) fx = (rw - 1) - fx;

				// back to source (sensor) coordinates
				double sx, sy;
				switch (rotation)
				{
				case 90: sx = fy; sy = (sh - 1) - fx; break;
				case 180: sx = (sw - 1) - fx; sy = (sh - 1) - fy; break;
				case 270: sx = (sw - 1) - fy; sy = fx; break;
				default: sx = fx; sy = fy; break;
				}
				if (sx < 0) sx = 0; if (sy < 0) sy = 0;
				if (sx > sw - 1) sx = sw - 1; if (sy > sh - 1) sy = sh - 1;

				// bilinear luma
				int x0 = (int)sx, y0 = (int)sy;
				int x1 = min(x0 + 1, sw - 1), y1 = min(y0 + 1, sh - 1);
				double ax = sx - x0, ay = sy - y0;
				const BYTE* r0 = Y + (size_t)y0 * cw;
				const BYTE* r1 = Y + (size_t)y1 * cw;
				double yv = (r0[x0] * (1 - ax) + r0[x1] * ax) * (1 - ay) + (r1[x0] * (1 - ax) + r1[x1] * ax) * ay;

				// nearest chroma
				const BYTE* uv = UV + (size_t)(y0 / 2) * cw + (size_t)(x0 & ~1);
				int c = (int)(yv + 0.5) - 16;
				int du = uv[0] - 128;
				int dv = uv[1] - 128;

				// BT.709 limited range (the phone encoder is configured for this)
				dst[0] = Clamp255((298 * c + 541 * du + 128) >> 8);            // B
				dst[1] = Clamp255((298 * c - 55 * du - 136 * dv + 128) >> 8);  // G
				dst[2] = Clamp255((298 * c + 459 * dv + 128) >> 8);            // R
				dst[3] = 255;
				dst += 4;
			}
		});

	*outWidth = ow;
	*outHeight = oh;
	return 1;
}

extern "C" __declspec(dllexport) int __stdcall OvraDec_GetSize(void* handle, int* width, int* height)
{
	auto d = (OvraDecoder*)handle;
	if (!d || !width || !height)
		return 0;
	*width = (int)d->width;
	*height = (int)d->height;
	return d->hasFrame ? 1 : 0;
}
