param(
    [Parameter(Mandatory = $true)]
    [string] $SourceDirectory,

    [Parameter(Mandatory = $true)]
    [string] $OutputPath
)

$ErrorActionPreference = 'Stop'

function Normalize-Text {
    param([AllowNull()][string] $Value)

    if ($null -eq $Value) {
        return $null
    }

    $trimmed = $Value.Trim()
    $trimmed = $trimmed -replace '^\s*•\s*', ''
    $trimmed = $trimmed -replace '[\r\n]+', ' '
    $trimmed = $trimmed -replace '\s+', ' '
    return [regex]::Replace($trimmed, '[^\x20-\x7E]', '?')
}

function Convert-CharacteristicData {
    param(
        [string] $CharacteristicName,
        [AllowNull()][string] $RawValue
    )

    $cleanValue = Normalize-Text $RawValue
    $data = [ordered]@{
        raw_value = $cleanValue
        value = $null
    }

    $numericNames = @('Speed', 'Hull', 'Hull Value', 'Command', 'Squadron', 'Engineering', 'Max Speed', 'Front Shield Value', 'Left Shield Value', 'Right Shield Value', 'Rear Shield Value')
    if ($numericNames -contains $CharacteristicName) {
        if ($cleanValue -match '^\d+$') {
            $data.value = [int] $cleanValue
        }
        return $data
    }

    $diceNames = @('Anti-Squadron', 'Anti-Squadron Value', 'Battery', 'Front Firing Arc', 'Left Firing Arc', 'Right Firing Arc', 'Rear Firing Arc')
    if ($diceNames -contains $CharacteristicName) {
        $dice = New-Object System.Collections.ArrayList
        if ($cleanValue -and $cleanValue -notin @('-', 'None')) {
            foreach ($match in [regex]::Matches($cleanValue, '(?i)(?:(\d+)\s*)?(Red|Blue|Black)')) {
                $count = if ($match.Groups[1].Success) { [int] $match.Groups[1].Value } else { 1 }
                [void] $dice.Add([ordered]@{ count = $count; color = $match.Groups[2].Value.Substring(0, 1).ToUpperInvariant() + $match.Groups[2].Value.Substring(1).ToLowerInvariant() })
            }
        }
        $data.value = $dice
        return $data
    }

    if ($CharacteristicName -eq 'Defense Tokens') {
        $tokens = New-Object System.Collections.ArrayList
        if ($cleanValue) {
            foreach ($tokenText in ($cleanValue -split ',')) {
                $token = $tokenText.Trim()
                if (-not $token) { continue }
                if ($token -match '^(\d+)\s+(.+)$') {
                    $count = [int] $Matches[1]
                    $name = $Matches[2].Trim()
                } else {
                    $count = 1
                    $name = $token
                }
                [void] $tokens.Add([ordered]@{ count = $count; name = $name })
            }
        }
        $data.value = $tokens
        return $data
    }

    if ($CharacteristicName -eq 'Upgrade Bar') {
        $slots = New-Object System.Collections.ArrayList
        if ($cleanValue) {
            foreach ($slot in ($cleanValue -split ',')) {
                if ($slot.Trim()) { [void] $slots.Add($slot.Trim()) }
            }
        }
        $data.value = $slots
        return $data
    }

    if ($CharacteristicName -eq 'Keywords') {
        $keywords = New-Object System.Collections.ArrayList
        if ($cleanValue) {
            foreach ($keyword in [regex]::Split($cleanValue, '\s*[,.]\s*')) {
                if ($keyword.Trim()) { [void] $keywords.Add($keyword.Trim()) }
            }
        }
        $data.value = [ordered]@{
            text = if ($cleanValue) { $cleanValue } else { $null }
            keywords = $keywords
        }
        return $data
    }

    if ($CharacteristicName -eq 'Squadron Ability') {
        $data.value = if ($cleanValue) { [ordered]@{ text = $cleanValue } } else { $null }
        return $data
    }

    $data.value = $cleanValue
    return $data
}

function Sql-Literal {
    param([AllowNull()][string] $Value)

    if ($null -eq $Value) {
        return 'NULL'
    }

    return "'$(($Value -replace "'", "''"))'"
}

function Get-Profile {
    param(
        [System.Xml.XmlNode] $Entry,
        [string] $ProfileType
    )

    foreach ($profile in $Entry.SelectNodes('.//bs:profile', $script:CatalogueNamespace)) {
        if ($profile.typeName -eq $ProfileType) {
            return $profile
        }
    }

    return $null
}

function Add-ProfileRecords {
    param(
        [System.Xml.XmlNode] $Profile,
        [string] $FactionName,
        [string] $ClassificationName,
        [string] $UnitTypeName,
        [string] $UnitVariantName,
        [string] $SourceCatalogue,
        [string] $SourceRevision
    )

    if ($null -eq $Profile) {
        return
    }

    $profileName = Normalize-Text $Profile.name
    foreach ($characteristic in $Profile.SelectNodes('bs:characteristics/bs:characteristic', $script:CatalogueNamespace)) {
        $record = [pscustomobject]@{
            FactionName = $FactionName
            ClassificationName = $ClassificationName
            UnitTypeName = $UnitTypeName
            UnitVariantName = $UnitVariantName
            UnitVariantDescription = "Imported profile: $profileName"
            CharacteristicName = Normalize-Text $characteristic.name
            CharacteristicValue = Normalize-Text $characteristic.'#text'
            ProfileName = $profileName
            SourceCatalogue = $SourceCatalogue
            SourceRevision = $SourceRevision
        }

        $script:ProfileRecords += $record
    }
}

