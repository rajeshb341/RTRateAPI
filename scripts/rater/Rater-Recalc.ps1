# For every test case: writes the input values into column C of the Rater, recalculates it and reads the output
# cells (the EXPECTED values). Runs against a temporary copy; the original Rater is never changed.
param(
    [Parameter(Mandatory)][string]$Workbook,
    [string]$Sheet = 'Rater',
    [string]$ValueCol = 'C',
    [Parameter(Mandatory)][string]$CasesFile,    # JSON: { outputRows:[..], cases:[{ id, inputs:{ "<row>": value } }] }
    [Parameter(Mandatory)][string]$OutFile,
    [string]$SaveDir = '',                       # optional: keep a filled copy of the Rater per test case
    [string]$ProgId = 'KET.Application',
    [switch]$Stream                              # also print each case's result as one "@@CASE <json>" line when it is ready
)
. "$PSScriptRoot\RaterCommon.ps1"

$xlCalculationManual = -4135
$xlCalculationAutomatic = -4105

$app = $null; $wb = $null; $copy = $null
try {
    $spec = Get-Content -LiteralPath $CasesFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $copy = New-RaterWorkCopy $Workbook
    $app = Start-RaterApp $ProgId
    $wb = $app.Workbooks.Open($copy)
    $ws = $wb.Worksheets.Item($Sheet)

    # Speed: with automatic calculation every input written recalculates the whole Rater (about 60 recalculations per
    # case). In manual mode the inputs are written first and CalculateFull below recalculates everything once.
    $manual = $false
    try { $app.ScreenUpdating = $false } catch { }
    try { $app.Calculation = $xlCalculationManual; $manual = $true } catch { }

    # The outputs are read in one call (column block from the first to the last output row) instead of one call per cell
    $rows = @($spec.outputRows | ForEach-Object { [int]$_ })
    $first = ($rows | Measure-Object -Minimum).Minimum
    $last = ($rows | Measure-Object -Maximum).Maximum
    $block = $ws.Range("$ValueCol$first`:$ValueCol$last")

    # Input cells keep their value from the previous case, so only changed inputs are written
    $written = @{}
    $results = New-Object System.Collections.Generic.List[object]
    foreach ($case in $spec.cases) {
        $entry = [ordered]@{ id = $case.id; error = $null; outputs = [ordered]@{} }
        try {
            foreach ($p in $case.inputs.PSObject.Properties) {
                $text = if ($null -eq $p.Value) { '' } else { "$($p.Value)" }
                if ($written.ContainsKey($p.Name) -and $written[$p.Name] -ceq $text) { continue }
                $written.Remove($p.Name)
                Set-RaterCell $ws.Range("$ValueCol$($p.Name)") $p.Value
                $written[$p.Name] = $text
            }
            $app.CalculateFull()
            $values = $block.Value2
            foreach ($row in $rows) {
                $value = if ($first -eq $last) { $values } else { $values[($row - $first + 1), 1] }
                $entry.outputs["$row"] = ConvertTo-PlainValue $value
            }
            if ($SaveDir) {
                if (-not (Test-Path -LiteralPath $SaveDir)) { New-Item -ItemType Directory -Force $SaveDir | Out-Null }
                # A filled copy should open like the original (automatic calculation)
                if ($manual) { $app.Calculation = $xlCalculationAutomatic }
                $wb.SaveCopyAs((Join-Path (Resolve-Path -LiteralPath $SaveDir) ("Rater_" + $case.id + ".xlsx")))
                if ($manual) { $app.Calculation = $xlCalculationManual }
            }
        } catch {
            $entry.error = $_.Exception.Message
            $written = @{}      # the state of the input cells is unknown: write every input of the next case
        }
        $results.Add($entry)
        if ($Stream) { Write-Output ('@@CASE ' + (ConvertTo-AsciiJson $entry)) }
    }
    Write-Utf8 $OutFile (ConvertTo-Json -InputObject @{ results = $results.ToArray() } -Depth 6)
    Write-Output "Recalculated $($results.Count) test case(s) -> $OutFile"
}
finally { Close-RaterApp $app $wb $copy }
