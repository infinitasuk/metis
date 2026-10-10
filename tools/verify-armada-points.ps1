param(
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $Commit = '47bfca1bddea459678568e5ee820597a433d9b41'
)

# Read-only provenance check for the supported points slice. Never uses a moving branch.
$ErrorActionPreference = 'Stop'
$profiles = @(
    @{ File = 'Imperial Navy.cat'; Name = 'TIE Fighter Squadron'; Faction = 'Galactic Empire' },
    @{ File = 'Rebel Fleet.cat'; Name = 'X-Wing Squadron'; Faction = 'Rebel Alliance' },
    @{ File = 'Rebel Fleet.cat'; Name = 'A-Wing Squadron'; Faction = 'Rebel Alliance' },
    @{ File = 'Imperial Navy.cat'; Name = 'TIE Interceptor Squadron'; Faction = 'Galactic Empire' }
)
$documents = @{}
$results = foreach ($profile in $profiles) {
    $url = "https://raw.githubusercontent.com/BSData/star-wars-armada/$Commit/$([uri]::EscapeDataString($profile.File))"
    if (-not $documents.ContainsKey($profile.File)) {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $url
        $documents[$profile.File] = $response.RawContentStream.ToArray()
    }
    $bytes = $documents[$profile.File]
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    $document = New-Object System.Xml.XmlDocument
    $document.XmlResolver = $null
    $document.LoadXml([System.Text.Encoding]::UTF8.GetString($bytes))
    $namespace = New-Object System.Xml.XmlNamespaceManager($document.NameTable)
    $namespace.AddNamespace('bs', 'http://www.battlescribe.net/schema/catalogueSchema')
    $entries = $document.SelectNodes("//bs:selectionEntry[@type='model' and @name='$($profile.Name)']", $namespace)
    if ($entries.Count -ne 1) { throw "Expected one source entry for $($profile.Name)" }
    $entry = $entries[0]
    $costs = $entry.SelectNodes("bs:costs/bs:cost[@name='pts']", $namespace)
    if ($costs.Count -ne 1) { throw "Expected one direct points cost for $($profile.Name)" }
    $characteristics = [ordered]@{}
    foreach ($value in $entry.SelectNodes("bs:profiles/bs:profile[@typeName='1.4 Squadrons']/bs:characteristics/bs:characteristic", $namespace)) {
        $characteristics[$value.GetAttribute('name')] = $value.InnerText.Trim()
    }
    if ($characteristics.Count -eq 0) { throw "Expected a squadron profile for $($profile.Name)" }
    [ordered]@{
        name = $profile.Name; faction = $profile.Faction; file = $profile.File
        revision = $document.DocumentElement.GetAttribute('revision'); entryId = $entry.GetAttribute('id')
        rawPoints = $costs[0].GetAttribute('value'); commit = $Commit; sha256 = $hash; url = $url
        characteristics = $characteristics
    }
}
$results | ConvertTo-Json -Depth 5
