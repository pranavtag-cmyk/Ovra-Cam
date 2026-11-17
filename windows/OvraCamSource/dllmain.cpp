#include "pch.h"
#include "Undocumented.h"
#include "Tools.h"
#include "EnumNames.h"
#include "MFTools.h"
#include "FrameGenerator.h"
#include "MediaStream.h"
#include "MediaSource.h"
#include "Activator.h"

// Ovra Cam media source: 9b7e3c41-6a2d-4f58-b0e3-2c5d8f1a7e64
GUID CLSID_VCam = { 0x9b7e3c41,0x6a2d,0x4f58,{0xb0,0xe3,0x2c,0x5d,0x8f,0x1a,0x7e,0x64} };
HMODULE _hModule;

BOOL APIENTRY DllMain(HMODULE hModule, DWORD dwReason, LPVOID lpReserved)
{
	switch (dwReason)
	{
	case DLL_PROCESS_ATTACH:
		_hModule = hModule;
		WinTraceRegister();
		WINTRACE(L"DllMain DLL_PROCESS_ATTACH '%s'", GetCommandLine());
		DisableThreadLibraryCalls(hModule);

		wil::SetResultLoggingCallback([](wil::FailureInfo const& failure) noexcept
			{
				wchar_t str[2048];
				if (SUCCEEDED(wil::GetFailureLogString(str, _countof(str), failure)))
				{
					WinTrace(2, 0, str); // 2 => error
				}
			});
		break;

	case DLL_PROCESS_DETACH:
		WINTRACE(L"DllMain DLL_PROCESS_DETACH '%s'", GetCommandLine());
		WinTraceUnregister();
		break;
	}
	return TRUE;
}

struct ClassFactory : winrt::implements<ClassFactory, IClassFactory>
{
	STDMETHODIMP CreateInstance(IUnknown* outer, GUID const& riid, void** result) noexcept final
	{
		RETURN_HR_IF_NULL(E_POINTER, result);
		*result = nullptr;
		if (outer)
			RETURN_HR(CLASS_E_NOAGGREGATION);

		auto vcam = winrt::make_self<Activator>();
		RETURN_IF_FAILED(vcam->Initialize());
		auto hr = vcam->QueryInterface(riid, result);
		if (FAILED(hr))
		{
			auto iid = GUID_ToStringW(riid);
			WINTRACE(L"ClassFactory QueryInterface failed on IID %s", iid.c_str());
		}
		return hr;
	}

	STDMETHODIMP LockServer(BOOL) noexcept final
	{
		return S_OK;
	}
};

__control_entrypoint(DllExport)
STDAPI DllCanUnloadNow()
{
	if (winrt::get_module_lock())
	{
		WINTRACE(L"DllCanUnloadNow S_FALSE");
		return S_FALSE;
	}

	winrt::clear_factory_cache();
	WINTRACE(L"DllCanUnloadNow S_OK");
	return S_OK;
}

_Check_return_
STDAPI DllGetClassObject(_In_ REFCLSID rclsid, _In_ REFIID riid, _Outptr_ LPVOID FAR* ppv)
{
	WINTRACE(L"DllGetClassObject rclsid:%s riid:%s", GUID_ToStringW(rclsid).c_str(), GUID_ToStringW(riid).c_str());
	RETURN_HR_IF_NULL(E_POINTER, ppv);
	*ppv = nullptr;

	if (rclsid == CLSID_VCam)
		return winrt::make_self<ClassFactory>()->QueryInterface(riid, ppv);

	RETURN_HR(E_NOINTERFACE);
}

using registry_key = winrt::handle_type<registry_traits>;

STDAPI DllRegisterServer()
{
	std::wstring exePath = wil::GetModuleFileNameW(_hModule).get();
	WINTRACE(L"DllRegisterServer '%s'", exePath.c_str());
	auto clsid = GUID_ToStringW(CLSID_VCam, false);
	std::wstring path = L"Software\\Classes\\CLSID\\" + clsid + L"\\InprocServer32";

	// note: a vcam *must* be registered in HKEY_LOCAL_MACHINE
	// for the frame server to be able to talk with it.
	registry_key key;
	RETURN_IF_WIN32_ERROR(RegWriteKey(HKEY_LOCAL_MACHINE, path.c_str(), key.put()));
	RETURN_IF_WIN32_ERROR(RegWriteValue(key.get(), nullptr, exePath));
	RETURN_IF_WIN32_ERROR(RegWriteValue(key.get(), L"ThreadingModel", L"Both"));
	return S_OK;
}

STDAPI DllUnregisterServer()
{
	std::wstring exePath = wil::GetModuleFileNameW(_hModule).get();
	WINTRACE(L"DllUnregisterServer '%s'", exePath.c_str());
	auto clsid = GUID_ToStringW(CLSID_VCam, false);
	std::wstring path = L"Software\\Classes\\CLSID\\" + clsid;
	RETURN_IF_WIN32_ERROR(RegDeleteTree(HKEY_LOCAL_MACHINE, path.c_str()));
	return S_OK;
}

// ---------------- Ovra Cam: virtual camera control, called by OvraCam.exe ----------------
// These are called from the desktop app (on an MTA thread). The camera lives as long as the app
// (MFVirtualCameraLifetime_Session), so it disappears cleanly when the app exits.

static wil::com_ptr_nothrow<IMFVirtualCamera> _ovraCam;
static bool _ovraMfStarted = false;

extern "C" __declspec(dllexport) HRESULT __stdcall OvraCam_Start(LPCWSTR friendlyName)
{
	if (_ovraCam)
		return S_OK;

	if (!_ovraMfStarted)
	{
		RETURN_IF_FAILED(MFStartup(MF_VERSION));
		_ovraMfStarted = true;
	}

	// must be the "{xxxxxxxx-...}" form (resolve=false), not the friendly name
	auto clsid = GUID_ToStringW(CLSID_VCam, false);
	wil::com_ptr_nothrow<IMFVirtualCamera> cam;
	RETURN_IF_FAILED_MSG(MFCreateVirtualCamera(
		MFVirtualCameraType_SoftwareCameraSource,
		MFVirtualCameraLifetime_Session,
		MFVirtualCameraAccess_CurrentUser,
		friendlyName && *friendlyName ? friendlyName : L"Ovra Cam",
		clsid.c_str(),
		nullptr,
		0,
		&cam), "Failed to create virtual camera");

	RETURN_IF_FAILED_MSG(cam->Start(nullptr), "Cannot start virtual camera");
	_ovraCam = cam;
	return S_OK;
}

extern "C" __declspec(dllexport) HRESULT __stdcall OvraCam_Stop()
{
	if (!_ovraCam)
		return S_OK;

	// note: don't call Shutdown, Remove is enough (see VCamSample notes)
	auto hr = _ovraCam->Remove();
	_ovraCam.reset();
	return hr;
}

extern "C" __declspec(dllexport) BOOL __stdcall OvraCam_IsRegistered()
{
	auto clsid = GUID_ToStringW(CLSID_VCam, false);
	std::wstring path = L"Software\\Classes\\CLSID\\" + clsid + L"\\InprocServer32";
	HKEY key;
	if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, path.c_str(), 0, KEY_READ, &key) != ERROR_SUCCESS)
		return FALSE;

	RegCloseKey(key);
	return TRUE;
}
