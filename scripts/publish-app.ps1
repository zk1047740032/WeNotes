param(
    [string]$VersionName,
    [string]$NotesFile,
    [switch]$UseCurrentVersion,
    [switch]$Publish
)

. (Join-Path $PSScriptRoot 'release-common.ps1')
$repoRoot = Split-Path -Parent $PSScriptRoot
$versionPath = Join-Path $repoRoot 'version.properties'
$configPath = Join-Path $repoRoot 'updates\compatibility.json'
$settingsPath = Join-Path $repoRoot 'signing.properties'
if (-not (Test-Path -LiteralPath $settingsPath)) { throw 'Run scripts/initialize-signing.ps1 or restore the original private signing files first.' }
if ($UseCurrentVersion -and $VersionName) { throw 'Choose UseCurrentVersion or VersionName, not both.' }
if (-not $NotesFile) { $NotesFile = Join-Path $repoRoot 'release-notes.md' }
$notes = (Get-Content -LiteralPath $NotesFile -Raw -Encoding UTF8).Trim()
if (-not $notes -or $notes.Length -gt 4000) { throw 'Release notes must contain 1 to 4000 characters.' }
$versions = @{}
foreach ($line in Get-Content -LiteralPath $versionPath) {
    if ($line -match '^(versionCode|versionName)=(.+)$') { $versions[$matches[1]] = $matches[2] }
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$oldCode = [int]$versions.versionCode
$newCode = if ($UseCurrentVersion) { $oldCode } else { $oldCode + 1 }
if ($UseCurrentVersion) { $VersionName = $versions.versionName }
elseif (-not $VersionName) {
    $parts = $versions.versionName.Split('.')
    $VersionName = $parts[0] + '.' + $parts[1] + '.' + ([int]$parts[2] + 1)
}
if ($VersionName -notmatch '^\d{1,6}\.\d{1,6}\.\d{1,6}$' -or $newCode -lt 1) { throw 'Invalid release version.' }
if (-not $UseCurrentVersion -and [version]$VersionName -le [version]$versions.versionName) { throw 'VersionName must increase.' }
$pin = Get-Content -LiteralPath $settingsPath | Where-Object { $_ -match '^signerSha256=' } | Select-Object -First 1
if (-not $pin -or $pin.Substring(13) -notmatch '^[0-9a-f]{64}$') { throw 'The original certificate fingerprint is missing from signing.properties.' }
$expectedSigner = $pin.Substring(13)
$javaRoot = Find-WeNotesJava $repoRoot
$sdkLine = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $sdkLine) { throw 'Configure Android SDK in local.properties first.' }
$sdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) -and $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android build tools are unavailable.' }
$headers = @{ 'User-Agent' = 'WeNotes-release-publisher' }
$remoteConfig = $null
$tag = 'v' + $VersionName
$api = 'https://api.github.com/repos/zk1047740032/WeNotes'
Push-Location -LiteralPath $repoRoot
$oldJava = $env:JAVA_HOME
try {
    if ($Publish) {
        if ((git branch --show-current).Trim() -ne 'main') { throw 'Publish from main after committing the application changes.' }
        if (@(git status --porcelain).Count -ne 0) { throw 'Commit application changes before publishing. The script commits only its version change.' }
        $headers = Get-WeNotesGitHubHeaders $repoRoot
        $remote = Invoke-RestMethod -Uri ($api + '/contents/updates/compatibility.json?ref=main') -Headers $headers -TimeoutSec 20
        $remoteConfig = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($remote.content)) | ConvertFrom-Json
        if ($newCode -le $remoteConfig.app.versionCode) { throw 'The new versionCode must exceed the published version.' }
        if ($remoteConfig.app.apk -and $remoteConfig.app.apk.signerSha256 -ne $expectedSigner) { throw 'The published certificate differs from this signing key. Restore the original signing files.' }
        $existing = $null
        try { $existing = Invoke-RestMethod -Uri ($api + '/releases/tags/' + $tag) -Headers $headers -TimeoutSec 20 }
        catch { if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw } }
        if ($existing -and -not $existing.draft) { throw 'This release is already published. To recover an interrupted announcement, publish its saved output metadata with publish-compatibility.ps1.' }
    }
    Write-WeNotesUtf8 $versionPath ("versionCode=$newCode`nversionName=$VersionName")
    $env:JAVA_HOME = $javaRoot
    & (Join-Path $repoRoot 'gradlew.bat') testDebugUnitTest lintDebug assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release checks/build failed. Nothing was published.' }
    $apkPath = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
    $certificates = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signerLine = $certificates | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
    if (-not $signerLine -or ($signerLine -split ': ', 2)[1].Trim() -cne $expectedSigner -or
        @($certificates | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest: ' }).Count -ne 1) { throw 'The APK was not signed with the pinned original certificate.' }
    $package = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apkPath
    if ($LASTEXITCODE -ne 0 -or -not ($package | Where-Object { $_ -match ("^package: name='com.wenotes.app' versionCode='$newCode' versionName='$([regex]::Escape($VersionName))'") })) { throw 'APK package/version verification failed.' }
    $releaseDirectory = Join-Path $repoRoot ('output\releases\' + $tag)
    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    $assetName = 'WeNote-' + $VersionName + '.apk'
    $releaseApk = Join-Path $releaseDirectory $assetName
    Copy-Item -LiteralPath $apkPath -Destination $releaseApk -Force
    $hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $releaseApk).Length
    if ($size -lt 1024 -or $size -gt 157286400) { throw 'APK size is outside the updater limits.' }
    $revision = [long]$config.revision
    if ($remoteConfig) { $revision = [Math]::Max($revision, [long]$remoteConfig.revision) }
    $config.revision = $revision + 1
    $config.app = [pscustomobject]@{
        versionCode = $newCode; versionName = $VersionName
        updateUrl = 'https://github.com/zk1047740032/WeNotes/releases'; releaseNotes = $notes
        apk = [pscustomobject]@{
            url = 'https://github.com/zk1047740032/WeNotes/releases/download/' + $tag + '/' + $assetName
            size = $size; sha256 = $hash; signerSha256 = $expectedSigner
        }
    }
    $releaseConfig = Join-Path $releaseDirectory 'compatibility.json'
    Write-WeNotesUtf8 $releaseConfig ($config | ConvertTo-Json -Depth 10)
    Write-WeNotesUtf8 (Join-Path $releaseDirectory 'release-notes.md') $notes
    if (-not $Publish) {
        [pscustomobject]@{ Status = 'prepared'; Version = $VersionName; Apk = $releaseApk; Metadata = $releaseConfig; CertificateSha256 = $expectedSigner }
        return
    }
    # Invoking -Publish authorizes committing the version, pushing main and publishing the release.
    git add -- version.properties
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stage the release version.' }
    git diff --cached --quiet -- version.properties
    if ($LASTEXITCODE -eq 1) {
        git commit -m ('Prepare release ' + $tag)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot commit the release version.' }
    } elseif ($LASTEXITCODE -ne 0) { throw 'Cannot inspect staged version changes.' }
    git push origin HEAD:main
    if ($LASTEXITCODE -ne 0) { throw 'Push failed; the release has not been made public.' }
    $commit = (git rev-parse HEAD).Trim()
    if ($existing) { $draft = $existing }
    else {
        $body = @{ tag_name = $tag; target_commitish = $commit; name = 'WeNote ' + $VersionName; body = $notes; draft = $true; prerelease = $false } | ConvertTo-Json
        $draft = Invoke-RestMethod -Method Post -Uri ($api + '/releases') -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
    }
    if ($draft.target_commitish -ne $commit) { throw 'An existing draft targets different source code. Delete or repair the draft before retrying.' }
    foreach ($asset in @($draft.assets)) {
        if ($asset.name -eq $assetName -or $asset.name -eq 'compatibility.json') {
            Invoke-RestMethod -Method Delete -Uri ($api + '/releases/assets/' + $asset.id) -Headers $headers -TimeoutSec 30 | Out-Null
        }
    }
    $uploadBase = ($draft.upload_url -split '\{', 2)[0]
    Invoke-RestMethod -Method Post -Uri ($uploadBase + '?name=' + $assetName) -Headers $headers -ContentType 'application/vnd.android.package-archive' -InFile $releaseApk -TimeoutSec 300 | Out-Null
    Invoke-RestMethod -Method Post -Uri ($uploadBase + '?name=compatibility.json') -Headers $headers -ContentType 'application/json' -InFile $releaseConfig -TimeoutSec 30 | Out-Null
    $published = Invoke-RestMethod -Method Patch -Uri ($api + '/releases/' + $draft.id) -Headers $headers -ContentType 'application/json' -Body '{"draft":false}' -TimeoutSec 30
    # Announce only after the APK is public. The compatibility publisher detects revision conflicts.
    & (Join-Path $PSScriptRoot 'publish-compatibility.ps1') -ConfigPath $releaseConfig -Publish
    git pull --ff-only origin main
    if ($LASTEXITCODE -ne 0) { Write-Warning 'The release is published. Pull main before the next release.' }
    [pscustomobject]@{ Status = 'published'; Url = $published.html_url; Version = $VersionName; Apk = $releaseApk }
} finally {
    $env:JAVA_HOME = $oldJava
    Pop-Location
}
