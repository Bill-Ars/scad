# =====================================================================
#  SCAD - Testes do DFS: HDFS com replicacao 3 (Fase 7)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\testes\teste-dfs.ps1
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)

function Titulo($t) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor Cyan
}
function DataNodesVivos {
    docker compose exec -T namenode hdfs dfsadmin -report 2>$null | Select-String "Live datanodes|Dead datanodes|^Hostname"
}
function Replicas($caminho) {
    docker compose exec -T namenode hdfs fsck $caminho -files -blocks -locations 2>$null |
        Select-String "\.pdf|repl=|Live_repl|DatanodeInfoWithStorage|Status"
}

# ---------------------------------------------------------------------
Titulo "TESTE 1 - O cluster HDFS: 1 NameNode e 3 DataNodes"
DataNodesVivos
Write-Host "Interface web do NameNode: http://localhost:9870  (separador Datanodes)" -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 2"

# ---------------------------------------------------------------------
Titulo "TESTE 2 - Maputo gera uma fatura PDF e guarda-a no HDFS"
$ordem = "ORD-DFS-" + (Get-Date -Format "HHmmss")
$f = Invoke-RestMethod -Method Post -Uri http://localhost:8081/dfs/fatura -ContentType 'application/json' `
     -Body ('{"ordemId":"' + $ordem + '","fornecedorId":"F1","produtoId":"P2","quantidade":90}')
$f | Format-List
Write-Host "--- onde estao as replicas do bloco (fsck) ---" -ForegroundColor Yellow
Replicas $f.caminho
Write-Host "Esperado: repl=3 e o bloco em datanode1, datanode2 e datanode3." -ForegroundColor Green
Read-Host "Carrega em Enter para o teste 3"

# ---------------------------------------------------------------------
Titulo "TESTE 3 - Leitura transparente a partir de OUTRO no (Beira)"
Invoke-WebRequest -UseBasicParsing "http://localhost:8082/dfs/ler?caminho=$($f.caminho)" -OutFile "$env:TEMP\fatura-beira.pdf"
Write-Host "A Beira leu $((Get-Item "$env:TEMP\fatura-beira.pdf").Length) bytes. A abrir o PDF..."
Start-Process "$env:TEMP\fatura-beira.pdf"
Write-Host "Ficheiros vistos por Nampula:" -ForegroundColor Yellow
Invoke-RestMethod "http://localhost:8083/dfs/listar?dir=/scad/faturas" | Format-Table pathSuffix, length, replication -AutoSize
Read-Host "Carrega em Enter para o teste 4"

# ---------------------------------------------------------------------
Titulo "TESTE 4 - FALHA: cai o agente que escreveu (Maputo) e um DataNode"
docker compose stop agente-maputo datanode1
Write-Host "A aguardar 15 s para o NameNode marcar o datanode1 como 'stale'..."
Start-Sleep -Seconds 15
DataNodesVivos
Invoke-WebRequest -UseBasicParsing "http://localhost:8082/dfs/ler?caminho=$($f.caminho)" -OutFile "$env:TEMP\fatura-falha.pdf"
Write-Host "Mesmo assim, a Beira leu $((Get-Item "$env:TEMP\fatura-falha.pdf").Length) bytes da fatura." -ForegroundColor Green
Write-Host "Esperado: a fatura continua acessivel (as outras 2 replicas estao vivas)." -ForegroundColor Green
docker compose start agente-maputo datanode1
Read-Host "Carrega em Enter para o teste 5"

# ---------------------------------------------------------------------
Titulo "TESTE 5 - Log de erros partilhado: dois nos escrevem no MESMO ficheiro"
Invoke-RestMethod -Method Post -Uri http://localhost:8082/dfs/log -ContentType 'application/json' -Body '{"texto":"Fornecedor F1 sem resposta (teste)"}' | Format-List
Invoke-RestMethod -Method Post -Uri http://localhost:8083/dfs/log -ContentType 'application/json' -Body '{"texto":"Stock de P4 abaixo do minimo (teste)"}' | Format-List
Write-Host "--- conteudo de /scad/logs/erros.log lido pela Beira ---" -ForegroundColor Yellow
(Invoke-WebRequest -UseBasicParsing "http://localhost:8082/dfs/ler?caminho=/scad/logs/erros.log").Content

Titulo "FIM DOS TESTES DO DFS"
