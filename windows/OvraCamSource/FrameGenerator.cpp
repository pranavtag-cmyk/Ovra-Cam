#include "pch.h"
#include "Undocumented.h"
#include "Tools.h"
#include "EnumNames.h"
#include "MFTools.h"
#include "FrameGenerator.h"
#include "OvraShared.h"

HRESULT FrameGenerator::EnsureRenderTarget(UINT width, UINT height)
{
	if (!HasD3DManager())
	{
		// create a D2D1 render target from WIC bitmap
		wil::com_ptr_nothrow<ID2D1Factory> d2d1Factory;
		RETURN_IF_FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, IID_PPV_ARGS(&d2d1Factory)));

		wil::com_ptr_nothrow<IWICImagingFactory> wicFactory;
		RETURN_IF_FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&wicFactory)));

		RETURN_IF_FAILED(wicFactory->CreateBitmap(width, height, GUID_WICPixelFormat32bppPBGRA, WICBitmapCacheOnDemand, &_bitmap));

		D2D1_RENDER_TARGET_PROPERTIES props{};
		props.pixelFormat.format = DXGI_FORMAT_B8G8R8A8_UNORM;
		props.pixelFormat.alphaMode = D2D1_ALPHA_MODE_PREMULTIPLIED;
		RETURN_IF_FAILED(d2d1Factory->CreateWicBitmapRenderTarget(_bitmap.get(), props, &_renderTarget));

		RETURN_IF_FAILED(CreateRenderTargetResources(width, height));
	}

	_prevTime = MFGetSystemTime();
	_frame = 0;
	return S_OK;
}

const bool FrameGenerator::HasD3DManager() const
{
	return _texture != nullptr;
}

HRESULT FrameGenerator::SetD3DManager(IUnknown* manager, UINT width, UINT height)
{
	RETURN_HR_IF_NULL(E_POINTER, manager);
	RETURN_HR_IF(E_INVALIDARG, !width || !height);

	RETURN_IF_FAILED(manager->QueryInterface(&_dxgiManager));
	RETURN_IF_FAILED(_dxgiManager->OpenDeviceHandle(&_deviceHandle));

	wil::com_ptr_nothrow<ID3D11Device> device;
	RETURN_IF_FAILED(_dxgiManager->GetVideoService(_deviceHandle, IID_PPV_ARGS(&device)));

	// create a texture/surface to write
	CD3D11_TEXTURE2D_DESC desc
	(
		DXGI_FORMAT_B8G8R8A8_UNORM,
		width,
		height,
		1,
		1,
		D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET
	);
	RETURN_IF_FAILED(device->CreateTexture2D(&desc, nullptr, &_texture));
	wil::com_ptr_nothrow<IDXGISurface> surface;
	RETURN_IF_FAILED(_texture.copy_to(&surface));

	// create a D2D1 render target from 2D GPU surface
	wil::com_ptr_nothrow<ID2D1Factory> d2d1Factory;
	RETURN_IF_FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, IID_PPV_ARGS(&d2d1Factory)));

	auto props = D2D1::RenderTargetProperties
	(
		D2D1_RENDER_TARGET_TYPE_DEFAULT,
		D2D1::PixelFormat(DXGI_FORMAT_UNKNOWN, D2D1_ALPHA_MODE_PREMULTIPLIED)
	);
	RETURN_IF_FAILED(d2d1Factory->CreateDxgiSurfaceRenderTarget(surface.get(), props, &_renderTarget));

	RETURN_IF_FAILED(CreateRenderTargetResources(width, height));

	// create GPU RGB => NV12 converter
	RETURN_IF_FAILED(CoCreateInstance(CLSID_VideoProcessorMFT, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&_converter)));

	wil::com_ptr_nothrow<IMFAttributes> atts;
	RETURN_IF_FAILED(_converter->GetAttributes(&atts));
	TraceMFAttributes(atts.get(), L"VideoProcessorMFT");

	MFT_OUTPUT_STREAM_INFO info{};
	RETURN_IF_FAILED(_converter->GetOutputStreamInfo(0, &info));
	WINTRACE(L"FrameGenerator::SetD3DManager CLSID_VideoProcessorMFT flags:0x%08X size:%u alignment:%u", info.dwFlags, info.cbSize, info.cbAlignment);

	wil::com_ptr_nothrow<IMFMediaType> inputType;
	RETURN_IF_FAILED(MFCreateMediaType(&inputType));
	inputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	inputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
	MFSetAttributeSize(inputType.get(), MF_MT_FRAME_SIZE, width, height);
	RETURN_IF_FAILED(_converter->SetInputType(0, inputType.get(), 0));

	wil::com_ptr_nothrow<IMFMediaType> outputType;
	RETURN_IF_FAILED(MFCreateMediaType(&outputType));
	outputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	outputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
	MFSetAttributeSize(outputType.get(), MF_MT_FRAME_SIZE, width, height);
	RETURN_IF_FAILED(_converter->SetOutputType(0, outputType.get(), 0));

	// make sure the video processor works on GPU
	RETURN_IF_FAILED(_converter->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, (ULONG_PTR)manager));
	return S_OK;
}

