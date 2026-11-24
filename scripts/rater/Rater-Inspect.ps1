# Reads the attribute definitions (D/E/F), current column-C value and dropdown list of the Rater input/output rows
# and writes them as JSON. Used to build test data and to label the report.
param(
    [Parameter(Mandatory)][string]$Workbook,
    [string]$Sheet = 'Rater',
    [string]$ValueCol = 'C',
    [Parameter(Mandatory)][string]$InputRows,
    [Parameter(Mandatory)][string]$OutputRows,
    [string]$Dependent = '',          # e.g. "16=15" => the list in row 16 depends on the value in row 15
    [Parameter(Mandatory)][string]$OutFile,
    [string]$ProgId = 'KET.Application',
    [string]$CountyRange = ''           # optional: "County!A2:C30000" => the rows of the county table (County, State, Subline)
)
. "$PSScriptRoot\RaterCommon.ps1"

function Get-ListValues {
    param($Ws, $Cell)
    $info = @{ values = $null; formula = $null }
    try { $v = $Cell.Validation; $type = $v.Type } catch { return $info }
    if ($type -ne 3) { return $info }
    $f = [string]$v.Formula1
    $info.formula = $f
    $list = New-Object System.Collections.Generic.List[object]
    if ($f.StartsWith('=')) {
        # Evaluate() does not resolve INDIRECT / named ranges, so let a scratch cell of the temp copy do it.
        try {
            $scratch = $Ws.Range('ZZ1')
            $scratch.Formula = '=TEXTJOIN("|",TRUE,' + $f.Substring(1) + ')'
            $app.Calculate()
            $raw = $scratch.Value2
            $scratch.Clear() | Out-Null
            $joined = if ($raw -is [string]) { $raw } else { '' }   # a number here means a spreadsheet error code
            if ($joined -and -not $joined.StartsWith('#')) {
                foreach ($x in ($joined -split '\|')) { if ($x -ne '') { $list.Add($x) } }
            }
        } catch { }
    } else {
        foreach ($x in ($f -split ',')) { if ($x.Trim() -ne '') { $list.Add($x.Trim()) } }
    }
    $info.values = $list.ToArray()
    return $info
}

$app = $null; $wb = $null; $copy = $null
try {
    $copy = New-RaterWorkCopy $Workbook
    $app = Start-RaterApp $ProgId
    $wb = $app.Workbooks.Open($copy)
    $ws = $wb.Worksheets.Item($Sheet)
    $app.CalculateFull()

    $deps = @{}
    foreach ($d in ($Dependent -split '[,;]' | Where-Object { $_ })) {
        $kv = $d -split '='; $deps[[int]$kv[0]] = [int]$kv[1]
    }

    $result = New-Object System.Collections.Generic.List[object]
    foreach ($role in @('INPUT', 'OUTPUT')) {
        $spec = if ($role -eq 'INPUT') { $InputRows } else { $OutputRows }
        foreach ($row in (Expand-Rows $spec)) {
            $name = [string]$ws.Range("F$row").Value2
            if ([string]::IsNullOrWhiteSpace($name)) { continue }   # separator rows such as F11
            $cell = $ws.Range("$ValueCol$row")
            $item = [ordered]@{
                row = $row; role = $role
                parent = [string]$ws.Range("D$row").Value2
                entity = [string]$ws.Range("E$row").Value2
                name = $name
                entityType = [string]$ws.Range("I$row").Value2      # ENTITY TYPE: Req/Resp = request input, Resp = output
                current = (ConvertTo-PlainValue $cell.Value2)
                values = @(); valuesByParent = @{}; dependsOnRow = $null; validation = $null
            }
            if ($role -eq 'INPUT') {
                $li = Get-ListValues $ws $cell
                $item.validation = $li.formula
                if ($li.values) { $item.values = @($li.values) }
                if ($deps.ContainsKey($row)) {
                    $pRow = $deps[$row]; $item.dependsOnRow = $pRow
                    $pCell = $ws.Range("$ValueCol$pRow")
                    $orig = $pCell.Formula
                    $pList = (Get-ListValues $ws $pCell).values
                    foreach ($pv in @($pList)) {
                        Set-RaterCell $pCell $pv
                        $app.Calculate()
                        $item.valuesByParent["$pv"] = @((Get-ListValues $ws $cell).values)
                    }
                    $pCell.Formula = $orig
                    $app.CalculateFull()
                }
            }
            $result.Add($item)
        }
    }
    # County table rows (all columns as text, read in one call; rows with a blank first column skipped)
    $countyRows = New-Object System.Collections.Generic.List[object]
    if ($CountyRange) {
        $at = $CountyRange.LastIndexOf('!')
        $values = $wb.Worksheets.Item($CountyRange.Substring(0, $at).Trim("'")).Range($CountyRange.Substring($at + 1)).Value2
        for ($r = 1; $r -le $values.GetLength(0); $r++) {
            if (-not ([string]$values[$r, 1]).Trim()) { continue }
            $cells = New-Object System.Collections.Generic.List[string]
            for ($c = 1; $c -le $values.GetLength(1); $c++) { $cells.Add(([string]$values[$r, $c]).Trim()) }
            $countyRows.Add($cells.ToArray())
        }
    }
    Write-Utf8 $OutFile (ConvertTo-Json -InputObject @{ attributes = $result.ToArray(); countyTable = $countyRows.ToArray() } -Depth 8)
    Write-Output "Inspected $($result.Count) attributes -> $OutFile"
}
finally { Close-RaterApp $app $wb $copy }
