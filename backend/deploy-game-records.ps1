$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\node_modules\.bin\wrangler.cmd d1 execute qlapp-db --remote --file=./migrations/0001_game_records.sql
    if ($LASTEXITCODE -ne 0) { throw 'Game record migration failed. Deployment stopped.' }
    & .\node_modules\.bin\wrangler.cmd deploy
    if ($LASTEXITCODE -ne 0) { throw 'Worker deployment failed.' }
} finally {
    Pop-Location
}
