#!/usr/bin/env pwsh
# ==========================================
# iOS 项目文件拆分脚本 (PowerShell)
# 用法: ./split_ios_project.ps1 ios_project_source.txt
# ==========================================

param(
    [string]$SourceFile = "ios_project_source.txt",
    [string]$OutputDir = "ImageTranslateOCR"
)

# 颜色输出函数
function Write-Info { Write-Host "ℹ️  $args" -ForegroundColor Blue }
function Write-Success { Write-Host "✅ $args" -ForegroundColor Green }
function Write-Warning { Write-Host "⚠️  $args" -ForegroundColor Yellow }
function Write-Error { Write-Host "❌ $args" -ForegroundColor Red }

# 检查源文件
if (-not (Test-Path $SourceFile)) {
    Write-Error "找不到源文件: $SourceFile"
    Write-Host ""
    Write-Host "请先创建 ios_project_source.txt 文件"
    exit 1
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   iOS 项目文件拆分工具 (PowerShell)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Info "源文件: $SourceFile"
Write-Info "输出目录: $OutputDir"
Write-Host ""

# 确认操作
$confirm = Read-Host "是否继续？将覆盖已存在的 $OutputDir 目录 (y/n)"
if ($confirm -ne "y" -and $confirm -ne "Y") {
    Write-Warning "操作已取消"
    exit 0
}

# 清空旧目录
if (Test-Path $OutputDir) {
    Write-Warning "删除旧目录: $OutputDir"
    Remove-Item -Recurse -Force $OutputDir
}

# 创建输出目录
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

# 统计变量
$currentFile = $null
$fileCount = 0
$contentLines = @()
$emptyFiles = @()

Write-Host ""
Write-Info "开始拆分文件..."

# 读取源文件
$lines = Get-Content $SourceFile -Encoding UTF8

foreach ($line in $lines) {
    # 检测文件标记 ===FILE:path===
    if ($line -match '^===FILE:(.+)===$') {
        # 保存上一个文件
        if ($currentFile) {
            $filePath = Join-Path $OutputDir $currentFile
            $dirPath = Split-Path $filePath -Parent
            
            # 创建目录
            if (-not (Test-Path $dirPath)) {
                New-Item -ItemType Directory -Force -Path $dirPath | Out-Null
            }
            
            # 写入文件
            if ($contentLines.Count -gt 0) {
                $contentLines -join "`n" | Out-File -FilePath $filePath -Encoding UTF8
            } else {
                New-Item -ItemType File -Force -Path $filePath | Out-Null
                $emptyFiles += $currentFile
            }
            
            Write-Success "创建: $currentFile"
            $fileCount++
            $contentLines = @()
        }
        
        $currentFile = $matches[1]
        continue
    }
    
    # 收集内容
    if ($currentFile) {
        $contentLines += $line
    }
}

# 处理最后一个文件
if ($currentFile) {
    $filePath = Join-Path $OutputDir $currentFile
    $dirPath = Split-Path $filePath -Parent
    
    if (-not (Test-Path $dirPath)) {
        New-Item -ItemType Directory -Force -Path $dirPath | Out-Null
    }
    
    if ($contentLines.Count -gt 0) {
        $contentLines -join "`n" | Out-File -FilePath $filePath -Encoding UTF8
    } else {
        New-Item -ItemType File -Force -Path $filePath | Out-Null
        $emptyFiles += $currentFile
    }
    
    Write-Success "创建: $currentFile"
    $fileCount++
}

# 创建 .gitignore
$gitignore = @'
# Xcode
*.xcworkspace
xcuserdata/
DerivedData/
*.xcuserstate

# CocoaPods
Pods/
Podfile.lock

# Swift Package Manager
.swiftpm/
Package.resolved

# Build
build/
*.ipa
*.dSYM.zip
*.dSYM

# OpenCV
opencv2.framework/

# IDE
.idea/
.vscode/
*.swp
*.swo
*~
'@

$gitignore | Out-File -FilePath (Join-Path $OutputDir ".gitignore") -Encoding UTF8
Write-Success "创建: .gitignore"

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Success "拆分完成！"
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📊 统计信息:"
Write-Host "   - 创建文件数: $fileCount"
Write-Host "   - 输出目录: $(Resolve-Path $OutputDir)"
Write-Host ""

if ($emptyFiles.Count -gt 0) {
    Write-Warning "以下文件内容为空:"
    foreach ($f in $emptyFiles) {
        Write-Host "   - $f"
    }
    Write-Host ""
}

# 显示项目结构
Write-Host "📁 项目结构:"
Write-Host ""

Get-ChildItem $OutputDir -Recurse -File |
    Where-Object { $_.Name -notmatch '\.gitignore$|\.pbxproj$' } |
    Sort-Object FullName |
    ForEach-Object {
        $relPath = $_.FullName.Substring((Resolve-Path $OutputDir).Path.Length + 1)
        $depth = ($relPath -split '[\\/]').Count - 1
        $indent = "  " * $depth
        Write-Host "  ${indent}📄 $($_.Name)"
    }

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  🚀 下一步操作" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. 进入项目目录:"
Write-Host "   cd $OutputDir"
Write-Host ""
Write-Host "2. 安装 OpenCV（选择一种方式）:"
Write-Host ""
Write-Host "   a) 使用 CocoaPods:"
Write-Host "      pod init"
Write-Host '      echo "pod '"'"'OpenCV'"'"', '"'"'~> 4.8.0'"'"'" >> Podfile'
Write-Host "      pod install"
Write-Host ""
Write-Host "   b) 手动安装:"
Write-Host "      从 https://opencv.org/releases/ 下载 iOS 版本"
Write-Host "      将 opencv2.framework 拖入项目"
Write-Host ""
Write-Host "3. 在 Xcode 中:"
Write-Host "   - 创建新 iOS 项目 (SwiftUI)"
Write-Host "   - Product Name: ImageTranslateOCR"
Write-Host "   - 将生成的文件复制到项目中"
Write-Host "   - 配置 Bridging Header"
Write-Host "   - 添加 OpenCV 框架"
Write-Host ""
Write-Host "4. 运行项目:"
Write-Host "   open ImageTranslateOCR.xcodeproj"
Write-Host ""
Write-Success "祝开发顺利！🎉"
Write-Host ""