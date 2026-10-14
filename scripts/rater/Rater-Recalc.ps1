# For every test case: writes the input values into column C of the Rater, recalculates it and reads the output
# cells (the EXPECTED values). Runs against a temporary copy; the original Rater is never changed.
param(
    [Parameter(Mandatory)][string]$Workbook,
    [string]$Sheet = 'Rater',
    [string]$ValueCol = 'C',
    [Parameter(Mandatory)][string]$CasesFile,    # JSON: { outputRows:[..], cases:[{ id, inputs:{ "<row>": value } }] }
    [Parameter(Mandatory)][string]$OutFile,
    [string]$SaveDir = '',                       # optional: keep a filled copy of the Rater per test case
    [string]$ProgId = 'KET.Application'
)
. "$PSScriptRoot\RaterCommon.ps1"

$app = $null; $wb = $null; $copy = $null
try {
    $spec = Get-Content -LiteralPath $CasesFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $copy = New-RaterWorkCopy $Workbook
    $app = Start-RaterApp $ProgId
    $wb = $app.Workbooks.Open($copy)
    $ws = $wb.Worksheets.Item($Sheet)

    $results = New-Object System.Collections.Generic.List[object]
    foreach ($case in $spec.cases) {
        $entry = [ordered]@{ id = $case.id; error = $null; outputs = [ordered]@{} }
        try {
            foreach ($p in $case.inputs.PSObject.Properties) {
                Set-RaterCell $ws.Range("$ValueCol$($p.Name)") $p.Value
            }
            $app.CalculateFull()
            foreach ($row in $spec.outputRows) {
                $entry.outputs["$row"] = ConvertTo-PlainValue $ws.Range("$ValueCol$row").Value2
            }
            if ($SaveDir) {
                if (-not (Test-Path -LiteralPath $SaveDir)) { New-Item -ItemType Directory -Force $SaveDir | Out-Null }
                $wb.SaveCopyAs((Join-Path (Resolve-Path -LiteralPath $SaveDir) ("Rater_" + $case.id + ".xlsx")))
            }
        } catch { $entry.error = $_.Exception.Message }
        $results.Add($entry)
    }
    Write-Utf8 $OutFile (ConvertTo-Json -InputObject @{ results = $results.ToArray() } -Depth 6)
    Write-Output "Recalculated $($results.Count) test case(s) -> $OutFile"
}
finally { Close-RaterApp $app $wb $copy }
