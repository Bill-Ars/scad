# Recomeca UM armazem do zero, sem mexer nos outros dois.
# Uso: powershell -ExecutionPolicy Bypass -File .\zerar-armazem.ps1 -Regiao Beira
#
# Apaga as bases de dados dessa regiao (mestre e replica): stock, ordens, pedidos dos clientes e auditoria.
# Depois volta a criar o armazem VAZIO e copia de outro armazem o que esta replicado em todos
# (catalogo de produtos e contas de clientes), para os tres continuarem iguais. Cada armazem tem os
# seus produtos: antes de apagar, guarda-se a lista dos que ESTE armazem vendia (do mestre ou da replica),
# e cada um volta com stock 0 e o mesmo minimo: o SCAD encomenda-o sozinho ao fornecedor.
# Se nenhuma das bases da regiao responder, o armazem volta sem produtos (acrescentar em Produtos > Editar).
# As faturas e os relatorios ja guardados no HDFS ficam.
param(
    [Parameter(Mandatory = $true)][ValidateSet('Maputo', 'Beira', 'Nampula')][string]$Regiao,
    [switch]$Sim     # nao pede confirmacao
)
Set-Location $PSScriptRoot
$r = $Regiao.ToLower()
$fonte = $null
foreach ($o in @('maputo', 'beira', 'nampula') | Where-Object { $_ -ne $r }) {
    docker compose exec -T "db-$o" pg_isready -q 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $fonte = $o; break }
}
if (-not $fonte) { Write-Host "Nenhum outro armazem esta ligado para copiar o catalogo e os clientes."; exit 1 }

if (-not $Sim) {
    Write-Host "Vai APAGAR todos os dados do armazem de $Regiao (stock, ordens, pedidos e auditoria)." -ForegroundColor Yellow
    if ((Read-Host "Escreva $Regiao para confirmar") -ne $Regiao) { Write-Host "Cancelado."; exit 1 }
}

function Sql($no, $sql) { docker compose exec -T $no psql -U postgres -d scad -At -v ON_ERROR_STOP=1 -c $sql }

# Produtos que este armazem vende (linhas do seu fragmento de stock), guardados antes de apagar
$seusProdutos = $null
foreach ($no in "db-$r", "db-$r-rep") {
    $linhas = Sql $no "SELECT format('INSERT INTO stock (produto_id, regiao, quantidade, limite_minimo, fornecedor_id) VALUES (%L, %L, 0, %s, %L) ON CONFLICT DO NOTHING;', produto_id, regiao, limite_minimo, fornecedor_id) FROM stock" 2>$null
    if ($LASTEXITCODE -eq 0) { $seusProdutos = @($linhas); break }
}
if ($null -eq $seusProdutos) { Write-Host "Aviso: nao foi possivel ler os produtos de $Regiao; o armazem volta sem produtos." -ForegroundColor Yellow; $seusProdutos = @() }

Write-Host "1/5 A parar o armazem de $Regiao..."
$servicos = "agente-$r", "robo-$r", "db-$r-rep", "db-$r"
$volumes = foreach ($s in "db-$r", "db-$r-rep") {
    $id = docker compose ps -a -q $s
    # So os volumes tem nome; as pastas montadas (scripts SQL) ficam vazias e sao ignoradas
    if ($id) { docker inspect $id --format '{{range .Mounts}}{{.Name}} {{end}}' }
}
docker compose stop $servicos 2>$null | Out-Null
docker compose rm -f $servicos 2>$null | Out-Null

Write-Host "2/5 A apagar as bases de dados de $Regiao..."
$apagar = @(($volumes -join ' ') -split '\s+' | Where-Object { $_ })
if ($apagar.Count -ne 2) { Write-Host "Nao encontrei os 2 volumes de $Regiao (encontrados: $($apagar -join ', ')). Nada foi apagado."; docker compose up -d $servicos 2>$null | Out-Null; exit 1 }
foreach ($v in $apagar) {
    docker volume rm $v | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "Nao foi possivel apagar o volume $v."; exit 1 }
    Write-Host "   volume $v apagado"
}

Write-Host "3/5 A criar as bases de dados vazias..."
docker compose up -d "db-$r" "db-$r-rep" 2>$null | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    $pronto = Sql "db-$r" "SELECT count(*) FROM stock" 2>$null
    if ($LASTEXITCODE -eq 0 -and "$pronto" -ne "") { break }
    Start-Sleep -Seconds 2
}
if ($LASTEXITCODE -ne 0) { Write-Host "A base de dados de $Regiao nao arrancou."; exit 1 }

Write-Host "4/5 A ligar o agente e o robo de $Regiao..."
docker compose up -d "agente-$r" "robo-$r" 2>$null | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    $tabelas = Sql "db-$r" "SELECT count(*) FROM information_schema.tables WHERE table_name = 'cliente'" 2>$null
    if ($tabelas -eq '1') { break }
    Start-Sleep -Seconds 2
}

Write-Host "5/5 A copiar o catalogo e as contas de clientes de $($fonte.Substring(0,1).ToUpper() + $fonte.Substring(1))..."
# O armazem de origem gera os comandos; format(%L) poe as aspas certas em cada valor
$produtos = Sql "db-$fonte" "SELECT format('INSERT INTO produto (id, nome, unidade, preco) VALUES (%L, %L, %L, %s) ON CONFLICT (id) DO UPDATE SET nome = EXCLUDED.nome, unidade = EXCLUDED.unidade, preco = EXCLUDED.preco;', id, nome, unidade, preco) FROM produto"
$clientes = Sql "db-$fonte" "SELECT format('INSERT INTO cliente (id, nome, tipo, email, senha_hash, criado_em) VALUES (%L, %L, %L, %L, %L, %L) ON CONFLICT DO NOTHING;', id, nome, tipo, email, senha_hash, criado_em) FROM cliente" 2>$null
# O stock da imagem inicial (zerar.ps1 -Demo) e substituido pela lista que este armazem tinha
$script = @($produtos) + @("DELETE FROM stock;") + @($seusProdutos) + @($clientes) | Where-Object { $_ }
$script -join "`n" | docker compose exec -T "db-$r" psql -U postgres -d scad -q -v ON_ERROR_STOP=1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "Falhou a copia do catalogo; o armazem arrancou so com os dados iniciais."; exit 1 }

$porta = @{ maputo = 8081; beira = 8082; nampula = 8083 }[$r]
for ($i = 0; $i -lt 60; $i++) {
    try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$porta/login.html" | Out-Null; break } catch { Start-Sleep -Seconds 2 }
}
Write-Host ""
Write-Host "Pronto: o armazem de $Regiao recomecou do zero." -ForegroundColor Green
Write-Host "Stock: $(Sql "db-$r" "SELECT string_agg(produto_id || '=' || quantidade, ', ' ORDER BY produto_id) FROM stock")"
Write-Host "Clientes: $(Sql "db-$r" "SELECT count(*) FROM cliente")"
Write-Host "Os produtos deste armazem comecam com stock 0: o SCAD encomenda-os sozinho ao fornecedor em poucos segundos."
