param(
    [Parameter(Mandatory = $true)]
    [string]$Action
)

$allowedActions = @("Validate", "Migrate", "Generate", "GenerateCheck", "Test")
$forbiddenActions = @("Clean", "Repair")

$normalizedAction = $allowedActions | Where-Object { $_.Equals($Action, [StringComparison]::OrdinalIgnoreCase) }
$forbiddenAction = $forbiddenActions | Where-Object { $_.Equals($Action, [StringComparison]::OrdinalIgnoreCase) }

if ($null -ne $forbiddenAction -or $null -eq $normalizedAction) {
    [Console]::Error.WriteLine("HDM005_DB_ACTION_FORBIDDEN action=$Action")
    exit 20
}

[Console]::Error.WriteLine("HDM005_SLICE_B_NOT_READY action=$normalizedAction")
exit 21
