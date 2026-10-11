param([ValidatePattern('^[0-9a-f]{40}$')][string] $Commit = '47bfca1bddea459678568e5ee820597a433d9b41')
$ErrorActionPreference = 'Stop'
function Fetch-Source([string] $Url) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url
    $bytes = $response.RawContentStream.ToArray()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    return @{ Text = [System.Text.Encoding]::UTF8.GetString($bytes); Hash = $hash }
}
$profiles = @(
    @{ File = 'Rebel Fleet.cat'; Name = 'CR90 Corvette A'; Faction = 'Rebel Alliance' },
    @{ File = 'Imperial Navy.cat'; Name = 'Raider I-class Corvette'; Faction = 'Galactic Empire' }
)
$result = foreach ($profile in $profiles) {
    $url = "https://raw.githubusercontent.com/BSData/star-wars-armada/$Commit/$([uri]::EscapeDataString($profile.File))"
    $source = Fetch-Source $url
    $document = New-Object System.Xml.XmlDocument; $document.XmlResolver = $null; $document.LoadXml($source.Text)
    $ns = New-Object System.Xml.XmlNamespaceManager($document.NameTable); $ns.AddNamespace('bs', 'http://www.battlescribe.net/schema/catalogueSchema')
    $entries = $document.SelectNodes("//bs:selectionEntry[@type='model' and @name='$($profile.Name)']", $ns)
    if ($entries.Count -ne 1) { throw 'Expected one ship entry' }
    $entry = $entries[0]; $costs = $entry.SelectNodes("bs:costs/bs:cost[@name='pts']", $ns)
    if ($costs.Count -ne 1) { throw 'Expected one direct ship cost' }
    $stats = [ordered]@{}
    foreach ($value in $entry.SelectNodes("bs:profiles/bs:profile[@typeName='1.1 Ships']/bs:characteristics/bs:characteristic", $ns)) { $stats[$value.GetAttribute('name')] = $value.InnerText.Trim() }
    [ordered]@{ name=$profile.Name; faction=$profile.Faction; file=$profile.File; revision=$document.DocumentElement.GetAttribute('revision'); entryId=$entry.GetAttribute('id'); rawPoints=$costs[0].GetAttribute('value'); commit=$Commit; sha256=$source.Hash; url=$url; characteristics=$stats }
}
$result | ConvertTo-Json -Depth 6
$tts = '598f7741a7e3d09dd0e362b47914dd5d54ec1fe9'
$texts = @{}
foreach ($file in @('TTS_armada/src/Magic Ruler.ttslua','TTS_armada/src/Global.ttslua','TTS_armada/src/defs/rebel/ships.ttslua','TTS_armada/src/defs/empire/ships.ttslua')) {
    $url = "https://raw.githubusercontent.com/Valadian/TabletopSimulatorIncludeDir/$tts/$($file.Replace(' ','%20'))"
    $source = Fetch-Source $url
    $texts[$file] = $source.Text
    [ordered]@{ file=$file; commit=$tts; sha256=$source.Hash; url=$url } | ConvertTo-Json
}
foreach ($item in @(@{ Name='CR90 Corvette A'; File='rebel' }, @{ Name='Raider I-class Corvette'; File='empire' })) {
    $match = [regex]::Match($texts["TTS_armada/src/defs/$($item.File)/ships.ttslua"], '(?s)name\s*=\s*"' + [regex]::Escape($item.Name) + '".*?maneuver\s*=\s*(\{\{[^\r\n]+\}\})')
    if (-not $match.Success) { throw "Maneuver chart missing: $($item.Name)" }
    $chart = $match.Groups[1].Value.Replace('"II"','2').Replace('"I"','1').Replace('"-"','0').Replace('{','[').Replace('}',']')
    [ordered]@{ name=$item.Name; chart=[object[]](ConvertFrom-Json -InputObject ('[[],'+$chart.Substring(1))) } | ConvertTo-Json -Depth 5
}
$ruler = $texts['TTS_armada/src/Magic Ruler.ttslua']; $global = $texts['TTS_armada/src/Global.ttslua']
$segment = [regex]::Match($ruler, 'vector.scale\(\{0,0,([0-9.]+)\s*\},this.getScale\(\)\)')
$angle = [regex]::Match($ruler, 'local new_rot = dir \* ([0-9.]+)')
$size = [regex]::Match($global, 'ship_size\s*=\s*\{\s*\{([0-9.]+),0,([0-9.]+)\}')
$offset = [regex]::Match($global, '\{([0-9.]+)\*direction,0,-([0-9.]+)\}')
if (-not ($segment.Success -and $angle.Success -and $size.Success -and $offset.Success)) { throw 'Pinned tool calibration was not found' }
$decimal = [Globalization.CultureInfo]::InvariantCulture
$halfAcross = [decimal]::Parse($size.Groups[1].Value,$decimal); $halfForward = [decimal]::Parse($size.Groups[2].Value,$decimal)
$clearance = [decimal]::Parse($offset.Groups[1].Value,$decimal); $setback = [decimal]::Parse($offset.Groups[2].Value,$decimal)
[ordered]@{ unit='tts'; scale=1; width=2*$halfForward; height=2*$halfAcross; segment=[decimal]::Parse($segment.Groups[1].Value,$decimal); angle=[decimal]::Parse($angle.Groups[1].Value,$decimal); anchor=@(($halfForward-$setback),($halfAcross+$clearance)); toolWidth=2*$clearance; toolPolicy='training capsule, not source mesh' } | ConvertTo-Json
