# =====================================================================
#  SCAD - Demonstração 1: fragmentação horizontal (SBDD)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\demo\demo-1-fragmentacao.ps1
#  Tenha o painel aberto em http://localhost:8081 (Início: gráfico "Stock por produto e armazém").
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Demonstração 1: fragmentação horizontal"

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
function EsperarPar($par, $estado) {
    Write-Host " (a aguardar que Maputo veja $par como $estado...)" -ForegroundColor DarkGray
    for ($i = 0; $i -lt 20; $i++) {
        try { $p = Invoke-RestMethod http://localhost:8081/pares } catch { $p = $null }
        if ($p -and ("" + $p."http://agente-$($par):8080").StartsWith($estado)) { return }
        Start-Sleep -Seconds 3
    }
}

# ---------------------------------------------------------------------
Cabecalho 1 "Onde está definida a fragmentação" "cada nó recebe um script SQL diferente: as tabelas comuns são replicadas e o stock é fragmentado por região."
Executar 'Select-String -Path docker-compose.yml -Pattern "0[12]-.*\.sql" | ForEach-Object { $_.Line.Trim() }'
Write-Host ""
Executar 'foreach ($f in "maputo.sql", "beira.sql", "nampula.sql") { $t = ""; Get-Content "db\$f" | ForEach-Object { if ($_ -match "CREATE TABLE (\w+)") { $t = $Matches[1] }; if ($_ -match "CHECK \(regiao") { "{0,-12} tabela {1,-13} {2}" -f $f, $t, ($_.Trim() -replace "\s+", " ") } } }'
Esperado "o mesmo comum.sql nos 3 nós e um fragmento diferente em cada um. Duas tabelas fragmentadas por região (stock e ordem_compra), ambas com CHECK (regiao = '...')."
Dizer "As tabelas de referência vão iguais para os três nós. O stock e as ordens de compra vão fragmentados: cada nó recebe só a sua região, e o CHECK impede que lá entrem linhas de outra região."
Pausa

# ---------------------------------------------------------------------
Cabecalho 2 "Cada nó guarda só o seu fragmento" "STOCK_MAPUTO = sigma regiao='Maputo' (STOCK)."
Write-Host " Fragmento de MAPUTO (pedido ao agente da porta 8081):" -ForegroundColor White
Executar 'Invoke-RestMethod http://localhost:8081/stock | Format-Table produto_id, nome, regiao, quantidade, limite_minimo, abaixo_minimo -AutoSize'
Write-Host " Fragmento da BEIRA (pedido ao agente da porta 8082):" -ForegroundColor White
Executar 'Invoke-RestMethod http://localhost:8082/stock | Format-Table produto_id, nome, regiao, quantidade, limite_minimo, abaixo_minimo -AutoSize'
Esperado "4 linhas em cada nó, todas da própria região."
Dizer "Cada agente lê apenas a sua base de dados regional. Maputo nem sequer tem as linhas da Beira: o acesso é local e rápido, e a carga fica repartida."
Pausa

# ---------------------------------------------------------------------
Cabecalho 3 "Tabelas replicadas: iguais em todos os nós" "produto e fornecedor mudam pouco e todos precisam delas, por isso existem nos 3 nós."
foreach ($db in "db-maputo", "db-beira", "db-nampula") {
    Executar "docker compose exec -T $db psql -U postgres -d scad -t -c ""SELECT '$db' AS no, (SELECT count(*) FROM produto) AS produtos, (SELECT count(*) FROM fornecedor) AS fornecedores, (SELECT count(*) FROM stock) AS linhas_stock;"""
}
Esperado "4 produtos e 2 fornecedores em TODOS os nós; 4 linhas de stock em cada um (só as suas)."
Dizer "Fragmenta-se o que é diferente por região e muito alterado; replica-se o que é igual para todos e pouco alterado."
Pausa

# ---------------------------------------------------------------------
Cabecalho 4 "Reconstrução: a tabela global" "STOCK = STOCK_MAPUTO U STOCK_BEIRA U STOCK_NAMPULA."
Executar '$g = Invoke-RestMethod http://localhost:8081/stock/global; "Linhas reconstruídas: $($g.totalLinhas)   Fragmentos indisponíveis: $($g.fragmentosIndisponiveis.Count)"; $g.linhas | Format-Table produto_id, regiao, quantidade, limite_minimo, abaixo_minimo -AutoSize'
Esperado "12 linhas = 4 produtos x 3 regiões (completude e reconstrução)."
Dizer "Para a visão global, o agente de Maputo pede o fragmento a cada um dos outros nós pela rede e faz a união. Nenhuma linha se perde: é a completude e a reconstrução."
Pausa

# ---------------------------------------------------------------------
Cabecalho 5 "Disjunção garantida pela base de dados" "uma linha só pode estar num fragmento: o CHECK recusa linhas de outra região."
Executar 'docker compose exec -T db-maputo psql -U postgres -d scad -c "INSERT INTO stock VALUES (''P1'',''Beira'',10,5,''F1'');" 2>&1 | ForEach-Object { "$_" }'
Esperado "ERROR: new row for relation stock violates check constraint stock_regiao_check (SQLSTATE 23514)."
Dizer "Tentei pôr uma linha da Beira na base de Maputo e o PostgreSQL recusou. A disjunção não depende do código Java: é a própria base de dados que se protege."
Pausa

# ---------------------------------------------------------------------
Cabecalho 6 "Falha parcial: um fragmento indisponível" "se um nó cair, a consulta global continua, com os dados que consegue obter."
Executar 'docker compose stop agente-nampula 2>$null'
EsperarPar "nampula" "EM FALHA"
Executar '$g = Invoke-RestMethod http://localhost:8081/stock/global; "Linhas: $($g.totalLinhas)   Fragmentos indisponíveis: $($g.fragmentosIndisponiveis -join ", ")"'
Esperado "8 linhas e Nampula indicado como indisponível. No painel, as barras de Nampula desaparecem do gráfico."
Dizer "O sistema continua disponível e diz claramente o que falta. Mas os dados de Nampula ficam inacessíveis enquanto o nó estiver em baixo: é por isso que precisamos de replicação, que vemos a seguir."
Pausa

Write-Host " A religar o agente de Nampula (cerca de 40 s)..." -ForegroundColor DarkGray
Executar 'docker compose start agente-nampula 2>$null'
EsperarPar "nampula" "VIVO"
Executar '"Linhas: " + (Invoke-RestMethod http://localhost:8081/stock/global).totalLinhas'
Esperado "12 linhas outra vez."

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " A tabela de stock está fragmentada horizontalmente por região em 3 nós." -ForegroundColor Magenta
Write-Host " As três regras estão cumpridas: completude e reconstrução (a união dos" -ForegroundColor Magenta
Write-Host " fragmentos dá as 12 linhas) e disjunção (o CHECK de cada fragmento recusa" -ForegroundColor Magenta
Write-Host " linhas de outra região). As tabelas de referência estão replicadas em todos." -ForegroundColor Magenta
Write-Host " Se um nó cair, a consulta global continua, com um resultado parcial." -ForegroundColor Magenta
Write-Host ""