// common to CPU & GPU
HRESULT FrameGenerator::CreateRenderTargetResources(UINT width, UINT height)
{
	assert(_renderTarget);
	RETURN_IF_FAILED(_renderTarget->CreateSolidColorBrush(D2D1::ColorF(1, 1, 1, 1), &_whiteBrush));
	RETURN_IF_FAILED(_renderTarget->CreateSolidColorBrush(D2D1::ColorF(0.486f, 0.427f, 1.0f, 1), &_accentBrush)); // #7C6DFF
	RETURN_IF_FAILED(_renderTarget->CreateSolidColorBrush(D2D1::ColorF(0.62f, 0.64f, 0.70f, 1), &_dimBrush));
	_phoneBitmap.reset();
	_phoneBitmapWidth = 0;
	_phoneBitmapHeight = 0;

	RETURN_IF_FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), (IUnknown**)&_dwrite));
	RETURN_IF_FAILED(_dwrite->CreateTextFormat(L"Segoe UI", nullptr, DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, (FLOAT)height / 30, L"", &_textFormat));
	RETURN_IF_FAILED(_textFormat->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_NEAR));
	RETURN_IF_FAILED(_textFormat->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER));
	RETURN_IF_FAILED(_dwrite->CreateTextFormat(L"Segoe UI", nullptr, DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, (FLOAT)height / 12, L"", &_titleFormat));
	RETURN_IF_FAILED(_titleFormat->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_FAR));
	RETURN_IF_FAILED(_titleFormat->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER));
	_width = width;
	_height = height;
	return S_OK;
}

