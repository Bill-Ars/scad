# =====================================================================
#  SCAD - Demonstração 3: Two-Phase Commit (2PC) entre armazéns
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\demo\demo-3-2pc.ps1
#  Tenha o painel aberto em http://localhost:8081 (página "Compras ao fornecedor").
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Demonstração 3: 2PC"

function Cabecalho($n, $titulo, $conceito) {
    Write-Host ""
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    Write-Host " PASSO $n - $titulo" -ForegroundColor Cyan
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    Write-Host " Conceito: $conceito" -ForegroundColor Gray
    Write-Host ""
}
function Executar($comando) {
    Write-Host "PS> $comando" -ForegroundColor Yellow
    Invoke-Expression "& { $comando } | Out-Host"
}
function Esperado($texto) { Write-Host ""; Write-Host " ESPERADO: $texto" -ForegroundColor Green }
function Dizer($texto)    { Write-Host " DIZER:    `"$texto`"" -ForegroundColor Magenta }
function Pausa { Write-Host ""; Read-Host " [Enter] para continuar" | Out-Null }
function Info($t) { Write-Host " $t" -ForegroundColor DarkGray }

# Envia uma compra multi-armazém ao coordenador (agente de Maputo) e mostra o resultado
function Compra($json) {
    Write-Host "PS> Invoke-RestMethod -Method Post http://localhost:8081/compras/multi -ContentType 'application/json' -Body '$json'" -ForegroundColor Yellow
    $r = Invoke-RestMethod -Method Post -Uri http://localhost:8081/compras/multi -ContentType 'application/json' -Body $json
    Write-Host ""
    Write-Host "  coordenador : $($r.coordenador)"
    Write-Host "  transação   : $($r.transacao)"
    Write-Host "  ordem       : $($r.ordemId)"
    Write-Host "  FASE 1 (votos):" -ForegroundColor White
    $r.fase1_votos.PSObject.Properties | ForEach-Object { Write-Host ("     {0,-8} -> {1}" -f $_.Name, $_.Value) }
    $cor = if ($r.decisao -eq "COMMIT") { "Green" } else { "Red" }
    Write-Host "  DECISÃO     : $($r.decisao)" -ForegroundColor $cor
    if ($r.fase2_resultados) {
        Write-Host "  FASE 2 (execução):" -ForegroundColor White
        $r.fase2_resultados.PSObject.Properties | ForEach-Object { Write-Host ("     {0,-8} -> {1}" -f $_.Name, $_.Value) }
    } else {
        Write-Host "  FASE 2      : $($r.fase2)" -ForegroundColor Red
    }
    return $r
}
function Ordens($db, $ordemId) {
    Executar "docker compose exec -T $db psql -U postgres -d scad -c ""SELECT '$db' AS base, id, regiao, quantidade, estado FROM ordem_compra WHERE id LIKE '$ordemId%';"""
}
function Pendentes {
    foreach ($p in @(@{n="Maputo"; u=8081}, @{n="Beira"; u=8082})) {
        Write-Host "PS> Invoke-RestMethod http://localhost:$($p.u)/tx/pendentes" -ForegroundColor Yellow
        $l = @(Invoke-RestMethod "http://localhost:$($p.u)/tx/pendentes" | ForEach-Object { $_ } | Where-Object { $_.gid })
        if ($l.Count -eq 0) { Write-Host "   $($p.n): (nenhuma transação à espera)" }
        else { $l | ForEach-Object { Write-Host "   $($p.n): PREPARADA À ESPERA -> $($_.gid)" -ForegroundColor Red } }
    }
}
function EsperarBeira {
    Info "(a aguardar que o agente da Beira volte a responder...)"
    for ($i = 0; $i -lt 30; $i++) {
        try { Invoke-RestMethod http://localhost:8082/estado | Out-Null; break } catch { Start-Sleep -Seconds 2 }
    }
    Info "(mais 12 s para o heartbeat e a cache de DNS do Java atualizarem...)"
    Start-Sleep -Seconds 12
}

# ---------------------------------------------------------------------
Cabecalho 1 "Onde está o 2PC no código" "coordenador (quem inicia a compra) + participantes (cada armazém)."
Executar 'Select-String -Path docker-compose.yml -Pattern "max_prepared_transactions" | Select-Object -First 1 | ForEach-Object { $_.Line.Trim() }'
Write-Host ""
Executar 'Select-String -Path agente\src\main\java\mz\ac\isutc\scad\agente\CoordenadorController.java -Pattern "FASE 1|FASE 2|DECISAO|/tx/preparar" | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber, $_.Line.Trim() }'
Write-Host ""
Executar 'Select-String -Path agente\src\main\java\mz\ac\isutc\scad\agente\ParticipanteController.java -Pattern "PREPARE TRANSACTION|COMMIT PREPARED|ROLLBACK PREPARED|c.rollback" | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber, $_.Line.Trim() }'
Esperado "o coordenador faz a votação (fase 1), grava a decisão e executa-a (fase 2); o participante usa PREPARE TRANSACTION."
Dizer "O agente onde a compra começa é o coordenador. Os armazéns envolvidos são participantes. Cada participante usa o PREPARE TRANSACTION do PostgreSQL, que grava a transação em disco sem a confirmar."
Pausa

# ---------------------------------------------------------------------
Cabecalho 2 "COMMIT: todos votam SIM" "compra de P2 dividida entre Maputo (50) e Beira (40): ou entra nos dois, ou em nenhum."
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":40}}'
Write-Host ""
Ordens db-maputo $r.ordemId
Ordens db-beira  $r.ordemId
Esperado "votos SIM + SIM, decisão COMMIT, e a mesma ordem gravada nas DUAS bases (sufixos -MAPUTO e -BEIRA)."
Dizer "Os dois armazéns votaram SIM, o coordenador decidiu COMMIT e a ordem ficou gravada nas duas bases de dados, em dois nós diferentes. No painel, a tabela de ordens já mostra as duas linhas."
Pausa

# ---------------------------------------------------------------------
Cabecalho 3 "O registo do coordenador" "a decisão é gravada ANTES da fase 2: é o que permite recuperar depois de uma falha."
Executar "docker compose exec -T db-maputo psql -U postgres -d scad -c ""SELECT hora, operacao, detalhe FROM auditoria WHERE detalhe LIKE '$($r.transacao)%' ORDER BY id;"""
Esperado "2PC-INICIO, 2PC-DECISAO (COMMIT) e 2PC-FIM, por esta ordem."
Dizer "O coordenador regista o início, a decisão e o fim. A decisão fica gravada antes de enviar a ordem final, para não se perder se ele cair."
Pausa

# ---------------------------------------------------------------------
Cabecalho 4 "ABORT: um participante vota NÃO" "quantidade 0 na Beira viola o CHECK (quantidade > 0): a Beira vota NÃO."
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":0}}'
Write-Host ""
Ordens db-maputo $r.ordemId
Esperado "Maputo SIM, Beira NÃO, decisão ABORT; NENHUMA linha em Maputo (0 rows)."
Dizer "Maputo estava pronto, mas a Beira não conseguiu. Basta um NÃO para abortar tudo: Maputo desfaz a sua parte. É a atomicidade: tudo ou nada."
Pausa

# ---------------------------------------------------------------------
Cabecalho 5 "ABORT: um participante está em falha" "sem resposta conta como voto NÃO (timeout de 3 s)."
Executar 'docker compose stop agente-beira 2>$null'
$r = Compra '{"produtoId":"P2","fornecedorId":"F1","quantidades":{"Maputo":50,"Beira":40}}'
Write-Host ""
Ordens db-maputo $r.ordemId
Esperado "Beira = NÃO (sem resposta), decisão ABORT, nenhuma linha em Maputo."
Dizer "O agente da Beira está em baixo. O coordenador não fica à espera para sempre: passado o timeout, conta como NÃO e aborta. Não há compras pela metade."
Executar 'docker compose start agente-beira 2>$null'
EsperarBeira
Pausa

# ---------------------------------------------------------------------
Cabecalho 6 "O coordenador falha entre a fase 1 e a fase 2" "o problema clássico do 2PC: os participantes ficam BLOQUEADOS à espera da decisão."
$r = Compra '{"produtoId":"P1","fornecedorId":"F1","quantidades":{"Maputo":10,"Beira":20},"simularFalhaCoordenador":true}'
$tx = $r.transacao
Write-Host ""
Pendentes
Ordens db-maputo $r.ordemId
Esperado "decisão COMMIT gravada, mas fase 2 não executada; as duas transações estão PREPARADAS À ESPERA e a ordem ainda não é visível."
Dizer "Os participantes votaram SIM e já não podem desistir sozinhos, mas também não podem confirmar sem a ordem do coordenador. Ficam bloqueados. É a grande limitação do 2PC."
Pausa

# ---------------------------------------------------------------------
Cabecalho 7 "Recuperação do coordenador" "ao voltar, o coordenador lê a decisão no registo e termina a fase 2."
Write-Host "PS> Invoke-RestMethod -Method Post http://localhost:8081/compras/multi/$tx/recuperar" -ForegroundColor Yellow
$rec = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/compras/multi/$tx/recuperar"
Write-Host "  decisão registada: $($rec.decisaoRegistada)" -ForegroundColor Green
$rec.fase2_resultados.PSObject.Properties | ForEach-Object { Write-Host ("     {0,-8} -> {1}" -f $_.Name, $_.Value) }
Write-Host ""
Pendentes
Ordens db-maputo $r.ordemId
Ordens db-beira  $r.ordemId
Esperado "decisão registada = COMMIT, nenhuma transação à espera, e a ordem de P1 visível nas duas bases."
Dizer "O coordenador recuperou: leu a decisão COMMIT no seu registo e enviou-a aos participantes. Ninguém perdeu dados e as duas bases ficaram consistentes."
Pausa

# ---------------------------------------------------------------------
Cabecalho 8 "Limitações do 2PC" "bloqueio, coordenador único, 2 rondas de mensagens."
Info "- Bloqueante: se o coordenador cair depois do voto, os participantes esperam (os registos ficam trancados)."
Info "- Custo: 2 rondas de mensagens por participante (preparar + confirmar/abortar)."
Info "- Alternativas: 3PC (evita parte do bloqueio) ou consenso (Paxos/Raft); em microsserviços, o padrão Saga."
Dizer "O 2PC garante atomicidade entre nós, mas é bloqueante. Por isso registamos a decisão e temos a recuperação."

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " Uma compra que envolve vários armazéns é uma transação distribuída. Na fase 1, o"  -ForegroundColor Magenta
Write-Host " coordenador pergunta a cada armazém se pode fazer a sua parte; cada um prepara e"  -ForegroundColor Magenta
Write-Host " vota SIM (PREPARE TRANSACTION) ou NÃO. Se todos votarem SIM, a decisão é COMMIT;"  -ForegroundColor Magenta
Write-Host " basta um NÃO ou uma falha para ABORT. Na fase 2, a decisão é aplicada em todos."   -ForegroundColor Magenta
Write-Host " A decisão é gravada antes da fase 2, para o coordenador poder recuperar."         -ForegroundColor Magenta
Write-Host ""
