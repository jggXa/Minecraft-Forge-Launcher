param(
    # 发布模式：先按 SemVer 2.0.0 递增版本号（依据 CHANGELOG.md 的 Unreleased 内容），再打包
    [switch]$Release,
    [ValidateSet("auto", "major", "minor", "patch")]
    [string]$Bump = "auto"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }

if ($Release) {
    & (Join-Path $root "scripts\bump-version.ps1") -Part $Bump
}

# 版本号唯一来源是 pom.xml，jpackage 与 BuildInfo 都从它派生
[xml]$pom = Get-Content -LiteralPath (Join-Path $root "pom.xml") -Raw
$version = $pom.project.version
if (-not $version) { throw "无法从 pom.xml 读取版本号" }
Write-Host "构建版本：$version"

Set-Location -LiteralPath $root

function Stop-LauncherProcesses {
    Get-Process MCLILaunder -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    # jpackage 的启动器会拉起 JVM 子进程，单杀 exe 可能仍占用 app\mc-launcher.jar
    # 只匹配 java/javaw，避免误杀命令行里恰好带 jar 名的构建终端或编辑器
    try {
        Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @('java.exe', 'javaw.exe') -and
                           $_.CommandLine -like "*mc-launcher.jar*" } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch {}
    Start-Sleep -Milliseconds 800
}

Stop-LauncherProcesses

mvn clean package
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

$jar = "target\mc-launcher.jar"
if (-not (Test-Path $jar)) { throw "Cannot find $jar" }

$entries = jar tf $jar
foreach ($need in @("com/google/gson/Gson.class",
                    "org/fusesource/jansi/Ansi.class",
                    "org/jline/terminal/Terminal.class")) {
    if (-not ($entries | Select-String -SimpleMatch $need)) {
        throw "jar is missing dependency: $need (make sure shade/assembly is used)"
    }
}

if (Test-Path "dist_in") { Remove-Item "dist_in" -Recurse -Force }
New-Item -ItemType Directory -Path "dist_in" | Out-Null
Copy-Item $jar "dist_in\"

if (Test-Path "dist_out\MCLILaunder") {
    Remove-Item "dist_out\MCLILaunder" -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path "dist_out\MCLILaunder") {
        throw "无法删除 dist_out\MCLILaunder（文件可能仍被占用）"
    }
}
if (-not (Test-Path "dist_out")) {
    New-Item -ItemType Directory -Path "dist_out" | Out-Null
}

jpackage `
  --type app-image `
  --name MCLILaunder `
  --input dist_in `
  --main-jar mc-launcher.jar `
  --main-class com.mclauncher.MainGui `
  --icon (Join-Path $PSScriptRoot "launcher-icon.ico") `
  --dest dist_out `
  --app-version $version `
  --vendor "jggXa" `
  --description "MCFL" `
  --java-options "-Dfile.encoding=UTF-8" `
  --java-options "-Dsun.stdout.encoding=UTF-8" `
  --java-options "-Dsun.stderr.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# ---- 单文件便携版：把 app/ 与 runtime/ 压进一个 MCFL.exe ----
# 用户只需复制 MCFL.exe 到任意空目录并双击，首次运行会自动解压运行环境。
Write-Host "正在生成单文件便携版 MCFL.exe ..."
$appImage = Join-Path $root "dist_out\MCLILaunder"
$payload = Join-Path $root "dist_out\payload.zip"
if (Test-Path $payload) { Remove-Item $payload -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory(
    $appImage, $payload, [IO.Compression.CompressionLevel]::Optimal, $false)

$template = Join-Path $root "tools\PortableLauncher.cs.in"
if (-not (Test-Path $template)) { throw "找不到单文件启动器模板: $template" }
$source = Join-Path $env:TEMP ("MCFL-Launcher-" + [guid]::NewGuid().ToString("N") + ".cs")
$code = [IO.File]::ReadAllText($template) -replace '__MCFL_VERSION__', $version
[IO.File]::WriteAllText($source, $code, (New-Object System.Text.UTF8Encoding($false)))

$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) {
    $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path $csc)) { throw "找不到 C# 编译器 csc.exe，无法生成 MCFL.exe" }

$portableExe = Join-Path $root "dist_out\MCFL.exe"
& $csc /nologo /codepage:65001 /target:winexe `
    "/out:$portableExe" `
    "/win32icon:$(Join-Path $root 'launcher-icon.ico')" `
    /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll `
    "/resource:$payload,payload.zip" $source
if ($LASTEXITCODE -ne 0) { throw "MCFL.exe 打包失败" }
Remove-Item $payload,$source -Force -ErrorAction SilentlyContinue
Write-Host "单文件便携版: $portableExe"

# ---- 部署到可信目录 ----
# 直接运行 dist_out 里的 exe 会被安全软件以低完整性沙箱运行，
# 导致写配置和游戏目录被拒绝。统一部署到 LocalAppData 并建快捷方式。
$deployDir = Join-Path $env:LOCALAPPDATA "Programs\MCLILaunder"
Stop-LauncherProcesses
New-Item -ItemType Directory -Path (Split-Path $deployDir) -Force | Out-Null
# 保留用户已有的 minecraft 游戏目录，只更新程序文件
Robocopy "dist_out\MCLILaunder" $deployDir /E /XD (Join-Path $deployDir "minecraft") /NFL /NDL /NJH /NJS /NP | Out-Null
$rc = $LASTEXITCODE
# Robocopy 0–7 都算成功，>=8 才是失败
if ($rc -ge 8) {
    Start-Sleep -Seconds 1
    Stop-LauncherProcesses
    Robocopy "dist_out\MCLILaunder" $deployDir /E /XD (Join-Path $deployDir "minecraft") /NFL /NDL /NJH /NJS /NP | Out-Null
    $rc = $LASTEXITCODE
}
if ($rc -ge 8) { throw "部署失败（Robocopy 退出码 $rc）" }

$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "MCFL.lnk"
$oldShortcut = Join-Path $desktop "MCLI Launcher.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $deployDir "MCLILaunder.exe"
$shortcut.WorkingDirectory = $deployDir
$shortcut.IconLocation = (Join-Path $deployDir "MCLILaunder.exe") + ",0"
$shortcut.Description = "MCFL"
$shortcut.Save()
if ((Test-Path $oldShortcut) -and ($oldShortcut -ne $shortcutPath)) {
    Remove-Item $oldShortcut -Force -ErrorAction SilentlyContinue
}

Write-Host "已部署到: $deployDir"
Write-Host "桌面快捷方式: $shortcutPath"