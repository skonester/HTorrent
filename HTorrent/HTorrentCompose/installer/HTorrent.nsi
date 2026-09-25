; HTorrent Windows installer (NSIS 3). Built by `gradlew packageInstaller`, which passes:
;   /DVERSION=1.0.0  /DSOURCE_DIR=<build/dist/HTorrent>  /DOUT_FILE=<setup exe path>
; Installs the packageApp layout (HTorrent.exe launcher + runtime/) per machine.

Unicode true
ManifestDPIAware true
SetCompressor /SOLID lzma

!ifndef VERSION
  !error "Pass /DVERSION=x.y.z"
!endif
!ifndef SOURCE_DIR
  !error "Pass /DSOURCE_DIR=path\to\build\dist\HTorrent"
!endif
!ifndef OUT_FILE
  !define OUT_FILE "HTorrent-Setup-${VERSION}.exe"
!endif

!define APP_NAME "HTorrent"
!define PUBLISHER "Skonester"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}"

!include "MUI2.nsh"
!include "x64.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"

Name "${APP_NAME} ${VERSION}"
OutFile "${OUT_FILE}"
InstallDir "$PROGRAMFILES64\${APP_NAME}"
InstallDirRegKey HKLM "${UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel admin
BrandingText "${APP_NAME} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "${APP_NAME} Setup"
VIAddVersionKey "CompanyName" "${PUBLISHER}"
VIAddVersionKey "LegalCopyright" "Copyright (c) 2026 ${PUBLISHER}"

!define MUI_ICON "${__FILEDIR__}\..\icon\favicon.ico"
!define MUI_UNICON "${__FILEDIR__}\..\icon\favicon.ico"
!define MUI_ABORTWARNING
!define MUI_COMPONENTSPAGE_NODESC
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Launch ${APP_NAME}"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchUnelevated

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

; The launcher and the jpackage runtime are both named HTorrent.exe; the stream player is HTorrentPlayer.exe.
; CSV output starts with the quoted image name when it is running, and with a localized "INFO:" line when it is not.
!macro WaitForAppExit
  app_running_check:
  nsExec::ExecToStack 'tasklist /FI "IMAGENAME eq HTorrent.exe" /NH /FO CSV'
  Pop $0
  Pop $1
  StrCpy $2 $1 14
  nsExec::ExecToStack 'tasklist /FI "IMAGENAME eq HTorrentPlayer.exe" /NH /FO CSV'
  Pop $0
  Pop $1
  StrCpy $3 $1 20
  ${If} $2 == '"HTorrent.exe"'
  ${OrIf} $3 == '"HTorrentPlayer.exe"'
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "${APP_NAME} is running. Close it, then click Retry." /SD IDCANCEL IDRETRY app_running_check
    Abort
  ${EndIf}
!macroend

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "${APP_NAME} requires 64-bit Windows." /SD IDOK
    Abort
  ${EndIf}
  SetRegView 64
FunctionEnd

Function un.onInit
  SetRegView 64
FunctionEnd

; The installer runs elevated; start the app through Explorer so it runs as the normal user.
Function LaunchUnelevated
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\HTorrent.exe"'
FunctionEnd

Section "${APP_NAME}" SecApp
  SectionIn RO
  !insertmacro WaitForAppExit
  SetShellVarContext all
  SetOutPath "$INSTDIR"
  ; Drop the previous version's JRE/JARs and player so upgrades never mix old and new files.
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\player"
  File /r "${SOURCE_DIR}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\HTorrent.exe" "" "$INSTDIR\HTorrent.exe" 0
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\HTorrent.exe"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "EstimatedSize" "$0"
SectionEnd

Section "Desktop shortcut" SecDesktop
  SetShellVarContext all
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\HTorrent.exe" "" "$INSTDIR\HTorrent.exe" 0
SectionEnd

; Removes only what the installer put down. Downloads and ~\.htorrent session data are left alone.
Section "Uninstall"
  !insertmacro WaitForAppExit
  SetShellVarContext all
  Delete "$DESKTOP\${APP_NAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APP_NAME}"

  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\player"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\HTorrent.exe"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\RQBIT-NOTICE.md"
  Delete "$INSTDIR\TORRENTSEARCH-NOTICE.md"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKLM "${UNINSTALL_KEY}"
SectionEnd
