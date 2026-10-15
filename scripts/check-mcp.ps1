# Live smoke check for the Whose.Domains MCP endpoint (/mcp, Streamable HTTP).
#
# Usage:
#   pwsh scripts/check-mcp.ps1                       # against http://localhost:8080
#   pwsh scripts/check-mcp.ps1 -BaseUrl https://whose.domains -Domain example.com
#
# This drives the JSON-RPC handshake (initialize -> initialized -> tools/list -> tools/call) by
# hand so it does not need any SDK. For a fully automated, dependency-free verification instead run:
#   mvn -pl wesite-web -am test -Dtest=McpEndpointEndToEndTest
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Domain = 'example.com',
    [string]$Tool = 'check_domain'
)

$ErrorActionPreference = 'Stop'
$endpoint = ($BaseUrl.TrimEnd('/')) + '/mcp'

function Send-JsonRpc {
    param([hashtable]$Payload, [string]$SessionId)
    $headers = @{
        'Content-Type' = 'application/json'
        'Accept'       = 'application/json, text/event-stream'
    }
    if ($SessionId) { $headers['Mcp-Session-Id'] = $SessionId }
    $body = $Payload | ConvertTo-Json -Depth 12 -Compress
    $response = Invoke-WebRequest -Uri $endpoint -Method Post -Headers $headers -Body $body -UseBasicParsing
    $session = $null
    $sessionHeader = $response.Headers['Mcp-Session-Id']
    if ($sessionHeader) { $session = @($sessionHeader)[0] }
    [pscustomobject]@{ Text = $response.Content; SessionId = $session }
}

function ConvertFrom-McpResponse {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $dataLines = $Text -split "`r?`n" | Where-Object { $_ -like 'data:*' }
    if ($dataLines.Count -gt 0) {
        $json = ($dataLines | ForEach-Object { $_.Substring(5).Trim() }) -join ''
        return $json | ConvertFrom-Json
    }
    return $Text | ConvertFrom-Json
}

Write-Host "Connecting to $endpoint ..."
$init = Send-JsonRpc -Payload @{
    jsonrpc = '2.0'; id = 1; method = 'initialize'
    params  = @{
        protocolVersion = '2025-06-18'
        capabilities    = @{}
        clientInfo       = @{ name = 'check-mcp'; version = '1.0' }
    }
}
$initResult = ConvertFrom-McpResponse -Text $init.Text
$session = $init.SessionId
$serverInfo = $initResult.result.serverInfo
Write-Host ("Server: {0} {1} (protocol {2})" -f $serverInfo.name, $serverInfo.version, $initResult.result.protocolVersion)

if ($session) {
    Send-JsonRpc -Payload @{ jsonrpc = '2.0'; method = 'notifications/initialized' } -SessionId $session | Out-Null
}

$tools = ConvertFrom-McpResponse -Text (Send-JsonRpc -Payload @{
        jsonrpc = '2.0'; id = 2; method = 'tools/list'
    } -SessionId $session).Text
Write-Host "Tools:"
$tools.result.tools | ForEach-Object { Write-Host ("  - {0}: {1}" -f $_.name, $_.description) }

$arguments = @{}
if ($Tool -eq 'check_domains') { $arguments['domains'] = @($Domain) } else { $arguments['domain'] = $Domain }

$call = ConvertFrom-McpResponse -Text (Send-JsonRpc -Payload @{
        jsonrpc = '2.0'; id = 3; method = 'tools/call'
        params  = @{ name = $Tool; arguments = $arguments }
    } -SessionId $session).Text

Write-Host ("`n{0} {1}:" -f $Tool, $Domain)
if ($call.result.isError) {
    Write-Warning $call.result.content[0].text
    exit 1
}
Write-Host $call.result.content[0].text