function Get-ProfileData {
    param(
        [System.Xml.XmlNode] $Entry,
        [System.Xml.XmlNode] $Profile,
        [System.Xml.XmlNamespaceManager] $Namespace
    )

    $characteristics = [ordered]@{}
    foreach ($characteristic in $Profile.SelectNodes('bs:characteristics/bs:characteristic', $Namespace)) {
        $name = Normalize-Text $characteristic.name
        $cleaned = Convert-CharacteristicData $name (Normalize-Text $characteristic.'#text')
        $characteristics[$name] = [ordered]@{
            raw_value = $cleaned.raw_value
            value = $cleaned.value
        }
    }

    $costs = @()
    foreach ($cost in $Entry.SelectNodes('.//bs:cost', $Namespace)) {
        $costs += [ordered]@{
            name = Normalize-Text $cost.name
            value = Normalize-Text $(if ($cost.value) { $cost.value } else { $cost.'#text' })
        }
    }

    $constraints = @()
    foreach ($constraint in $Entry.SelectNodes('.//bs:constraint', $Namespace)) {
        $constraints += [ordered]@{
            field = Normalize-Text $constraint.field
            scope = Normalize-Text $constraint.scope
            value = Normalize-Text $constraint.value
            type = Normalize-Text $constraint.type
        }
    }

    return [ordered]@{
        characteristics = $characteristics
        costs = $costs
        constraints = $constraints
    }
}

function Add-GameObjectRecord {
    param(
        [System.Xml.XmlNode] $Entry,
        [System.Xml.XmlNode] $Profile,
        [string] $ObjectTypeCode,
        [string] $ObjectTypeName,
        [string] $FactionName,
        [string] $SourceFile,
        [string] $SourceRevision,
        [System.Xml.XmlNamespaceManager] $Namespace
    )

    $profileName = Normalize-Text $Profile.name
    $entryName = Normalize-Text $Entry.name
    $objectName = if ($profileName) { $profileName } else { $entryName }
    $objectCode = "armada.$($SourceFile.Replace('.cat', '').Replace('.gst', '').ToLowerInvariant()).$($Entry.id).$($Profile.id)"
    $objectData = Get-ProfileData $Entry $Profile $Namespace
    $objectData['profile_type'] = Normalize-Text $Profile.typeName
    $objectData['catalogue_entry_name'] = $entryName

    $script:ObjectRecords += [pscustomobject]@{
        ObjectCode = $objectCode
        ObjectName = $objectName
        ObjectDescription = "Imported from $SourceFile profile $($Profile.typeName)."
        ObjectTypeCode = $ObjectTypeCode
        ObjectTypeName = $ObjectTypeName
        FactionName = $FactionName
        ObjectData = ($objectData | ConvertTo-Json -Compress -Depth 20)
        SourceReference = (([ordered]@{
            file = $SourceFile
            revision = $SourceRevision
            entry_id = [string] $Entry.id
            profile_id = [string] $Profile.id
        }) | ConvertTo-Json -Compress -Depth 10)
    }
}

$script:CatalogueNamespace = New-Object System.Xml.XmlNamespaceManager((New-Object System.Xml.NameTable))
$script:CatalogueNamespace.AddNamespace('bs', 'http://www.battlescribe.net/schema/catalogueSchema')
$gameNamespace = New-Object System.Xml.XmlNamespaceManager((New-Object System.Xml.NameTable))
$gameNamespace.AddNamespace('bs', 'http://www.battlescribe.net/schema/gameSystemSchema')

$gameSystemPath = Join-Path $SourceDirectory 'Star_Wars_Armada.gst'
[xml] $gameSystem = Get-Content -LiteralPath $gameSystemPath
$categoryNames = @{}
foreach ($category in $gameSystem.SelectNodes('//bs:categoryEntry', $gameNamespace)) {
    $categoryNames[$category.id] = Normalize-Text $category.name
}

$factionNames = @{
    'Galactic Republic.cat' = 'Galactic Republic'
    'Imperial Navy.cat' = 'Galactic Empire'
    'Rebel Fleet.cat' = 'Rebel Alliance'
    'Separatist Alliance.cat' = 'Separatist Alliance'
}

