; Ovra Cam installer (Inno Setup 6). Built by GitHub Actions - see .github/workflows/build.yml
; Expects: ..\..\dist\app\ (OvraCam.exe + files), ..\..\dist\OvraCamSource.dll, ..\..\dist\adb\ (adb.exe + dlls)

#define AppName "Ovra Cam"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{4E2B7C1A-9D35-4F0E-8A61-3B7D2C9E5F10}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Ovra
AppCopyright=Coded by Pranav Tagadpallewar
DefaultDirName={autopf}\Ovra Cam
DefaultGroupName=Ovra Cam
DisableProgramGroupPage=yes
OutputDir=..\..\dist
OutputBaseFilename=OvraCam-Setup-{#AppVersion}
SetupIconFile=..\OvraCamApp\Assets\ovracam.ico
UninstallDisplayIcon={app}\OvraCam.exe
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
; Windows 11 is required for virtual cameras
MinVersion=10.0.22000
CloseApplications=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "startup"; Description: "Start Ovra Cam with Windows (in the tray)"; GroupDescription: "Options:"; Flags: unchecked

[Dirs]
; the virtual camera (Frame Server service) and the app share frames through this folder
Name: "{commonappdata}\OvraCam"; Permissions: everyone-modify

[Files]
Source: "..\..\dist\app\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\dist\OvraCamSource.dll"; DestDir: "{app}"; Flags: ignoreversion regserver restartreplace uninsrestartdelete 64bit
Source: "..\..\dist\adb\*"; DestDir: "{app}\adb"; Flags: ignoreversion
Source: "..\OvraCamSource\LICENSE-VCamSample.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Ovra Cam"; Filename: "{app}\OvraCam.exe"
Name: "{group}\Uninstall Ovra Cam"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Ovra Cam"; Filename: "{app}\OvraCam.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "OvraCam"; ValueData: """{app}\OvraCam.exe"" --tray"; Flags: uninsdeletevalue; Tasks: startup

[Run]
; allow Wi-Fi discovery (UDP 5151) and connections for the app
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Ovra Cam"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Ovra Cam"" dir=in action=allow program=""{app}\OvraCam.exe"" enable=yes profile=any"; Flags: runhidden waituntilterminated
Filename: "{app}\OvraCam.exe"; Description: "Open Ovra Cam now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM OvraCam.exe"; Flags: runhidden; RunOnceId: "KillApp"
Filename: "{app}\adb\adb.exe"; Parameters: "kill-server"; Flags: runhidden; RunOnceId: "KillAdb"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Ovra Cam"""; Flags: runhidden; RunOnceId: "DelFw"

[UninstallDelete]
Type: filesandordirs; Name: "{commonappdata}\OvraCam"

[Code]
// The camera DLL is loaded by Windows' Frame Server services; stop them so the file can be replaced.
// They start again automatically the next time an app opens a camera.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM OvraCam.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM adb.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\net.exe'), 'stop FrameServerMonitor', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\net.exe'), 'stop FrameServer', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;
