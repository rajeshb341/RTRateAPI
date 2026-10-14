# Shared helpers for the Rater scripts. Drives WPS Spreadsheets (KET.Application) or Excel through COM.
# The original workbook is never modified: every script works on a temporary copy.

$ErrorActionPreference = 'Stop'

function Start-RaterApp {
    param([string]$ProgId = 'KET.Application')
    foreach ($id in @($ProgId, 'Excel.Application')) {
        try {
            $app = New-Object -ComObject $id
            $app.Visible = $false
            $app.DisplayAlerts = $false
            return $app
        } catch { }
    }
    throw "Could not start a spreadsheet application via COM (tried $ProgId and Excel.Application)."
}

function New-RaterWorkCopy {
    param([string]$Workbook)
    if (-not (Test-Path -LiteralPath $Workbook)) { throw "Rater workbook not found: $Workbook" }
    $copy = Join-Path ([IO.Path]::GetTempPath()) ("rater_work_" + [guid]::NewGuid().ToString('N') + ".xlsx")
    Copy-Item -LiteralPath $Workbook -Destination $copy -Force
    return $copy
}

function Expand-Rows {
    param([string]$Spec)
    $rows = New-Object System.Collections.Generic.List[int]
    foreach ($part in ($Spec -split '[,;\s]+' | Where-Object { $_ })) {
        if ($part -match '^(\d+)-(\d+)$') { for ($i = [int]$Matches[1]; $i -le [int]$Matches[2]; $i++) { $rows.Add($i) } }
        elseif ($part -match '^\d+$') { $rows.Add([int]$part) }
        else { throw "Invalid row spec: $part" }
    }
    return ,$rows.ToArray()
}

$script:ErrorCodes = @{
    -2146826281 = '#DIV/0!'; -2146826246 = '#N/A'; -2146826259 = '#NAME?'; -2146826288 = '#NULL!'
    -2146826252 = '#NUM!';  -2146826265 = '#REF!'; -2146826273 = '#VALUE!'
}

# Converts a COM cell value to something JSON-friendly (spreadsheet error codes become their text).
function ConvertTo-PlainValue {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [int]) {
        if ($script:ErrorCodes.ContainsKey($Value)) { return $script:ErrorCodes[$Value] }
        return "#ERR($Value)"
    }
    if ($Value -is [datetime]) { return $Value.ToString('MM/dd/yyyy') }
    return $Value
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding $false))
}

# Writes a value the way a user would type it: numbers as numbers, everything else as text.
function Set-RaterCell {
    param($Cell, $Value)
    if ($null -eq $Value -or "$Value" -eq '') { $Cell.Value2 = $null; return }
    $text = "$Value"
    $num = 0.0
    if ([double]::TryParse($text, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$num)) {
        $Cell.Value2 = $num
    } elseif ($text -match '^[\d/\-: ]+$') {
        $Cell.Formula = "'" + $text    # stop dates / ranges such as 06/21/2026 or 614-652 being re-interpreted
    } else {
        # Formula (not Value2) takes plain text reliably through WPS COM; a leading apostrophe keeps '=...' text literal
        $Cell.Formula = if ($text.StartsWith('=')) { "'" + $text } else { $text }
    }
}

function Close-RaterApp {
    param($App, $Workbook, [string]$WorkCopy)
    try { if ($Workbook) { $Workbook.Close($false) } } catch { }
    try { if ($App) { $App.Quit() } } catch { }
    try { if ($WorkCopy -and (Test-Path -LiteralPath $WorkCopy)) { Remove-Item -LiteralPath $WorkCopy -Force } } catch { }
}
