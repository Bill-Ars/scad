# =====================================================================
#  SCAD - Testes do CORBA / JacORB (Fase 6)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-corba.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function Consulta($produto) {
    docker compose exec -T auditoria sh /app/run.sh mz.ac.isutc.scad.corba.ConsultaAuditoria $produto 2>$null |
        Select-String -NotMatch "INFO|WARN|org.jacorb|^\s*$"
}

# ---------------------------------------------------------------------
Titulo "TESTE 1 - Registo no Naming Service e alertas iniciais"
docker compose logs auditoria robo-maputo robo-beira robo-nampula 2>$null |
    Select-String "\[AUDITORIA\]|\[ROBO"
Write-Host "Esperado: auditoria e 3 robos registados; so ha alertas se algum stock estiver no minimo." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 2"

# ---------------------------------------------------------------------
Titulo "TESTE 2 - A auditoria consulta o saldo de P2 em cada robo (consultarSaldo)"
Consulta P2
Write-Host "Esperado: um saldo por regiao, com a latencia de cada chamada CORBA/IIOP." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 3"

# ---------------------------------------------------------------------
Titulo "TESTE 3 - Novo alerta: o stock de P1 em Nampula desce abaixo do minimo"
docker compose exec -T db-nampula psql -U postgres -d scad -c "UPDATE stock SET quantidade = 20 WHERE produto_id = 'P1';"
Write-Host "A aguardar 12 s (o robo verifica o stock de 10 em 10 s)..."
Start-Sleep -Seconds 12
docker compose logs --since 30s auditoria robo-nampula 2>$null | Select-String "\[AUDITORIA\]|\[ROBO"
Write-Host "Esperado: o robo de Nampula chama dispararAlerta (oneway) e a auditoria regista P1; o agente tambem compra sozinho." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 4"

# ---------------------------------------------------------------------
Titulo "TESTE 4 - Falha de um robo: a auditoria continua a falar com os outros"
docker compose stop robo-beira
Consulta P2
Write-Host "Esperado: Beira INDISPONIVEL; Maputo e Nampula respondem." -ForegroundColor Green
docker compose start robo-beira
docker compose exec -T db-nampula psql -U postgres -d scad -c "UPDATE stock SET quantidade = 70 WHERE produto_id = 'P1';" | Out-Null

Titulo "FIM DOS TESTES DO CORBA"
