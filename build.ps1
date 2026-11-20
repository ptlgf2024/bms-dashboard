$ErrorActionPreference = 'Continue'
# 外部工具（javac / java / aapt2）常将提示写入 stderr，PowerShell 5.1 在 Stop 模式下
# 会误判为终止错误；改为 Continue 后依赖下方每步的 $LASTEXITCODE 检查捕获真实失败。
$ROOT = $PSScriptRoot
$WORK = 'C:\bmsdash_build'

$SDK  = 'C:\Android\sdk'
$BT34 = "$SDK\build-tools\34.0.0"
$D8   = "$SDK\build-tools\36.0.0\lib\d8.jar"
$AJAR = "$SDK\platforms\android-34\android.jar"
$OUT  = "$WORK\build"
$GEN  = "$OUT\gen"
$CLS  = "$OUT\classes"
$APK  = "$OUT\apk"

Remove-Item -Recurse -Force $WORK -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$WORK\src", "$WORK\res", $GEN, $CLS, $APK | Out-Null
Copy-Item -Path "$ROOT\src\*" -Destination "$WORK\src" -Recurse -Force
Copy-Item -Path "$ROOT\res\*" -Destination "$WORK\res" -Recurse -Force
Copy-Item -Path "$ROOT\AndroidManifest.xml" -Destination "$WORK\AndroidManifest.xml" -Force

Write-Host '== 1. aapt2 compile =='
& "$BT34\aapt2.exe" compile --dir "$WORK\res" -o "$OUT\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '== 2. aapt2 link =='
& "$BT34\aapt2.exe" link -o "$OUT\base.apk" `
    -I $AJAR `
    --manifest "$WORK\AndroidManifest.xml" `
    --java $GEN `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    "$OUT\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== 3. javac =='
$srcs = Get-ChildItem -Path "$WORK\src" -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$gen  = Get-ChildItem -Path $GEN -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath $AJAR -d $CLS ($srcs + $gen)
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '== 4. jar =='
& jar cf "$APK\classes.jar" -C $CLS .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Host '== 5. d8 =='
& java -cp $D8 com.android.tools.r8.D8 --lib $AJAR --min-api 24 --output "$APK" "$APK\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== 6. merge dex =='
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Add-FileToZip($zipPath, $filePath, $entryName) {
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Update')
    try {
        $existing = $zip.GetEntry($entryName)
        if ($existing) { $existing.Delete() }
        $fs = [System.IO.File]::OpenRead($filePath)
        try {
            $entry = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $es = $entry.Open()
            try { $fs.CopyTo($es) } finally { $es.Dispose() }
        } finally { $fs.Dispose() }
    } finally { $zip.Dispose() }
}
Copy-Item "$OUT\base.apk" "$APK\app-unsigned.apk" -Force
Add-FileToZip "$APK\app-unsigned.apk" "$APK\classes.dex" 'classes.dex'

Write-Host '== 7. zipalign =='
& "$BT34\zipalign.exe" -f -p 4 "$APK\app-unsigned.apk" "$APK\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== 8. sign =='
# 签名密码从环境变量读取，避免硬编码泄露：
#   $env:BMS_KS_PASS = '你的密码'; .\build.ps1
$KS_PASS = $env:BMS_KS_PASS
if (-not $KS_PASS) { throw '请设置环境变量 BMS_KS_PASS（签名密钥密码）' }
$KS_ALIAS = if ($env:BMS_KS_ALIAS) { $env:BMS_KS_ALIAS } else { 'silentmonitor' }
& java -jar "$BT34\lib\apksigner.jar" sign `
    --ks "$ROOT\release.keystore" `
    --ks-key-alias $KS_ALIAS `
    --ks-pass "pass:$KS_PASS" `
    --key-pass "pass:$KS_PASS" `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out "$APK\bms-dashboard-1.0.apk" "$APK\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Host '== 9. verify =='
& java -jar "$BT34\lib\apksigner.jar" verify --print-certs "$APK\bms-dashboard-1.0.apk"

$DEST = "$ROOT\build\apk"
New-Item -ItemType Directory -Force -Path $DEST | Out-Null
Copy-Item "$APK\bms-dashboard-1.0.apk" "$DEST\bms-dashboard-1.0.apk" -Force
Write-Host '== DONE =='
Get-Item "$DEST\bms-dashboard-1.0.apk" | Select-Object FullName, Length
