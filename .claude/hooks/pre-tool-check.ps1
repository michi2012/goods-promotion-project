$data = [Console]::In.ReadToEnd()

try {
    $command = ($data | ConvertFrom-Json).tool_input.command
} catch {
    $command = $data
}

if ($command -match 'git\s+push.{0,20}(--force|-f\b)') {
    exit 2
}
if ($command -match 'mysql' -and $command -match 'DROP\s+(TABLE|DATABASE|COLUMN)') {
    exit 2
}
exit 0
