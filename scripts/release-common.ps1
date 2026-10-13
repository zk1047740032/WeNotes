$ErrorActionPreference = 'Stop'

function Find-WeNotesJava([string]$Root) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $gradleConfig = Join-Path $Root '.gradle\config.properties'
    if (Test-Path -LiteralPath $gradleConfig) {
        $line = Get-Content -LiteralPath $gradleConfig | Where-Object { $_ -match '^java\.home=' } | Select-Object -First 1
        if ($line) {
            $javaPath = $line.Substring(10).Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath (Join-Path $javaPath 'bin\java.exe')) { return $javaPath }
        }
    }
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    $candidate = Get-ChildItem -LiteralPath $jdks -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?:17|21)' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw 'Set JAVA_HOME to a JDK 17 or 21 installation before building.'
}

function Get-WeNotesGitHubHeaders([string]$Root) {
    $oldPrompt = $env:GIT_TERMINAL_PROMPT
    $oldInteractive = $env:GCM_INTERACTIVE
    Push-Location -LiteralPath $Root
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
        return @{ Authorization = 'Bearer ' + $credential.password; 'User-Agent' = 'WeNotes-release-publisher'; Accept = 'application/vnd.github+json' }
    } finally {
        $env:GIT_TERMINAL_PROMPT = $oldPrompt
        $env:GCM_INTERACTIVE = $oldInteractive
        Pop-Location
    }
}

function Write-WeNotesUtf8([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text.Replace("`r`n", "`n").TrimEnd() + "`n", [System.Text.UTF8Encoding]::new($false))
}
