#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$NoGui,
    [string]$ChatUrl='',
    [string]$AuthUrl='',
    [string]$BrowserPath='',
    [string]$WorkingRoot='',
    [string]$Checks='multi_turn,streaming,structured,tools',
    [switch]$HeadlessBrowser,
    [int]$TimeoutSeconds=240
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'enterprise-model-browser-engine.ps1')
if(-not $WorkingRoot){
    $WorkingRoot=Join-Path $PSScriptRoot '.funnywork-model-check'
    $repo=Join-Path $PSScriptRoot '..\..'
    if([IO.File]::Exists((Join-Path $repo 'AGENTS.md'))){$WorkingRoot=Join-Path $repo '.local\agent\model-browser-check'}
}
$selected=@($Checks.Split(',')|Where-Object {$_}|Select-Object -Unique)
foreach($name in $selected){if($name -notin @('multi_turn','streaming','structured','tools')){throw 'INVALID_CHECKS'}}
if($TimeoutSeconds -lt 30 -or $TimeoutSeconds -gt 1800){throw 'INVALID_TIMEOUT'}
if($NoGui){
    $code=1
    try{
        $null=Start-ModelBrowser $ChatUrl $AuthUrl $BrowserPath $WorkingRoot $selected $true -Headless:$HeadlessBrowser
        $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds);$last=''
        while([DateTime]::UtcNow -lt $deadline){
            try{$update=Update-ModelBrowser}catch{if((Get-ModelCheckCode $_)-in @('PAGE_OPERATION_FAILED','BROWSER_COMMAND_FAILED')){Start-Sleep -Milliseconds 500;continue};throw}
            if($update.state -ne $last){[Console]::WriteLine($update.state);$last=$update.state}
            if($update.state -in @('COMPLETE','ADAPTER_REQUIRED')){$code=0;if(@($update.report.checks|Where-Object {$_['status'] -ne 'PASS'}).Count -gt 0){$code=2};break}
            Start-Sleep -Milliseconds 300
        }
        if($last -notin @('COMPLETE','ADAPTER_REQUIRED')){Throw-ModelCheck 'TEST_TIMEOUT'}
    }catch{[Console]::Error.WriteLine((Get-ModelCheckCode $_))}
    finally{Stop-ModelBrowser;if($script:ModelSession){[Console]::WriteLine('Cleanup: '+$script:ModelSession.Cleanup)}}
    exit $code
}
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){[Console]::Error.WriteLine('WINDOWS_GUI_REQUIRED');exit 1}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$script:TextMap=Read-ModelJson ([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'enterprise-model-browser-text.json')))
$script:Locale=0;if(-not [Globalization.CultureInfo]::CurrentUICulture.Name.StartsWith('zh')){$script:Locale=1}
function Text-Model([string]$Key){if($script:TextMap.ContainsKey($Key)){return [string]$script:TextMap[$Key][$script:Locale]};if($Key -cmatch '^[A-Z][A-Z0-9_]+$'){return [string]$script:TextMap['LOCAL_OPERATION_FAILED'][$script:Locale]};return $Key}
$form=[Windows.Forms.Form]::new();$form.Width=820;$form.Height=710;$form.MinimumSize=[Drawing.Size]::new(820,710);$form.StartPosition='CenterScreen';$form.BackColor=[Drawing.ColorTranslator]::FromHtml('#F8F6F1');$form.Font=[Drawing.Font]::new('Segoe UI',10)
$script:Labels=@{}
function Add-ModelLabel([string]$Key,[int]$X,[int]$Y,[int]$Width=740){$c=[Windows.Forms.Label]::new();$c.Location=[Drawing.Point]::new($X,$Y);$c.Size=[Drawing.Size]::new($Width,25);$c.Text=Text-Model $Key;$form.Controls.Add($c);$script:Labels[$Key]=$c;return $c}
$null=Add-ModelLabel 'brand' 28 19 250
$title=Add-ModelLabel 'title' 28 55;$title.Font=[Drawing.Font]::new('Segoe UI',20,[Drawing.FontStyle]::Bold);$title.Height=40
$null=Add-ModelLabel 'auth' 28 112
$authBox=[Windows.Forms.TextBox]::new();$authBox.Location=[Drawing.Point]::new(28,140);$authBox.Size=[Drawing.Size]::new(742,30);$authBox.Text=$AuthUrl;$form.Controls.Add($authBox)
$null=Add-ModelLabel 'chat' 28 184
$chatBox=[Windows.Forms.TextBox]::new();$chatBox.Location=[Drawing.Point]::new(28,212);$chatBox.Size=[Drawing.Size]::new(742,30);$chatBox.Text=$ChatUrl;$form.Controls.Add($chatBox)
$null=Add-ModelLabel 'scope' 28 258 120
$checkBoxes=@{};$x=155
foreach($key in @('multi_turn','streaming','structured','tools')){$box=[Windows.Forms.CheckBox]::new();$box.Location=[Drawing.Point]::new($x,258);$box.Size=[Drawing.Size]::new(145,26);$box.Text=Text-Model $key;$box.Checked=$selected -contains $key;$form.Controls.Add($box);$checkBoxes[$key]=$box;$x+=145}
function Add-ModelButton([string]$Key,[int]$X,[int]$Width){$b=[Windows.Forms.Button]::new();$b.Location=[Drawing.Point]::new($X,300);$b.Size=[Drawing.Size]::new($Width,38);$b.FlatStyle='Flat';$b.Text=Text-Model $Key;$form.Controls.Add($b);$script:Labels[$Key]=$b;return $b}
$start=Add-ModelButton 'start' 28 150;$start.BackColor=[Drawing.ColorTranslator]::FromHtml('#292724');$start.ForeColor=[Drawing.Color]::White
$login=Add-ModelButton 'login' 190 120;$openChat=Add-ModelButton 'chatButton' 322 130;$stop=Add-ModelButton 'stop' 464 110
$login.Enabled=$false;$openChat.Enabled=$false;$stop.Enabled=$false
$cost=Add-ModelLabel 'cost' 28 347;$cost.ForeColor=[Drawing.ColorTranslator]::FromHtml('#706B64');$cost.Font=[Drawing.Font]::new('Segoe UI',9)
$statusLabel=Add-ModelLabel 'IDLE' 28 383;$statusLabel.Height=42
$table=[Windows.Forms.ListView]::new();$table.Location=[Drawing.Point]::new(28,431);$table.Size=[Drawing.Size]::new(742,148);$table.View='Details';$table.FullRowSelect=$true;$table.GridLines=$false;$table.BackColor=[Drawing.ColorTranslator]::FromHtml('#FFFDFA');$table.BorderStyle='FixedSingle';$null=$table.Columns.Add((Text-Model 'item'),170);$null=$table.Columns.Add((Text-Model 'result'),110);$null=$table.Columns.Add((Text-Model 'details'),430);$form.Controls.Add($table)
$reportButton=[Windows.Forms.Button]::new();$reportButton.Location=[Drawing.Point]::new(28,599);$reportButton.Size=[Drawing.Size]::new(210,34);$reportButton.Text=Text-Model 'openReport';$reportButton.Enabled=$false;$form.Controls.Add($reportButton)
$language=[Windows.Forms.ComboBox]::new();$language.Location=[Drawing.Point]::new(649,18);$language.Size=[Drawing.Size]::new(120,28);$language.DropDownStyle='DropDownList';$null=$language.Items.Add('中文');$null=$language.Items.Add('English');$language.SelectedIndex=$script:Locale;$form.Controls.Add($language)
$script:UiState='IDLE';$script:PollBusy=$false;$script:Finished=$false
function Refresh-ModelUi {
    $form.Text='FunnyWork - '+(Text-Model 'title')
    foreach($key in $script:Labels.Keys){if($key -ne 'IDLE'){$script:Labels[$key].Text=Text-Model $key}}
    foreach($key in $checkBoxes.Keys){$checkBoxes[$key].Text=Text-Model $key}
    $statusLabel.Text=Text-Model $script:UiState;$reportButton.Text=Text-Model 'openReport'
    $table.Columns[0].Text=Text-Model 'item';$table.Columns[1].Text=Text-Model 'result';$table.Columns[2].Text=Text-Model 'details'
    if($script:ModelSession -and $script:ModelSession.Report){$items=@($script:ModelSession.Report.checks);for($i=0;$i -lt [Math]::Min($items.Count,$table.Items.Count);$i++){$table.Items[$i].Text=Text-Model $items[$i]['name'];$table.Items[$i].SubItems[1].Text=Text-Model $items[$i]['status'];if($items[$i]['reason']){$table.Items[$i].SubItems[2].Text=Text-Model $items[$i]['reason']}}}
}
function Set-ModelState([string]$State){$script:UiState=$State;$statusLabel.Text=Text-Model $State}
$timer=[Windows.Forms.Timer]::new();$timer.Interval=1000
$start.Add_Click({
    if($script:PollBusy){return};$script:PollBusy=$true;$start.Enabled=$false;Set-ModelState 'STARTING';$table.Items.Clear();$reportButton.Enabled=$false;[Windows.Forms.Application]::DoEvents()
    try{
        $names=@(@('multi_turn','streaming','structured','tools')|Where-Object {$checkBoxes[$_].Checked})
        $null=Start-ModelBrowser $chatBox.Text.Trim() $authBox.Text.Trim() $BrowserPath $WorkingRoot $names $true
        $login.Enabled=[bool]$authBox.Text.Trim();$openChat.Enabled=$true;$stop.Enabled=$true;$script:Finished=$false;$timer.Start();Set-ModelState 'WAITING_CHAT'
    }catch{Set-ModelState (Get-ModelCheckCode $_);$start.Enabled=$true}
    finally{$script:PollBusy=$false}
})
$login.Add_Click({try{Open-ModelPage 'auth'}catch{Set-ModelState (Get-ModelCheckCode $_)}})
$openChat.Add_Click({try{Open-ModelPage 'chat'}catch{Set-ModelState (Get-ModelCheckCode $_)}})
$stop.Add_Click({$timer.Stop();Stop-ModelBrowser;$start.Enabled=($script:ModelSession.Cleanup -eq 'REMOVED');$login.Enabled=$false;$openChat.Enabled=$false;Set-ModelState $script:ModelSession.Cleanup;if($script:ModelSession.Cleanup -eq 'REMOVED'){Set-ModelState 'STOPPED';$stop.Enabled=$false}})
$reportButton.Add_Click({if($script:ModelSession.ReportPath){Start-Process -FilePath 'explorer.exe' -ArgumentList @('/select,',('"'+$script:ModelSession.ReportPath+'"'))}})
$language.Add_SelectedIndexChanged({$script:Locale=$language.SelectedIndex;Refresh-ModelUi})
$timer.Add_Tick({
    if($script:PollBusy -or $script:Finished){return};$script:PollBusy=$true
    try{
        $update=Update-ModelBrowser;Set-ModelState $update.state
        if($update.observed){$identified=@($update.observed|Where-Object {@($_['model_ids']).Count -gt 0})|Select-Object -First 1;if($identified){$statusLabel.Text+=("`r`n"+(@($identified['model_ids']) -join ', '))}}
        if($update.state -in @('COMPLETE','ADAPTER_REQUIRED')){
            $table.Items.Clear();foreach($item in $update.report.checks){$row=[Windows.Forms.ListViewItem]::new((Text-Model $item['name']));$null=$row.SubItems.Add((Text-Model $item['status']));$detail='';if($item['reason']){$detail=Text-Model $item['reason']};$null=$row.SubItems.Add($detail);$null=$table.Items.Add($row)}
            $reportButton.Enabled=$true;$login.Enabled=$false;$openChat.Enabled=$false;$script:Finished=$true;$timer.Stop()
        }
    }catch{Set-ModelState (Get-ModelCheckCode $_)}finally{$script:PollBusy=$false}
})
$form.Add_FormClosing({$timer.Stop();Stop-ModelBrowser})
Refresh-ModelUi
try{[void]$form.ShowDialog()}finally{$timer.Dispose();$form.Dispose();Stop-ModelBrowser}
