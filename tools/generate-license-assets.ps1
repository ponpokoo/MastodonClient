param(
    [string]$ArtifactsJson = 'build/license-audit/artifacts.json',
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repoRoot = Split-Path $PSScriptRoot -Parent
$outputDirectory = Join-Path $repoRoot 'app/src/main/assets/licenses'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$writtenFiles = [System.Collections.Generic.HashSet[string]]::new()

function Read-ArchiveNotices([System.IO.Compression.ZipArchive]$Archive) {
    foreach ($entry in $Archive.Entries) {
        if ($entry.FullName -match '(?i)(^|/)(LICENSE([._-].*)?|NOTICE([._-].*)?|COPYING([._-].*)?|third_party_licenses\.txt)$' -and $entry.Length -gt 0) {
            $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8)
            try {
                $content = $reader.ReadToEnd()
                if ($content.Contains([char]0)) { throw "Non-text notice: $($entry.FullName)" }
                [pscustomobject]@{ Path = $entry.FullName; Text = $content }
            } finally { $reader.Dispose() }
        }
        if ($entry.FullName -eq 'classes.jar' -or $entry.FullName -match '^libs/.*\.jar$') {
            $memory = [System.IO.MemoryStream]::new()
            $input = $entry.Open()
            try { $input.CopyTo($memory) } finally { $input.Dispose() }
            $memory.Position = 0
            $nested = [System.IO.Compression.ZipArchive]::new($memory, [System.IO.Compression.ZipArchiveMode]::Read)
            try { Read-ArchiveNotices $nested } finally { $nested.Dispose(); $memory.Dispose() }
        }
    }
}

function Write-Document([string]$Text) {
    $bytes = $utf8.GetBytes($Text.Replace("`r`n", "`n"))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    $name = "$hash.txt"
    [System.IO.File]::WriteAllBytes((Join-Path $outputDirectory $name), $bytes)
    [void]$writtenFiles.Add($name)
    return $name
}

$rootLicense = Get-Content (Join-Path $repoRoot 'LICENSE') -Raw
$apacheStart = [regex]::Match($rootLicense, '(?m)^Apache License\r?$').Index
if (-not [regex]::IsMatch($rootLicense, '(?m)^Apache License\r?$')) { throw 'Apache text missing from LICENSE' }
$apache = $rootLicense.Substring($apacheStart).Trim()
$mpl = Get-Content (Join-Path $PSScriptRoot 'license-texts/MPL-2.0.txt') -Raw
$protobuf = Get-Content (Join-Path $PSScriptRoot 'license-texts/Protocol-Buffers-LICENSE.txt') -Raw
$projectNotice = Get-Content (Join-Path $repoRoot 'NOTICE') -Raw
$entries = [System.Collections.Generic.List[object]]::new()
$entries.Add([ordered]@{
    id = 'nagisa'; title = 'Nagisa'; version = ''; license = 'Apache License 2.0'
    file = (Write-Document ($rootLicense.Trim() + "`n`n" + $projectNotice.Trim() + "`n"))
})

$artifactsPath = if ([System.IO.Path]::IsPathRooted($ArtifactsJson)) { $ArtifactsJson } else { Join-Path $repoRoot $ArtifactsJson }
$artifacts = Get-Content $artifactsPath -Raw | ConvertFrom-Json
$groups = $artifacts | Group-Object { "$($_.group):$($_.module):$($_.version)" } | Sort-Object Name
$inventory = [System.Collections.Generic.List[object]]::new()
foreach ($group in $groups) {
    $artifact = $group.Group[0]
    $pomDirectory = Join-Path (Join-Path (Join-Path $GradleCache $artifact.group) $artifact.module) $artifact.version
    $pom = Get-ChildItem -LiteralPath $pomDirectory -Recurse -Filter '*.pom' | Select-Object -First 1
    if (-not $pom) { throw "POM missing: $($group.Name)" }
    [xml]$metadata = Get-Content $pom.FullName -Raw
    $declaredLicense = (@($metadata.project.licenses.license) | ForEach-Object { $_.name }) -join '; '
    $title = [string]$metadata.project.name
    if (-not $title -or $title.Contains('${') -or $title -in @('project', 'unspecified')) {
        $title = $artifact.module
    }
    $license = if ($declaredLicense -match 'Apache') { 'Apache License 2.0' }
        elseif ($declaredLicense -match 'BSD-3-Clause') { 'BSD 3-Clause' }
        elseif ($declaredLicense -eq 'Android Software Development Kit License') { 'Google SDK利用条件・第三者通知' }
        elseif ($artifact.group -in @('com.google.guava', 'com.google.zxing')) { 'Apache License 2.0' }
        else { throw "Unreviewed license: $($group.Name) ($declaredLicense)" }

    $body = switch ($license) {
        'Apache License 2.0' { $apache }
        'BSD 3-Clause' { $protobuf.Trim() }
        'Google SDK利用条件・第三者通知' { "Google SDKの利用条件:`nhttps://developer.android.com/studio/terms`n`n以下はSDKに同梱されている第三者通知です。" }
    }
    $parts = [System.Collections.Generic.List[string]]::new()
    $parts.Add($body)
    $uniqueText = [System.Collections.Generic.HashSet[string]]::new()
    [void]$uniqueText.Add(($body -replace '\s+', ''))
    $noticeCount = 0
    foreach ($archiveFile in ($group.Group.file | Sort-Object -Unique)) {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($archiveFile)
        try {
            foreach ($notice in @(Read-ArchiveNotices $zip)) {
                $text = $notice.Text.Trim()
                if ($uniqueText.Add(($text -replace '\s+', ''))) {
                    $parts.Add($text)
                    $noticeCount++
                }
            }
        } finally { $zip.Dispose() }
    }
    if ($artifact.group -eq 'com.squareup.okhttp3' -and $artifact.module -eq 'okhttp') {
        $parts.Add($mpl.Trim())
    }
    $entries.Add([ordered]@{
        id = $group.Name; title = $title; version = $artifact.version; license = $license
        file = (Write-Document (($parts -join "`n`n") + "`n"))
    })
    $inventory.Add([ordered]@{ coordinate = $group.Name; license = $license; notices = $noticeCount })
}

$json = [ordered]@{ schemaVersion = 1; entries = @($entries.ToArray()) } | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $outputDirectory 'index.json'), $json + "`n", $utf8)
# Only remove obsolete hash-named files produced by this generator, never a directory.
Get-ChildItem -LiteralPath $outputDirectory -File -Filter '*.txt' | Where-Object {
    $_.Name -match '^[0-9a-f]{64}\.txt$' -and -not $writtenFiles.Contains($_.Name)
} | ForEach-Object { Remove-Item -LiteralPath $_.FullName }

$summaryPath = Join-Path $repoRoot 'build/license-audit/current-inventory.json'
[System.IO.File]::WriteAllText($summaryPath, (@($inventory.ToArray()) | ConvertTo-Json -Depth 5) + "`n", $utf8)
Write-Output "Generated $($entries.Count) license entries ($($groups.Count) release runtime coordinates) and $($writtenFiles.Count) text documents."
