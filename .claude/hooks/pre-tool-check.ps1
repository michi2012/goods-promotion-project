$data = [Console]::In.ReadToEnd()
if ($data -match 'push.{0,20}(--force|-f\b)|DROP\s+TABLE|DROP\s+DATABASE|DROP\s+COLUMN') {
    exit 2
}
exit 0
