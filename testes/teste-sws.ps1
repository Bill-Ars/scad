# =====================================================================
#  SCAD - Testes dos Servicos Web: REST/JSON e SOAP/XML (Fase 8)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-sws.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function PostJson($url, $json) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $url -ContentType 'application/json' -Body $json
        Write-Host "HTTP $($r.StatusCode)" -ForegroundColor Yellow
        $r.Content
    } catch {
        $resp = $_.Exception.Response
        Write-Host "HTTP $([int]$resp.StatusCode)" -ForegroundColor Yellow
        (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
}
function Enviar($json) {
    Invoke-RestMethod -Method Post -Uri http://localhost:8081/fornecedores/enviar -ContentType 'application/json' -Body $json -TimeoutSec 60
}

# ---------------------------------------------------------------------
Titulo "TESTE 1 - Contrato SOAP: WSDL publicado pelo fornecedor legado"
(Invoke-WebRequest -UseBasicParsing "http://localhost:8092/ws?wsdl").Content -split "`n" | Select-Object -First 25
Read-Host "Carrega em Enter para o teste 2"

# ---------------------------------------------------------------------
Titulo "TESTE 2 - REST direto: codigos HTTP e idempotencia"
Write-Host "a) ordem nova -> esperado 201 Created"
PostJson http://localhost:8091/api/ordens '{"ordemId":"TESTE-1","produtoId":"P2","quantidade":90,"regiao":"Maputo"}'
Write-Host "b) MESMA ordem repetida -> esperado 200 OK (nao duplica)"
PostJson http://localhost:8091/api/ordens '{"ordemId":"TESTE-1","produtoId":"P2","quantidade":90,"regiao":"Maputo"}'
Write-Host "c) quantidade 0 -> esperado 400 Bad Request"
PostJson http://localhost:8091/api/ordens '{"ordemId":"TESTE-2","produtoId":"P2","quantidade":0,"regiao":"Maputo"}'
Read-Host "Carrega em Enter para o teste 3"

# ---------------------------------------------------------------------
Titulo "TESTE 3 - O agente de Maputo envia ordens pelas DUAS rotas"
Write-Host "--- F1 = Distribuidora Moderna (REST/JSON) ---" -ForegroundColor Yellow
$r = Enviar '{"fornecedorId":"F1","produtoId":"P2","quantidade":90}'
$r | Select-Object ordemId, fornecedor, rota, estado, tentativas | Format-List
Write-Host "Pedido JSON enviado:"; $r.pedidoEnviado
Write-Host "Resposta:"; $r.resposta | Format-List

Write-Host "--- F2 = Grossista Legado (SOAP/XML) ---" -ForegroundColor Yellow
$r = Enviar '{"fornecedorId":"F2","produtoId":"P4","quantidade":120}'
$r | Select-Object ordemId, fornecedor, rota, estado, tentativas | Format-List
Write-Host "Envelope SOAP enviado:"; $r.pedidoEnviado
Write-Host "Envelope SOAP recebido:"; $r.resposta.respostaXml
Read-Host "Carrega em Enter para o teste 4"

# ---------------------------------------------------------------------
Titulo "TESTE 4 - SOAP Fault: ordem invalida para o fornecedor legado"
$r = Enviar '{"fornecedorId":"F2","produtoId":"P4","quantidade":0}'
$r | Select-Object ordemId, rota, estado, tentativas | Format-List
Write-Host "Esperado: estado REJEITADA com 'SOAP FAULT quantidade tem de ser positiva' (sem novas tentativas)." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 5"

# ---------------------------------------------------------------------
Titulo "TESTE 5 - Fornecedor REST indisponivel: novas tentativas e idempotencia"
docker compose stop fornecedor-rest
$r = Enviar '{"ordemId":"ORD-RETRY-1","fornecedorId":"F1","produtoId":"P1","quantidade":40}'
$r | Select-Object ordemId, rota, estado, tentativas | Format-List
Write-Host "Esperado: 3 tentativas FALHOU (espera 1 s, 2 s) e estado FALHOU." -ForegroundColor Green
docker compose start fornecedor-rest
Write-Host "A aguardar 30 s que o fornecedor volte..."
Start-Sleep -Seconds 30
Write-Host "Reenvio da MESMA ordem:" -ForegroundColor Yellow
$r = Enviar '{"ordemId":"ORD-RETRY-1","fornecedorId":"F1","produtoId":"P1","quantidade":40}'
$r | Select-Object ordemId, estado | Format-List; $r.resposta | Format-List
Write-Host "Reenvio outra vez (simula uma repeticao depois de timeout):" -ForegroundColor Yellow
$r = Enviar '{"ordemId":"ORD-RETRY-1","fornecedorId":"F1","produtoId":"P1","quantidade":40}'
$r.resposta | Select-Object ordemId, estado, httpStatus | Format-List
Write-Host "Esperado: 1.o reenvio httpStatus 201; 2.o reenvio httpStatus 200 = a ordem NAO foi duplicada." -ForegroundColor Green

Titulo "FIM DOS TESTES DOS SERVICOS WEB"
