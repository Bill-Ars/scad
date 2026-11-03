# =====================================================================
#  SCAD - Testes de SOD: Berkeley e Ricart-Agrawala (Fase 5)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-sod.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function Relogios {
    8081, 8082, 8083 | ForEach-Object { Invoke-RestMethod "http://localhost:$_/relogio" } |
        Format-Table regiao, hora, desvioMs, mestreBerkeley -AutoSize
}
function DemoRA($porta) {
    Start-Job -ArgumentList $porta -ScriptBlock {
        param($p)
        Invoke-RestMethod -Method Post -TimeoutSec 90 -Uri "http://localhost:$p/ra/demo?recurso=F1:P2&segundos=5"
    }
}
function MostrarRA($resultados) {
    $resultados | Sort-Object entrouEm |
        Format-Table regiao, lamportTs, pedidoEm, entrouEm, saiuEm, esperouMs, replyAdiadoEnviadoA -AutoSize
    $resultados | ForEach-Object {
        if ($_.ignorados.Count -gt 0) { Write-Host "$($_.regiao) ignorou: $($_.ignorados -join ', ')" -ForegroundColor Yellow }
    }
}

# ---------------------------------------------------------------------
Titulo "TESTE 1 - BERKELEY: relogios desacertados"
Invoke-RestMethod -Method Post http://localhost:8081/relogio/desacertar | Out-Null
Invoke-RestMethod -Method Post http://localhost:8082/relogio/desacertar | Out-Null
Invoke-RestMethod -Method Post http://localhost:8083/relogio/desacertar | Out-Null
Relogios
Write-Host "Maputo (mestre) 0 s, Beira +25 s, Nampula -10 s." -ForegroundColor Green
Read-Host "Carrega em Enter para o mestre (Maputo) correr o algoritmo de Berkeley"

$b = Invoke-RestMethod -Method Post http://localhost:8081/relogio/sincronizar
$b | ConvertTo-Json -Depth 4
Relogios
Write-Host "Esperado: media das diferencas ~ +5000 ms; ajustes ~ Maputo +5000, Beira -20000, Nampula +15000." -ForegroundColor Green
Write-Host "Depois da ronda, os desvioMs dos tres nos ficam praticamente iguais (~ +5000)." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 2"

# ---------------------------------------------------------------------
Titulo "TESTE 2 - RICART-AGRAWALA: Maputo e Beira querem o lote F1:P2 ao mesmo tempo"
$j1 = DemoRA 8081
$j2 = DemoRA 8082
$res = @($j1, $j2) | Wait-Job | Receive-Job
Remove-Job $j1, $j2
MostrarRA $res
Write-Host "Esperado: os intervalos [entrouEm, saiuEm] NAO se sobrepoem." -ForegroundColor Green
Write-Host "Um dos nos esperou ~5000 ms e o outro aparece em 'replyAdiadoEnviadoA'." -ForegroundColor Green
Write-Host "--- mensagens trocadas (logs) ---" -ForegroundColor Yellow
docker compose logs --since 1m agente-maputo agente-beira agente-nampula | Select-String "\[RA F1:P2\]"
Read-Host "Carrega em Enter para o teste 3"

# ---------------------------------------------------------------------
Titulo "TESTE 3 - RICART-AGRAWALA com o no de Nampula EM FALHA"
docker compose stop agente-nampula
Write-Host "A aguardar 8 s para o heartbeat detetar a falha..."
Start-Sleep -Seconds 8
$j1 = DemoRA 8081
$j2 = DemoRA 8082
$res = @($j1, $j2) | Wait-Job | Receive-Job
Remove-Job $j1, $j2
MostrarRA $res
Write-Host "Esperado: exclusao mutua continua a funcionar; Nampula aparece como ignorado (em falha)." -ForegroundColor Green
docker compose start agente-nampula

Titulo "FIM DOS TESTES DE SOD"
