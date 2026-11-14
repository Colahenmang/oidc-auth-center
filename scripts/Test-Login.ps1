#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$IncludeCallbackConfig,
    [switch]$IncludeSso,
    [switch]$IncludeHrAccess,
    [switch]$IncludeEmployeeManagement,
    [switch]$UseAlternateWorkOrderCallback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$center = [uri]'http://127.0.0.1:9000/'
$demo = [uri]'http://127.0.0.1:8081/'
$login = [uri]::new($center, '/login')
$authorizeEndpoint = [uri]::new($center, '/oauth2/authorize')
$start = [uri]::new($demo, '/oauth2/authorization/work-order')
$adminPage = [uri]::new($center, '/admin/application')
$employeesPage = [uri]::new($center, '/admin/employees')
$hrAccess = [uri]::new($center, '/admin/employees/hr-access')
$hr = [uri]'http://127.0.0.1:8082/'
$hrStart = [uri]::new($hr, '/oauth2/authorization/hr')
$hrCallback = [uri]::new($hr, '/login/oauth2/code/hr')
$defaultCallback = [uri]::new($demo, '/login/oauth2/code/work-order')
$alternateCallback = [uri]::new($demo, '/login/oauth2/code/work-order-alternate')
$callback = if ($UseAlternateWorkOrderCallback) { $alternateCallback } else { $defaultCallback }
$mismatchedCallback = if ($UseAlternateWorkOrderCallback) { $defaultCallback } else { $alternateCallback }
$employeePassword = 'Demo-Employee-2026!'
$adminPassword = 'Demo-Admin-2026!'
$employeeName = '演示员工'

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

function Get-InputValue([string]$Html, [string]$Name, [string]$Id = '') {
    $fieldName = [regex]::Escape($Name)
    $matchId = if ($Id) { '(?=[^>]*\sid\s*=\s*["'']{0}["''])' -f [regex]::Escape($Id) } else { '' }
    $inputPattern = '(?is)<input\b(?=[^>]*\sname\s*=\s*["'']{0}["'']){1}[^>]*\svalue\s*=\s*["'']([^"'']*)["'']' -f $fieldName, $matchId
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
        clientId = 'work-order-demo'
        redirectUri = $RedirectUri
        _csrf = Get-InputValue $form.Body '_csrf'
    }
    Assert-Test ($save.Status -in 200, 302, 303) 'The callback configuration could not be saved.'
    $saved = Send-Request $Client $adminPage
    Assert-Test ((Get-InputValue $saved.Body 'redirectUri' 'redirect-uri') -ceq $RedirectUri) 'The saved work-order callback did not match.'
}

function Find-HrAccessForms([string]$Html) {
    $pattern = '(?is)<form\b[^>]*\baction\s*=\s*["'']/admin/employees/hr-access["''][^>]*>.*?</form>'
    return [regex]::Matches($Html, $pattern) | ForEach-Object { $_.Value }
}

function Get-HrAccessForm($Client, [string]$Username = 'employee') {
    $query = [uri]::EscapeDataString($Username)
    $page = Send-Request $Client ([uri]::new($employeesPage, "?q=$query"))
    Assert-Test ($page.Status -eq 200) 'The employee directory did not load.'
    $forms = @(Find-HrAccessForms $page.Body | Where-Object { (Get-InputValue $_ 'username') -ceq $Username })
    Assert-Test ($forms.Count -eq 1) "The HR access form for $Username did not load."
    return $forms[0]
}

function Set-HrAccess($Client, [bool]$Allowed, [string]$Username = 'employee') {
    $form = Get-HrAccessForm $Client $Username
    $nextAction = Get-InputValue $form 'allowed'
    $desired = $Allowed.ToString().ToLowerInvariant()
    if ($nextAction -ne $desired) { return }

    $saved = Send-Request $Client $hrAccess @{
        username = $Username
        allowed = $desired
        q = $Username
        _csrf = Get-InputValue $form '_csrf'
    }
    $null = Get-Redirect $saved $employeesPage 'Admin could not change HR access.'
    $updated = Get-HrAccessForm $Client $Username
    Assert-Test ((Get-InputValue $updated 'allowed') -ceq (-not $Allowed).ToString().ToLowerInvariant()) 'The saved HR access did not match.'
}

