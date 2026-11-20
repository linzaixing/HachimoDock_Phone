﻿﻿﻿﻿﻿﻿﻿﻿﻿# 用 Windows 自带的中文语音识别引擎（System.Speech）把一段 WAV 转成文字。
# 由 bridge/src/server.mjs 调用，输出一行 JSON：{"ok":true,"text":"..."} 或 {"ok":false,"error":"..."}
# 需要系统已安装「中文(简体)」语音识别引擎（Windows 设置 → 时间和语言 → 语音 → 语音语言）。

param([Parameter(Mandatory = $true)][string]$Path)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Result([bool]$ok, [string]$text, [string]$errorText) {
  $obj = [ordered]@{ ok = $ok; text = $text; error = $errorText }
  Write-Output ($obj | ConvertTo-Json -Compress)
}

try {
  Add-Type -AssemblyName System.Speech
  $info = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() |
    Where-Object { $_.Culture.Name -like 'zh*' } |
    Select-Object -First 1
  if ($null -eq $info) {
    Write-Result $false '' '这台 PC 没有中文语音识别引擎'
    exit 0
  }

  $engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine($info)
  $engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
  $engine.SetInputToWaveFile($Path)
  $result = $engine.Recognize()
  $engine.Dispose()

  if ($null -eq $result -or [string]::IsNullOrWhiteSpace($result.Text)) {
    Write-Result $false '' '没听清'
  }
  else {
    Write-Result $true $result.Text ''
  }
}
catch {
  Write-Result $false '' $_.Exception.Message
}
