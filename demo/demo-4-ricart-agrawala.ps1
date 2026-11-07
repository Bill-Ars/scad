# =====================================================================
#  SCAD - Demonstração 4: exclusão mútua distribuída (Ricart-Agrawala)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\demo\demo-4-ricart-agrawala.ps1
#  Recurso partilhado: o lote "fornecedor:produto" (ex.: F1:P2). Só um armazém o pode comprar de cada vez.
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Demonstração 4: Ricart-Agrawala"

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

$Nomes = @{ 8081 = "Maputo"; 8082 = "Beira"; 8083 = "Nampula" }

# Lança pedidos à secção crítica EM SIMULTÂNEO (um processo por nó) e mostra o resultado
function PedirAoMesmoTempo($pedidos, $segundos) {
    foreach ($p in $pedidos) {
        Write-Host ("PS> Invoke-RestMethod -Method Post ""http://localhost:{0}/ra/demo?recurso={1}&segundos={2}""   # {3}" -f $p.porta, $p.recurso, $segundos, $Nomes[$p.porta]) -ForegroundColor Yellow
    }
    $jobs = foreach ($p in $pedidos) {
        Start-Job -ArgumentList $p.porta, $p.recurso, $segundos -ScriptBlock {
            param($porta, $recurso, $seg)
            Invoke-RestMethod -Method Post -TimeoutSec 90 -Uri "http://localhost:$porta/ra/demo?recurso=$recurso&segundos=$seg"
        }
    }
    Info "(os pedidos foram lançados ao mesmo tempo; a aguardar que todos saiam da secção crítica...)"
    $res = @($jobs | Wait-Job | Receive-Job)
    $jobs | Remove-Job
    $res = $res | Sort-Object entrouEm
    $res | Format-Table regiao, recurso, lamportTs, @{n="entrou";e={([datetime]$_.entrouEm).ToString("HH:mm:ss.fff")}},
        @{n="saiu";e={([datetime]$_.saiuEm).ToString("HH:mm:ss.fff")}}, esperouMs, requestsEnviados, repliesRecebidos,
        @{n="replyAdiadoA";e={($_.replyAdiadoEnviadoA | ForEach-Object { $_ -replace "http://agente-|:8080","" }) -join ","}} -AutoSize | Out-Host
    foreach ($x in $res) {
        if ($x.ignorados.Count -gt 0) { Write-Host (" {0} ignorou: {1}" -f $x.regiao, (($x.ignorados | ForEach-Object { $_ -replace "http://agente-|:8080","" }) -join ", ")) -ForegroundColor Yellow }
    }
    # verificação automática: no mesmo recurso, ninguém entra antes de o anterior sair
    foreach ($grupo in ($res | Group-Object recurso)) {
        $l = @($grupo.Group | Sort-Object entrouEm); $ok = $true
        for ($i = 1; $i -lt $l.Count; $i++) { if ([datetime]$l[$i].entrouEm -lt [datetime]$l[$i-1].saiuEm) { $ok = $false } }
        if ($l.Count -gt 1) {
            if ($ok) { Write-Host " VERIFICAÇÃO [$($grupo.Name)]: os intervalos NÃO se sobrepõem -> exclusão mútua garantida" -ForegroundColor Green }
            else     { Write-Host " VERIFICAÇÃO [$($grupo.Name)]: SOBREPOSIÇÃO detetada!" -ForegroundColor Red }
        }
    }
}
function Mensagens($desde) {
    Executar "docker compose logs --no-log-prefix --since $desde agente-maputo agente-beira agente-nampula | Select-String '\[RA ' | ForEach-Object { (`$_.Line -replace '^.*\[RA ', '[RA ') -replace 'http://agente-|:8080','' }"
}

# ---------------------------------------------------------------------
Cabecalho 0 "Preparação: sincronizar os relógios" "para comparar as horas de entrada/saída de nós diferentes, os relógios têm de estar acertados (Berkeley, Parte 5)."
Executar 'Invoke-RestMethod -Method Post http://localhost:8081/relogio/sincronizar | Out-Null; "relógios sincronizados pelo mestre (Maputo)"'
Pausa

# ---------------------------------------------------------------------
Cabecalho 1 "O algoritmo no código" "relógio de Lamport, REQUEST a todos, REPLY imediato ou adiado, desempate pelo id."
Executar 'Select-String -Path agente\src\main\java\mz\ac\isutc\scad\agente\RicartAgrawala.java -Pattern "TIMEOUT_MS =|case .Maputo|lamport.incrementAndGet|meuPedidoEMaisAntigo =|r.ts < ts|ADIA o REPLY|vivo\(p\)\) destinos|SAIU e envia" | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber, $_.Line.Trim() }'
Esperado "ts = lamport+1; adia o REPLY se estiver DENTRO ou se o seu pedido for mais antigo (ts menor, ou id menor em empate)."
Dizer "Quem quer entrar envia um REQUEST com o seu relógio de Lamport a todos os outros. Só entra quando receber REPLY de todos. Quem está dentro, ou tem um pedido mais antigo, adia o REPLY até sair."
Pausa