function Exit-AppSession($Client, [uri]$AppHome) {
    $page = Send-Request $Client $AppHome
    Assert-Test ($page.Status -eq 200 -and $page.Body.Contains($employeeName)) 'The employee session was missing before app logout.'
    $signedOut = Send-Request $Client ([uri]::new($AppHome, '/logout')) @{
        _csrf = Get-InputValue $page.Body '_csrf'
    }
    $null = Get-Redirect $signedOut $AppHome 'App logout did not return to the app.'
}

function Complete-AppSso($Client, [uri]$Start, [uri]$Callback, [uri]$AppHome, [string]$DisplayName = $employeeName) {
    $authorize = Get-Redirect (Send-Request $Client $Start) $authorizeEndpoint 'The app did not initiate sign-in.'
    Confirm-AppSignIn $Client $authorize $Callback $AppHome $DisplayName
}

function Confirm-AppSignIn($Client, [uri]$Authorize, [uri]$Callback, [uri]$AppHome, [string]$DisplayName) {
    $returned = Get-Redirect (Send-Request $Client $authorize) $Callback 'The identity provider did not resume sign-in at the app callback.'
    Assert-Test ($returned.Query -match '(?:[?&])code=[^&]+') 'The app callback was missing the authorization code.'
    $landing = Get-Redirect (Send-Request $Client $returned) $AppHome 'The app did not complete sign-in.'
    $page = Send-Request $Client $landing
    Assert-Test ($page.Status -eq 200 -and $page.Body.Contains($DisplayName)) 'The app did not show the authenticated employee.'
}

function Confirm-HrDenied($Client, [string]$DisplayName) {
    $hrAuthorize = Get-Redirect (Send-Request $Client $hrStart) $authorizeEndpoint 'HR did not initiate sign-in.'
    $hrDenied = Get-Redirect (Send-Request $Client $hrAuthorize) $hrCallback 'The identity provider did not return the denial to the registered HR callback.'
    $deniedParams = [System.Web.HttpUtility]::ParseQueryString($hrDenied.Query)
    Assert-Test ($deniedParams['error'] -ceq 'access_denied' -and $null -eq $deniedParams['code']) 'Denied HR access should return access_denied and no authorization code.'
    $hrError = Get-Redirect (Send-Request $Client $hrDenied) $hr 'HR did not return to its sign-in page after denial.'
    $page = Send-Request $Client $hrError
    Assert-Test ($hrError.Query -ceq '?error=access_denied' -and $page.Status -eq 200 -and $page.Body.Contains('没有人事应用的访问权限') -and !$page.Body.Contains($DisplayName)) 'HR should explain the denial and remain signed out.'
}

function Get-EmployeeCount([string]$Html) {
    $value = [regex]::Match($Html, '(?is)<[^>]*\bid\s*=\s*["'']employee-count["''][^>]*>\s*(\d+)\s*<')
    Assert-Test $value.Success 'The directory did not show the total employee count.'
    return [int]$value.Groups[1].Value
}

