param(
    [string]$BaseUrl = 'https://whose.domains',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$failures = [System.Collections.Generic.List[string]]::new()

function Add-SeoFailure([System.Collections.Generic.List[string]]$failureList, [string]$message) {
    [void]$failureList.Add($message)
}

function Get-CanonicalLinkInfo([string]$html) {
    $canonicalHrefs = [System.Collections.Generic.List[string]]::new()
    $canonicalRelationCount = 0
    $linkMatches = [regex]::Matches($html, '<link\b[^>]*>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

    foreach ($linkMatch in $linkMatches) {
        $linkTag = $linkMatch.Value
        $relMatch = [regex]::Match(
            $linkTag,
            '(?:^|\s)rel\s*=\s*["'']([^"'']*)["'']',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if (-not $relMatch.Success -or -not [regex]::IsMatch(
                $relMatch.Groups[1].Value,
                '(?:^|\s)canonical(?:\s|$)',
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            continue
        }

        $canonicalRelationCount++
        $hrefMatch = [regex]::Match(
            $linkTag,
            '(?:^|\s)href\s*=\s*["'']([^"'']+)["'']',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($hrefMatch.Success -and -not [string]::IsNullOrWhiteSpace($hrefMatch.Groups[1].Value)) {
            $canonicalHrefs.Add($hrefMatch.Groups[1].Value.Trim())
        }
    }

    return [pscustomobject]@{
        RelationCount = $canonicalRelationCount
        Hrefs = @($canonicalHrefs)
    }
}

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)

function Get-SeoResponse([string]$url) {
    try {
        return $client.GetAsync($url).GetAwaiter().GetResult()
    } catch {
        Add-SeoFailure $failures "GET failed: $url - $($_.Exception.Message)"
        return $null
    }
}

function Read-SeoBody($response, [string]$url) {
    if ($null -eq $response) { return $null }

    try {
        return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } catch {
        Add-SeoFailure $failures "Could not read response body: $url - $($_.Exception.Message)"
        return $null
    }
}

function Get-RedirectLocation($response, [string]$requestUrl) {
    if ($null -eq $response -or $null -eq $response.Headers.Location) { return $null }

    $location = $response.Headers.Location
    if ($location.IsAbsoluteUri) { return $location.AbsoluteUri }

    return [uri]::new([uri]$requestUrl, $location).AbsoluteUri
}

function Invoke-SeoSelfTest {
    $selfTestFailures = [System.Collections.Generic.List[string]]::new()

    $hrefFirst = Get-CanonicalLinkInfo '<link href="https://whose.domains/tools/whois-lookup" rel="canonical">'
    if ($hrefFirst.RelationCount -ne 1 -or $hrefFirst.Hrefs.Count -ne 1 -or $hrefFirst.Hrefs[0] -ne 'https://whose.domains/tools/whois-lookup') {
        Add-SeoFailure $selfTestFailures 'href-before-rel canonical link was not recognized'
    }

    $tokenizedRel = Get-CanonicalLinkInfo "<link rel='alternate canonical' href='https://whose.domains/'>"
    if ($tokenizedRel.RelationCount -ne 1 -or $tokenizedRel.Hrefs.Count -ne 1) {
        Add-SeoFailure $selfTestFailures 'canonical rel token was not recognized'
    }

    $dataRel = Get-CanonicalLinkInfo '<link data-rel="canonical" href="https://whose.domains/">'
    if ($dataRel.RelationCount -ne 0 -or $dataRel.Hrefs.Count -ne 0) {
        Add-SeoFailure $selfTestFailures 'data-rel was mistaken for rel'
    }

    $duplicateCanonical = Get-CanonicalLinkInfo '<link rel="canonical" href="https://whose.domains/"><link href="https://whose.domains/other" rel="canonical">'
    if ($duplicateCanonical.RelationCount -ne 2 -or $duplicateCanonical.Hrefs.Count -ne 2) {
        Add-SeoFailure $selfTestFailures 'duplicate canonical links were not counted'
    }

    $relativeResponse = [pscustomobject]@{ Headers = [pscustomobject]@{ Location = [uri]::new('/tools/whois-lookup', [System.UriKind]::Relative) } }
    if ((Get-RedirectLocation $relativeResponse 'https://whose.domains/tools/whois-lookup/') -ne 'https://whose.domains/tools/whois-lookup') {
        Add-SeoFailure $selfTestFailures 'relative redirect location was not normalized'
    }

    $absoluteResponse = [pscustomobject]@{ Headers = [pscustomobject]@{ Location = [uri]'https://whose.domains/' } }
    if ((Get-RedirectLocation $absoluteResponse 'https://www.whose.domains/') -ne 'https://whose.domains/') {
        Add-SeoFailure $selfTestFailures 'absolute redirect location was not preserved'
    }

    $aggregatedFailures = [System.Collections.Generic.List[string]]::new()
    Add-SeoFailure $aggregatedFailures 'first failure'
    Add-SeoFailure $aggregatedFailures 'second failure'
    if ($aggregatedFailures.Count -ne 2 -or $aggregatedFailures[0] -ne 'first failure' -or $aggregatedFailures[1] -ne 'second failure') {
        Add-SeoFailure $selfTestFailures 'failures were not aggregated in order'
    }

    if ($selfTestFailures.Count -gt 0) {
        $selfTestFailures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
        return $false
    }

    Write-Host 'SEO offline self-test passed.'
    return $true
}

if ($SelfTest) {
    if (Invoke-SeoSelfTest) { exit 0 }
    exit 1
}

$locations = @()
try {
    $robotsResponse = Get-SeoResponse "$BaseUrl/robots.txt"
    try {
        if ($null -eq $robotsResponse -or [int]$robotsResponse.StatusCode -ne 200) {
            Add-SeoFailure $failures 'robots.txt must return HTTP 200'
        } else {
            $robots = Read-SeoBody $robotsResponse "$BaseUrl/robots.txt"
            if ($robots -notmatch '(?im)^Sitemap:\s+https://whose\.domains/sitemap_all\.xml\s*$') {
                Add-SeoFailure $failures 'robots.txt does not declare the production sitemap'
            }
        }
    } finally {
        if ($null -ne $robotsResponse) { $robotsResponse.Dispose() }
    }

    $sitemapResponse = Get-SeoResponse "$BaseUrl/sitemap_all.xml"
    try {
        if ($null -eq $sitemapResponse -or [int]$sitemapResponse.StatusCode -ne 200) {
            Add-SeoFailure $failures 'sitemap_all.xml must return HTTP 200'
        } else {
            try {
                [xml]$sitemap = Read-SeoBody $sitemapResponse "$BaseUrl/sitemap_all.xml"
                $locations = @($sitemap.urlset.url.loc | ForEach-Object { [string]$_ })
            } catch {
                Add-SeoFailure $failures "sitemap_all.xml is not valid XML: $($_.Exception.Message)"
            }
        }
    } finally {
        if ($null -ne $sitemapResponse) { $sitemapResponse.Dispose() }
    }

    foreach ($location in $locations) {
        if (-not $location.StartsWith('https://whose.domains/')) {
            Add-SeoFailure $failures "Non-canonical sitemap origin or root slash: $location"
            continue
        }

        $response = Get-SeoResponse $location
        try {
            if ($null -eq $response) { continue }
            if ([int]$response.StatusCode -ne 200) {
                Add-SeoFailure $failures "Sitemap URL must directly return 200: $location returned $([int]$response.StatusCode)"
                continue
            }

            $contentType = $response.Content.Headers.ContentType
            $mediaType = if ($null -eq $contentType) { $null } else { $contentType.MediaType }
            if ($mediaType -eq 'text/html') {
                $html = Read-SeoBody $response $location
                $canonicalInfo = Get-CanonicalLinkInfo $html
                if ($canonicalInfo.RelationCount -ne 1 -or $canonicalInfo.Hrefs.Count -ne 1) {
                    Add-SeoFailure $failures "Expected one canonical link with href: $location found $($canonicalInfo.RelationCount) canonical rel values and $($canonicalInfo.Hrefs.Count) href values"
                } elseif ($canonicalInfo.Hrefs[0] -ne $location) {
                    Add-SeoFailure $failures "Canonical mismatch: $location -> $($canonicalInfo.Hrefs[0])"
                }
            }
        } finally {
            if ($null -ne $response) { $response.Dispose() }
        }
    }

    $slashUrl = "$BaseUrl/tools/whois-lookup/"
    $slashResponse = Get-SeoResponse $slashUrl
    try {
        $slashLocation = Get-RedirectLocation $slashResponse $slashUrl
        if ($null -eq $slashResponse -or [int]$slashResponse.StatusCode -ne 301 -or $slashLocation -ne 'https://whose.domains/tools/whois-lookup') {
            Add-SeoFailure $failures 'Trailing-slash tool URL must 301 to its canonical URL'
        }
    } finally {
        if ($null -ne $slashResponse) { $slashResponse.Dispose() }
    }

    $wwwUrl = 'https://www.whose.domains/'
    $wwwResponse = Get-SeoResponse $wwwUrl
    try {
        $wwwLocation = Get-RedirectLocation $wwwResponse $wwwUrl
        if ($null -eq $wwwResponse -or [int]$wwwResponse.StatusCode -ne 301 -or $wwwLocation -ne 'https://whose.domains/') {
            Add-SeoFailure $failures 'www homepage must 301 to the canonical homepage'
        }
    } finally {
        if ($null -ne $wwwResponse) { $wwwResponse.Dispose() }
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Host "SEO checks passed for $($locations.Count) sitemap URLs."
exit 0
