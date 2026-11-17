# Ovra Cam

Use your Android phone (e.g. realme X2 Pro) as a webcam on Windows 11, over **USB or Wi-Fi**.
It adds a real Windows camera called **"Ovra Cam"** that works in Teams, Zoom, Google Meet, OBS and the Windows Camera app.

*Coded by Pranav Tagadpallewar · an Ovra app*

## Download
Every push to `main` builds both apps with GitHub Actions and publishes them under **Releases**:
- `OvraCam.apk` - install on the phone
- `OvraCam-Setup-x.y.z.exe` - install on the PC (Windows 11, needs admin once)

## Setup
1. **Phone:** install `OvraCam.apk`, open it, allow camera access.
2. **PC:** run the installer, then open Ovra Cam (it starts the "Ovra Cam" webcam).
3. **Connect:**
   - **USB (best):** turn on USB debugging (Settings → About phone → Version → tap *Build number* 7 times, then Settings → Additional settings → Developer options → *USB debugging*). Plug in, tap *Allow* on the phone. Ovra Cam connects by itself.
   - **Wi-Fi:** phone and PC on the same Wi-Fi. The phone appears in the list - press Connect, or type the IP shown on the phone.
4. In your meeting app, choose **Ovra Cam** as the camera.

## How it works
```
Phone (Camera2 → hardware H.264) ──TCP 5150 (USB via adb forward, or Wi-Fi)──► OvraCam.exe (H.264 decode via Media Foundation, rotate)
                                                                         │ writes frames to C:\ProgramData\OvraCam\frame.bin
                                                                         ▼
                                    OvraCamSource.dll (Media Foundation virtual camera, loaded by Windows Frame Server)
                                                                         ▼
                                                     Teams / Zoom / Meet / OBS / Camera app
```
- `android/` - Kotlin phone app (`com.ovra.cam`)
- `windows/OvraCamApp/` - WPF desktop app (.NET 8)
- `windows/OvraCamSource/` - C++ virtual camera, based on [VCamSample](https://github.com/smourier/VCamSample) by Simon Mourier (MIT)
- `windows/installer/` - Inno Setup script

The camera offers 1920×1080 (default), 1280×720 and 3840×2160 (4K) at 30 fps; the phone streams 720p, 1080p or 4K. Focus can be locked, refocused or set manually from either device. Ports: TCP 5150 (video), UDP 5151 (phone discovery).