$script:ProfileRecords = @()
$script:ObjectRecords = @()
$profileObjectTypes = [ordered]@{
    '2.0 Commander' = @('card.commander', 'Commander card')
    '2.1 Title' = @('card.title', 'Title card')
    '3.1 Officer' = @('card.officer', 'Officer card')
    '3.2 Weapons Team' = @('card.weapons_team', 'Weapons Team card')
    '3.3 Support Team' = @('card.support_team', 'Support Team card')
    '4.1 Fleet Support' = @('card.fleet_support', 'Fleet Support card')
    '4.2 Fleet Command' = @('card.fleet_command', 'Fleet Command card')
    '5.1 Offensive Retrofit' = @('card.offensive_retrofit', 'Offensive Retrofit card')
    '5.2 Defensive Retrofit' = @('card.defensive_retrofit', 'Defensive Retrofit card')
    '5.3 Experimental Retrofit' = @('card.experimental_retrofit', 'Experimental Retrofit card')
    '6.1 Ordnance' = @('card.ordnance', 'Ordnance card')
    '6.2 Ion Cannons' = @('card.ion_cannon', 'Ion Cannon card')
    '6.3 Turbolasers' = @('card.turbolaser', 'Turbolaser card')
    '6.4 Superweapon' = @('card.superweapon', 'Superweapon card')
    '7.1 Dual Type Upgrade - Weapons Team & Offensive Retrofit' = @('card.dual_upgrade', 'Dual-type upgrade card')
}
$catalogueFiles = Get-ChildItem -LiteralPath $SourceDirectory -Filter '*.cat' | Sort-Object Name
foreach ($catalogueFile in $catalogueFiles) {
    if (-not $factionNames.ContainsKey($catalogueFile.Name)) {
        continue
    }

    [xml] $catalogue = Get-Content -LiteralPath $catalogueFile.FullName
    $factionName = $factionNames[$catalogueFile.Name]
    $revision = Normalize-Text $catalogue.catalogue.revision

    # Squadron entries are represented as a family with model profiles below it.
    foreach ($unitEntry in $catalogue.SelectNodes('/bs:catalogue/bs:selectionEntries/bs:selectionEntry[@type="unit"]', $script:CatalogueNamespace)) {
        $unitTypeName = Normalize-Text $unitEntry.name
        foreach ($modelEntry in $unitEntry.SelectNodes('.//bs:selectionEntry[@type="model"]', $script:CatalogueNamespace)) {
            $profile = Get-Profile $modelEntry '1.4 Squadrons'
            Add-ProfileRecords $profile $factionName 'Squadron' $unitTypeName (Normalize-Text $modelEntry.name) $catalogueFile.Name $revision
        }
    }

    # Ship entries are grouped by their ERD category, with each profile as a variant.
    foreach ($shipEntry in $catalogue.SelectNodes('/bs:catalogue/bs:selectionEntries/bs:selectionEntry[@type="model"]', $script:CatalogueNamespace)) {
        $primaryCategory = $shipEntry.SelectSingleNode('bs:categoryLinks/bs:categoryLink[@primary="true"]', $script:CatalogueNamespace)
        if ($null -eq $primaryCategory -or -not $categoryNames.ContainsKey($primaryCategory.targetId)) {
            continue
        }

        $profile = Get-Profile $shipEntry '1.1 Ships'
        Add-ProfileRecords $profile $factionName 'Ship' $categoryNames[$primaryCategory.targetId] (Normalize-Text $shipEntry.name) $catalogueFile.Name $revision
    }

    # Upgrade profiles are generic game objects. Their source constraints and
    # costs remain in JSON until the legality engine gives them typed meaning.
    foreach ($entry in $catalogue.SelectNodes('//bs:selectionEntry', $script:CatalogueNamespace)) {
        foreach ($profile in $entry.SelectNodes('bs:profiles/bs:profile', $script:CatalogueNamespace)) {
            $profileType = Normalize-Text $profile.typeName
            if ($profileObjectTypes.Contains($profileType)) {
                $objectType = $profileObjectTypes[$profileType]
                Add-GameObjectRecord $entry $profile $objectType[0] $objectType[1] $factionName $catalogueFile.Name $revision $script:CatalogueNamespace
            }
        }
    }
}

$objectiveObjectTypes = [ordered]@{
    '8.1 Assault Objective' = @('card.objective_assault', 'Assault objective card')
    '8.2 Defense Objective' = @('card.objective_defense', 'Defense objective card')
    '8.3 Navigation Objective' = @('card.objective_navigation', 'Navigation objective card')
    '8.4 Campaign Objective' = @('card.objective_campaign', 'Campaign objective card')
    '8.5 Scenario Objective' = @('card.objective_scenario', 'Scenario objective card')
}
$gameSystemRevision = Normalize-Text $gameSystem.gameSystem.revision
foreach ($entry in $gameSystem.SelectNodes('//bs:selectionEntry', $gameNamespace)) {
    foreach ($profile in $entry.SelectNodes('bs:profiles/bs:profile', $gameNamespace)) {
        $profileType = Normalize-Text $profile.typeName
        if ($objectiveObjectTypes.Contains($profileType)) {
            $objectType = $objectiveObjectTypes[$profileType]
            Add-GameObjectRecord $entry $profile $objectType[0] $objectType[1] $null 'Star_Wars_Armada.gst' $gameSystemRevision $gameNamespace
        }
    }
}

$gameName = 'Star Wars: Armada'
$gameDescription = 'Star Wars: Armada 1.5-era reference data imported from community-maintained BSData catalogues.'
$builder = New-Object System.Text.StringBuilder
[void] $builder.AppendLine('-- METIS Armada reference seed data')
[void] $builder.AppendLine('-- Source: https://github.com/BSData/star-wars-armada')
[void] $builder.AppendLine('-- Source catalogues contain January 2025 errata updates.')
[void] $builder.AppendLine('-- Ships, squadrons, upgrades, objectives, phases, scenarios, and fleet metadata are imported.')
[void] $builder.AppendLine('-- Characteristic values are cleaned into typed JSON while raw_value preserves the source text.')
[void] $builder.AppendLine('-- Rule execution remains outside the static catalogue seed.')
[void] $builder.AppendLine('BEGIN;')
[void] $builder.AppendLine('SET LOCAL search_path TO game_engine, public;')
[void] $builder.AppendLine()

[void] $builder.AppendLine("INSERT INTO game (game_name, game_description, minimum_number_of_players, maximum_number_of_players, minimum_number_of_cpu, maximum_number_of_cpu) VALUES ($(Sql-Literal $gameName), $(Sql-Literal $gameDescription), 2, 2, 0, 2);")
[void] $builder.AppendLine()