function Test-EmployeeManagement($Admin, $ExistingEmployee) {
    # Use a disposable auth database for this check if the demo directory must stay clean.
    $denied = Send-Request $ExistingEmployee $employeesPage
    Assert-Test ($denied.Status -eq 403) 'An employee should not be able to open the directory.'

    $original = Send-Request $Admin $employeesPage
    Assert-Test ($original.Status -eq 200) 'The employee directory did not load.'
    $originalCount = Get-EmployeeCount $original.Body
    $originalHrAction = Get-InputValue (Get-HrAccessForm $Admin) 'allowed'
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $newUsername = "test-$suffix"
    $displayName = "联调员工$suffix"
    $password = "Employee-Test-$suffix!"
    $searchPage = [uri]::new($employeesPage, "?q=$suffix")
    $empty = Send-Request $Admin $searchPage
    Assert-Test ($empty.Status -eq 200 -and @(Find-HrAccessForms $empty.Body).Count -eq 0) 'A search for a new username should be empty before creation.'

    $create = @{
        username = $newUsername
        displayName = $displayName
        password = $password
        _csrf = Get-InputValue $original.Body '_csrf'
    }
    $created = Send-Request $Admin $employeesPage $create
    $null = Get-Redirect $created $employeesPage 'The administrator could not create an employee.'
    $result = Send-Request $Admin $searchPage
    $forms = @(Find-HrAccessForms $result.Body)
    Assert-Test ($result.Status -eq 200 -and $forms.Count -eq 1 -and $result.Body.Contains($displayName)) 'Search should return the new employee only.'
    Assert-Test ((Get-InputValue $forms[0] 'username') -ceq $newUsername) 'Search returned the wrong account.'
    Assert-Test ((Get-InputValue $forms[0] 'allowed') -ceq 'true') 'A newly created employee should not have HR access.'
    Assert-Test ((Get-EmployeeCount $result.Body) -eq $originalCount + 1) 'The total employee count did not increase.'
    $nameSearch = Send-Request $Admin ([uri]::new($employeesPage, ('?q=' + [uri]::EscapeDataString($displayName))))
    Assert-Test ($nameSearch.Status -eq 200 -and @(Find-HrAccessForms $nameSearch.Body).Count -eq 1 -and $nameSearch.Body.Contains($displayName)) 'Search by display name did not find the new employee.'

    $duplicate = Send-Request $Admin $employeesPage @{
        username = $newUsername
        displayName = "重复账号$suffix"
        password = "Different-Test-$suffix!"
        _csrf = Get-InputValue $result.Body '_csrf'
    }
    Assert-Test ($duplicate.Status -in 400, 409) 'A duplicate username must be rejected.'
    $afterDuplicate = Send-Request $Admin $employeesPage
    Assert-Test ((Get-EmployeeCount $afterDuplicate.Body) -eq $originalCount + 1) 'A duplicate attempt changed the employee count.'

    $newEmployee = New-TestClient
    try {
        $authorize = Get-Redirect (Send-Request $newEmployee $start) $authorizeEndpoint 'Work-order did not initiate sign-in.'
        $form = Get-Redirect (Send-Request $newEmployee $authorize) $login 'A new employee should reach the login form.'
        $signIn = Send-Login $newEmployee (Send-Request $newEmployee $form) $newUsername $password
        $resume = Get-Redirect $signIn $authorizeEndpoint 'The new employee could not sign in.'
        Confirm-AppSignIn $newEmployee $resume $callback $demo $displayName
        Confirm-HrDenied $newEmployee $displayName
        Set-HrAccess $Admin $true $newUsername
        Complete-AppSso $newEmployee $hrStart $hrCallback $hr $displayName
        Assert-Test ((Get-InputValue (Get-HrAccessForm $Admin) 'allowed') -ceq $originalHrAction) 'Granting HR to the new employee changed the existing employee.'
    } finally {
        try { Set-HrAccess $Admin $false $newUsername } finally { $newEmployee.Dispose() }
    }
    Write-Host "PASS: employee creation, total count, search, duplicate rejection, work-order sign-in, and isolated HR access. Test account retained: $newUsername."
}

function Test-HrSso($Client) {
    $hrAnonymous = Send-Request $Client $hr
    Assert-Test ($hrAnonymous.Status -eq 200 -and $hrAnonymous.Body.Contains($hrStart.AbsolutePath) -and !$hrAnonymous.Body.Contains($employeeName)) 'HR should begin without its own app session after work-order login.'

    $hrAuthorize = Get-Redirect (Send-Request $Client $hrStart) $authorizeEndpoint 'HR did not redirect to the shared identity provider.'
    $hrAuthorizeParams = [System.Web.HttpUtility]::ParseQueryString($hrAuthorize.Query)
    Assert-Test ($hrAuthorizeParams['client_id'] -ceq 'hr-demo' -and $hrAuthorizeParams['redirect_uri'] -ceq $hrCallback.AbsoluteUri) 'HR did not use its own client ID and callback.'
    $hrReturned = Get-Redirect (Send-Request $Client $hrAuthorize) $hrCallback 'The shared identity provider session did not send HR directly to its callback.'
    Assert-Test ($hrReturned.Query -match '(?:[?&])code=[^&]+') 'The HR callback was missing the authorization code.'
    $hrLanding = Get-Redirect (Send-Request $Client $hrReturned) $hr 'HR could not complete its callback.'
    $hrSignedIn = Send-Request $Client $hrLanding
    $hrRefresh = Send-Request $Client $hr
    Assert-Test ($hrSignedIn.Status -eq 200 -and $hrSignedIn.Body.Contains($employeeName) -and $hrRefresh.Status -eq 200 -and $hrRefresh.Body.Contains($employeeName)) 'HR should identify the employee after SSO and refresh.'
    $workOrderRefresh = Send-Request $Client $demo
    Assert-Test ($workOrderRefresh.Status -eq 200 -and $workOrderRefresh.Body.Contains($employeeName)) 'Work-order should keep its own employee session after HR login.'

    $freshEmployee = New-TestClient
    try {
        $freshAuthorize = Get-Redirect (Send-Request $freshEmployee $hrStart) $authorizeEndpoint 'HR did not start authorization in a fresh session.'
        $null = Get-Redirect (Send-Request $freshEmployee $freshAuthorize) $login 'HR should require credentials in a fresh session.'
    } finally {
        $freshEmployee.Dispose()
    }
    Write-Host 'PASS: HR reuses the identity provider session and establishes its own app session. A fresh session still requires login.'
}

