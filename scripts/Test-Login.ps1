#Requires -Version 7.0
[CmdletBinding()]
param([switch]$IncludeCallbackConfig)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$center = [uri]'http://127.0.0.1:9000/'
$demo = [uri]'http://127.0.0.1:8081/'
$login = [uri]::new($center, '/login')
$start = [uri]::new($demo, '/oauth2/authorization/work-order')
$adminPage = [uri]::new($center, '/admin/application')
$callback = [uri]::new($demo, '/login/oauth2/code/work-order')
$alternate = [uri]::new($demo, '/login/oauth2/code/work-order-alternate')
$employeePassword = 'Demo-Employee-2026!'
$adminPassword = 'Demo-Admin-2026!'
$employeeName = '小朱'

function Assert-Test([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}

function New-TestClient {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    return $client
}

function Send-Request([System.Net.Http.HttpClient]$Client, [uri]$Uri, [hashtable]$Form) {
    $method = [System.Net.Http.HttpMethod]::Get
    if ($null -ne $Form) { $method = [System.Net.Http.HttpMethod]::Post }
    $request = [System.Net.Http.HttpRequestMessage]::new($method, $Uri)
    $response = $null
    try {
        if ($null -ne $Form) {
            $fields = [System.Collections.Generic.Dictionary[string, string]]::new()
            foreach ($name in $Form.Keys) { $fields.Add($name, $Form[$name]) }
            $request.Content = [System.Net.Http.FormUrlEncodedContent]::new($fields)
        }
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $location = $response.Headers.Location
        if ($null -ne $location) { $location = [uri]::new($Uri, $location) }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Location = $location
            Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } catch {
        throw "HTTP request failed at $($Uri.GetLeftPart([UriPartial]::Authority)). Check that the application is running."
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Get-Redirect($Response, [uri]$Expected, [string]$Message) {
    $location = $Response.Location
    Assert-Test ($Response.Status -in 302, 303 -and $null -ne $location) $Message
    Assert-Test ($location.GetLeftPart([UriPartial]::Path) -ceq $Expected.GetLeftPart([UriPartial]::Path)) $Message
    return $location
}

function Get-InputValue([string]$Html, [string]$Name) {
    $fieldName = [regex]::Escape($Name)
    $inputPattern = '(?is)<input\b(?=[^>]*\bname\s*=\s*["'']{0}["''])[^>]*\bvalue\s*=\s*["'']([^"'']*)["'']' -f $fieldName
    $field = [regex]::Match($Html, $inputPattern)
    Assert-Test $field.Success "The form did not contain $Name."
    return [System.Net.WebUtility]::HtmlDecode($field.Groups[1].Value)
}

function Send-Login($Client, $FormPage, [string]$Username, [string]$Password) {
    Assert-Test ($FormPage.Status -eq 200) 'The login form did not load.'
    return Send-Request $Client $login @{
        username = $Username
        password = $Password
        _csrf = Get-InputValue $FormPage.Body '_csrf'
    }
}

function Set-Callback($Client, [string]$RedirectUri) {
    $form = Send-Request $Client $adminPage
    Assert-Test ($form.Status -eq 200) 'The application configuration form did not load.'
    $save = Send-Request $Client $adminPage @{
        redirectUri = $RedirectUri
        _csrf = Get-InputValue $form.Body '_csrf'
    }
    Assert-Test ($save.Status -in 200, 302, 303) 'The callback configuration could not be saved.'
    $saved = Send-Request $Client $adminPage
    Assert-Test ((Get-InputValue $saved.Body 'redirectUri') -ceq $RedirectUri) 'The saved callback did not match.'
}

$employee = New-TestClient
$admin = New-TestClient
try {
    $anonymous = Send-Request $employee $demo
    Assert-Test ($anonymous.Status -eq 200 -and $anonymous.Body.Contains($start.AbsolutePath) -and !$anonymous.Body.Contains($employeeName)) 'The anonymous page should show a login link and no employee name.'
    $authorize = Get-Redirect (Send-Request $employee $start) ([uri]::new($center, '/oauth2/authorize')) 'The demo did not redirect to its own identity provider.'
    $loginForm = Get-Redirect (Send-Request $employee $authorize) $login 'The identity provider did not open its login form.'
    Write-Host 'PASS: anonymous page and redirect to the identity provider.'

    $wrong = Send-Login $employee (Send-Request $employee $loginForm) 'employee' 'incorrect-test-password'
    $retry = Get-Redirect $wrong $login 'The wrong password should return to the login form.'
    Assert-Test ($retry.Query -match '(?:[?&])error(?:[=&]|$)') 'The wrong password should show a login error.'
    $signedIn = Send-Login $employee (Send-Request $employee $retry) 'employee' $employeePassword
    $resume = Get-Redirect $signedIn $authorize 'Login did not resume the authorization request.'
    $differentPort = [UriBuilder]::new($authorize)
    $authorizeParams = [System.Web.HttpUtility]::ParseQueryString($authorize.Query)
    Assert-Test ($authorizeParams['redirect_uri'] -ceq $callback.AbsoluteUri) 'The demo did not request its expected callback.'
    $authorizeParams['redirect_uri'] = $callback.AbsoluteUri.Replace(':8081/', ':8082/')
    $differentPort.Query = $authorizeParams.ToString()
    $portRejected = Send-Request $employee $differentPort.Uri
    Assert-Test ($portRejected.Status -eq 400 -and $null -eq $portRejected.Location) 'The provider should reject the unregistered port.'
    $returned = Get-Redirect (Send-Request $employee $resume) $callback 'The identity provider did not redirect to the registered callback.'
    Assert-Test ($returned.Query -match '(?:[?&])code=[^&]+') 'The callback was missing the authorization code.'
    $landing = Get-Redirect (Send-Request $employee $returned) $demo 'The demo could not complete the callback.'
    $signedInPage = Send-Request $employee $landing
    $refresh = Send-Request $employee $demo
    Assert-Test ($signedInPage.Status -eq 200 -and $signedInPage.Body.Contains($employeeName) -and $refresh.Status -eq 200 -and $refresh.Body.Contains($employeeName)) 'The demo should identify the employee after login and refresh.'
    Write-Host 'PASS: wrong password, exact callback matching, verified employee, and session refresh.'

    if ($IncludeCallbackConfig) {
        $denied = Send-Request $employee $adminPage
        Assert-Test ($denied.Status -eq 403) 'An employee should not be able to open application configuration.'
        $adminLogin = Get-Redirect (Send-Request $admin $adminPage) $login 'The configuration page should require administrator login.'
        $adminSignedIn = Send-Login $admin (Send-Request $admin $adminLogin) 'admin' $adminPassword
        $adminReturn = Get-Redirect $adminSignedIn $adminPage 'Administrator login did not open application configuration.'
        $adminForm = Send-Request $admin $adminReturn
        $original = Get-InputValue $adminForm.Body 'redirectUri'
        Assert-Test ($original -ceq $callback.AbsoluteUri) 'The starting configuration did not contain the default callback.'
        try {
            Set-Callback $admin $alternate.AbsoluteUri
            $oldAuthorize = Get-Redirect (Send-Request $employee $start) $authorize 'The demo did not start a new authorization request.'
            $rejected = Send-Request $employee $oldAuthorize
            Assert-Test ($rejected.Status -eq 400 -and $null -eq $rejected.Location) 'The identity provider should reject the old callback without redirecting to it.'
            Write-Host 'PASS: employee denied administration, alternate callback saved, old callback rejected.'
        } finally {
            Set-Callback $admin $original
        }
        Write-Host 'PASS: original callback restored.'
    }
} finally {
    $employee.Dispose()
    $admin.Dispose()
}
