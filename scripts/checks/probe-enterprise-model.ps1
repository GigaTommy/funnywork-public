#Requires -Version 5.1
<#
.SYNOPSIS
Bounded enterprise model POC. No Python, modules or administrator rights required.
.EXAMPLE
powershell.exe -NoProfile -File .\probe-enterprise-model.ps1 -Config .\config.json -Output .\report.json -PromptEnv FW_POC_API_TOKEN -Active
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Config,
    [Parameter(Mandatory=$true)][string]$Output,
    [string[]]$PromptEnv = @(),
    [switch]$Active,
    [string]$Checks = 'multi_turn,streaming,structured,tools'
)
$ErrorActionPreference = 'Stop'

function Stop-Probe([string]$Code, [int]$Status = 0) {
    $errorObject = New-Object System.Exception('Probe failure')
    $errorObject.Data['ProbeCode'] = $Code
    $errorObject.Data['HttpStatus'] = $Status
    throw $errorObject
}
function Get-Failure($Exception) {
    while ($null -ne $Exception) {
        if ($Exception.Data.Contains('ProbeCode')) {
            return @{code=[string]$Exception.Data['ProbeCode']; status=[int]$Exception.Data['HttpStatus']}
        }
        $Exception = $Exception.InnerException
    }
    return @{code='RESPONSE_SCHEMA_OR_LOCAL_ERROR'; status=0}
}
function Convert-Tree($Value, [hashtable]$Slots, [switch]$ExpandEnv) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $result = @{}
        foreach ($key in $Value.Keys) { $result[$key] = Convert-Tree $Value[$key] $Slots -ExpandEnv:$ExpandEnv }
        return $result
    }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $result = @{}
        foreach ($property in $Value.PSObject.Properties) { $result[$property.Name] = Convert-Tree $property.Value $Slots -ExpandEnv:$ExpandEnv }
        return $result
    }
    if ($Value -is [array]) {
        $result = @()
        foreach ($item in $Value) { $result += ,(Convert-Tree $item $Slots -ExpandEnv:$ExpandEnv) }
        return ,$result
    }
    if ($Value -is [string]) {
        if ($ExpandEnv) {
            return [regex]::Replace($Value, '\$\{([A-Z][A-Z0-9_]*)\}', {
                param($match)
                $replacement = [Environment]::GetEnvironmentVariable($match.Groups[1].Value, 'Process')
                if ([string]::IsNullOrEmpty($replacement)) { Stop-Probe ('MISSING_ENV_' + $match.Groups[1].Value) }
                return $replacement
            })
        }
        if ($null -ne $Slots -and $Slots.ContainsKey($Value)) { return ,$Slots[$Value] }
    }
    return $Value
}
function Get-At($Value, [string]$Path) {
    if ($Path) {
        foreach ($part in $Path.Split('.')) {
            if ($Value -is [array]) { $Value = $Value[[int]$part] }
            elseif ($Value -is [System.Collections.IDictionary]) {
                if (-not $Value.Contains($part)) { Stop-Probe 'RESPONSE_FIELD_MISSING' }
                $Value = $Value[$part]
            } else { Stop-Probe 'RESPONSE_FIELD_MISSING' }
        }
    }
    return ,$Value
}
function Get-Setting([string]$Name, $Default) {
    if ($script:Settings.ContainsKey($Name)) { return ,$script:Settings[$Name] }
    return ,$Default
}
function Test-Url([string]$Address) {
    $uri = $null
    if (-not [Uri]::TryCreate($Address, [UriKind]::Absolute, [ref]$uri)) { Stop-Probe 'INVALID_URL' }
    if ($uri.Scheme -notin @('http','https') -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        Stop-Probe 'INVALID_URL_USE_NO_QUERY_CREDENTIALS'
    }
    if ($uri.Scheme -eq 'http' -and $uri.Host -notin @('localhost','127.0.0.1','[::1]','::1') -and -not $script:Settings['allow_http']) {
        Stop-Probe 'HTTP_REQUIRES_EXPLICIT_ALLOW_HTTP'
    }
    return $uri.AbsoluteUri.TrimEnd('/')
}
function Get-Endpoint([string]$Path) {
    if (-not $Path.StartsWith('/') -or $Path.StartsWith('//')) { Stop-Probe 'API_PATH_MUST_START_WITH_SINGLE_SLASH' }
    return Test-Url ($script:BaseUrl + $Path)
}
function Convert-Json($Text) { return Convert-Tree (ConvertFrom-Json -InputObject $Text) $null }
function Read-Body($Stream, $Request, $Clock) {
    $memory = New-Object System.IO.MemoryStream
    $buffer = New-Object byte[] 4096
    try {
        while ($true) {
            $remaining = [int]($script:TimeoutMs - $Clock.ElapsedMilliseconds)
            if ($remaining -le 0) { $Request.Abort(); Stop-Probe 'RESPONSE_DEADLINE' }
            $pending = $Stream.ReadAsync($buffer, 0, $buffer.Length)
            if (-not $pending.Wait($remaining)) { $Request.Abort(); Stop-Probe 'RESPONSE_DEADLINE' }
            $count = $pending.GetAwaiter().GetResult()
            if ($count -eq 0) { break }
            if ($memory.Length + $count -gt 2097152) { $Request.Abort(); Stop-Probe 'RESPONSE_SIZE_LIMIT' }
            $memory.Write($buffer, 0, $count)
        }
        return [Text.Encoding]::UTF8.GetString($memory.ToArray())
    } finally { $memory.Dispose() }
}
function Invoke-ProbeRequest([string]$Path, $Body = $null, [switch]$Stream, [string]$Address = '', [hashtable]$Headers = $null) {
    if (-not $Address) { $Address = Get-Endpoint $Path }
    $Address = Test-Url $Address
    $request = [Net.HttpWebRequest]::Create($Address)
    $request.AllowAutoRedirect = $false
    $request.Proxy = $null
    $request.Timeout = $script:TimeoutMs
    $request.ReadWriteTimeout = $script:TimeoutMs
    $request.ContentType = 'application/json'
    $request.Accept = 'application/json'
    if ($Stream) { $request.Accept = 'text/event-stream' }
    $request.UserAgent = 'funnywork-enterprise-probe-powershell/1'
    if ($null -eq $Headers) { $Headers = $script:ApiHeaders }
    foreach ($key in $Headers.Keys) {
        switch ($key.ToLowerInvariant()) {
            'accept' { $request.Accept = [string]$Headers[$key] }
            'content-type' { $request.ContentType = [string]$Headers[$key] }
            'user-agent' { $request.UserAgent = [string]$Headers[$key] }
            'host' { Stop-Probe 'HOST_HEADER_OVERRIDE_NOT_ALLOWED' }
            default { $request.Headers[$key] = [string]$Headers[$key] }
        }
    }
    $script:RequestCount++
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $response = $null
    try {
        if ($null -ne $Body) {
            $bytes = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 50 -Compress))
            if ($bytes.Length -gt 65536) { Stop-Probe 'REQUEST_SIZE_LIMIT' }
            $request.Method = 'POST'
            $request.ContentLength = $bytes.Length
            $writer = $request.GetRequestStream()
            try { $writer.Write($bytes, 0, $bytes.Length) } finally { $writer.Dispose() }
        }
        $response = $request.GetResponse()
        $status = [int]$response.StatusCode
        if ($status -ge 300 -and $status -lt 400) { Stop-Probe 'REDIRECT_LOGIN_OR_ROUTING' $status }
        if ($Stream) {
            if (-not $response.ContentType.ToLowerInvariant().StartsWith('text/event-stream')) { Stop-Probe 'EXPECTED_SSE_NOT_RETURNED' }
            return Read-Sse $response.GetResponseStream() $request $clock
        }
        $text = Read-Body $response.GetResponseStream() $request $clock
        if ($response.ContentType -like 'text/html*' -or $text.TrimStart().StartsWith('<')) { Stop-Probe 'HTML_LOGIN_OR_WEBPAGE_NOT_MODEL_JSON' }
        return Convert-Json $text
    } catch [Net.WebException] {
        $failedResponse = $_.Exception.Response
        if ($null -ne $failedResponse) {
            $status = [int]$failedResponse.StatusCode
            $failedResponse.Close()
            if ($status -in @(401,403)) { Stop-Probe 'AUTH_REQUIRED' $status }
            if ($status -eq 429) { Stop-Probe 'RATE_LIMITED' $status }
            if ($status -ge 300 -and $status -lt 400) { Stop-Probe 'REDIRECT_LOGIN_OR_ROUTING' $status }
            Stop-Probe 'HTTP_ERROR' $status
        }
        Stop-Probe 'NETWORK_OR_TLS_ERROR'
    } finally {
        if ($null -ne $response) { $response.Close() }
        $request.Abort()
    }
}
function Read-Sse($Stream, $Request, $Clock) {
    $buffer = New-Object byte[] 4096
    $decoder = [Text.Encoding]::UTF8.GetDecoder()
    $characters = New-Object char[] 8192
    $pendingText = ''
    $fields = New-Object 'System.Collections.Generic.List[string]'
    $events = New-Object 'System.Collections.Generic.List[object]'
    $size = 0
    $first = $null
    $done = $false
    while (-not $done) {
        $remaining = [int]($script:TimeoutMs - $Clock.ElapsedMilliseconds)
        if ($remaining -le 0) { Stop-Probe 'STREAM_DEADLINE' }
        $pending = $Stream.ReadAsync($buffer, 0, $buffer.Length)
        if (-not $pending.Wait($remaining)) { $Request.Abort(); Stop-Probe 'STREAM_DEADLINE' }
        $count = $pending.GetAwaiter().GetResult()
        if ($count -eq 0) {
            if ($pendingText.Length -gt 0 -or $fields.Count -gt 0) { Stop-Probe 'TRUNCATED_SSE_EVENT' }
            break
        }
        $size += $count
        if ($size -gt 2097152) { Stop-Probe 'STREAM_SIZE_LIMIT' }
        $written = $decoder.GetChars($buffer, 0, $count, $characters, 0)
        $pendingText += New-Object string($characters, 0, $written)
        while ($pendingText.Contains("`n")) {
            $index = $pendingText.IndexOf("`n")
            if ($index -gt 65536) { Stop-Probe 'STREAM_SIZE_LIMIT' }
            $line = $pendingText.Substring(0,$index).TrimEnd("`r")
            $pendingText = $pendingText.Substring($index + 1)
            if ($line.Length -eq 0 -and $fields.Count -gt 0) {
                $data = [string]::Join("`n",$fields.ToArray())
                $fields.Clear()
                if ($data -eq '[DONE]') { $event = '[DONE]' } else { $event = Convert-Json $data }
                if ($event -is [hashtable] -and ($event['error'] -or $event['type'] -eq 'error')) { Stop-Probe 'UPSTREAM_STREAM_ERROR' }
                $events.Add($event)
                if ($events.Count -gt 4096) { Stop-Probe 'STREAM_SIZE_LIMIT' }
                if ($null -eq $first) { $first = $Clock.ElapsedMilliseconds }
                if (($event -is [string] -and $event -eq '[DONE]') -or ($event -is [hashtable] -and $event['type'] -eq 'message_stop')) { $done = $true; break }
            } elseif ($line.StartsWith('data:')) { $fields.Add($line.Substring(5).TrimStart(' ')) }
        }
        if ($pendingText.Length -gt 65536) { Stop-Probe 'STREAM_SIZE_LIMIT' }
    }
    return @{events=$events.ToArray(); first_event_ms=$first; elapsed_ms=$Clock.ElapsedMilliseconds}
}
function Get-Usage($Response) {
    $result = @{}
    $usage = $Response['usage']
    if ($usage -is [hashtable]) {
        foreach ($key in @('input_tokens','output_tokens','prompt_tokens','completion_tokens','total_tokens')) {
            $value = $usage[$key]
            if (($value -is [int] -or $value -is [long]) -and $value -ge 0) { $result[$key] = $value }
        }
    }
    return $result
}
function Get-Text($Response, [string]$Protocol) {
    if ($Protocol -eq 'chat_completions') { $text = Get-At $Response 'choices.0.message.content' }
    elseif ($Protocol -eq 'messages') {
        $text = ''
        foreach ($block in $Response['content']) { if ($block['type'] -eq 'text') { $text += $block['text'] } }
    } else { $text = Get-At $Response $script:Settings['custom']['text_path'] }
    if ($text -isnot [string]) { Stop-Probe 'TEXT_RESPONSE_SCHEMA_MISMATCH' }
    return $text
}
function Invoke-Inference($Messages, [string]$Protocol, [hashtable]$Extra = @{}, [switch]$Stream) {
    $body = @{}
    $defaults = $script:Settings['request_defaults']
    if ($defaults) { foreach ($key in $defaults.Keys) { $body[$key] = $defaults[$key] } }
    $body['model'] = $script:Settings['model']; $body['messages'] = $Messages
    $body['max_tokens'] = 128; $body['stream'] = [bool]$Stream
    foreach ($key in $Extra.Keys) { $body[$key] = $Extra[$key] }
    if ($Protocol -eq 'custom') {
        $custom = $script:Settings['custom']
        $body = Convert-Tree $custom['body'] @{'{{model}}'=$script:Settings['model']; '{{messages}}'=$Messages; '{{max_tokens}}'=128; '{{stream}}'=[bool]$Stream}
        $path = $custom['path']
    } elseif ($Protocol -eq 'chat_completions') { $path = Get-Setting 'chat_path' '/chat/completions' }
    else { $path = Get-Setting 'messages_path' '/messages' }
    return Invoke-ProbeRequest -Path $path -Body $body -Stream:$Stream
}
function Add-Check([string]$Name, [scriptblock]$Action) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        $evidence = & $Action
        $script:Report.checks.Add(@{name=$Name; status='PASS'; evidence=$evidence; elapsed_ms=$clock.ElapsedMilliseconds})
        return $true
    } catch {
        $failure = Get-Failure $_.Exception
        $status = 'NOT_CONFIRMED'
        if ($failure.status -in @(401,403,429) -or $failure.code -match '^(AUTH_|LOGIN_|REDIRECT_|HTML_)') { $status = 'BLOCKED' }
        $script:Report.checks.Add(@{name=$Name; status=$status; reason=$failure.code; http_status=$failure.status})
        return $false
    }
}
function Invoke-Login {
    $login = $script:Settings['login']
    if ($login) {
        $headers = @{}
        if ($login['headers']) { $headers = $login['headers'] }
        $result = Invoke-ProbeRequest -Address $login['url'] -Body $login['body'] -Headers $headers
        try { $token = Get-At $result $login['token_path'] } catch { Stop-Probe 'LOGIN_TOKEN_NOT_FOUND' }
        if ($token -isnot [string] -or -not $token -or $token -match '[\r\n]') { Stop-Probe 'LOGIN_TOKEN_NOT_FOUND' }
        $header = 'Authorization'; $prefix = 'Bearer '
        if ($login.ContainsKey('header')) { $header = $login['header'] }
        if ($login.ContainsKey('prefix')) { $prefix = $login['prefix'] }
        $script:ApiHeaders[$header] = $prefix + $token
    }
    return @{configured_login_completed=[bool]$login}
}
function Get-Strings($Value) {
    if ($Value -is [string]) { return $Value }
    if ($Value -is [hashtable]) { foreach ($item in $Value.Values) { Get-Strings $item } }
    elseif ($Value -is [array]) { foreach ($item in $Value) { Get-Strings $item } }
}
function Invoke-Catalog {
    $response = Invoke-ProbeRequest -Path (Get-Setting 'models_path' '/models')
    $items = Get-At $response (Get-Setting 'models_items_path' 'data')
    if ($items -isnot [array]) { Stop-Probe 'MODEL_LIST_SCHEMA_MISMATCH' }
    $fragments = @((@(Get-Strings $script:ApiHeaders) + @(Get-Strings $script:Settings['login'])) | ForEach-Object { $_ -split '[\s;=]+' } | Where-Object { $_ })
    $ids = @()
    foreach ($item in @($items | Select-Object -First 200)) {
        $id = Get-At $item (Get-Setting 'model_id_path' 'id')
        if ($id -is [string] -and $id -cmatch '^[A-Za-z0-9_./:@+\-]{1,160}$') {
            $sensitive = $false
            foreach ($fragment in $fragments) { if ($id -ceq $fragment -or ($fragment.Length -ge 4 -and $id.Contains($fragment))) { $sensitive = $true } }
            if (-not $sensitive -and $ids -cnotcontains $id) { $ids += $id }
        }
    }
    $script:Report.models = $ids
    return @{model_count_returned=$items.Count; saved_ids=$ids.Count; metadata_is_not_capability_proof=$true}
}
function Invoke-Basic([string]$Protocol) {
    $result = Invoke-Inference @(@{role='user';content='Reply with exactly FW_PROBE_OK.'}) $Protocol
    if (-not (Get-Text $result $Protocol).Contains('FW_PROBE_OK')) { Stop-Probe 'BASIC_EXPECTED_TEXT_MISSING' }
    return @{response_shape=$Protocol;usage=(Get-Usage $result)}
}
function Invoke-MultiTurn {
    $marker = 'FW_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    $messages = @(@{role='user';content=('Remember this code: '+$marker)}, @{role='assistant';content='I will remember the code.'}, @{role='user';content='Return only the code from the first message.'})
    $result = Invoke-Inference $messages $script:Protocol
    if (-not (Get-Text $result $script:Protocol).Contains($marker)) { Stop-Probe 'MULTI_TURN_MARKER_MISSING' }
    return @{caller_supplied_history_verified=$true;server_session_storage='NOT_TESTED'}
}
function Invoke-Structured {
    $extra = @{}
    if ($script:Protocol -eq 'chat_completions') { $extra['response_format'] = @{type='json_object'} }
    $result = Invoke-Inference @(@{role='user';content='Return only a JSON object exactly {"probe":"FW_JSON_OK","n":7}.'}) $script:Protocol $extra
    $value = Convert-Json (Get-Text $result $script:Protocol)
    if ($value -isnot [hashtable] -or $value.Count -ne 2 -or $value['probe'] -cne 'FW_JSON_OK' -or $value['n'] -isnot [int] -and $value['n'] -isnot [long] -or $value['n'] -ne 7) { Stop-Probe 'JSON_CONTENT_MISMATCH' }
    return @{valid_json_observed=$true;schema_enforcement='NOT_TESTED';json_mode_requested=($script:Protocol -eq 'chat_completions')}
}
function Invoke-Streaming {
    $response = Invoke-Inference @(@{role='user';content='Reply with exactly FW_STREAM_OK.'}) $script:Protocol @{} -Stream
    $text = ''; $terminal = $false; $chunks = 0; $usage = @{}
    foreach ($event in $response.events) {
        if ($event -is [string] -and $event -eq '[DONE]') { if ($script:Protocol -eq 'chat_completions') { $terminal = $true }; continue }
        if ($event -isnot [hashtable]) { Stop-Probe 'STREAM_SCHEMA_MISMATCH' }
        $chunk = ''
        if ($script:Protocol -eq 'chat_completions') {
            foreach ($choice in $event['choices']) { $chunk += $choice['delta']['content'] }
        } else {
            if ($event['type'] -eq 'content_block_delta') { $chunk = $event['delta']['text'] }
            if ($event['type'] -eq 'message_stop') { $terminal = $true }
        }
        $text += $chunk; if ($chunk) { $chunks++ }
        foreach ($source in @($event, $event['message'])) {
            if ($source -is [hashtable]) { $part = Get-Usage $source; foreach ($key in $part.Keys) { $usage[$key] = $part[$key] } }
        }
    }
    if (-not $terminal -or -not $text.Contains('FW_STREAM_OK')) { Stop-Probe 'STREAM_INCOMPLETE_OR_TEXT_MISSING' }
    return @{text_events=$chunks;terminal_event=$terminal;usage=$usage;first_event_ms=$response.first_event_ms;elapsed_ms=$response.elapsed_ms;proxy_buffering='NOT_TESTED'}
}
function Invoke-Tools {
    $schema = @{type='object';properties=@{code=@{type='string'}};required=@('code');additionalProperties=$false}
    $chat = $script:Protocol -eq 'chat_completions'
    if ($chat) {
        $extra = @{tools=@(@{type='function';function=@{name='probe_echo';description='Return a test code.';parameters=$schema}});tool_choice=@{type='function';function=@{name='probe_echo'}}}
    } else {
        $extra = @{tools=@(@{name='probe_echo';description='Return a test code.';input_schema=$schema});tool_choice=@{type='tool';name='probe_echo'}}
    }
    $messages = @(@{role='user';content='Call probe_echo with code FW_TOOL_OK, then repeat the tool result exactly.'})
    $result = Invoke-Inference $messages $script:Protocol $extra
    if ($chat) {
        $message = Get-At $result 'choices.0.message'
        $calls = @($message['tool_calls'])
        if ($calls.Count -ne 1 -or $calls[0]['type'] -ne 'function') { Stop-Probe 'EXPECTED_SINGLE_TOOL_CALL' }
        $call = $calls[0]; $name = $call['function']['name']; $arguments = Convert-Json $call['function']['arguments']; $id = $call['id']
        $assistant = @{role='assistant';content=$message['content'];tool_calls=$calls}
    } else {
        $calls = @($result['content'] | Where-Object { $_['type'] -eq 'tool_use' })
        if ($calls.Count -ne 1) { Stop-Probe 'EXPECTED_SINGLE_TOOL_CALL' }
        $call = $calls[0]; $name = $call['name']; $arguments = $call['input']; $id = $call['id']
        $assistant = @{role='assistant';content=$result['content']}
    }
    if ($name -cne 'probe_echo' -or $arguments -isnot [hashtable] -or $arguments.Count -ne 1 -or $arguments['code'] -cne 'FW_TOOL_OK' -or $id -isnot [string] -or -not $id) { Stop-Probe 'TOOL_ARGUMENTS_MISMATCH' }
    # Only a local echo stub; never execute provider-supplied code.
    $marker = 'FW_RESULT_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    $messages += $assistant
    if ($chat) { $messages += @{role='tool';tool_call_id=$id;content=$marker}; $extra['tool_choice'] = 'none' }
    else { $messages += @{role='user';content=@(@{type='tool_result';tool_use_id=$id;content=$marker})}; $extra['tool_choice'] = @{type='auto'} }
    $final = Invoke-Inference $messages $script:Protocol $extra
    if (-not (Get-Text $final $script:Protocol).Contains($marker)) { Stop-Probe 'TOOL_RESULT_ROUNDTRIP_NOT_CONFIRMED' }
    return @{forced_tool_roundtrip_verified=$true;autonomous_tool_selection='NOT_TESTED';parallel_tools='NOT_TESTED';tool_streaming='NOT_TESTED'}
}
function Invoke-Run {
    if (-not (Add-Check 'authentication_setup' { Invoke-Login })) { return }
    if ($script:Settings.ContainsKey('models_path') -and $null -eq $script:Settings['models_path']) {
        $script:Report.checks.Add(@{name='model_catalog';status='NOT_TESTED';reason='NO_CATALOG_CONFIGURED'})
    } else { $null = Add-Check 'model_catalog' { Invoke-Catalog } }
    if (-not $Active) { return }
    $candidates = @($script:Protocol)
    if ($script:Protocol -eq 'auto') { $candidates = @('chat_completions','messages') }
    foreach ($candidate in $candidates) {
        if (Add-Check ('basic_'+$candidate) { Invoke-Basic $candidate }) { $script:Protocol = $candidate; $script:Report.protocol = $candidate; break }
        if ($script:Report.checks[$script:Report.checks.Count-1].status -eq 'BLOCKED') { return }
    }
    if (-not $script:Report.protocol) { return }
    foreach ($name in $script:Selected) {
        if ($script:Protocol -eq 'custom' -and $name -ne 'multi_turn') { $script:Report.checks.Add(@{name=$name;status='NOT_TESTED';reason='CUSTOM_ADAPTER_NOT_CONFIGURED'}); continue }
        switch ($name) {
            'multi_turn' { $null = Add-Check $name { Invoke-MultiTurn } }
            'streaming' { $null = Add-Check $name { Invoke-Streaming } }
            'structured' { $null = Add-Check $name { Invoke-Structured } }
            'tools' { $null = Add-Check $name { Invoke-Tools } }
        }
        if ($script:Report.checks[$script:Report.checks.Count-1].status -eq 'BLOCKED') { break }
    }
}

