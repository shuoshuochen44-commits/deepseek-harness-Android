# 构建 DSH Android APK（无 Gradle，直接用 build-tools 走 aapt2 / d8 / apksigner）
#
# 用法：pwsh -File tools\build-apk.ps1 [-Assets <dir>] [-JniLibs <dir>] [-Out <file>]
#   前置：tools\package-payload.ps1 已产出 assets\payload.zip
#
# -Assets/-JniLibs/-Out 用于出测试变体（例如 x86_64 模拟器包），默认是 arm64 正式包。
param(
    [switch]$SkipPayload,
    [string]$Keystore = "",
    [string]$KeystorePass = $env:DSH_KEYSTORE_PASSWORD,
    [string]$KeyAlias = $env:DSH_KEY_ALIAS,
    [string]$Assets = "",
    [string]$JniLibs = "",
    [string]$Out = "",
    [string]$BuildSub = "build",
    [switch]$Debuggable,
    [int]$VersionCode = 3,
    [string]$VersionName = "0.2.3-mobile"
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($KeystorePass)) { throw '请设置 DSH_KEYSTORE_PASSWORD 或传入 -KeystorePass。' }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw '请设置 DSH_KEY_ALIAS 或传入 -KeyAlias。' }
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME。' }
if (-not $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_HOME) { throw '请设置 ANDROID_SDK_ROOT。' }
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
$jdkl = $env:JAVA_HOME
$buildTools = Join-Path $sdk 'build-tools\37.0.0'
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
$appDir = Join-Path $root 'app'
$buildDir = Join-Path $root $BuildSub
$buildDir = [System.IO.Path]::GetFullPath($buildDir)
$workspacePrefix = [System.IO.Path]::GetFullPath($root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $buildDir.StartsWith($workspacePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw '构建目录必须位于当前工程内，避免清理其它目录。'
}
$distDir = Join-Path $root 'dist'

if (-not $Assets) { $Assets = Join-Path $appDir 'assets' }
if (-not $JniLibs) { $JniLibs = Join-Path $appDir 'jniLibs' }
if (-not $Out) { $Out = Join-Path $distDir 'DSH-android-optimized.apk' }
if (-not $Keystore) { $Keystore = Join-Path $root 'keystore\dsh-release.jks' }

if (-not (Test-Path $Keystore)) { throw '请通过 -Keystore 指定你自己的签名文件。' }
New-Item -ItemType Directory -Force -Path $buildDir, $distDir | Out-Null
$env:JAVA_HOME = $jdkl

Write-Host '==> [1/7] 编译 Java' -ForegroundColor Cyan
$classes = Join-Path $buildDir 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = Get-ChildItem (Join-Path $appDir 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$libJars = @(Get-ChildItem (Join-Path $appDir 'libs') -Filter *.jar -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName })
$classpath = $androidJar
if ($libJars.Count -gt 0) { $classpath = ($libJars + $androidJar) -join ';' }
& (Join-Path $jdkl 'bin\javac.exe') --release 11 -nowarn -encoding UTF-8 `
    -classpath $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '==> [2/7] d8 生成 dex' -ForegroundColor Cyan
$dexDir = Join-Path $buildDir 'dex'
if (Test-Path $dexDir) { Remove-Item -Recurse -Force $dexDir }
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$dexInputs = @($classFiles) + @($libJars)
& (Join-Path $buildTools 'd8.bat') --release --min-api 26 --lib $androidJar --output $dexDir @dexInputs
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '==> [3/7] 编译资源' -ForegroundColor Cyan
$resZip = Join-Path $buildDir 'res.zip'
if (Test-Path $resZip) { Remove-Item -Force $resZip }
& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $appDir 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '==> [4/7] 链接 APK' -ForegroundColor Cyan
$baseApk = Join-Path $buildDir 'base.apk'
if (Test-Path $baseApk) { Remove-Item -Force $baseApk }

$manifest = Join-Path $appDir 'AndroidManifest.xml'
if ($Debuggable) {
    # 诊断用：允许 run-as 读取 files/ 下的日志
    $dbg = Join-Path $buildDir 'AndroidManifest.debug.xml'
    (Get-Content $manifest -Raw) -replace '<application', '<application android:debuggable="true"' |
        Set-Content $dbg -Encoding utf8
    $manifest = $dbg
    Write-Host '    以 debuggable 构建（仅用于诊断）'
}

$aapt2Args = @(
    'link',
    '-I', $androidJar,
    '--manifest', $manifest,
    '-o', $baseApk,
    '--min-sdk-version', '26',
    '--target-sdk-version', '36',
    '--version-code', [string]$VersionCode,
    '--version-name', $VersionName,
    '--auto-add-overlay',
    '--no-version-vectors'
)
if (Test-Path $Assets) { $aapt2Args += @('-A', $Assets) }
$aapt2Args += $resZip
& (Join-Path $buildTools 'aapt2.exe') @aapt2Args
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '==> [5/7] 注入 classes.dex / jniLibs' -ForegroundColor Cyan
$unsigned = Join-Path $buildDir 'unsigned.apk'
if (Test-Path $unsigned) { Remove-Item -Force $unsigned }
Copy-Item $baseApk $unsigned

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    # Windows aapt2 may put backslashes in nested asset names. Android AssetManager
    # always opens them with '/', so normalize before signing the package.
    $nestedAssets = @($zip.Entries | Where-Object {
        $_.FullName.StartsWith('assets/') -and $_.FullName.Contains('\')
    })
    foreach ($asset in $nestedAssets) {
        $normalizedName = $asset.FullName.Replace('\', '/')
        if ($null -ne $zip.GetEntry($normalizedName)) { throw "重复资源: $normalizedName" }
        $buffer = [System.IO.MemoryStream]::new()
        $input = $asset.Open()
        try { $input.CopyTo($buffer) } finally { $input.Dispose() }
        $asset.Delete()
        $replacement = $zip.CreateEntry($normalizedName, [System.IO.Compression.CompressionLevel]::Optimal)
        $outputStream = $replacement.Open()
        try {
            $buffer.Position = 0
            $buffer.CopyTo($outputStream)
        } finally {
            $outputStream.Dispose()
            $buffer.Dispose()
        }
    }

    $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    $fs = [System.IO.File]::OpenRead((Join-Path $dexDir 'classes.dex'))
    $fs.CopyTo($es)
    $fs.Close(); $es.Close()

    $jni = $JniLibs
    if (Test-Path $jni) {
        $files = Get-ChildItem $jni -Recurse -File
        foreach ($f in $files) {
            $rel = 'lib/' + $f.FullName.Substring($jni.Length + 1).Replace('\', '/')
            $entry2 = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::NoCompression)
            $dstStream = $entry2.Open()
            $srcStream = [System.IO.File]::OpenRead($f.FullName)
            try {
                $srcStream.CopyTo($dstStream)
            } finally {
                $srcStream.Dispose()
                $dstStream.Dispose()
            }
            Write-Host ("    + {0} ({1:N1} MB)" -f $rel, ($f.Length / 1MB))
        }
    }
} finally {
    $zip.Dispose()
}

Write-Host '==> [6/7] zipalign' -ForegroundColor Cyan
$aligned = Join-Path $buildDir 'aligned.apk'
if (Test-Path $aligned) { Remove-Item -Force $aligned }
& (Join-Path $buildTools 'zipalign.exe') -f -P 16 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '==> [7/7] 签名' -ForegroundColor Cyan
$signed = $Out
if (Test-Path $signed) { Remove-Item -Force $signed }
& (Join-Path $buildTools 'apksigner.bat') sign `
    --ks $Keystore --ks-pass "pass:$KeystorePass" --key-pass "pass:$KeystorePass" `
    --ks-key-alias $KeyAlias `
    --min-sdk-version 26 --max-sdk-version 36 `
    --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
$verification = & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $signed
if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }
$verification | Select-Object -First 6

$size = (Get-Item $signed).Length / 1MB
Write-Host ("`n完成: {0}  ({1:N1} MB)" -f $signed, $size) -ForegroundColor Green
