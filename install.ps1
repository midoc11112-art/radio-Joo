# Radio-Joo v3 one-shot installer (run from the folder where you extracted this zip):
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
# It (1) cleans the old project, (2) installs the new app + cloud code, (3) creates the Cloudflare KV store,
# (4) deploys + warms up the Worker, (5) pushes to GitHub so the APK builds. Nothing manual.
param([string]$Project = "C:\Users\X\Desktop\radio-Joo", [string]$KvId = "68505956ecd843a494b8aabb2ca2bc2d", [switch]$Force)
$ErrorActionPreference = "Continue"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Base = "https://radio-joo-proxy.midoc11112.workers.dev"

function Step($t) { Write-Host "`n=== $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host "ERROR: $t" -ForegroundColor Red; Read-Host "Press Enter to exit"; exit 1 }

if ((Resolve-Path $Here).Path -eq (Resolve-Path $Project -ErrorAction SilentlyContinue).Path) { Fail "Extract the zip in a DIFFERENT folder (e.g. Desktop\radio-Joo-v3), not inside the project folder." }
if (-not (Test-Path (Join-Path $Project ".git"))) { Fail "Project folder not found or not a git repo: $Project" }
foreach ($tool in @("git", "node", "npx")) { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "$tool is not installed / not in PATH" } }

Step "0/5 Checking logins"
$who = (npx --yes wrangler whoami 2>&1 | Out-String)
if ($who -match "not authenticated|not logged in|You are not") { Fail "Cloudflare is not logged in. Run: npx wrangler login   then run this again." }
Write-Host "    Cloudflare login OK" -ForegroundColor Green
Set-Location $Project
$remote = (git remote get-url origin 2>&1 | Out-String).Trim()
Write-Host "    GitHub remote: $remote" -ForegroundColor Green

# ---------------------------------------------------------------- 1) clean + install
Step "1/5 Cleaning old project and installing v3 files"
Set-Location $Project
git tag -f backup-before-v3 2>$null | Out-Null          # safety net: git checkout backup-before-v3 restores the old project
Get-ChildItem -Force | Where-Object { $_.Name -ne ".git" } | Remove-Item -Recurse -Force
Get-ChildItem -Force $Here | Where-Object { $_.Name -ne "install.ps1" -and $_.Name -ne ".git" } | Copy-Item -Destination $Project -Recurse -Force
Write-Host "    new project files installed" -ForegroundColor Green

# ---------------------------------------------------------------- 2) Cloudflare KV
Step "2/5 Cloudflare KV store"
Set-Location (Join-Path $Project "worker")
$kvId = $KvId
if (-not $kvId) {
    foreach ($cmd in @("kv namespace create RADIO_KV", "kv:namespace create RADIO_KV")) {
        $out = (Invoke-Expression "npx --yes wrangler $cmd 2>&1" | Out-String)
        if ($out -match '"?id"?\s*[:=]\s*"([0-9a-f]{32})"') { $kvId = $Matches[1]; break }
    }
}
if (-not $kvId) { Write-Host "    KV unavailable - continuing WITHOUT persistent storage (works, but learns less)." -ForegroundColor Yellow }
else { Write-Host "    using KV namespace RADIO_KV: $kvId" -ForegroundColor Green }

$kvBlock = if ($kvId) { "`n[[kv_namespaces]]`nbinding = `"RADIO_KV`"`nid = `"$kvId`"`n" } else { "" }
$toml = @"
name = "radio-joo-proxy"
main = "src/index.js"
compatibility_date = "2024-09-23"
workers_dev = true

[triggers]
crons = ["*/10 * * * *"]
$kvBlock
"@
[System.IO.File]::WriteAllText((Join-Path (Get-Location) "wrangler.toml"), $toml, (New-Object System.Text.UTF8Encoding $false))

# ---------------------------------------------------------------- 3) deploy
Step "3/5 Deploying the cloud brain"
$keyFile = Join-Path $Project ".admin-key"
if (Test-Path $keyFile) { $adminKey = (Get-Content $keyFile -Raw).Trim() }
else { $adminKey = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ }); Set-Content -Path $keyFile -Value $adminKey -NoNewline }
npx --yes wrangler deploy --var "ADMIN_KEY:$adminKey"
if ($LASTEXITCODE -ne 0) { Fail "wrangler deploy failed (run 'npx wrangler login' once, then run this script again)" }

# ---------------------------------------------------------------- 4) warm up: discover + verify the first catalog
Step "4/5 Warming up (the cloud now discovers and verifies stations by itself)"
$last = $null
for ($i = 1; $i -le 40; $i++) {
    try {
        $r = Invoke-RestMethod -Uri "$Base/v2/admin/step?key=$adminKey" -TimeoutSec 90
        $last = $r
        Write-Host ("    step {0}: discovery {1}/{2}, stations {3}" -f $i, $r.cursor, $r.tasks, $r.stations)
        if ($r.cursor -ge $r.tasks) { break }
    } catch { Write-Host "    step $i : $($_.Exception.Message)" -ForegroundColor Yellow }
    Start-Sleep -Seconds 2
}
try { $h = Invoke-RestMethod -Uri "$Base/v2/health" -TimeoutSec 30; Write-Host ("    cloud health: {0} stations {1}" -f $h.stations, ($h.byCategory | ConvertTo-Json -Compress)) -ForegroundColor Green } catch { }

# ---------------------------------------------------------------- 5) GitHub
Step "5/5 Pushing to GitHub (the APK builds there)"
Set-Location $Project
git fetch origin main 2>$null | Out-Null
git reset --soft FETCH_HEAD 2>$null | Out-Null          # commit only the difference, no force-push needed
git add -A
git commit -m "Radio-Joo v3: new self-updating engine (failover, data saver, learning) + cloud brain; clean project" | Out-Null
if ($Force) { git push -f origin main } else { git push origin main }
if ($LASTEXITCODE -ne 0) { Fail "git push failed. Run again with -Force:  powershell -ExecutionPolicy Bypass -File .\install.ps1 -Force" }

Write-Host "`nDONE. Open https://github.com/midoc11112-art/radio-Joo/actions , wait for the green build, download RadioJoo-APK." -ForegroundColor Green
Read-Host "Press Enter to exit"
