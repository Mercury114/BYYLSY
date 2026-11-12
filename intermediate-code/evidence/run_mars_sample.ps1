$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$marsJar = Join-Path $env:TEMP 'Mars-Compile-2021.jar'
$runRoot = Join-Path $PSScriptRoot 'mars-sample-run'
$classes = Join-Path $runRoot 'classes'

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if (-not (Test-Path -LiteralPath $marsJar)) {
    throw "Course MARS jar was not found: $marsJar"
}

New-Item -ItemType Directory -Path $classes -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'tests/sample-testfile.txt') `
    -Destination (Join-Path $runRoot 'testfile.txt')

Write-Host 'Compile source program and generate MIPS'
javac --release 8 -encoding UTF-8 -d $classes (Join-Path $projectRoot 'src/Compiler.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Push-Location $runRoot
try {
    java -cp $classes Compiler
    if ($LASTEXITCODE -ne 0) { throw 'Compiler failed' }
    Write-Host "Generated mips.txt ($((Get-Item 'mips.txt').Length) bytes)"
    Write-Host 'Run mips.txt in course MARS 4.5'
    Get-Content -LiteralPath (Join-Path $projectRoot 'tests/sample-stdin.txt') -Raw |
        java -jar $marsJar nc sm mips.txt
    if ($LASTEXITCODE -ne 0) { throw "MARS exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
