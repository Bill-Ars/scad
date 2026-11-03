# =====================================================================
#  SCAD - Testes do Two-Phase Commit (Fase 4.3)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-2pc.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function Compra($json) {
    Invoke-RestMethod -Method Post -Uri http://localhost:8081/compras/multi `
        -ContentType 'application/json' -Body $json
}
function Ordens($db) {
    Write-Host "--- ordens em $db ---" -ForegroundColor Yellow
    docker compose exec -T $db psql -U postgres -d scad -c "SELECT id, regiao, quantidade, estado FROM ordem_compra ORDER BY criada_em;"
}
function Pendentes($porta, $nome) {
    Write-Host "--- transacoes preparadas a espera em $nome ---" -ForegroundColor Yellow
    $p = Invoke-RestMethod http://localhost:$porta/tx/pendentes
    if ($p.Count -eq 0) { Write-Host "(nenhuma)" } else { $p | Format-Table -AutoSize }
}

# ---------------------------------------------------------------------
Titulo "TESTE 1 - COMMIT: Maputo e Beira votam SIM"
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":40}}'
$r | ConvertTo-Json -Depth 5
Ordens db-maputo
Ordens db-beira
Write-Host "Esperado: a MESMA ordem aparece nas duas bases." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 2"

# ---------------------------------------------------------------------
Titulo "TESTE 2 - ABORT: a Beira vota NAO (quantidade 0 viola o CHECK)"
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":0}}'
$r | ConvertTo-Json -Depth 5
Ordens db-maputo
Write-Host "Esperado: decisao ABORT e NENHUMA ordem nova em Maputo (atomicidade)." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 3"

# ---------------------------------------------------------------------
Titulo "TESTE 3 - ABORT: o agente da Beira esta em falha"
docker compose stop agente-beira
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":40}}'
$r | ConvertTo-Json -Depth 5
Write-Host "Esperado: Beira = NAO (sem resposta) e decisao ABORT." -ForegroundColor Green
docker compose start agente-beira
Write-Host "A aguardar 45 s que o agente da Beira volte a arrancar..."
Start-Sleep -Seconds 45
Read-Host "Carrega em Enter para o teste 4"

# ---------------------------------------------------------------------
Titulo "TESTE 4 - O coordenador falha entre a fase 1 e a fase 2 (bloqueio)"
$r = Compra '{"produtoId":"P1","fornecedorId":"F1","quantidades":{"Maputo":10,"Beira":20},"simularFalhaCoordenador":true}'
$r | ConvertTo-Json -Depth 5
Pendentes 8081 "Maputo"
Pendentes 8082 "Beira"
Ordens db-maputo
Write-Host "Esperado: decisao COMMIT gravada, mas a fase 2 NAO correu." -ForegroundColor Green
Write-Host "Os participantes ficam BLOQUEADOS com a transacao preparada e a ordem P1 ainda nao aparece." -ForegroundColor Green
Read-Host "Carrega em Enter para o coordenador RECUPERAR a partir do log"

$rec = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/compras/multi/$($r.transacao)/recuperar"
$rec | ConvertTo-Json -Depth 5
Pendentes 8081 "Maputo"
Pendentes 8082 "Beira"
Ordens db-maputo
Ordens db-beira
Write-Host "Esperado: decisao registada = COMMIT, pendentes vazias e a ordem P1 visivel nas duas bases." -ForegroundColor Green

Titulo "FIM DOS TESTES DO 2PC"
