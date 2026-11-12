# Helpers to drive the app over adb: dump the screen, list texts, tap or long-press a text.
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Dump { & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; (& $adb shell cat /sdcard/ui.xml) -join "`n" }
function Texts { [regex]::Matches((Dump), 'text="([^"]+)"') | ForEach-Object { [System.Net.WebUtility]::HtmlDecode($_.Groups[1].Value) } }
function Center($text) {
    $m = [regex]::Match((Dump), 'text="' + [regex]::Escape($text) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { throw "not on screen: $text" }
    @((([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2), (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
}
function Tap($text) { $c = Center $text; & $adb shell input tap $c[0] $c[1] }
function LongPress($text) { $c = Center $text; & $adb shell input swipe $c[0] $c[1] $c[0] $c[1] 900 }
