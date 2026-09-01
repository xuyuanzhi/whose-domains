param(
    [string]$BaseUrl = 'https://whose.domains',
    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 20,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Add-SmokeFailure(
        [System.Collections.Generic.List[string]]$failureList,
        [string]$message) {
    [void]$failureList.Add($message)
}

function Get-ProductionBaseUrl([string]$value) {
    $candidate = if ($null -eq $value) { '' } else { $value.Trim() }
    try {
        $uri = [uri]$candidate
    } catch {
        $uri = $null
    }

    $isProductionOrigin = $null -ne $uri -and
        $uri.IsAbsoluteUri -and
        $uri.Scheme -ceq 'https' -and
        $uri.DnsSafeHost -ceq 'whose.domains' -and
        $uri.Port -eq 443 -and
        [string]::IsNullOrEmpty($uri.UserInfo)
    $isOriginOnly = $isProductionOrigin -and
        $uri.AbsolutePath -ceq '/' -and
        [string]::IsNullOrEmpty($uri.Query) -and
        [string]::IsNullOrEmpty($uri.Fragment)

    if (-not $isOriginOnly) {
        throw 'Production smoke is production-only and requires https://whose.domains as BaseUrl.'
    }

    return 'https://whose.domains'
}

function Test-SmokeHtmlResponse(
        [int]$statusCode,
        [string]$mediaType,
        [AllowEmptyString()][string]$body,
        [string]$location,
        [System.Collections.Generic.List[string]]$failureList) {
    if ($statusCode -ne 200) {
        Add-SmokeFailure $failureList "Expected HTTP 200: $location returned $statusCode"
        return
    }
    if ($mediaType -ine 'text/html') {
        Add-SmokeFailure $failureList "Expected Content-Type text/html: $location returned $mediaType"
        return
    }
    if ([string]::IsNullOrWhiteSpace($body)) {
        Add-SmokeFailure $failureList "Expected a non-empty response body: $location"
        return
    }
    if ($body -notmatch '(?i)<html(?:\s|>)') {
        Add-SmokeFailure $failureList "Expected an HTML document: $location"
    }
}

function Assert-SmokeSelfTestCase(
        [string]$name,
        [int]$statusCode,
        [string]$mediaType,
        [AllowEmptyString()][string]$body,
        [int]$expectedFailureCount,
        [string]$expectedMessageFragment,
        [System.Collections.Generic.List[string]]$selfTestFailures) {
    $caseFailures = [System.Collections.Generic.List[string]]::new()
    Test-SmokeHtmlResponse $statusCode $mediaType $body "https://whose.domains/$name" $caseFailures

    if ($caseFailures.Count -ne $expectedFailureCount) {
        Add-SmokeFailure $selfTestFailures "$name produced $($caseFailures.Count) failures; expected $expectedFailureCount"
        return
    }
    if ($expectedFailureCount -gt 0 -and -not $caseFailures[0].Contains($expectedMessageFragment)) {
        Add-SmokeFailure $selfTestFailures "$name returned the wrong failure: $($caseFailures[0])"
    }
}

function Invoke-SmokeSelfTest {
    $selfTestFailures = [System.Collections.Generic.List[string]]::new()
    Assert-SmokeSelfTestCase 'valid' 200 'text/html' '<html><body>ok</body></html>' 0 '' $selfTestFailures
    Assert-SmokeSelfTestCase 'status' 503 'text/html' '<html></html>' 1 'HTTP 200' $selfTestFailures
    Assert-SmokeSelfTestCase 'media' 200 'application/json' '{}' 1 'Content-Type text/html' $selfTestFailures
    Assert-SmokeSelfTestCase 'empty' 200 'text/html' '' 1 'non-empty response body' $selfTestFailures
    Assert-SmokeSelfTestCase 'document' 200 'text/html' '<body>missing root element</body>' 1 'HTML document' $selfTestFailures

    if ($selfTestFailures.Count -gt 0) {
        $selfTestFailures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
        return $false
    }

    Write-Host 'Production smoke self-test cases passed: 5.'
    Write-Host 'Production smoke offline self-test passed.'
    return $true
}

$BaseUrl = Get-ProductionBaseUrl $BaseUrl

if ($SelfTest) {
    if (Invoke-SmokeSelfTest) { exit 0 }
    exit 1
}

$checks = @(
    [pscustomobject]@{ Name = 'Homepage'; Path = '/' },
    [pscustomobject]@{ Name = 'Tool catalog'; Path = '/tools' },
    [pscustomobject]@{ Name = 'WHOIS lookup'; Path = '/tools/whois-lookup' },
    [pscustomobject]@{ Name = 'DNS analyzer'; Path = '/tools/dns-analyzer' },
    [pscustomobject]@{ Name = 'SSL checker'; Path = '/tools/ssl-checker' },
    [pscustomobject]@{ Name = 'Login'; Path = '/login' },
    [pscustomobject]@{ Name = 'Help center'; Path = '/help-center' }
)

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('WhoseDomains-Production-Smoke/1.0')

try {
    foreach ($check in $checks) {
        $url = "$BaseUrl$($check.Path)"
        $failureCountBefore = $failures.Count
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $response = $null
        try {
            try {
                $response = $client.GetAsync($url).GetAwaiter().GetResult()
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $contentType = $response.Content.Headers.ContentType
                $mediaType = if ($null -eq $contentType) { $null } else { $contentType.MediaType }
                Test-SmokeHtmlResponse ([int]$response.StatusCode) $mediaType $body $url $failures
            } catch {
                Add-SmokeFailure $failures "GET failed: $url - $($_.Exception.Message)"
            }
        } finally {
            $stopwatch.Stop()
            if ($null -ne $response) { $response.Dispose() }
        }

        if ($failures.Count -eq $failureCountBefore) {
            Write-Host ("PASS {0} {1}ms {2}" -f $check.Name, $stopwatch.ElapsedMilliseconds, $url)
        }
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Host "Production smoke checks passed: $($checks.Count)."
exit 0