# ---------------------------------------------------------------------
Cabecalho 2 "Sem concorrência" "se ninguém mais quer o lote, todos respondem logo: 2(N-1) = 4 mensagens."
$t = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
PedirAoMesmoTempo @(@{porta=8081; recurso="F1:P2"}) 2
Mensagens $t
Esperado "esperouMs perto de 0; 2 REQUEST enviados e 2 REPLY recebidos; Beira e Nampula concedem REPLY imediato."
Dizer "Com 3 nós, entrar na secção crítica custa 2(N-1) = 4 mensagens: 2 REQUEST e 2 REPLY."
Pausa

# ---------------------------------------------------------------------
Cabecalho 3 "Maputo e Beira querem o MESMO lote ao mesmo tempo" "o pedido com menor timestamp de Lamport entra primeiro; o outro espera."
$t = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
PedirAoMesmoTempo @(@{porta=8081; recurso="F1:P2"}, @{porta=8082; recurso="F1:P2"}) 5
Mensagens $t
Esperado "um entra logo; o outro espera ~5000 ms; o primeiro envia o REPLY adiado ao segundo quando sai; NÃO há sobreposição."
Dizer "Os dois pediram o lote ao mesmo tempo. O de pedido mais antigo entrou; o outro ficou à espera do REPLY adiado, que só chegou quando o primeiro saiu. Nunca estiveram os dois dentro."
Pausa

# ---------------------------------------------------------------------
Cabecalho 4 "Os 3 armazéns ao mesmo tempo" "a ordem de entrada segue a ordem total (timestamp de Lamport, depois id)."
$t = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
PedirAoMesmoTempo @(@{porta=8081; recurso="F1:P2"}, @{porta=8082; recurso="F1:P2"}, @{porta=8083; recurso="F1:P2"}) 4
Esperado "entram um de cada vez (esperas de ~0, ~4000 e ~8000 ms); nenhuma sobreposição."
Dizer "Com os três a competir, entram um de cada vez, pela ordem dos timestamps. Em empate, ganha o id menor: Maputo 1, Beira 2, Nampula 3."
Pausa

# ---------------------------------------------------------------------
Cabecalho 5 "Lotes DIFERENTES não se bloqueiam" "a exclusão mútua é por recurso: F1:P2 e F2:P4 são independentes."
PedirAoMesmoTempo @(@{porta=8081; recurso="F1:P2"}, @{porta=8082; recurso="F2:P4"}) 4
Esperado "os dois entram logo (esperouMs perto de 0), porque pedem recursos diferentes."
Dizer "O bloqueio é só por lote. Maputo a comprar arroz não impede a Beira de comprar farinha a outro fornecedor."
Pausa

# ---------------------------------------------------------------------
Cabecalho 6 "Tolerância a falhas: Nampula em baixo" "o algoritmo original bloqueava à espera de um nó morto; aqui o heartbeat diz quem está vivo."
Executar 'docker compose stop agente-nampula 2>$null'
Info "(a aguardar 8 s para o heartbeat detetar a falha...)"
Start-Sleep -Seconds 8
PedirAoMesmoTempo @(@{porta=8081; recurso="F1:P2"}, @{porta=8082; recurso="F1:P2"}) 4
Esperado "a exclusão mútua continua (sem sobreposição) e Nampula aparece como ignorado (em falha)."
Dizer "No Ricart-Agrawala original, um nó morto bloqueava todos, porque nunca responde. Aqui, o heartbeat diz que Nampula está em falha e os outros deixam de esperar por ele; há também um timeout de 20 s."
Executar 'docker compose start agente-nampula 2>$null'
Info "(Nampula a arrancar de novo; volta a participar quando o heartbeat o vir vivo.)"

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " Para que dois armazéns não comprem o mesmo lote em duplicado, o lote é uma secção" -ForegroundColor Magenta
Write-Host " crítica distribuída. Quem quer entrar envia um REQUEST com o relógio de Lamport a"   -ForegroundColor Magenta
Write-Host " todos e só entra com REPLY de todos. Quem está dentro, ou pediu antes, adia o REPLY." -ForegroundColor Magenta
Write-Host " Custa 2(N-1) mensagens, não precisa de coordenador central e, com o heartbeat,"      -ForegroundColor Magenta
Write-Host " continua a funcionar quando um nó falha."                                            -ForegroundColor Magenta
Write-Host ""
