# Windows 로그인 사용자의 DPAPI로 인증 자료를 암호화합니다.
# 비밀 값은 명령행 인수가 아니라 표준 입력으로 받고 Node 자식 프로세스에만 반환합니다.
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try {
    Add-Type -AssemblyName System.Security
    $taskRequest = [Console]::In.ReadToEnd() | ConvertFrom-Json
    if ($taskRequest.operation -eq 'encrypt') {
        $taskBytes = [System.Text.Encoding]::UTF8.GetBytes([string]$taskRequest.data)
        $taskEncrypted = [System.Security.Cryptography.ProtectedData]::Protect(
            $taskBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Console]::Out.Write([Convert]::ToBase64String($taskEncrypted))
    } elseif ($taskRequest.operation -eq 'decrypt') {
        $taskBytes = [Convert]::FromBase64String([string]$taskRequest.data)
        $taskPlain = [System.Security.Cryptography.ProtectedData]::Unprotect(
            $taskBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Console]::Out.Write([System.Text.Encoding]::UTF8.GetString($taskPlain))
    } else {
        throw 'Unsupported operation'
    }
} catch {
    # 예외 원문이나 입력을 출력하면 인증 토큰이 노출될 수 있으므로 고정 문구만 사용합니다.
    [Console]::Error.Write('Windows credential protection failed.')
    exit 1
}
