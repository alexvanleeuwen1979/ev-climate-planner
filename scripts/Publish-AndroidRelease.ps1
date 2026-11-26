[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [Parameter(Mandatory)]
    [string]$ApkPath,

    [Parameter(Mandatory)]
    [string]$NotesPath
)

$ErrorActionPreference = 'Stop'
$repository = 'alexvanleeuwen1979/ev-climate-planner'
$tag = "v$Version"
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$notes = (Resolve-Path -LiteralPath $NotesPath).Path
$project = Split-Path -Parent $PSScriptRoot
$commit = git -C $project rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Could not read the current project commit.' }

$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Install Android SDK Build-Tools first.' }

& (Join-Path $buildTools.FullName 'apksigner.bat') verify $apk
if ($LASTEXITCODE -ne 0) { throw 'The APK must have a valid release signature.' }
$metadata = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not read APK metadata.' }
if (!($metadata -match "^package: name='nl\.axlproductions\.kiaklimaatplanner' .*versionName='$([regex]::Escape($Version))'") -or
    $metadata -match '^application-debuggable') {
    throw "Expected a non-debug EV Climate Planner APK with version $Version."
}

if (!$PSCmdlet.ShouldProcess("$repository $tag", 'Publish the signed APK as the latest GitHub release')) { return }

$temp = Join-Path ([IO.Path]::GetTempPath()) ("ev-climate-release-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$asset = Join-Path $temp 'EV-Climate-Planner.apk'
try {
    Copy-Item -LiteralPath $apk -Destination $asset
    gh release create $tag $asset --repo $repository --target $commit --title "EV Climate Planner $tag" --notes-file $notes --latest
    if ($LASTEXITCODE -ne 0) { throw "GitHub rejected the new release $tag." }
} finally {
    if (Test-Path -LiteralPath $asset) { Remove-Item -LiteralPath $asset }
    Remove-Item -LiteralPath $temp
}