HRESULT FrameGenerator::Generate(IMFSample* sample, REFGUID format, IMFSample** outSample)
{
	RETURN_HR_IF_NULL(E_POINTER, sample);
	RETURN_HR_IF_NULL(E_POINTER, outSample);
	*outSample = nullptr;

	// render the latest phone frame (or a placeholder) - common to CPU & GPU
	if (_renderTarget && _textFormat && _dwrite && _whiteBrush)
	{
		auto hasFrame = ReadSharedFrame();
		_renderTarget->BeginDraw();
		if (hasFrame)
		{
			DrawPhoneFrame();
		}
		else
		{
			DrawPlaceholder();
		}
		auto hrDraw = _renderTarget->EndDraw();
		if (hrDraw == D2DERR_RECREATE_TARGET)
		{
			_phoneBitmap.reset();
		}
	}

	// build a sample using either D3D/DXGI (GPU) or WIC (CPU)
	wil::com_ptr_nothrow<IMFMediaBuffer> mediaBuffer;
	if (HasD3DManager())
	{
		// remove all existing buffers
		RETURN_IF_FAILED(sample->RemoveAllBuffers());

		// create a buffer from this and add to sample
		RETURN_IF_FAILED(MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), _texture.get(), 0, 0, &mediaBuffer));
		RETURN_IF_FAILED(sample->AddBuffer(mediaBuffer.get()));

		// if we're on GPU & format is not RGB, convert using GPU
		if (format == MFVideoFormat_NV12)
		{
			assert(_converter);
			RETURN_IF_FAILED(_converter->ProcessInput(0, sample, 0));

			// let converter build the sample for us, note it works because we gave it the D3DManager
			MFT_OUTPUT_DATA_BUFFER buffer = {};
			DWORD status = 0;
			RETURN_IF_FAILED(_converter->ProcessOutput(0, 1, &buffer, &status));
			*outSample = buffer.pSample;
		}
		else
		{
			sample->AddRef();
			*outSample = sample;
		}

		_frame++;
		return S_OK;
	}

	RETURN_IF_FAILED(sample->GetBufferByIndex(0, &mediaBuffer));
	wil::com_ptr_nothrow<IMF2DBuffer2> buffer2D;
	BYTE* scanline;
	LONG pitch;
	BYTE* start;
	DWORD length;
	RETURN_IF_FAILED(mediaBuffer->QueryInterface(IID_PPV_ARGS(&buffer2D)));
	RETURN_IF_FAILED(buffer2D->Lock2DSize(MF2DBuffer_LockFlags_Write, &scanline, &pitch, &start, &length));

	wil::com_ptr_nothrow<IWICBitmapLock> lock;
	auto hr = _bitmap->Lock(nullptr, WICBitmapLockRead, &lock);
	// now we're using regular COM macros because we want to be sure to unlock (or we could use try/catch)
	if (SUCCEEDED(hr))
	{
		UINT w, h;
		hr = lock->GetSize(&w, &h);
		if (SUCCEEDED(hr))
		{
			UINT wicStride;
			hr = lock->GetStride(&wicStride);
			if (SUCCEEDED(hr))
			{
				UINT wicSize;
				WICInProcPointer wicPointer;
				hr = lock->GetDataPointer(&wicSize, &wicPointer);
				if (SUCCEEDED(hr))
				{
					WINTRACE(L"WIC stride:%u WIC size:%u MF pitch:%u MF length:%u frame:%u format:%s", wicStride, wicSize, pitch, length, _frame, GUID_ToStringW(format).c_str());
					if (format == MFVideoFormat_NV12)
					{
						// note we could use MF's converter too
						hr = RGB32ToNV12(wicPointer, wicSize, wicStride, w, h, scanline, length, pitch);
					}
					else
					{
						hr = (wicSize != length || wicStride != pitch) ? E_FAIL : S_OK;
						if (SUCCEEDED(hr))
						{
							if (assert_true(wicPointer)) // WIC annotation is currently wrong on GetDataPointer wicPointer arg
							{
								CopyMemory(scanline, wicPointer, length);
							}
						}
					}

					if (SUCCEEDED(hr))
					{
						_frame++;
						sample->AddRef();
						*outSample = sample;
					}
				}
			}
		}
		lock.reset();
	}

	buffer2D->Unlock2D();
	return hr;
}


// ---------------- Ovra Cam shared frame ----------------

void FrameGenerator::CloseSharedView()
{
	if (_sharedView)
	{
		UnmapViewOfFile(_sharedView);
		_sharedView = nullptr;
	}

	if (_sharedMapping)
	{
		CloseHandle(_sharedMapping);
		_sharedMapping = nullptr;
	}

	if (_sharedFile != INVALID_HANDLE_VALUE)
	{
		CloseHandle(_sharedFile);
		_sharedFile = INVALID_HANDLE_VALUE;
	}
}

