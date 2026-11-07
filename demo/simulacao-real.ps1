# =====================================================================
#  SCAD - Simulação de um dia real de utilização
#  Executar a partir da pasta scad (de preferência logo a seguir ao zerar.ps1):
#     powershell -ExecutionPolicy Bypass -File .\demo\simulacao-real.ps1
#  Tenha o painel aberto em http://localhost:8081 (senha: admin1234).
#  História: os supermercados vendem, o stock desce, os robôs alertam, o sistema
#  compra sozinho ao fornecedor certo, guarda a fatura e resiste a falhas.
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Simulação de um dia real"

function Ato($n, $titulo, $historia) {
    Write-Host ""
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    Write-Host " ATO $n - $titulo" -ForegroundColor Cyan
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    Write-Host " $historia" -ForegroundColor Gray
    Write-Host ""
}
function Executar($comando) {
    Write-Host "PS> $comando" -ForegroundColor Yellow
    Invoke-Expression "& { $comando } | Out-Host"
}
function Painel($texto)   { Write-Host " NO PAINEL: $texto" -ForegroundColor Cyan }
function Dizer($texto)    { Write-Host " DIZER:     `"$texto`"" -ForegroundColor Magenta }
function Pausa { Write-Host ""; Read-Host " [Enter] para continuar" | Out-Null }
function Info($t) { Write-Host " $t" -ForegroundColor DarkGray }

function Vender($db, $regiao, $produto, $unidades) {
    Write-Host " Caixa de $regiao regista a venda de $unidades unidades de $produto" -ForegroundColor White
    Executar "docker compose exec -T $db psql -U postgres -d scad -c ""UPDATE stock SET quantidade = quantidade - $unidades WHERE produto_id = '$produto' RETURNING produto_id, regiao, quantidade, limite_minimo, CASE WHEN quantidade <= limite_minimo THEN 'NO MINIMO' ELSE 'ok' END AS situacao;"""
}
function Receber($db, $regiao, $produto, $unidades) {
    Write-Host " O camião do fornecedor chegou a $regiao: entram $unidades unidades de $produto" -ForegroundColor White
    Executar "docker compose exec -T $db psql -U postgres -d scad -c ""UPDATE stock SET quantidade = quantidade + $unidades WHERE produto_id = '$produto' RETURNING produto_id, regiao, quantidade, limite_minimo;"""
}
function MostrarCompra($r) {
    Write-Host ""
    Write-Host "  Nó: $($r.no)   Produto: $($r.produtoId)" -ForegroundColor White
    $r.passos | ForEach-Object { Write-Host "    $_" }
    $cor = if ($r.resultado -eq "COMPRA CONCLUIDA") { "Green" } else { "Yellow" }
    Write-Host "  RESULTADO: $($r.resultado)" -ForegroundColor $cor
}
function CompraAutomatica($porta, $produto) {
    Write-Host "PS> Invoke-RestMethod -Method Post ""http://localhost:$porta/compras/automatica?produtoId=$produto""" -ForegroundColor Yellow
    $r = Invoke-RestMethod -Method Post -TimeoutSec 120 -Uri "http://localhost:$porta/compras/automatica?produtoId=$produto"
    MostrarCompra $r
    return $r
}

# ---------------------------------------------------------------------
Ato 1 "Abertura do dia" "Os 3 armazéns abrem. O mestre (Maputo) acerta os relógios com o algoritmo de Berkeley."
Executar '8081, 8082, 8083 | ForEach-Object { Invoke-RestMethod "http://localhost:$_/relogio" } | Format-Table regiao, hora, desvioMs -AutoSize'
Executar 'Invoke-RestMethod -Method Post http://localhost:8081/relogio/sincronizar | Out-Null; "Berkeley: relógios sincronizados"'
Executar '8081, 8082, 8083 | ForEach-Object { Invoke-RestMethod "http://localhost:$_/relogio" } | Format-Table regiao, hora, desvioMs -AutoSize'
Executar '(Invoke-RestMethod http://localhost:8081/stock/global).linhas | Format-Table produto_id, nome, regiao, quantidade, limite_minimo, abaixo_minimo -AutoSize'
Painel "entrar com admin1234; ver os 3 nós ONLINE, o stock global e os relógios."
Dizer "O dia começa com os relógios desacertados, como em máquinas reais. O mestre sincroniza-os para que todos os registos tenham a mesma hora."
Pausa

# ---------------------------------------------------------------------
Ato 2 "Vendas em Maputo" "Os clientes compram muito arroz (P1). O stock de Maputo desce de 120 para 30, abaixo do mínimo de 50."
Vender db-maputo Maputo P1 90
Painel "o gráfico de stock de Maputo mostra P1 a vermelho (abaixo do mínimo)."
Dizer "Os dados de Maputo só estão no nó de Maputo: é o seu fragmento."
Pausa

# ---------------------------------------------------------------------
Ato 3 "O robô de stock deteta e alerta (CORBA)" "O robô de Maputo verifica o stock de 10 em 10 s e chama dispararAlerta na Auditoria central."
Info "(a aguardar 12 s pelo próximo ciclo do robô...)"
Start-Sleep -Seconds 12
Executar 'docker compose logs --since 40s robo-maputo auditoria 2>$null | Select-String "P1" | ForEach-Object { $_.Line }'
Executar 'docker compose exec -T auditoria sh /app/run.sh mz.ac.isutc.scad.corba.ConsultaAuditoria P1 2>$null | Select-String -NotMatch "INFO|WARN|org.jacorb|^\s*$"'
Dizer "O robô enviou o alerta por CORBA (oneway, sem esperar resposta). A Auditoria pode perguntar o saldo a cada robô com consultarSaldo."
Pausa

# ---------------------------------------------------------------------
Ato 4 "Compra automática de P1 em Maputo" "O agente decide sozinho: RA -> stock global -> 2PC -> fornecedor REST -> fatura no HDFS."
Info "(o agente verifica o stock de 10 em 10 s: a compra ja arrancou sozinha)"
$compra = Invoke-RestMethod http://localhost:8081/painel/auditoria | Where-Object { $_.operacao -eq 'COMPRA-AUTO' } | Select-Object -First 1
if ($compra) { $compra.detalhe -split ' \| ' | ForEach-Object { Write-Host "    $_" } } else { Info "(ainda a decorrer: ver a pagina Compras)" }
$ultimaFatura = Invoke-RestMethod "http://localhost:8081/dfs/listar?dir=/scad/faturas" | Select-Object -Last 1
$faturaP1 = if ($ultimaFatura) { "/scad/faturas/$($ultimaFatura.pathSuffix)" }
Dizer "Sem ninguém escolher fornecedor nem quantidade: o sistema detetou a falta, calculou a reposição (2 x mínimo - stock) e encomendou."
Pausa

# ---------------------------------------------------------------------
Ato 5 "O fornecedor recebeu e a fatura ficou guardada" "Confirmar no fornecedor (REST) e abrir a fatura PDF que está no HDFS, replicada em 3 DataNodes."
Executar 'Invoke-RestMethod http://localhost:8091/api/ordens | Format-Table ordemId, produtoId, quantidade, regiao, estado -AutoSize'
Executar 'Invoke-RestMethod "http://localhost:8081/dfs/listar?dir=/scad/faturas" | Format-Table pathSuffix, length, replication -AutoSize'
if ($faturaP1) {
    Executar "Invoke-WebRequest -UseBasicParsing 'http://localhost:8082/dfs/ler?caminho=$faturaP1' -OutFile `"`$env:TEMP\fatura-P1.pdf`"; Start-Process `"`$env:TEMP\fatura-P1.pdf`"; 'fatura lida a partir da BEIRA e aberta'"
}
Painel "tabela de ordens com a nova ordem; cartão HDFS com 1 fatura; http://localhost:9870 > Utilities > Browse > /scad/faturas."
Dizer "A fatura foi escrita por Maputo, mas li-a a partir da Beira: o sistema de ficheiros é distribuído e partilhado por todos os nós."
Pausa

# ---------------------------------------------------------------------
Ato 6 "Chega a mercadoria a Maputo" "A entrega é automática, 30 s depois da ordem: o agente dá entrada do stock e marca a ordem como RECEBIDA."
Info "(a aguardar 35 s pela entrega...)"
Start-Sleep -Seconds 35
Executar '(Invoke-RestMethod http://localhost:8081/stock).Where({ $_.produto_id -eq "P1" }) | Format-Table produto_id, regiao, quantidade, limite_minimo, em_encomenda -AutoSize'
Painel "P1 de Maputo volta ao normal e a ordem passa a RECEBIDA."
Pausa

# ---------------------------------------------------------------------
Ato 7 "Maputo e Beira precisam de óleo (P2) ao mesmo tempo" "Os dois agentes decidem comprar no mesmo instante. Sem controlo, o fornecedor receberia 2 encomendas."
Vender db-maputo Maputo P2 40
Vender db-beira Beira P2 35
Info "(os dois agentes lançam a compra automática ao mesmo tempo...)"
$jobs = foreach ($p in 8081, 8082) {
    Write-Host "PS> Invoke-RestMethod -Method Post ""http://localhost:$p/compras/automatica?produtoId=P2""   (em paralelo)" -ForegroundColor Yellow
    Start-Job -ArgumentList $p -ScriptBlock { param($p) Invoke-RestMethod -Method Post -TimeoutSec 120 -Uri "http://localhost:$p/compras/automatica?produtoId=P2" }
}
$res = @($jobs | Wait-Job | Receive-Job); $jobs | Remove-Job
$res | ForEach-Object { MostrarCompra $_ }
Painel "ou, na página Apresentação, o botão 'Compra simultânea Maputo + Beira', que faz exatamente isto."
Dizer "Os dois quiseram comprar, mas o Ricart-Agrawala só deixou entrar um de cada vez. O primeiro comprou para as duas regiões, com 2PC; o segundo viu que o lote já estava encomendado e não duplicou."
Pausa

# ---------------------------------------------------------------------
Ato 8 "Nampula precisa de farinha (P4) a um fornecedor antigo" "O fornecedor F2 é um sistema legado que só fala SOAP/XML."
Vender db-nampula Nampula P4 50
$r = CompraAutomatica 8083 P4
Painel "ou, na página Apresentação, o botão 'Compra por SOAP em Nampula'."
Dizer "O mesmo fluxo, mas a ordem seguiu como mensagem SOAP, validada pelo WSDL. O agente escolhe a rota conforme o fornecedor."
Pausa

# ---------------------------------------------------------------------
Ato 9 "Problema: o fornecedor REST fica sem serviço" "A Beira vende arroz e precisa de comprar, mas o servidor do fornecedor F1 caiu."
Executar 'docker compose stop fornecedor-rest 2>$null'
Vender db-beira Beira P1 70
$r = CompraAutomatica 8082 P1
Executar '(Invoke-WebRequest -UseBasicParsing "http://localhost:8082/dfs/ler?caminho=/scad/logs/erros-beira.log").Content'
Dizer "O sistema tentou 3 vezes, com espera crescente. Como o fornecedor não respondeu, o erro ficou registado no log do HDFS, para ser tratado. Os armazéns continuaram a funcionar."
Executar 'docker compose start fornecedor-rest 2>$null'
Pausa

# ---------------------------------------------------------------------
Ato 10 "Fim do dia: auditoria" "Tudo o que aconteceu ficou registado, com a hora sincronizada por Berkeley."
Executar 'docker compose exec -T db-maputo psql -U postgres -d scad -c "SELECT to_char(hora, ''HH24:MI:SS'') AS hora, operacao, left(detalhe, 55) AS detalhe FROM auditoria ORDER BY id;"'
Executar 'docker compose exec -T db-beira psql -U postgres -d scad -c "SELECT to_char(hora, ''HH24:MI:SS'') AS hora, operacao, left(detalhe, 55) AS detalhe FROM auditoria ORDER BY id;"'
Painel "a tabela de ordens mostra todas as compras do dia; os KPIs mostram ordens e faturas."

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " Num dia normal, as vendas fazem descer o stock de cada armazém. Os robôs detetam a" -ForegroundColor Magenta
Write-Host " falta e alertam a auditoria por CORBA. O agente compra sozinho: entra na secção"     -ForegroundColor Magenta
Write-Host " crítica do lote (Ricart-Agrawala), junta as necessidades de todas as regiões, grava"  -ForegroundColor Magenta
Write-Host " a ordem com 2PC, envia-a por REST ou SOAP e guarda a fatura no HDFS. Se algo falha,"  -ForegroundColor Magenta
Write-Host " o erro fica registado e o resto do sistema continua."                               -ForegroundColor Magenta
Write-Host ""
