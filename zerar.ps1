# Recomeca o SCAD do zero: APAGA ordens, faturas, auditoria, clientes, produtos e stock, reconstroi as imagens
# e volta a arrancar tudo VAZIO: so ficam os 3 armazens e os 2 fornecedores.
# Os produtos, o stock inicial e os stocks minimos registam-se depois no sistema (pagina Produtos).
#
# Uso:  powershell -ExecutionPolicy Bypass -File .\zerar.ps1
#       powershell -ExecutionPolicy Bypass -File .\zerar.ps1 -Demo    (carrega 4 produtos de exemplo, usados pelos scripts de testes/)
param([switch]$Demo)

Set-Location $PSScriptRoot

Write-Host "A apagar contentores e dados..."
docker compose down -v
if ($LASTEXITCODE -ne 0) { Write-Host "Falhou: docker compose down -v"; exit 1 }

Write-Host "A reconstruir e arrancar..."
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Write-Host "Falhou: docker compose up"; exit 1 }

Write-Host "A esperar pelos agentes..."
foreach ($porta in 8081, 8082, 8083) {
    $pronto = $false
    for ($i = 0; $i -lt 90 -and -not $pronto; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$porta/login.html" | Out-Null
            $pronto = $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if (-not $pronto) { Write-Host "O agente na porta $porta nao respondeu"; exit 1 }
}

if ($Demo) {
    Write-Host "A carregar os produtos de demonstracao..."
    foreach ($r in 'maputo', 'beira', 'nampula') {
        Get-Content -Raw -Encoding UTF8 ".\db\demo\$r.sql" | docker compose exec -T "db-$r" psql -U postgres -d scad -q -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { Write-Host "Falhou a carregar os dados de demonstracao em $r"; exit 1 }
    }
}

Write-Host "Pronto. Sistema: http://localhost:8081 (gestao: senha admin1234)"
if (-not $Demo) { Write-Host "Os armazens estao vazios: registe os produtos, o stock inicial e os minimos em Produtos." }