void FrameGenerator::EnsureSharedView()
{
	if (_sharedView)
		return;

	auto now = GetTickCount64();
	if (_lastOpenAttempt && now - _lastOpenAttempt < 1000)
		return;

	_lastOpenAttempt = now;
	_sharedFile = CreateFileW(OVRA_SHARED_FILE, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
	if (_sharedFile == INVALID_HANDLE_VALUE)
		return;

	LARGE_INTEGER size{};
	if (!GetFileSizeEx(_sharedFile, &size) || size.QuadPart < OVRA_FILE_SIZE)
	{
		CloseSharedView();
		return;
	}

	_sharedMapping = CreateFileMappingW(_sharedFile, nullptr, PAGE_READONLY, 0, 0, nullptr);
	if (!_sharedMapping)
	{
		CloseSharedView();
		return;
	}

	_sharedView = (const BYTE*)MapViewOfFile(_sharedMapping, FILE_MAP_READ, 0, 0, OVRA_FILE_SIZE);
	if (!_sharedView)
	{
		CloseSharedView();
		return;
	}
	WINTRACE(L"OvraCam shared frame file opened");
}

// returns true if there's a recent phone frame in _frameCopy
bool FrameGenerator::ReadSharedFrame()
{
	EnsureSharedView();
	if (_sharedView)
	{
		auto header = (const OvraSharedHeader*)_sharedView;
		if (header->magic == OVRA_MAGIC)
		{
			auto seq1 = header->seq;
			MemoryBarrier();
			if (!(seq1 & 1) && seq1 != _lastSeq)
			{
				auto w = header->width;
				auto h = header->height;
				auto stride = header->stride;
				if (w >= 16 && h >= 16 && w <= OVRA_MAX_WIDTH && h <= OVRA_MAX_HEIGHT && stride == w * 4)
				{
					auto size = (size_t)stride * h;
					if (_frameCopy.size() < size)
					{
						_frameCopy.resize(size);
					}

					CopyMemory(_frameCopy.data(), _sharedView + OVRA_HEADER_SIZE, size);
					MemoryBarrier();
					auto seq2 = header->seq;
					if (seq1 == seq2)
					{
						_lastSeq = seq1;
						_frameWidth = w;
						_frameHeight = h;
						_lastFrameTime = GetTickCount64();
						// push into the D2D bitmap
						if (_renderTarget)
						{
							if (!_phoneBitmap || _phoneBitmapWidth != w || _phoneBitmapHeight != h)
							{
								_phoneBitmap.reset();
								auto props = D2D1::BitmapProperties(D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE));
								if (SUCCEEDED(_renderTarget->CreateBitmap(D2D1::SizeU(w, h), _frameCopy.data(), stride, props, &_phoneBitmap)))
								{
									_phoneBitmapWidth = w;
									_phoneBitmapHeight = h;
								}
							}
							else
							{
								_phoneBitmap->CopyFromMemory(nullptr, _frameCopy.data(), stride);
							}
						}
					}
				}
			}
		}
	}

	return _phoneBitmap && _lastFrameTime && (GetTickCount64() - _lastFrameTime) < OVRA_STALE_MS;
}

void FrameGenerator::DrawPhoneFrame()
{
	_renderTarget->Clear(D2D1::ColorF(0, 0, 0, 1));

	// fit (letterbox) the phone frame into the output
	auto fw = (float)_phoneBitmapWidth;
	auto fh = (float)_phoneBitmapHeight;
	auto scale = min((float)_width / fw, (float)_height / fh);
	auto dw = fw * scale;
	auto dh = fh * scale;
	auto x = ((float)_width - dw) / 2;
	auto y = ((float)_height - dh) / 2;
	_renderTarget->DrawBitmap(_phoneBitmap.get(), D2D1::RectF(x, y, x + dw, y + dh), 1.0f, D2D1_BITMAP_INTERPOLATION_MODE_LINEAR);
	_frame++;
}

void FrameGenerator::DrawPlaceholder()
{
	_renderTarget->Clear(D2D1::ColorF(0.055f, 0.059f, 0.075f, 1)); // #0E0F13

	auto w = (float)_width;
	auto h = (float)_height;

	// simple camera glyph: rounded body + lens ring
	auto cx = w / 2;
	auto cy = h * 0.36f;
	auto bodyW = h * 0.20f;
	auto bodyH = h * 0.13f;
	_renderTarget->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(cx - bodyW / 2, cy - bodyH / 2, cx + bodyW / 2, cy + bodyH / 2), h * 0.02f, h * 0.02f), _accentBrush.get(), h * 0.008f);
	_renderTarget->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), bodyH * 0.30f, bodyH * 0.30f), _accentBrush.get(), h * 0.008f);

	const wchar_t* title = L"Ovra Cam";
	_renderTarget->DrawText(title, (UINT32)wcslen(title), _titleFormat.get(), D2D1::RectF(0, 0, w, h * 0.58f), _whiteBrush.get());

	const wchar_t* text = _sharedView ?
		L"Waiting for your phone\u2026\nOpen Ovra Cam on your phone and connect it in the Ovra Cam app on this PC." :
		L"Open the Ovra Cam app on this PC to connect your phone.";
	_renderTarget->DrawText(text, (UINT32)wcslen(text), _textFormat.get(), D2D1::RectF(w * 0.1f, h * 0.62f, w * 0.9f, h), _dimBrush.get());
	_frame++;
}