$previousTls = [Net.ServicePointManager]::SecurityProtocol
$promptValues = @{}
$exitCode = 1
try {
    $script:Selected = @($Checks.Split(',') | Where-Object { $_ } | Select-Object -Unique)
    foreach ($name in $script:Selected) { if ($name -notin @('multi_turn','streaming','structured','tools')) { Stop-Probe 'INVALID_CHECK_NAME' } }
    $outputPath = [IO.Path]::GetFullPath($Output)
    if (Test-Path -LiteralPath $outputPath) { Stop-Probe 'OUTPUT_ALREADY_EXISTS' }
    foreach ($name in $PromptEnv) {
        if ($name -cnotmatch '^[A-Z][A-Z0-9_]*$') { Stop-Probe 'INVALID_ENV_NAME' }
        if ([Console]::IsInputRedirected) { Stop-Probe 'INTERACTIVE_SECRET_REQUIRES_TTY' }
        $promptValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        $secure = Read-Host -Prompt $name -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { [Environment]::SetEnvironmentVariable($name,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer),'Process') }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
    }
    $script:Settings = Convert-Tree (ConvertFrom-Json -InputObject ([IO.File]::ReadAllText([IO.Path]::GetFullPath($Config)))) $null -ExpandEnv
    if ($script:Settings['ca_file']) { Stop-Probe 'USE_WINDOWS_TRUST_STORE_FOR_ENTERPRISE_CA' }
    $script:BaseUrl = Test-Url $script:Settings['base_url']
    $timeout = Get-Setting 'timeout_seconds' 30
    if ($timeout -isnot [ValueType] -or $timeout -lt 1 -or $timeout -gt 120) { Stop-Probe 'TIMEOUT_MUST_BE_1_TO_120' }
    $script:TimeoutMs = [int]($timeout * 1000)
    $script:Protocol = Get-Setting 'protocol' 'auto'
    if ($script:Protocol -notin @('auto','chat_completions','messages','custom')) { Stop-Probe 'INVALID_PROTOCOL' }
    if ($Active -and ($script:Settings['model'] -isnot [string] -or -not $script:Settings['model'])) { Stop-Probe 'MODEL_REQUIRED_FOR_ACTIVE_PROBES' }
    $script:ApiHeaders = @{}
    if ($script:Settings['headers']) { $script:ApiHeaders = $script:Settings['headers'].Clone() }
    $script:RequestCount = 0
    $script:Report = @{schema_version=1;created_at=[DateTime]::UtcNow.ToString('o');checks=(New-Object 'System.Collections.Generic.List[object]');models=@();protocol=$null;
        limits=@{context_tokens='NOT_TESTED';concurrency='NOT_TESTED';images='NOT_TESTED';server_cancellation='NOT_TESTED'};
        notes=@('Results apply only to this account, endpoint, model and deployment at test time.','Failure does not prove the underlying model lacks a capability.','No business data, generated text, credentials or raw responses are recorded.')}
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-Run
    $script:Report['request_count'] = $script:RequestCount
    $script:Report['inference_verified'] = [bool]$script:Report.protocol
    $parent = [IO.Path]::GetDirectoryName($outputPath)
    $null = [IO.Directory]::CreateDirectory($parent)
    # Apply a private Windows ACL atomically with CreateNew; never replace an existing report.
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true,$false)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','Allow')
        $acl.AddAccessRule($rule)
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $info = New-Object IO.FileInfo($outputPath)
            $file = [IO.FileSystemAclExtensions]::Create($info,[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::Write,[IO.FileShare]::None,4096,[IO.FileOptions]::None,$acl)
        } else {
            $file = New-Object IO.FileStream($outputPath,[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::Write,[IO.FileShare]::None,4096,[IO.FileOptions]::None,$acl)
        }
    } else { $file = New-Object IO.FileStream($outputPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None) }
    try {
        $payload = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $script:Report -Depth 50))
        $file.Write($payload,0,$payload.Length)
    } finally { $file.Dispose() }
    foreach ($check in $script:Report.checks) { Write-Host ($check.name + ': ' + $check.status) }
    Write-Host ('Requests: ' + $script:RequestCount + '; report written.')
    $exitCode = 0
    if (@($script:Report.checks | Where-Object { $_.status -notin @('PASS','NOT_TESTED') }).Count -gt 0) { $exitCode = 2 }
} catch {
    $failure = Get-Failure $_.Exception
    [Console]::Error.WriteLine('Probe stopped: ' + $failure.code + '. No credentials or response body printed.')
} finally {
    [Net.ServicePointManager]::SecurityProtocol = $previousTls
    foreach ($name in $promptValues.Keys) { [Environment]::SetEnvironmentVariable($name,$promptValues[$name],'Process') }
}
exit $exitCode
