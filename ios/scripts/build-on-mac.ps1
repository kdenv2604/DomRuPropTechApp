param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [ValidateSet('Simulator', 'Device')][string]$Target = 'Simulator',
    [switch]$Test,
    [string]$SimulatorID
)

$ErrorActionPreference = 'Stop'
$iosRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Test -and ($Target -ne 'Simulator' -or $SimulatorID -notmatch '^[0-9A-Fa-f-]{36}$')) {
    throw 'For tests, specify -Target Simulator and -SimulatorID from xcrun simctl list devices available.'
}
$archiveName = 'ios-source-' + [Guid]::NewGuid().ToString('N') + '.tar.gz'
$archive = Join-Path ([IO.Path]::GetTempPath()) $archiveName
$remoteRoot = 'Projects/DomRuPropTechApp/ios'

function Assert-NativeSuccess([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

try {
    # Только открытые исходники. Vendor, сборки, настройки Xcode и Android-ключи не передаются.
    & tar --exclude=xcuserdata --exclude='*.xcuserstate' -czf $archive -C $iosRoot `
        Sources Resources Tests UITests scripts DomruTechnics.xcodeproj README.md
    Assert-NativeSuccess 'Source archive'
    & ssh -o BatchMode=yes mac-mini "mkdir -p ~/$remoteRoot"
    Assert-NativeSuccess 'Remote directory'
    & scp -q $archive "mac-mini:Projects/DomRuPropTechApp/$archiveName"
    Assert-NativeSuccess 'Source upload'

    $destination = if ($Target -eq 'Device') { 'generic/platform=iOS' } elseif ($Test) { "platform=iOS Simulator,id=$SimulatorID" } else { 'generic/platform=iOS Simulator' }
    $signing = if ($Target -eq 'Device') { 'CODE_SIGNING_ALLOWED=NO' } else { 'CODE_SIGN_IDENTITY=-' }
    $action = if ($Test) { 'test' } else { 'build' }
    $buildDirectory = if ($Target -eq 'Device') { 'build-device' } else { 'build-signed-sim' }
    $commands = @"
set -euo pipefail
cd ~/Projects/DomRuPropTechApp
tar -xzf '$archiveName' -C ios
rm -- '$archiveName'
cd ios
bash scripts/bootstrap-vlc.sh
xcodebuild -project DomruTechnics.xcodeproj -scheme DomruTechnics -configuration '$Configuration' -destination '$destination' -derivedDataPath '$buildDirectory' '$signing' '$action'
"@
    # PowerShell добавляет CRLF при передаче строки в stdin; bash ожидает LF.
    $commands | & ssh -o BatchMode=yes mac-mini "tr -d '\r' | bash -s"
    Assert-NativeSuccess 'Xcode build'
} finally {
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
}
