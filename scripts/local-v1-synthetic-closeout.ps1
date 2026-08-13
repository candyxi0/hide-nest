# Local V1 synthetic closeout invocation helper.
#
# Reads the high-entropy bearer and X-Action-Capability strictly from the
# process environment. They are never written to disk, HTML, URL, response,
# logs or reports. The Idempotency-Key is bound to the request's submissionId.
#
# Usage:
#   $env:HIDE_NEST_SYNTHETIC_TOKEN = "..."
#   $env:HIDE_NEST_SYNTHETIC_CAPABILITY = "..."
#   .\scripts\local-v1-synthetic-closeout.ps1 -BodyPath .\request.json -Port 8080

param(
    [Parameter(Mandatory = $true)][string]$BodyPath,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$token = $env:HIDE_NEST_SYNTHETIC_TOKEN
$capability = $env:HIDE_NEST_SYNTHETIC_CAPABILITY
if ([string]::IsNullOrWhiteSpace($token)) { throw "HIDE_NEST_SYNTHETIC_TOKEN is not set" }
if ([string]::IsNullOrWhiteSpace($capability)) { throw "HIDE_NEST_SYNTHETIC_CAPABILITY is not set" }

if (-not (Test-Path -LiteralPath $BodyPath -PathType Leaf)) {
    throw "Body file not found: $BodyPath"
}
$body = Get-Content -LiteralPath $BodyPath -Raw -Encoding UTF8

try {
    $parsed = $body | ConvertFrom-Json
    $submissionId = [string]$parsed.submissionId
} catch {
    throw "Body must be valid JSON with a submissionId field"
}
if ([string]::IsNullOrWhiteSpace($submissionId)) {
    throw "Body must include a submissionId field"
}

$uri = "http://127.0.0.1:$Port/v1/closeout-submissions"
$headers = @{
    "Authorization" = "Bearer $token"
    "X-Action-Capability" = $capability
    "Idempotency-Key" = $submissionId
    "Content-Type" = "application/json"
}

Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $body
