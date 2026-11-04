# =====================================================================
#  SCAD - Teste de integracao: compra automatica de ponta a ponta (Fase 9)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-integracao.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function Automatica($porta, $produto) {
    Start-Job -ArgumentList $porta, $produto -ScriptBlock {
        param($p, $prod)
        Invoke-RestMethod -Method Post -TimeoutSec 120 -Uri "http://localhost:$p/compras/automatica?produtoId=$prod"
    }
}
function Mostrar($r) {
    Write-Host ""
    Write-Host ">>> No $($r.no): $($r.resultado)" -ForegroundColor Yellow
    $r.passos | ForEach-Object { Write-Host "    $_" }
}

Invoke-RestMethod -Method Post http://localhost:8081/relogio/sincronizar | Out-Null

# ---------------------------------------------------------------------
Titulo "CENARIO 1 - Maputo e Beira detetam ao MESMO tempo falta de P2 (fornecedor F1, REST)"
$j1 = Automatica 8081 P2
$j2 = Automatica 8082 P2
$res = @($j1, $j2) | Wait-Job | Receive-Job
Remove-Job $j1, $j2
$res | ForEach-Object { Mostrar $_ }
Write-Host ""
Write-Host "Esperado: UM no faz a compra completa (2PC + REST + fatura); o outro entra depois na" -ForegroundColor Green
Write-Host "seccao critica, ve que o lote ja foi encomendado e NAO duplica." -ForegroundColor Green
Read-Host "Carrega em Enter para ver as provas"

Titulo "PROVAS DO CENARIO 1"
Write-Host "--- ordens P2 em cada armazem (2PC) ---" -ForegroundColor Yellow
docker compose exec -T db-maputo psql -U postgres -d scad -c "SELECT id, regiao, quantidade, criada_em FROM ordem_compra WHERE produto_id='P2' ORDER BY criada_em DESC LIMIT 2;"
docker compose exec -T db-beira  psql -U postgres -d scad -c "SELECT id, regiao, quantidade, criada_em FROM ordem_compra WHERE produto_id='P2' ORDER BY criada_em DESC LIMIT 2;"
Write-Host "--- ordens recebidas pelo fornecedor REST ---" -ForegroundColor Yellow
Invoke-RestMethod http://localhost:8091/api/ordens | Format-Table ordemId, produtoId, quantidade, regiao, estado -AutoSize
Write-Host "--- faturas no HDFS ---" -ForegroundColor Yellow
Invoke-RestMethod "http://localhost:8083/dfs/listar?dir=/scad/faturas" | Format-Table pathSuffix, length, replication -AutoSize
Write-Host "--- auditoria de Maputo (hora sincronizada por Berkeley) ---" -ForegroundColor Yellow
docker compose exec -T db-maputo psql -U postgres -d scad -c "SELECT hora, operacao, left(detalhe, 60) AS detalhe FROM auditoria ORDER BY id DESC LIMIT 6;"
Read-Host "Carrega em Enter para o cenario 2"

# ---------------------------------------------------------------------
Titulo "CENARIO 2 - Nampula: falta de P4 (fornecedor F2, SOAP/XML)"
$r = Invoke-RestMethod -Method Post -TimeoutSec 120 -Uri "http://localhost:8083/compras/automatica?produtoId=P4"
Mostrar $r
Write-Host "Esperado: compra concluida pela rota SOAP e fatura no HDFS." -ForegroundColor Green

Titulo "FIM DO TESTE DE INTEGRACAO"