$employee = New-TestClient
$admin = New-TestClient
try {
    $anonymous = Send-Request $employee $demo
    Assert-Test ($anonymous.Status -eq 200 -and $anonymous.Body.Contains($start.AbsolutePath) -and !$anonymous.Body.Contains($employeeName)) 'The anonymous page should show a login link and no employee name.'
    $authorize = Get-Redirect (Send-Request $employee $start) $authorizeEndpoint 'The demo did not redirect to its own identity provider.'
    $loginForm = Get-Redirect (Send-Request $employee $authorize) $login 'The identity provider did not open its login form.'
    $devToolsProbe = Send-Request $employee ([uri]::new($center, '/.well-known/appspecific/com.chrome.devtools.json'))
    Assert-Test ($devToolsProbe.Status -eq 404 -and $null -eq $devToolsProbe.Location) 'The DevTools probe should not redirect to login.'
    $null = Send-Request $employee ([uri]::new($center, '/favicon.ico'))
    Write-Host 'PASS: anonymous page and redirect to the identity provider.'

    $wrong = Send-Login $employee (Send-Request $employee $loginForm) 'employee' 'incorrect-test-password'
    $retry = Get-Redirect $wrong $login 'The wrong password should return to the login form.'
    Assert-Test ($retry.Query -match '(?:[?&])error(?:[=&]|$)') 'The wrong password should show a login error.'
    $signedIn = Send-Login $employee (Send-Request $employee $retry) 'employee' $employeePassword
    $resume = Get-Redirect $signedIn $authorize 'Login did not resume the authorization request after background requests.'
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

    if ($IncludeCallbackConfig -or $IncludeHrAccess -or $IncludeEmployeeManagement) {
        $denied = Send-Request $employee $adminPage
        Assert-Test ($denied.Status -eq 403) 'An employee should not be able to open application configuration.'
        $adminLogin = Get-Redirect (Send-Request $admin $adminPage) $login 'The configuration page should require administrator login.'
        $adminSignedIn = Send-Login $admin (Send-Request $admin $adminLogin) 'admin' $adminPassword
        $adminReturn = Get-Redirect $adminSignedIn $adminPage 'Administrator login did not open application configuration.'
    }

    if ($IncludeHrAccess) {
        $initialNextAction = Get-InputValue (Get-HrAccessForm $admin) 'allowed'
        Assert-Test ($initialNextAction -cin 'true', 'false') 'The HR access form must offer a Boolean action.'
        $originalHrAccess = $initialNextAction -ceq 'false'
        try {
            Set-HrAccess $admin $true
            Test-HrSso $employee
            Set-HrAccess $admin $false
            Exit-AppSession $employee $hr

            $employeeForm = Send-Request $employee $login
            Assert-Test ($employeeForm.Status -eq 200) 'Could not obtain an employee CSRF token.'
            $employeeUpdate = Send-Request $employee $hrAccess @{
                username = 'employee'
                allowed = 'true'
                _csrf = Get-InputValue $employeeForm.Body '_csrf'
            }
            Assert-Test ($employeeUpdate.Status -eq 403) 'An employee should not be able to grant HR access.'

            Confirm-HrDenied $employee $employeeName

            Exit-AppSession $employee $demo
            Complete-AppSso $employee $start $callback $demo
            Set-HrAccess $admin $true
            Complete-AppSso $employee $hrStart $hrCallback $hr
            Write-Host 'PASS: an administrator can grant and revoke HR access. A revoked employee cannot sign in to HR but can still sign in to work-order.'
        } finally {
            Set-HrAccess $admin $originalHrAccess
        }
    } elseif ($IncludeSso) {
        Test-HrSso $employee
    }

    if ($IncludeEmployeeManagement) {
        Test-EmployeeManagement $admin $employee
    }

    if ($IncludeCallbackConfig) {
        $adminForm = Send-Request $admin $adminReturn
        $original = Get-InputValue $adminForm.Body 'redirectUri' 'redirect-uri'
        Assert-Test ($original -ceq $callback.AbsoluteUri) 'The starting configuration did not contain the expected callback.'
        try {
            Set-Callback $admin $mismatchedCallback.AbsoluteUri
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
