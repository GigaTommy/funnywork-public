#Requires -Version 5.1
# Local browser controller. No external modules; only the tool-owned browser profile is used.
function Throw-ModelCheck([string]$Code) { $e=[Exception]::new($Code);$e.Data['ModelCheckCode']=$Code;throw $e }
function Get-ModelCheckCode($ErrorObject) {
    $e=$ErrorObject.Exception
    while($null -ne $e){if($e.Data.Contains('ModelCheckCode')){return [string]$e.Data['ModelCheckCode']};$e=$e.InnerException}
    return 'LOCAL_OPERATION_FAILED'
}
function Convert-ModelTree($Value) {
    if($null -eq $Value){return $null}
    if($Value -is [Management.Automation.PSCustomObject]){$map=@{};foreach($p in $Value.PSObject.Properties){$map[$p.Name]=Convert-ModelTree $p.Value};return $map}
    if($Value -is [array]){$items=@();foreach($item in $Value){$items+=,(Convert-ModelTree $item)};return ,$items}
    return $Value
}
function Read-ModelJson([string]$Text){return Convert-ModelTree (ConvertFrom-Json -InputObject $Text)}
function Convert-ModelJson($Value){return ConvertTo-Json -InputObject $Value -Depth 60 -Compress}
function Get-ModelOrigin([string]$Address){
    $uri=$null
    if(-not [Uri]::TryCreate($Address,[UriKind]::Absolute,[ref]$uri)-or $uri.Scheme -notin @('http','https')-or $uri.UserInfo){Throw-ModelCheck 'INVALID_PAGE_ADDRESS'}
    return $uri.GetLeftPart([UriPartial]::Authority)
}
function Resolve-ModelBrowser([string]$Requested){
    if($Requested){if(-not [IO.File]::Exists($Requested)){Throw-ModelCheck 'BROWSER_NOT_FOUND'};return [IO.Path]::GetFullPath($Requested)}
    $candidates=@()
    foreach($base in @(${env:ProgramFiles(x86)},$env:ProgramFiles,$env:LOCALAPPDATA)){
        if($base){$candidates+=(Join-Path $base 'Microsoft\Edge\Application\msedge.exe')}
    }
    foreach($base in @($env:ProgramFiles,${env:ProgramFiles(x86)},$env:LOCALAPPDATA)){
        if($base){$candidates+=(Join-Path $base 'Google\Chrome\Application\chrome.exe')}
    }
    foreach($candidate in $candidates){if([IO.File]::Exists($candidate)){return $candidate}}
    Throw-ModelCheck 'BROWSER_NOT_FOUND'
}
function Read-ModelLocalJson([string]$Address){
    $uri=[Uri]$Address
    if(-not $uri.IsLoopback -or $uri.Scheme -ne 'http'){Throw-ModelCheck 'LOCAL_DEBUG_ADDRESS_REQUIRED'}
    $request=[Net.HttpWebRequest]::Create($uri);$request.Proxy=$null;$request.AllowAutoRedirect=$false;$request.Timeout=1000;$response=$null
    try{$response=$request.GetResponse();if([int]$response.StatusCode -ne 200){Throw-ModelCheck 'LOCAL_DEBUG_NOT_READY'};$reader=[IO.StreamReader]::new($response.GetResponseStream());try{$text=$reader.ReadToEnd();if($text.Length -gt 1048576){Throw-ModelCheck 'LOCAL_RESPONSE_TOO_LARGE'};return Read-ModelJson $text}finally{$reader.Dispose()}}
    finally{if($response){$response.Close()};$request.Abort()}
}
function Receive-ModelCdp([int]$WaitMs=0){
    $s=$script:ModelSession
    if(-not $s.Socket -or $s.Socket.State -ne [Net.WebSockets.WebSocketState]::Open){Throw-ModelCheck 'BROWSER_DISCONNECTED'}
    if($null -eq $s.ReceiveTask){$s.ReceiveTask=$s.Socket.ReceiveAsync([ArraySegment[byte]]::new($s.Buffer),[Threading.CancellationToken]::None)}
    if(-not $s.ReceiveTask.IsCompleted){if($WaitMs -le 0){return};if(-not $s.ReceiveTask.Wait($WaitMs)){return}}
    $part=$s.ReceiveTask.GetAwaiter().GetResult();$s.ReceiveTask=$null
    if($part.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close){Throw-ModelCheck 'BROWSER_DISCONNECTED'}
    if($s.Message.Length+$part.Count -gt 2097152){Throw-ModelCheck 'DEBUG_RESPONSE_TOO_LARGE'}
    $s.Message.Write($s.Buffer,0,$part.Count)
    if($part.EndOfMessage){
        $packet=Read-ModelJson ([Text.Encoding]::UTF8.GetString($s.Message.ToArray()));$s.Message.SetLength(0)
        if($packet.ContainsKey('id')){$s.Replies[[string]$packet['id']]=$packet}
        # Page and Runtime events are intentionally not retained or logged.
    }
}
function Send-ModelCdp([string]$Method,[hashtable]$Parameters=@{},[int]$TimeoutMs=5000){
    $s=$script:ModelSession;$s.CommandId++;$id=[string]$s.CommandId
    $raw=[Text.Encoding]::UTF8.GetBytes((Convert-ModelJson @{id=$s.CommandId;method=$Method;params=$Parameters}))
    $pending=$s.Socket.SendAsync([ArraySegment[byte]]::new($raw),[Net.WebSockets.WebSocketMessageType]::Text,$true,[Threading.CancellationToken]::None)
    if(-not $pending.Wait($TimeoutMs)){Throw-ModelCheck 'BROWSER_COMMAND_TIMEOUT'};[void]$pending.GetAwaiter().GetResult()
    $clock=[Diagnostics.Stopwatch]::StartNew()
    while(-not $s.Replies.ContainsKey($id)){
        if($clock.ElapsedMilliseconds -gt $TimeoutMs){Throw-ModelCheck 'BROWSER_COMMAND_TIMEOUT'}
        Receive-ModelCdp 50
    }
    $packet=$s.Replies[$id];$s.Replies.Remove($id)
    if($packet.ContainsKey('error')){Throw-ModelCheck 'BROWSER_COMMAND_FAILED'}
    return $packet['result']
}
function Invoke-ModelJs([string]$Expression){
    $reply=Send-ModelCdp 'Runtime.evaluate' @{expression=$Expression;returnByValue=$true;awaitPromise=$false}
    if($reply.ContainsKey('exceptionDetails')){Throw-ModelCheck 'PAGE_OPERATION_FAILED'}
    return $reply['result']['value']
}
function Start-ModelBrowser([string]$ChatAddress,[string]$AuthAddress,[string]$BrowserPath,[string]$WorkingRoot,[string[]]$Checks,[bool]$AllowCrossOrigin=$true,[switch]$Headless){
    if($script:ModelSession -and $script:ModelSession.Running){Throw-ModelCheck 'SESSION_ALREADY_RUNNING'}
    if($script:ModelSession -and $script:ModelSession.Cleanup -eq 'PROFILE_RETAINED'){Throw-ModelCheck 'BROWSER_DATA_IN_USE'}
    $origin=Get-ModelOrigin $ChatAddress
    if($AuthAddress){$null=Get-ModelOrigin $AuthAddress}
    $executable=Resolve-ModelBrowser $BrowserPath
    $work=[IO.Path]::GetFullPath($WorkingRoot)
    $null=[IO.Directory]::CreateDirectory($work)
    $run=Join-Path $work ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
    $null=[IO.Directory]::CreateDirectory($run);$testProfile=Join-Path $run 'browser-profile';$null=[IO.Directory]::CreateDirectory($testProfile)
    # Never connect to an existing user browser or expose its default profile.
    $script:ModelSession=@{Running=$false;Run=$run;Profile=$testProfile;Browser=$null;Socket=$null;ReceiveTask=$null;Buffer=(New-Object byte[] 16384);Message=[IO.MemoryStream]::new();Replies=@{};CommandId=0;Origin=$origin;Chat=$ChatAddress;Auth=$AuthAddress;Checks=$Checks;Cross=$AllowCrossOrigin;State='STARTING';Observed=@();Report=$null;Testing=$false;Started=[DateTime]::UtcNow;Cleanup='PENDING'}
    $s=$script:ModelSession
    if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){
        $identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
        $rule=[Security.AccessControl.FileSystemAccessRule]::new($identity,[Security.AccessControl.FileSystemRights]::FullControl,([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule);$directory=[IO.DirectoryInfo]::new($run)
        if($PSVersionTable.PSVersion.Major -ge 6){[IO.FileSystemAclExtensions]::SetAccessControl($directory,$acl)}else{$directory.SetAccessControl($acl)}
        $profileDirectory=[IO.DirectoryInfo]::new($testProfile)
        if($PSVersionTable.PSVersion.Major -ge 6){[IO.FileSystemAclExtensions]::SetAccessControl($profileDirectory,$acl)}else{$profileDirectory.SetAccessControl($acl)}
    }
    $arguments=@('--remote-debugging-address=127.0.0.1','--remote-debugging-port=0',('--user-data-dir="'+$testProfile+'"'),'--no-first-run','--no-default-browser-check','--disable-background-networking','--new-window','about:blank')
    if($Headless){$arguments=@('--headless=new')+$arguments}
    try{
        $s.Browser=Start-Process -FilePath $executable -ArgumentList $arguments -PassThru -RedirectStandardOutput (Join-Path $testProfile 'stdout.log') -RedirectStandardError (Join-Path $testProfile 'stderr.log')
        $activePort=Join-Path $testProfile 'DevToolsActivePort';$until=[DateTime]::UtcNow.AddSeconds(20)
        while(-not [IO.File]::Exists($activePort)){
            if([DateTime]::UtcNow -gt $until){Throw-ModelCheck 'BROWSER_DEBUG_UNAVAILABLE'}
            Start-Sleep -Milliseconds 100
        }
        $lines=[IO.File]::ReadAllLines($activePort);$port=0
        if(-not [int]::TryParse($lines[0],[ref]$port)-or $port -lt 1 -or $port -gt 65535){Throw-ModelCheck 'INVALID_DEBUG_PORT'}
        $targets=Read-ModelLocalJson ('http://127.0.0.1:'+$port+'/json/list')
        $target=@($targets|Where-Object {$_['type'] -eq 'page'})|Select-Object -First 1
        if(-not $target){Throw-ModelCheck 'BROWSER_PAGE_NOT_FOUND'}
        $ws=[Uri]$target['webSocketDebuggerUrl']
        if($ws.Scheme -ne 'ws'-or -not $ws.IsLoopback -or $ws.Port -ne $port){Throw-ModelCheck 'INVALID_DEBUG_SOCKET'}
        $s.Socket=[Net.WebSockets.ClientWebSocket]::new();$s.Socket.Options.Proxy=$null
        $connect=$s.Socket.ConnectAsync($ws,[Threading.CancellationToken]::None)
        if(-not $connect.Wait(5000)){Throw-ModelCheck 'BROWSER_CONNECT_TIMEOUT'};[void]$connect.GetAwaiter().GetResult()
        $helper=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'browser-model-probe.js'))
        $quoted=Convert-ModelJson $origin
        $s.Bootstrap='(()=>{if(window!==top||location.origin!=='+$quoted+')return;'+$helper+"`nFunnyWorkPOC.start();})();"
        $null=Send-ModelCdp 'Page.enable';$null=Send-ModelCdp 'Runtime.enable'
        $null=Send-ModelCdp 'Page.addScriptToEvaluateOnNewDocument' @{source=$s.Bootstrap}
        $null=Send-ModelCdp 'Page.navigate' @{url=$ChatAddress}
        $s.Running=$true;$s.State='WAITING_CHAT'
        return @{state=$s.State;run=$run}
    }catch{Stop-ModelBrowser;throw}
}
function Open-ModelPage([string]$Which){
    $s=$script:ModelSession;if(-not $s.Running){Throw-ModelCheck 'NO_ACTIVE_SESSION'}
    $address=$s.Chat;if($Which -eq 'auth'){$address=$s.Auth};if(-not $address){Throw-ModelCheck 'AUTH_ADDRESS_EMPTY'}
    $null=Send-ModelCdp 'Page.navigate' @{url=$address};$s.State='WAITING_CHAT';$s.Testing=$false
}
function Update-ModelBrowser {
    $s=$script:ModelSession;if(-not $s -or -not $s.Running){return @{state='IDLE'}}
    if($s.Report){return @{state=$s.State;report=$s.Report;observed=$s.Observed}}
    $origin=Convert-ModelJson $s.Origin
    if($s.Testing){
        $result=Invoke-ModelJs 'JSON.stringify(window.__fwNativeResult||null)'
        if($result -and $result -ne 'null'){
            $s.Report=Read-ModelJson $result;$s.State='COMPLETE'
            Write-ModelReport
            return @{state='COMPLETE';report=$s.Report;observed=$s.Observed}
        }
        if([DateTime]::UtcNow -gt $s.TestDeadline){Throw-ModelCheck 'TEST_TIMEOUT'}
        return @{state='TESTING';observed=$s.Observed}
    }
    $current=Invoke-ModelJs 'location.origin'
    if($current -ne $s.Origin){$s.State='LOGIN';return @{state='LOGIN'}}
    # Reinstall after reload if needed; script runs only in the configured chat origin.
    $null=Invoke-ModelJs $s.Bootstrap
    $text=Invoke-ModelJs 'JSON.stringify(FunnyWorkPOC.snapshot())'
    if(-not $text){return @{state='WAITING_CHAT'}}
    $snapshot=Read-ModelJson $text;$s.Observed=@($snapshot['records'])
    $eligible=@($s.Observed|Where-Object {$_['method'] -eq 'POST'-and $_['status'] -eq 200 -and $_['protocol_candidate'] -in @('chat_completions','messages')-and @($_['model_ids']).Count -gt 0})
    if($eligible.Count -eq 0){
        $state='WAITING_MESSAGE'
        if(@($s.Observed|Where-Object {$_['method'] -eq 'POST'-and $_['status'] -eq 200 -and $_['protocol_candidate'] -eq 'custom_or_unknown' -and @($_['request_fields']|Where-Object {$_ -in @('messages','query','prompt','question','inputs')}).Count -gt 0}).Count -gt 0){
            $s.State='ADAPTER_REQUIRED';$s.Report=@{source='browser-capability-test';request_count=0;checks=@(@{name='basic';status='NOT_TESTED';reason='CUSTOM_ADAPTER_REQUIRED'})}
            Write-ModelReport
            return @{state=$s.State;observed=$s.Observed;report=$s.Report}
        }
        return @{state=$state;observed=$s.Observed}
    }
    $candidate=$eligible[0];$id=Convert-ModelJson $candidate['id'];$address=Convert-ModelJson $candidate['url']
    # Wait for the original chat reply to complete before sending synthetic tests.
    $ready=Invoke-ModelJs ('performance.getEntriesByType("resource").some(e=>{try{const u=new URL(e.name);const p=u.pathname.split("/").map(s=>s.length>48||/^[a-f0-9-]{32,}$/i.test(s)?"[id]":s).join("/");return u.origin+p==='+$address+'&&e.responseEnd>0}catch{return false}})')
    if(-not $ready){return @{state='WAITING_REPLY';observed=$s.Observed}}
    $options=Convert-ModelJson @{checks=@($s.Checks);allowCrossOrigin=$s.Cross}
    $expression='window.__fwNativeResult=null;FunnyWorkPOC.test('+$id+','+$options+').then(r=>{window.__fwNativeResult=r},()=>{window.__fwNativeResult={source:"browser-capability-test",checks:[{name:"basic",status:"NOT_CONFIRMED",reason:"PAGE_TEST_FAILED"}],request_count:0}});true'
    $null=Invoke-ModelJs $expression;$s.Testing=$true;$s.TestDeadline=[DateTime]::UtcNow.AddSeconds(210);$s.State='TESTING'
    return @{state='TESTING';observed=$s.Observed}
}
function Write-ModelReport {
    $s=$script:ModelSession
    if(-not $s -or -not $s.Report){return}
    $document=@{schema_version=1;created_at=[DateTime]::UtcNow.ToString('o');source='native-browser-check';observed=$s.Observed;result=$s.Report}
    $path=Join-Path $s.Run 'report.json'
    [IO.File]::WriteAllText($path,(Convert-ModelJson $document),[Text.UTF8Encoding]::new($false))
    $s.ReportPath=$path
}
function Stop-ModelBrowser {
    $s=$script:ModelSession;if(-not $s){return}
    $s.Running=$false
    if($s.Socket){
        try{if($s.Socket.State -eq [Net.WebSockets.WebSocketState]::Open){$null=Invoke-ModelJs 'window.FunnyWorkPOC?.stop();true';$null=Send-ModelCdp 'Browser.close' @{} 2000}}catch{}
        try{$s.Socket.Abort();$s.Socket.Dispose()}catch{}
    }
    if($s.Message){$s.Message.Dispose()}
    if($s.Browser){try{if(-not $s.Browser.HasExited){$null=$s.Browser.WaitForExit(5000)};if(-not $s.Browser.HasExited){$s.Browser.Kill();$null=$s.Browser.WaitForExit(3000)}}catch{}}
    $s.Cleanup='REMOVED'
    if($s.Profile -and [IO.Directory]::Exists($s.Profile)){
        try{[IO.Directory]::Delete($s.Profile,$true)}catch{$s.Cleanup='PROFILE_RETAINED'}
    }
    $s.State='STOPPED'
}