[void] $builder.AppendLine("INSERT INTO content_source (source_code, source_name, source_url, licence) VALUES ('bsdata.star-wars-armada', 'BSData Star Wars Armada', 'https://github.com/BSData/star-wars-armada', 'CC BY-SA 4.0') ON CONFLICT (source_code) DO UPDATE SET source_name = EXCLUDED.source_name, source_url = EXCLUDED.source_url, licence = EXCLUDED.licence;")
[void] $builder.AppendLine("INSERT INTO game_version (game_id, content_source_id, version_code, version_name, release_date, source_revision, version_config_json) SELECT g.game_id, cs.content_source_id, 'armada_1_5', 'Armada 1.5 reference catalogue', DATE '2025-01-01', $(Sql-Literal $gameSystemRevision), '{`"catalogue_format`":`"BattleScribe`",`"status`":`"reference_only`"}'::jsonb FROM game g CROSS JOIN content_source cs WHERE g.game_name = $(Sql-Literal $gameName) AND cs.source_code = 'bsdata.star-wars-armada';")
[void] $builder.AppendLine()

$objectTypeDefinitions = @(
    @('card.unit', 'Unit card', 'card'),
    @('card.commander', 'Commander card', 'card'),
    @('card.title', 'Title card', 'card'),
    @('card.officer', 'Officer card', 'card'),
    @('card.weapons_team', 'Weapons Team card', 'card'),
    @('card.support_team', 'Support Team card', 'card'),
    @('card.fleet_support', 'Fleet Support card', 'card'),
    @('card.fleet_command', 'Fleet Command card', 'card'),
    @('card.offensive_retrofit', 'Offensive Retrofit card', 'card'),
    @('card.defensive_retrofit', 'Defensive Retrofit card', 'card'),
    @('card.experimental_retrofit', 'Experimental Retrofit card', 'card'),
    @('card.ordnance', 'Ordnance card', 'card'),
    @('card.ion_cannon', 'Ion Cannon card', 'card'),
    @('card.turbolaser', 'Turbolaser card', 'card'),
    @('card.superweapon', 'Superweapon card', 'card'),
    @('card.dual_upgrade', 'Dual-type upgrade card', 'card'),
    @('card.objective_assault', 'Assault objective card', 'card'),
    @('card.objective_defense', 'Defense objective card', 'card'),
    @('card.objective_navigation', 'Navigation objective card', 'card'),
    @('card.objective_campaign', 'Campaign objective card', 'card'),
    @('card.objective_scenario', 'Scenario objective card', 'card'),
    @('deck.objective', 'Objective deck', 'deck'),
    @('deck.damage', 'Damage deck', 'deck'),
    @('token.defense', 'Defense token', 'token'),
    @('token.command', 'Command token', 'token'),
    @('die.attack', 'Attack die', 'die'),
    @('dial.command', 'Command dial', 'dial'),
    @('dial.speed', 'Speed dial', 'dial'),
    @('base.ship', 'Ship base', 'base'),
    @('base.squadron', 'Squadron base', 'base'),
    @('profile.attack', 'Attack profile', 'profile'),
    @('profile.movement', 'Movement profile', 'profile'),
    @('ability', 'Ability', 'rule_component'),
    @('keyword', 'Keyword', 'rule_component')
)
foreach ($objectType in $objectTypeDefinitions) {
    [void] $builder.AppendLine("INSERT INTO game_object_type (game_id, object_type_code, object_type_name, object_kind) SELECT game_id, $(Sql-Literal $objectType[0]), $(Sql-Literal $objectType[1]), $(Sql-Literal $objectType[2]) FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
[void] $builder.AppendLine()

$factionDescriptions = @{
    'Galactic Empire' = 'Imperial Navy faction.'
    'Rebel Alliance' = 'Rebel Fleet faction.'
    'Galactic Republic' = 'Galactic Republic faction.'
    'Separatist Alliance' = 'Separatist Alliance faction.'
}
foreach ($factionName in @('Galactic Empire', 'Rebel Alliance', 'Galactic Republic', 'Separatist Alliance')) {
    [void] $builder.AppendLine("INSERT INTO faction (game_id, faction_name, faction_description) SELECT game_id, $(Sql-Literal $factionName), $(Sql-Literal $factionDescriptions[$factionName]) FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
[void] $builder.AppendLine()

foreach ($factionName in @('Galactic Empire', 'Rebel Alliance', 'Galactic Republic', 'Separatist Alliance')) {
    foreach ($classificationName in @('Ship', 'Squadron')) {
        [void] $builder.AppendLine("INSERT INTO unit_classification (faction_id, unit_classification_name, unit_classification_description) SELECT faction_id, $(Sql-Literal $classificationName), $(Sql-Literal "Armada $classificationName classification.") FROM faction WHERE faction_name = $(Sql-Literal $factionName) AND game_id = (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName));")
    }
}
[void] $builder.AppendLine()

$unitTypes = @{}
foreach ($record in $script:ProfileRecords) {
    $key = "$($record.FactionName)|$($record.ClassificationName)|$($record.UnitTypeName)"
    if (-not $unitTypes.ContainsKey($key)) {
        $unitTypes[$key] = $record
    }
}
foreach ($record in $unitTypes.Values) {
    [void] $builder.AppendLine("INSERT INTO unit_type (unit_classification_id, unit_type_name, unit_type_description) SELECT unit_classification_id, $(Sql-Literal $record.UnitTypeName), $(Sql-Literal "Imported from the $($record.ClassificationName.ToLowerInvariant()) catalogue.") FROM unit_classification WHERE unit_classification_name = $(Sql-Literal $record.ClassificationName) AND faction_id = (SELECT faction_id FROM faction WHERE faction_name = $(Sql-Literal $record.FactionName) AND game_id = (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName)));" )
}
[void] $builder.AppendLine()

$unitVariants = @{}
foreach ($record in $script:ProfileRecords) {
    $key = "$($record.FactionName)|$($record.ClassificationName)|$($record.UnitTypeName)|$($record.UnitVariantName)"
    if (-not $unitVariants.ContainsKey($key)) {
        $unitVariants[$key] = $record
    }
}
foreach ($record in $unitVariants.Values) {
    [void] $builder.AppendLine("INSERT INTO unit_variant (unit_type_id, unit_variant_name, unit_variant_description) SELECT unit_type_id, $(Sql-Literal $record.UnitVariantName), $(Sql-Literal $record.UnitVariantDescription) FROM unit_type WHERE unit_type_name = $(Sql-Literal $record.UnitTypeName) AND unit_classification_id = (SELECT unit_classification_id FROM unit_classification WHERE unit_classification_name = $(Sql-Literal $record.ClassificationName) AND faction_id = (SELECT faction_id FROM faction WHERE faction_name = $(Sql-Literal $record.FactionName) AND game_id = (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName))));")
}
[void] $builder.AppendLine()

$characteristicNames = @{}
foreach ($record in $script:ProfileRecords) {
    if (-not $characteristicNames.ContainsKey($record.CharacteristicName)) {
        $characteristicNames[$record.CharacteristicName] = $true
    }
}
foreach ($characteristicName in ($characteristicNames.Keys | Sort-Object)) {
    [void] $builder.AppendLine("INSERT INTO characteristic_type (characteristic_type_name, characteristic_type_description) VALUES ($(Sql-Literal $characteristicName), 'Imported from Armada unit profiles; value semantics remain to be defined by the engine.') ON CONFLICT (characteristic_type_name) DO NOTHING;")
}
[void] $builder.AppendLine()

foreach ($record in $script:ProfileRecords) {
    $cleanedValue = Convert-CharacteristicData $record.CharacteristicName $record.CharacteristicValue
    $characteristicData = [ordered]@{
        source = [ordered]@{
            catalogue = $record.SourceCatalogue
            revision = $record.SourceRevision
            profile = $record.ProfileName
            characteristic = $record.CharacteristicName
        }
        raw_value = $cleanedValue.raw_value
        value = $cleanedValue.value
    } | ConvertTo-Json -Compress -Depth 10
    [void] $builder.AppendLine("INSERT INTO unit_variant_characteristic (unit_variant_id, characteristic_type_id, characteristic_json) SELECT uv.unit_variant_id, ct.characteristic_type_id, $(Sql-Literal $characteristicData)::jsonb FROM unit_variant uv JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN characteristic_type ct ON ct.characteristic_type_name = $(Sql-Literal $record.CharacteristicName) WHERE f.faction_name = $(Sql-Literal $record.FactionName) AND uc.unit_classification_name = $(Sql-Literal $record.ClassificationName) AND ut.unit_type_name = $(Sql-Literal $record.UnitTypeName) AND uv.unit_variant_name = $(Sql-Literal $record.UnitVariantName);")
}
[void] $builder.AppendLine()

[void] $builder.AppendLine("INSERT INTO terrain_type (game_id, terrain_type_name, terrain_type, default_characteristic_json) SELECT game_id, terrain_type_name, 'Obstacle', default_characteristic_json FROM (VALUES")
[void] $builder.AppendLine('    (''Asteroid Field'', ''{"obstacle": true}''::jsonb),')
[void] $builder.AppendLine('    (''Debris Field'', ''{"obstacle": true}''::jsonb),')
[void] $builder.AppendLine('    (''Dust Field'', ''{"obstacle": true}''::jsonb),')
[void] $builder.AppendLine('    (''Station'', ''{"obstacle": true}''::jsonb)')
[void] $builder.AppendLine(") AS terrain(terrain_type_name, default_characteristic_json) CROSS JOIN (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName)) AS selected_game;")
[void] $builder.AppendLine()

$rules = @(
    @('Movement', 'Movement and maneuver rules require executable game logic.'),
    @('Combat', 'Attack, defense, damage, and firing arc rules require executable game logic.'),
    @('Squadron Activation', 'Squadron activation and engagement rules require executable game logic.'),
    @('Command', 'Command dial and token rules require executable game logic.'),
    @('Deployment', 'Deployment and setup rules require executable game logic.'),
    @('Objectives and Scoring', 'Objective and victory rules require an explicit scenario model.')
)
foreach ($rule in $rules) {
    $referenceData = Sql-Literal '{"status": "reference_only"}'
    [void] $builder.AppendLine("INSERT INTO game_rule (game_id, game_rule_name, game_rule_description, game_rule_characteristic_json) SELECT game_id, $(Sql-Literal $rule[0]), $(Sql-Literal $rule[1]), ${referenceData}::jsonb FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
[void] $builder.AppendLine()

$modeConfigs = @{
    'Standard Battle' = '{"mode_type":"standard_battle","players":{"minimum":2,"maximum":2},"rounds":{"phases":["command","ship","squadron","status"]},"status":"reference_only"}'
    'Fleet Battle' = '{"mode_type":"fleet_battle","players":{"minimum":2,"maximum":2},"rounds":{"phases":["command","ship","squadron","status"]},"status":"reference_only"}'
}
$modeCodes = @{
    'Standard Battle' = 'standard_battle'
    'Fleet Battle' = 'fleet_battle'
}
foreach ($modeName in @('Standard Battle', 'Fleet Battle')) {
    [void] $builder.AppendLine("INSERT INTO game_mode (game_id, game_mode_code, game_mode_name, game_mode_description, mode_config_json) SELECT game_id, $(Sql-Literal $modeCodes[$modeName]), $(Sql-Literal $modeName), 'Scenario definition placeholder; detailed objectives and restrictions are not yet modelled.', $(Sql-Literal $modeConfigs[$modeName])::jsonb FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
[void] $builder.AppendLine()

$ruleOrder = 0
foreach ($modeName in @('Standard Battle', 'Fleet Battle')) {
    foreach ($rule in $rules) {
        [void] $builder.AppendLine("INSERT INTO game_mode_rule (game_mode_id, game_rule_id, rule_order, rule_config_json) SELECT gm.game_mode_id, gr.game_rule_id, $ruleOrder, '{}'::jsonb FROM game_mode gm JOIN game_rule gr ON gr.game_id = gm.game_id WHERE gm.game_mode_code = $(Sql-Literal $modeCodes[$modeName]) AND gr.game_rule_name = $(Sql-Literal $rule[0]);")
        $ruleOrder++
    }
    $ruleOrder = 0
}

[void] $builder.AppendLine()
[void] $builder.AppendLine('-- Phase and action definitions are data-driven; the engine supplies execution semantics later.')
$phases = @(
    @('command', 'Command Phase', 'Players reveal and resolve command dials.', '{"phase_ordering":"initiative_player_first"}'),
    @('ship', 'Ship Phase', 'Players activate ships and resolve their commands.', '{"activation":"alternating"}'),
    @('squadron', 'Squadron Phase', 'Players activate squadrons and resolve their commands.', '{"activation":"alternating"}'),
    @('status', 'Status Phase', 'Players resolve end-of-round effects and refresh tokens.', '{"refresh":"defense_tokens"}')
)
foreach ($phase in $phases) {
    [void] $builder.AppendLine("INSERT INTO phase_definition (game_id, phase_code, phase_name, phase_description, phase_config_json) SELECT game_id, $(Sql-Literal $phase[0]), $(Sql-Literal $phase[1]), $(Sql-Literal $phase[2]), $(Sql-Literal $phase[3])::jsonb FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
$actions = @(
    @('concentrate_fire', 'Concentrate Fire', 'Improve an attack during the ship phase.'),
    @('navigate', 'Navigate', 'Change speed or adjust a maneuver during the ship phase.'),
    @('repair', 'Repair', 'Restore shields or repair damage during the ship phase.'),
    @('squadron', 'Squadron', 'Activate squadrons during the ship phase.'),
    @('pass', 'Pass', 'Pass activation priority when permitted by the game mode.')
)
foreach ($action in $actions) {
    [void] $builder.AppendLine("INSERT INTO action_definition (game_id, action_code, action_name, action_description) SELECT game_id, $(Sql-Literal $action[0]), $(Sql-Literal $action[1]), $(Sql-Literal $action[2]) FROM game WHERE game_name = $(Sql-Literal $gameName);")
}
foreach ($modeName in @('Standard Battle', 'Fleet Battle')) {
    foreach ($phase in $phases) {
        $phaseOrder = [array]::IndexOf($phases, $phase)
        [void] $builder.AppendLine("INSERT INTO game_mode_phase (game_mode_id, phase_id, phase_order, phase_config_json) SELECT gm.game_mode_id, p.phase_id, $phaseOrder, p.phase_config_json FROM game_mode gm JOIN phase_definition p ON p.game_id = gm.game_id WHERE gm.game_mode_code = $(Sql-Literal $modeCodes[$modeName]) AND p.phase_code = $(Sql-Literal $phase[0]);")
    }
}

[void] $builder.AppendLine()
[void] $builder.AppendLine('-- Generic objects imported from catalogue and game-system profiles.')
foreach ($object in $script:ObjectRecords) {
    [void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_description, display_text, object_data_json, source_reference_json) SELECT g.game_id, got.game_object_type_id, $(Sql-Literal $object.ObjectCode), $(Sql-Literal $object.ObjectName), $(Sql-Literal $object.ObjectDescription), $(Sql-Literal $object.ObjectName), $(Sql-Literal $object.ObjectData)::jsonb, $(Sql-Literal $object.SourceReference)::jsonb FROM game g JOIN game_object_type got ON got.game_id = g.game_id AND got.object_type_code = $(Sql-Literal $object.ObjectTypeCode) WHERE g.game_name = $(Sql-Literal $gameName);")
    if ($object.FactionName) {
        [void] $builder.AppendLine("INSERT INTO game_object_eligibility (game_object_id, faction_id, eligibility_type) SELECT go.game_object_id, f.faction_id, 'include' FROM game_object go JOIN faction f ON f.game_id = go.game_id WHERE go.object_code = $(Sql-Literal $object.ObjectCode) AND f.faction_name = $(Sql-Literal $object.FactionName);")
    }
}

$simpleObjects = @(
    @('deck.objective', 'objective_deck', 'Objective Deck', '{"deck_type":"objective","status":"reference_only"}'),
    @('deck.damage', 'damage_deck', 'Damage Deck', '{"deck_type":"damage","status":"reference_only","source_note":"Individual damage cards are not present in the BSData source."}'),
    @('token.defense', 'token_brace', 'Brace', '{"token_type":"Brace"}'),
    @('token.defense', 'token_redirect', 'Redirect', '{"token_type":"Redirect"}'),
    @('token.defense', 'token_scatter', 'Scatter', '{"token_type":"Scatter"}'),
    @('token.defense', 'token_evade', 'Evade', '{"token_type":"Evade"}'),
    @('token.defense', 'token_contain', 'Contain', '{"token_type":"Contain"}'),
    @('token.defense', 'token_salvo', 'Salvo', '{"token_type":"Salvo"}'),
    @('token.command', 'token_concentrate_fire', 'Concentrate Fire', '{"token_type":"Concentrate Fire"}'),
    @('token.command', 'token_navigate', 'Navigate', '{"token_type":"Navigate"}'),
    @('token.command', 'token_repair', 'Repair', '{"token_type":"Repair"}'),
    @('token.command', 'token_squadron', 'Squadron', '{"token_type":"Squadron"}'),
    @('die.attack', 'die_red', 'Red attack die', '{"color":"Red"}'),
    @('die.attack', 'die_blue', 'Blue attack die', '{"color":"Blue"}'),
    @('die.attack', 'die_black', 'Black attack die', '{"color":"Black"}'),
    @('dial.command', 'command_dial', 'Command dial', '{"dial_type":"command"}'),
    @('dial.speed', 'speed_dial', 'Speed dial', '{"dial_type":"speed"}'),
    @('base.ship', 'ship_base', 'Ship base', '{"geometry":"ship_base","status":"reference_only"}'),
    @('base.squadron', 'squadron_base', 'Squadron base', '{"geometry":"squadron_base","status":"reference_only"}')
)
foreach ($object in $simpleObjects) {
    [void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_data_json) SELECT g.game_id, got.game_object_type_id, $(Sql-Literal $object[1]), $(Sql-Literal $object[2]), $(Sql-Literal $object[3])::jsonb FROM game g JOIN game_object_type got ON got.game_id = g.game_id AND got.object_type_code = $(Sql-Literal $object[0]) WHERE g.game_name = $(Sql-Literal $gameName);")
}

[void] $builder.AppendLine()
[void] $builder.AppendLine('-- Unit variants are also addressable as generic unit-card objects.')
[void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_description, object_data_json) SELECT f.game_id, got.game_object_type_id, 'unit_variant:' || uv.unit_variant_id, uv.unit_variant_name, uv.unit_variant_description, jsonb_build_object('unit_variant_id', uv.unit_variant_id, 'unit_type_id', ut.unit_type_id, 'classification', uc.unit_classification_name, 'faction', f.faction_name) FROM unit_variant uv JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN game_object_type got ON got.game_id = f.game_id AND got.object_type_code = 'card.unit' WHERE f.game_id = (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName));")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code) SELECT uv.unit_variant_id, go.game_object_id, 'unit_card' FROM unit_variant uv JOIN game_object go ON go.object_code = 'unit_variant:' || uv.unit_variant_id;")

[void] $builder.AppendLine()
[void] $builder.AppendLine('-- Derived profiles and token/keyword associations preserve the source characteristics while exposing reusable objects.')
[void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_data_json) SELECT f.game_id, got.game_object_type_id, 'attack_profile:' || uvc.unit_variant_characteristic_id, uv.unit_variant_name || ' ' || ct.characteristic_type_name, uvc.characteristic_json FROM unit_variant_characteristic uvc JOIN unit_variant uv ON uv.unit_variant_id = uvc.unit_variant_id JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id JOIN game_object_type got ON got.game_id = f.game_id AND got.object_type_code = 'profile.attack' WHERE ct.characteristic_type_name = 'Anti-Squadron' OR ct.characteristic_type_name LIKE '%Firing Arc';")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code) SELECT uvc.unit_variant_id, go.game_object_id, 'attack_profile' FROM unit_variant_characteristic uvc JOIN game_object go ON go.object_code = 'attack_profile:' || uvc.unit_variant_characteristic_id;")
[void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_data_json) SELECT f.game_id, got.game_object_type_id, 'movement_profile:' || uv.unit_variant_id, uv.unit_variant_name || ' movement', jsonb_object_agg(ct.characteristic_type_name, uvc.characteristic_json) FROM unit_variant uv JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN unit_variant_characteristic uvc ON uvc.unit_variant_id = uv.unit_variant_id JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id JOIN game_object_type got ON got.game_id = f.game_id AND got.object_type_code = 'profile.movement' WHERE ct.characteristic_type_name IN ('Speed', 'Max Speed') GROUP BY f.game_id, got.game_object_type_id, uv.unit_variant_id, uv.unit_variant_name;")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code) SELECT uv.unit_variant_id, go.game_object_id, 'movement_profile' FROM unit_variant uv JOIN game_object go ON go.object_code = 'movement_profile:' || uv.unit_variant_id;")
[void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_data_json) SELECT f.game_id, got.game_object_type_id, 'ability:' || uvc.unit_variant_characteristic_id, uv.unit_variant_name || ' ability', uvc.characteristic_json FROM unit_variant_characteristic uvc JOIN unit_variant uv ON uv.unit_variant_id = uvc.unit_variant_id JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id JOIN game_object_type got ON got.game_id = f.game_id AND got.object_type_code = 'ability' WHERE ct.characteristic_type_name = 'Squadron Ability';")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code) SELECT uvc.unit_variant_id, go.game_object_id, 'ability' FROM unit_variant_characteristic uvc JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id JOIN game_object go ON go.object_code = 'ability:' || uvc.unit_variant_characteristic_id WHERE ct.characteristic_type_name = 'Squadron Ability';")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code, quantity) SELECT uvc.unit_variant_id, go.game_object_id, 'defense_token', (token->>'count')::integer FROM unit_variant_characteristic uvc CROSS JOIN LATERAL jsonb_array_elements(uvc.characteristic_json->'value') token JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id JOIN game_object go ON go.object_name = token->>'name' AND go.object_code LIKE 'token_%' WHERE ct.characteristic_type_name = 'Defense Tokens' AND (token->>'count')::integer > 0 ON CONFLICT DO NOTHING;")
[void] $builder.AppendLine("INSERT INTO game_object (game_id, game_object_type_id, object_code, object_name, object_data_json) SELECT DISTINCT f.game_id, got.game_object_type_id, 'keyword:' || md5(keyword.value), keyword.value, jsonb_build_object('keyword', keyword.value) FROM unit_variant_characteristic uvc JOIN unit_variant uv ON uv.unit_variant_id = uvc.unit_variant_id JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(uvc.characteristic_json->'value'->'keywords') = 'array' THEN uvc.characteristic_json->'value'->'keywords' ELSE '[]'::jsonb END) keyword JOIN game_object_type got ON got.game_id = f.game_id AND got.object_type_code = 'keyword';")
[void] $builder.AppendLine("INSERT INTO unit_variant_game_object (unit_variant_id, game_object_id, usage_code) SELECT DISTINCT uvc.unit_variant_id, go.game_object_id, 'keyword' FROM unit_variant_characteristic uvc JOIN unit_variant uv ON uv.unit_variant_id = uvc.unit_variant_id JOIN unit_type ut ON ut.unit_type_id = uv.unit_type_id JOIN unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id JOIN faction f ON f.faction_id = uc.faction_id JOIN characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(uvc.characteristic_json->'value'->'keywords') = 'array' THEN uvc.characteristic_json->'value'->'keywords' ELSE '[]'::jsonb END) keyword JOIN game_object go ON go.object_code = 'keyword:' || md5(keyword.value) WHERE ct.characteristic_type_name = 'Keywords';")

[void] $builder.AppendLine()
[void] $builder.AppendLine('-- Scenarios, objective pools, board geometry and fleet-format metadata.')
[void] $builder.AppendLine("INSERT INTO scenario_definition (game_id, scenario_code, scenario_name, scenario_description, scenario_config_json) SELECT game_id, 'standard_battle', 'Standard Battle Scenario', 'Reference scenario container for the three standard objective categories.', '{`"objective_categories`": [`"assault`",`"defense`",`"navigation`"],`"status`":`"reference_only`"}'::jsonb FROM game WHERE game_name = $(Sql-Literal $gameName);")
[void] $builder.AppendLine("INSERT INTO board_definition (game_id, board_code, board_name, width, height, coordinate_system, board_config_json) SELECT game_id, 'standard_6x3', 'Standard 6 ft x 3 ft play area', 1800, 900, 'continuous_2d_mm', '{`"source`":`"standard Armada play area`",`"status`":`"reference_only`"}'::jsonb FROM game WHERE game_name = $(Sql-Literal $gameName);")
[void] $builder.AppendLine("INSERT INTO scenario_board (scenario_id, board_definition_id) SELECT s.scenario_id, b.board_definition_id FROM scenario_definition s JOIN board_definition b ON b.game_id = s.game_id WHERE s.scenario_code = 'standard_battle' AND b.board_code = 'standard_6x3';")
foreach ($modeName in @('Standard Battle', 'Fleet Battle')) {
    [void] $builder.AppendLine("INSERT INTO game_mode_scenario (game_mode_id, scenario_id, is_default) SELECT gm.game_mode_id, s.scenario_id, $(if ($modeName -eq 'Standard Battle') { 'true' } else { 'false' }) FROM game_mode gm JOIN scenario_definition s ON s.game_id = gm.game_id WHERE gm.game_mode_code = $(Sql-Literal $modeCodes[$modeName]) AND s.scenario_code = 'standard_battle';")
}
[void] $builder.AppendLine("INSERT INTO scenario_game_object (scenario_id, game_object_id, usage_code) SELECT s.scenario_id, go.game_object_id, 'objective_card' FROM scenario_definition s JOIN game_object go ON go.game_id = (SELECT game_id FROM game WHERE game_name = $(Sql-Literal $gameName)) JOIN game_object_type got ON got.game_object_type_id = go.game_object_type_id WHERE s.scenario_code = 'standard_battle' AND got.object_type_code IN ('card.objective_assault', 'card.objective_defense', 'card.objective_navigation');")
[void] $builder.AppendLine("INSERT INTO game_mode_game_object (game_mode_id, game_object_id, usage_code) SELECT gm.game_mode_id, go.game_object_id, 'objective_card' FROM game_mode gm JOIN game_object go ON go.game_id = gm.game_id JOIN game_object_type got ON got.game_object_type_id = go.game_object_type_id WHERE got.object_type_code IN ('card.objective_assault', 'card.objective_defense', 'card.objective_navigation');")
foreach ($modeName in @('Standard Battle', 'Fleet Battle')) {
    [void] $builder.AppendLine("INSERT INTO fleet_format (game_mode_id, format_code, format_name, points_limit, format_config_json) SELECT game_mode_id, 'standard_fleet', 'Standard fleet', 400, '{`"ship_limit`":3,`"squadron_limit`":5,`"status`":`"reference_only`"}'::jsonb FROM game_mode WHERE game_mode_code = $(Sql-Literal $modeCodes[$modeName]);")
}
[void] $builder.AppendLine("INSERT INTO fleet_format_faction (fleet_format_id, faction_id) SELECT ff.fleet_format_id, f.faction_id FROM fleet_format ff JOIN game_mode gm ON gm.game_mode_id = ff.game_mode_id JOIN faction f ON f.game_id = gm.game_id;")
[void] $builder.AppendLine("INSERT INTO fleet_unit_limit (fleet_format_id, unit_classification_id, maximum_count, limit_config_json) SELECT ff.fleet_format_id, uc.unit_classification_id, CASE uc.unit_classification_name WHEN 'Ship' THEN 3 WHEN 'Squadron' THEN 5 END, '{`"source`":`"standard fleet reference`",`"status`":`"reference_only`"}'::jsonb FROM fleet_format ff JOIN game_mode gm ON gm.game_mode_id = ff.game_mode_id JOIN faction f ON f.game_id = gm.game_id JOIN unit_classification uc ON uc.faction_id = f.faction_id GROUP BY ff.fleet_format_id, uc.unit_classification_id, uc.unit_classification_name;")

[void] $builder.AppendLine('COMMIT;')
[void] $builder.AppendLine()
[void] $builder.AppendLine("-- Imported $(($unitTypes.Count)) unit types, $(($unitVariants.Count)) variants, $(($script:ProfileRecords.Count)) characteristic values, and $(($script:ObjectRecords.Count)) catalogue objects.")

$parentDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $parentDirectory)) {
    New-Item -ItemType Directory -Path $parentDirectory -Force | Out-Null
}
[System.IO.File]::WriteAllText($OutputPath, $builder.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Generated $OutputPath"
