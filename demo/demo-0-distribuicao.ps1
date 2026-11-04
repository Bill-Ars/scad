# =====================================================================
#  SCAD - Demonstração 0: o que torna o sistema distribuído
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\demo\demo-0-distribuicao.ps1
#  Tenha o painel aberto em http://localhost:8081 ao lado.
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Demonstração 0: distribuição"

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
    Invoke-Expression "$comando | Out-Host"
}
function Esperado($texto) { Write-Host ""; Write-Host " ESPERADO: $texto" -ForegroundColor Green }
function Dizer($texto)    { Write-Host " DIZER:    `"$texto`"" -ForegroundColor Magenta }
function Pausa { Write-Host ""; Read-Host " [Enter] para continuar" | Out-Null }
function EsperarBeira($estado) {
    Write-Host " (a aguardar que Maputo veja a Beira como $estado...)" -ForegroundColor DarkGray
    for ($i = 0; $i -lt 15; $i++) {
        try { $p = Invoke-RestMethod http://localhost:8081/pares } catch { $p = $null }
        if ($p -and ("" + $p.'http://agente-beira:8080').StartsWith($estado)) { Write-Host " (demorou cerca de $($i * 3) s)" -ForegroundColor DarkGray; return }
        Start-Sleep -Seconds 3
    }
}

# ---------------------------------------------------------------------
Cabecalho 1 "Os nós do sistema" "um sistema distribuído é um conjunto de nós independentes que só comunicam pela rede."
Executar 'docker compose ps --format "table {{.Service}}\t{{.Status}}"'
Esperado "20 serviços com o estado Up: 3 agentes, 6 bases de dados, HDFS, CORBA e fornecedores."
Dizer "Cada linha é um nó. Vou provar que são nós independentes, e não apenas um programa."
Pausa

# ---------------------------------------------------------------------
Cabecalho 2 "Prova 1: processos separados" "cada nó é um processo isolado, com a sua própria CPU e memória."
Executar 'docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" (docker compose ps -q)'
Esperado "um consumo de CPU e de memória diferente para cada contentor."
Dizer "Cada nó é um processo isolado, com a sua memória. Não há memória partilhada entre eles."
Pausa

# ---------------------------------------------------------------------
Cabecalho 3 "Prova 2: endereços de rede próprios" "os nós comunicam por uma rede virtual, cada um com o seu IP."
Executar 'docker network inspect scad_scad-net --format "{{range .Containers}}{{.Name}}  {{.IPv4Address}}{{println}}{{end}}"'
Esperado "um IP diferente (172.x.x.x) para cada nó."
Dizer "Cada nó tem o seu endereço IP. Comunicam como computadores ligados ao mesmo switch."
Pausa

# ---------------------------------------------------------------------
Cabecalho 4 "Prova 3: dados próprios em cada nó" "sem disco partilhado: cada base de dados só guarda os dados da sua região."
Executar 'docker compose exec -T db-maputo psql -U postgres -d scad -c "SELECT regiao, count(*) AS linhas FROM stock GROUP BY regiao;"'
Executar 'docker compose exec -T db-beira psql -U postgres -d scad -c "SELECT regiao, count(*) AS linhas FROM stock GROUP BY regiao;"'
Esperado "a base de Maputo só tem linhas de Maputo e a da Beira só tem linhas da Beira."
Dizer "Cada nó tem o seu próprio disco e os seus próprios dados."
Pausa

# ---------------------------------------------------------------------
Cabecalho 5 "O mesmo código, configurações diferentes" "os 3 agentes são o mesmo programa; a configuração faz deles nós diferentes."
Executar '[regex]::Matches((Get-Content docker-compose.yml -Raw), "(?ms)^  agente-\w+:.*?PARES:[^\r\n]*") | ForEach-Object { $_.Value; "" }'
Esperado "build: ./agente nos três agentes, com REGIAO e PARES diferentes."
Dizer "Os três agentes são construídos a partir do mesmo código. Cada um conhece os outros apenas por endereços de rede, na variável PARES."
Pausa

# ---------------------------------------------------------------------
Cabecalho 6 "Heartbeat: cada nó vigia os outros" "de 5 em 5 segundos, cada agente pergunta aos pares se estão vivos (timeout de 2 s)."
Write-Host " Visão de MAPUTO (porta 8081):" -ForegroundColor White
Executar 'Invoke-RestMethod http://localhost:8081/pares | Format-List'
Write-Host " Visão da BEIRA (porta 8082):" -ForegroundColor White
Executar 'Invoke-RestMethod http://localhost:8082/pares | Format-List'
Write-Host " Visão de NAMPULA (porta 8083):" -ForegroundColor White
Executar 'Invoke-RestMethod http://localhost:8083/pares | Format-List'
Write-Host " Heartbeat registado DENTRO do contentor de Maputo:" -ForegroundColor White
Executar 'docker compose logs agente-maputo 2>$null | Select-String "MonitorPares" | Select-Object -Last 4'
Esperado "cada nó mostra os OUTROS dois (nunca a si próprio) como VIVO."
Dizer "A porta 8081 é o nó de Maputo: pergunto-lhe o que ele sabe. Cada nó constrói a sua própria visão com as respostas ao heartbeat que envia pela rede. Não existe um estado global."
Pausa

# ---------------------------------------------------------------------
Cabecalho 7 "Prova 4: só comunicam pela rede" "isolar um nó da rede, SEM o desligar."
Executar 'docker network disconnect scad_scad-net scad-agente-beira-1'
EsperarBeira "EM FALHA"
Executar 'docker compose ps agente-beira --format "table {{.Service}}\t{{.Status}}"'
Executar 'Invoke-RestMethod http://localhost:8081/pares | Format-List'
Esperado "o contentor da Beira continua Up, mas Maputo vê-o como EM FALHA. No painel, o cartão da Beira fica vermelho."
Dizer "A Beira continua a correr, mas está isolada. Para os outros, é igual a estar morta: a única ligação entre os nós é a rede. Um nó isolado parece um nó morto."
Pausa

Write-Host " A religar a Beira à rede (com o nome DNS do serviço)..." -ForegroundColor DarkGray
Executar 'docker network connect --alias agente-beira scad_scad-net scad-agente-beira-1'
EsperarBeira "VIVO"
Executar 'Invoke-RestMethod http://localhost:8081/pares | Format-List'
Esperado "a Beira volta a VIVO. No painel, o cartão volta a verde."
Dizer "O --alias repõe o nome agente-beira no DNS: os nós encontram-se pelo nome, é a transparência de localização. A recuperação demora uns segundos porque a falha de DNS ficou em cache: num sistema distribuído, a recuperação não é instantânea."
Pausa

# ---------------------------------------------------------------------
Cabecalho 8 "Prova 5: falha parcial" "um nó falha e os outros continuam a funcionar."
Executar 'docker compose stop agente-beira 2>$null'
Executar 'docker compose ps -a agente-beira --format "table {{.Service}}\t{{.Status}}"'
EsperarBeira "EM FALHA"
Executar 'Invoke-RestMethod http://localhost:8081/estado | Format-List regiao, baseDeDados, servidorBD, modo'
Executar 'Invoke-RestMethod http://localhost:8083/estado | Format-List regiao, baseDeDados, servidorBD, modo'
Executar '(Invoke-RestMethod http://localhost:8081/stock/global) | Format-List totalLinhas, fragmentosIndisponiveis'
Esperado "Maputo e Nampula respondem; o stock global devolve 8 linhas e indica a Beira como indisponível."
Dizer "A Beira caiu e o resto do sistema continua: é a falha parcial. A consulta global responde com os dados que consegue e diz o que falta."
Pausa

Write-Host " A religar o agente da Beira (o Spring Boot demora cerca de 40 s)..." -ForegroundColor DarkGray
Executar 'docker compose start agente-beira 2>$null'
EsperarBeira "VIVO"
Executar 'Invoke-RestMethod http://localhost:8081/pares | Format-List'
Esperado "a Beira de volta como VIVO."

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " O SCAD é distribuído porque cada nó é um processo isolado, com a sua memória," -ForegroundColor Magenta
Write-Host " o seu disco e o seu IP, e só comunica por mensagens na rede. Um nó isolado"   -ForegroundColor Magenta
Write-Host " parece morto aos outros, e a falha de um nó não derruba os restantes."        -ForegroundColor Magenta
Write-Host " Limitação: todos correm no mesmo computador (simulo a falha de processos,"   -ForegroundColor Magenta
Write-Host " não a falha física da máquina)."                                             -ForegroundColor Magenta
Write-Host ""
