param([switch]$Publish, [string]$ConfigPath)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configPath = if ($ConfigPath) { $ConfigPath } else { Join-Path $repoRoot 'updates\compatibility.json' }
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($config.schema -ne 1 -or $config.revision -lt 1) { throw 'Invalid configuration schema or revision.' }
if (@($config.PSObject.Properties.Name | Where-Object { $_ -cnotin @('schema','revision','app','profiles') }).Count) {
    throw 'Only public compatibility metadata may be published.'
}
if (@($config.app.PSObject.Properties.Name | Where-Object { $_ -cnotin @('versionCode','versionName','updateUrl','releaseNotes','apk') }).Count) {
    throw 'Unsupported application metadata.'
}
if ($config.app.releaseNotes -and $config.app.releaseNotes.Length -gt 4000) { throw 'Release notes are too long.' }
if ($config.app.apk) {
    $apk = $config.app.apk
    if (@($apk.PSObject.Properties.Name | Where-Object { $_ -cnotin @('url','size','sha256','signerSha256') }).Count -or
        $apk.url -cnotmatch '^https://github\.com/zk1047740032/WeNotes/releases/download/v\d{1,6}\.\d{1,6}\.\d{1,6}/[A-Za-z0-9._-]+\.apk$' -or
        $apk.size -lt 1024 -or $apk.size -gt 157286400 -or $apk.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $apk.signerSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid APK metadata.' }
}
if ($config.app.versionCode -lt 1 -or
    $config.app.versionName -notmatch '^\d{1,6}\.\d{1,6}\.\d{1,6}$' -or
    $config.app.updateUrl -cnotmatch '^https://github\.com/zk1047740032/WeNotes/releases(?:/[^?#\\]*)?$') {
    throw 'Invalid application version or update address.'
}
if (@($config.profiles).Count -lt 1 -or @($config.profiles).Count -gt 32) { throw 'Invalid profile count.' }
$seenVersions = @{}
foreach ($profile in $config.profiles) {
    if (@($profile.PSObject.Properties.Name | Where-Object {
        $_ -cnotin @('skillVersion','adapter','minAppVersionCode','pageSize')
    }).Count) { throw 'Unsupported compatibility metadata.' }
    if ($profile.skillVersion -notmatch '^\d{1,6}\.\d{1,6}\.\d{1,6}$' -or
        $profile.adapter -cnotmatch '^weread-library-v[1-9][0-9]*$' -or
        $profile.minAppVersionCode -lt 1 -or $profile.pageSize -lt 1 -or $profile.pageSize -gt 100 -or
        $seenVersions.ContainsKey($profile.skillVersion)) { throw 'Invalid compatibility profile.' }
    $seenVersions[$profile.skillVersion] = $true
}
$publicText = ($config | ConvertTo-Json -Depth 8).Replace("`r`n", "`n").TrimEnd() + "`n"
$publicBytes = [System.Text.Encoding]::UTF8.GetBytes($publicText)
if ($publicBytes.Length -gt 32768) { throw 'Compatibility metadata exceeds the application limit.' }
$endpoint = 'https://api.github.com/repos/zk1047740032/WeNotes/contents/updates/compatibility.json'
$headers = @{ 'User-Agent' = 'WeNotes-update-publisher' }
$current = $null
try {
    $current = Invoke-RestMethod -Uri $endpoint -Headers $headers -TimeoutSec 20
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
}
if ($null -ne $current) {
    $currentText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($current.content))
    $currentConfig = $currentText | ConvertFrom-Json
    $currentCanonical = ($currentConfig | ConvertTo-Json -Depth 8).Replace("`r`n", "`n").TrimEnd() + "`n"
    if ($currentCanonical -eq $publicText) {
        [pscustomobject]@{ Status = 'unchanged'; Url = $current.html_url; Revision = $config.revision }
        return
    }
    if ($config.revision -le $currentConfig.revision) { throw 'Increase revision before replacing published configuration.' }
}
if (-not $Publish) {
    [pscustomobject]@{ Status = 'validated'; File = $configPath; Revision = $config.revision; Bytes = $publicBytes.Length }
    return
}
$previousTerminalPrompt = $env:GIT_TERMINAL_PROMPT
$previousInteractive = $env:GCM_INTERACTIVE
Push-Location -LiteralPath $repoRoot
try {
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:GCM_INTERACTIVE = 'never'
    $credentialLines = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication is unavailable.' }
    $credential = @{}
    foreach ($line in $credentialLines) {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) { $credential[$parts[0]] = $parts[1] }
    }
    if (-not $credential.ContainsKey('password')) { throw 'GitHub authentication is unavailable.' }
    $headers.Authorization = 'Bearer ' + $credential['password']
    $payload = @{
        message = 'Publish verified WeRead compatibility configuration (revision ' + $config.revision + ')'
        content = [Convert]::ToBase64String($publicBytes)
        branch = 'main'
    }
    if ($null -ne $current) { $payload.sha = $current.sha }
    $publishArguments = @{
        Method = 'Put'; Uri = $endpoint; Headers = $headers; ContentType = 'application/json'
        Body = ($payload | ConvertTo-Json -Compress); TimeoutSec = 30
    }
    $published = Invoke-RestMethod @publishArguments
    [pscustomobject]@{ Status = 'published'; Url = $published.content.html_url; Commit = $published.commit.sha; Revision = $config.revision }
} finally {
    $env:GIT_TERMINAL_PROMPT = $previousTerminalPrompt
    $env:GCM_INTERACTIVE = $previousInteractive
    Pop-Location
}
