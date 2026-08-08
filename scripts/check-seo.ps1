param([string]$BaseUrl = 'https://whose.domains')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$failures = [System.Collections.Generic.List[string]]::new()
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)

function Get-SeoResponse([string]$url) {
    try {
        return $client.GetAsync($url).GetAwaiter().GetResult()
    } catch {
        $failures.Add("GET failed: $url - $($_.Exception.Message)")
        return $null
    }
}

function Read-SeoBody($response, [string]$url) {
    if ($null -eq $response) { return $null }

    try {
        return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } catch {
        $failures.Add("Could not read response body: $url - $($_.Exception.Message)")
        return $null
    }
}

function Get-RedirectLocation($response, [string]$requestUrl) {
    if ($null -eq $response -or $null -eq $response.Headers.Location) { return $null }

    $location = $response.Headers.Location
    if ($location.IsAbsoluteUri) { return $location.AbsoluteUri }

    return [uri]::new([uri]$requestUrl, $location).AbsoluteUri
}

$locations = @()
try {
    $robotsResponse = Get-SeoResponse "$BaseUrl/robots.txt"
    try {
        if ($null -eq $robotsResponse -or [int]$robotsResponse.StatusCode -ne 200) {
            $failures.Add('robots.txt must return HTTP 200')
        } else {
            $robots = Read-SeoBody $robotsResponse "$BaseUrl/robots.txt"
            if ($robots -notmatch '(?im)^Sitemap:\s+https://whose\.domains/sitemap_all\.xml\s*$') {
                $failures.Add('robots.txt does not declare the production sitemap')
            }
        }
    } finally {
        if ($null -ne $robotsResponse) { $robotsResponse.Dispose() }
    }

    $sitemapResponse = Get-SeoResponse "$BaseUrl/sitemap_all.xml"
    try {
        if ($null -eq $sitemapResponse -or [int]$sitemapResponse.StatusCode -ne 200) {
            $failures.Add('sitemap_all.xml must return HTTP 200')
        } else {
            try {
                [xml]$sitemap = Read-SeoBody $sitemapResponse "$BaseUrl/sitemap_all.xml"
                $locations = @($sitemap.urlset.url.loc | ForEach-Object { [string]$_ })
            } catch {
                $failures.Add("sitemap_all.xml is not valid XML: $($_.Exception.Message)")
            }
        }
    } finally {
        if ($null -ne $sitemapResponse) { $sitemapResponse.Dispose() }
    }

    foreach ($location in $locations) {
        if (-not $location.StartsWith('https://whose.domains/')) {
            $failures.Add("Non-canonical sitemap origin or root slash: $location")
            continue
        }

        $response = Get-SeoResponse $location
        try {
            if ($null -eq $response) { continue }
            if ([int]$response.StatusCode -ne 200) {
                $failures.Add("Sitemap URL must directly return 200: $location returned $([int]$response.StatusCode)")
                continue
            }

            $contentType = $response.Content.Headers.ContentType
            $mediaType = if ($null -eq $contentType) { $null } else { $contentType.MediaType }
            if ($mediaType -eq 'text/html') {
                $html = Read-SeoBody $response $location
                # Canonical selector shape: rel=["']canonical
                $canonicalMatches = [regex]::Matches(
                    $html,
                    '<link[^>]+rel=["'']canonical["''][^>]+href=["'']([^"'']+)["''][^>]*>',
                    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                if ($canonicalMatches.Count -ne 1) {
                    $failures.Add("Expected one canonical link: $location found $($canonicalMatches.Count)")
                } elseif ($canonicalMatches[0].Groups[1].Value -ne $location) {
                    $failures.Add("Canonical mismatch: $location -> $($canonicalMatches[0].Groups[1].Value)")
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
            $failures.Add('Trailing-slash tool URL must 301 to its canonical URL')
        }
    } finally {
        if ($null -ne $slashResponse) { $slashResponse.Dispose() }
    }

    $wwwUrl = 'https://www.whose.domains/'
    $wwwResponse = Get-SeoResponse $wwwUrl
    try {
        $wwwLocation = Get-RedirectLocation $wwwResponse $wwwUrl
        if ($null -eq $wwwResponse -or [int]$wwwResponse.StatusCode -ne 301 -or $wwwLocation -ne 'https://whose.domains/') {
            $failures.Add('www homepage must 301 to the canonical homepage')
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
