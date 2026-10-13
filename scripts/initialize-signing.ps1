param(
    [string]$KeyStorePath = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$KeyAlias = 'androiddebugkey'
)

. (Join-Path $PSScriptRoot 'release-common.ps1')
$repoRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repoRoot 'signing.properties'
if (Test-Path -LiteralPath $settingsPath) {
    Write-Output 'Fixed signing is already configured. Existing files were kept.'
    return
}
if (-not (Test-Path -LiteralPath $KeyStorePath -PathType Leaf)) { throw 'The original signing keystore is missing. Do not generate a replacement for an existing installation.' }
$privateDirectory = Join-Path $repoRoot '.signing'
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
$fixedKey = Join-Path $privateDirectory 'wenotes.keystore'
if (Test-Path -LiteralPath $fixedKey) { throw 'A saved signing keystore already exists. Restore signing.properties instead of replacing it.' }
$javaRoot = Find-WeNotesJava $repoRoot
$storePassword = if ($env:WENOTES_STORE_PASSWORD) { $env:WENOTES_STORE_PASSWORD } else { 'android' }
$keyPassword = if ($env:WENOTES_KEY_PASSWORD) { $env:WENOTES_KEY_PASSWORD } else { $storePassword }
$certificatePath = Join-Path $privateDirectory 'certificate.der'
$oldCertificatePassword = $env:WENOTES_CERT_PASSWORD
try {
    $env:WENOTES_CERT_PASSWORD = $storePassword
    & (Join-Path $javaRoot 'bin\keytool.exe') -exportcert -keystore $KeyStorePath -alias $KeyAlias -storepass:env WENOTES_CERT_PASSWORD -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the original signing certificate.' }
    $signerHash = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $KeyStorePath -Destination $fixedKey
    function Escape-Property([string]$Value) {
        if ($Value -match '[\r\n]') { throw 'Multiline signing properties are unsupported.' }
        return $Value.Replace('\', '\\').Replace(':', '\:').Replace('=', '\=').Replace(' ', '\ ')
    }
    $text = @(
        'storeFile=.signing/wenotes.keystore',
        ('storePassword=' + (Escape-Property $storePassword)),
        ('keyAlias=' + (Escape-Property $KeyAlias)),
        ('keyPassword=' + (Escape-Property $keyPassword)),
        ('signerSha256=' + $signerHash)
    ) -join "`n"
    Write-WeNotesUtf8 $settingsPath $text
    [pscustomobject]@{ Status = 'configured'; CertificateSha256 = $signerHash; Backup = 'Keep .signing/wenotes.keystore and signing.properties together in a private backup.' }
} finally {
    $env:WENOTES_CERT_PASSWORD = $oldCertificatePassword
    if (Test-Path -LiteralPath $certificatePath) { Remove-Item -LiteralPath $certificatePath }
}
