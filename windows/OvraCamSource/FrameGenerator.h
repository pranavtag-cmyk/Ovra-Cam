#pragma once

class FrameGenerator
{
	UINT _width;
	UINT _height;
	ULONGLONG _frame;
	MFTIME _prevTime;
	UINT _fps;
	HANDLE _deviceHandle;
	wil::com_ptr_nothrow<ID3D11Texture2D> _texture;
	wil::com_ptr_nothrow<ID2D1RenderTarget> _renderTarget;
	wil::com_ptr_nothrow<ID2D1SolidColorBrush> _whiteBrush;
	wil::com_ptr_nothrow<IDWriteTextFormat> _textFormat;
	wil::com_ptr_nothrow<IDWriteFactory> _dwrite;
	wil::com_ptr_nothrow<IMFTransform> _converter;
	wil::com_ptr_nothrow<IWICBitmap> _bitmap;
	wil::com_ptr_nothrow<IMFDXGIDeviceManager> _dxgiManager;
	wil::com_ptr_nothrow<IDWriteTextFormat> _titleFormat;
	wil::com_ptr_nothrow<ID2D1SolidColorBrush> _accentBrush;
	wil::com_ptr_nothrow<ID2D1SolidColorBrush> _dimBrush;
	wil::com_ptr_nothrow<ID2D1Bitmap> _phoneBitmap;
	UINT _phoneBitmapWidth;
	UINT _phoneBitmapHeight;

	// Ovra Cam: frames written by the Ovra Cam desktop app into a shared file
	HANDLE _sharedFile;
	HANDLE _sharedMapping;
	const BYTE* _sharedView;
	ULONGLONG _lastOpenAttempt;
	LONGLONG _lastSeq;
	std::vector<BYTE> _frameCopy;
	UINT _frameWidth;
	UINT _frameHeight;
	ULONGLONG _lastFrameTime; // GetTickCount64 when a new frame was last seen

	HRESULT CreateRenderTargetResources(UINT width, UINT height);
	void EnsureSharedView();
	void CloseSharedView();
	bool ReadSharedFrame();
	void DrawPhoneFrame();
	void DrawPlaceholder();

public:
	FrameGenerator() :
		_width(0),
		_height(0),
		_frame(0),
		_fps(0),
		_deviceHandle(nullptr),
		_prevTime(MFGetSystemTime()),
		_phoneBitmapWidth(0),
		_phoneBitmapHeight(0),
		_sharedFile(INVALID_HANDLE_VALUE),
		_sharedMapping(nullptr),
		_sharedView(nullptr),
		_lastOpenAttempt(0),
		_lastSeq(-1),
		_frameWidth(0),
		_frameHeight(0),
		_lastFrameTime(0)
	{

	}

	~FrameGenerator()
	{
		CloseSharedView();
		if (_dxgiManager && _deviceHandle)
		{
			auto hr = _dxgiManager->CloseDeviceHandle(_deviceHandle); // don't report error at that point
			if (FAILED(hr))
			{
				WINTRACE(L"FrameGenerator CloseDeviceHandle: 0x%08X", hr);
			}
		}
	}

	HRESULT SetD3DManager(IUnknown* manager, UINT width, UINT height);
	const bool HasD3DManager() const;
	HRESULT EnsureRenderTarget(UINT width, UINT height);
	HRESULT Generate(IMFSample* sample, REFGUID format, IMFSample** outSample);
};