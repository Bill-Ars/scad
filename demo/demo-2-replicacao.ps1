# =====================================================================
#  SCAD - Demonstração 2: replicação mestre-escravo (SBDD)
#  Executar a partir da pasta scad:
#     powershell -ExecutionPolicy Bypass -File .\demo\demo-2-replicacao.ps1
#  Tenha o painel aberto em http://localhost:8081 (Estado do sistema: o cartão da Beira passa a "Na cópia de segurança").
#  ATENÇÃO: no fim, a região da Beira é reconstruída (os dados da Beira voltam aos iniciais).
# =====================================================================
Set-Location (Split-Path $PSScriptRoot -Parent)
$Host.UI.RawUI.WindowTitle = "SCAD - Demonstração 2: replicação"

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
function EsperarServidor($servidor) {
    Info "(a aguardar que o agente da Beira se ligue a $servidor...)"
    for ($i = 0; $i -lt 20; $i++) {
        try { $e = Invoke-RestMethod http://localhost:8082/estado } catch { $e = $null }
        if ($e -and $e.servidorBD -eq $servidor) { return }
        Start-Sleep -Seconds 2
    }
}

# ---------------------------------------------------------------------
Cabecalho 1 "Como a réplica é criada" "a réplica copia o mestre (pg_basebackup) e depois recebe continuamente o WAL."
Executar '[regex]::Matches((Get-Content docker-compose.yml -Raw), "(?ms)^  db-beira-rep:.*?depends_on:[^\r\n]*") | ForEach-Object { $_.Value }'
Write-Host ""
Executar 'Select-String -Path db\replica.sh -Pattern "pg_basebackup|-D |standby|exec postgres" | ForEach-Object { $_.Line.Trim() }'
Write-Host ""
Executar 'Select-String -Path db\00-replicacao.sh -Pattern "ROLE|pg_hba" | ForEach-Object { $_.Line.Trim() }'
Esperado "a réplica tem PRIMARIO: db-beira e arranca com pg_basebackup -X stream -R; o mestre cria o utilizador replicador."
Dizer "Cada região tem um mestre e uma réplica. Na primeira vez, a réplica copia o mestre inteiro com o pg_basebackup. A opção -R deixa-a configurada como standby, a seguir o mestre."
Pausa

# ---------------------------------------------------------------------
Cabecalho 2 "A replicação está ativa nas 3 regiões" "o mestre envia o WAL (Write-Ahead Log) à réplica por streaming."
foreach ($db in "db-maputo", "db-beira", "db-nampula") {
    Executar "docker compose exec -T $db psql -U postgres -c ""SELECT '$db' AS mestre, application_name AS replica, client_addr, state, sync_state FROM pg_stat_replication;"""
}
Esperado "cada mestre vê a sua réplica com state = streaming e sync_state = async."
Dizer "Cada mestre está a enviar o seu diário de alterações, o WAL, à réplica. A replicação é assíncrona: o mestre não espera pela réplica para confirmar uma escrita."
Pausa

# ---------------------------------------------------------------------
Cabecalho 3 "Quem é mestre e quem é réplica" "pg_is_in_recovery() devolve f num mestre e t numa réplica."
Executar 'docker compose exec -T db-beira psql -U postgres -t -A -c "SELECT ''db-beira     -> em recuperacao (replica)? '' || pg_is_in_recovery();"'
Executar 'docker compose exec -T db-beira-rep psql -U postgres -t -A -c "SELECT ''db-beira-rep -> em recuperacao (replica)? '' || pg_is_in_recovery();"'
Executar 'Invoke-RestMethod http://localhost:8082/estado | Format-List regiao, servidorBD, modo'
Esperado "db-beira = false (mestre), db-beira-rep = true (réplica); o agente da Beira está ligado ao MESTRE."
Dizer "A réplica está em modo de recuperação contínua: está sempre a aplicar o WAL que chega do mestre. O agente escreve e lê no mestre."
Pausa

# ---------------------------------------------------------------------
Cabecalho 4 "Uma escrita no mestre aparece na réplica" "o que é escrito no mestre é reaplicado na réplica, quase de imediato."
Executar 'docker compose exec -T db-beira psql -U postgres -d scad -c "UPDATE stock SET quantidade = quantidade + 1 WHERE produto_id = ''P3'' RETURNING ''MESTRE'' AS onde, produto_id, quantidade;"'
Start-Sleep -Seconds 1
Executar 'docker compose exec -T db-beira-rep psql -U postgres -d scad -c "SELECT ''REPLICA'' AS onde, produto_id, quantidade FROM stock WHERE produto_id = ''P3'';"'
Executar 'docker compose exec -T db-beira-rep psql -U postgres -t -A -c "SELECT ''atraso da replica: '' || coalesce(round(extract(epoch FROM now() - pg_last_xact_replay_timestamp()) * 1000) || '' ms'', ''-'');"'
Esperado "a mesma quantidade de P3 no mestre e na réplica; atraso de poucos milissegundos."
Dizer "Alterei o stock no mestre e a réplica já tem o mesmo valor. É uma cópia exata, atualizada quase em tempo real."
Pausa

# ---------------------------------------------------------------------
Cabecalho 5 "A réplica é só de leitura" "no modelo mestre-escravo, só o mestre aceita escritas."
Executar 'docker compose exec -T db-beira-rep psql -U postgres -d scad -c "UPDATE stock SET quantidade = 0 WHERE produto_id = ''P3'';" 2>&1 | ForEach-Object { "$_" }'
Esperado "ERROR: cannot execute UPDATE in a read-only transaction."
Dizer "A réplica recusa escritas. Assim não há conflitos: só existe uma fonte de verdade, o mestre."
Pausa

# ---------------------------------------------------------------------
Cabecalho 6 "Failover: o mestre cai" "o driver JDBC tem os dois servidores e liga-se à réplica se o mestre falhar (preferPrimary)."
Executar 'Select-String -Path docker-compose.yml -Pattern "DB_URL: .*db-beira" | ForEach-Object { $_.Line.Trim() }'
Executar 'docker compose stop db-beira 2>$null'
EsperarServidor "db-beira-rep"
Executar 'Invoke-RestMethod http://localhost:8082/estado | Format-List regiao, baseDeDados, servidorBD, modo'
Executar 'Invoke-RestMethod http://localhost:8082/stock | Format-Table produto_id, nome, regiao, quantidade -AutoSize'
Esperado "o agente da Beira passou para db-beira-rep em modo RÉPLICA e continua a ler o stock. No painel, o cartão da Beira mostra RÉPLICA (amarelo)."
Dizer "O mestre da Beira caiu, mas os dados continuam acessíveis: o agente passou automaticamente para a réplica. Por agora só pode ler, porque a réplica não aceita escritas."
Pausa

# ---------------------------------------------------------------------
Cabecalho 7 "Promoção: a réplica passa a mestre" "pg_promote() termina a recuperação e a réplica passa a aceitar escritas."
Executar 'docker compose exec -T db-beira-rep psql -U postgres -t -A -c "SELECT ''promovida: '' || pg_promote();"'
Start-Sleep -Seconds 3
Executar 'docker compose exec -T db-beira-rep psql -U postgres -t -A -c "SELECT ''db-beira-rep -> em recuperacao (replica)? '' || pg_is_in_recovery();"'
Executar 'docker compose exec -T db-beira-rep psql -U postgres -d scad -c "UPDATE stock SET quantidade = quantidade + 1 WHERE produto_id = ''P3'' RETURNING ''NOVO MESTRE'' AS onde, produto_id, quantidade;"'
EsperarServidor "db-beira-rep"
Executar 'Invoke-RestMethod http://localhost:8082/estado | Format-List regiao, servidorBD, modo'
Esperado "pg_is_in_recovery = false; a escrita é aceite; o agente mostra db-beira-rep como MESTRE."
Dizer "Promovi a réplica a mestre: a região da Beira voltou a aceitar escritas, sem perder os dados. Neste projeto a promoção é manual; numa instalação real seria automática, por exemplo com o Patroni."
Pausa

# ---------------------------------------------------------------------
Cabecalho 8 "Split-brain: porque o antigo mestre não pode voltar assim" "se o db-beira voltasse agora, haveria DOIS mestres com dados diferentes."
Info "NÃO vamos ligar o db-beira antigo. Se o fizéssemos, ele voltaria como mestre, com os dados de antes da falha,"
Info "enquanto o db-beira-rep (o novo mestre) já tem escritas novas: é o split-brain (cérebro dividido)."
Esperado "explicação: o antigo mestre tem de ser reconstruído como réplica do novo (pg_rewind ou novo pg_basebackup)."
Dizer "O antigo mestre não pode voltar diretamente, porque ficaríamos com dois mestres e dados divergentes. Teria de ser reconstruído como réplica do novo mestre."
Pausa

# ---------------------------------------------------------------------
Cabecalho 9 "Repor a região da Beira" "reconstruir o mestre e a réplica da Beira a partir dos scripts iniciais."
$r = Read-Host " Repor agora a região da Beira (dados da Beira voltam aos iniciais)? [S/n]"
if ($r -ne "n") {
    Executar 'docker compose rm -sf db-beira db-beira-rep 2>$null'
    Executar 'docker volume rm scad_dados-beira scad_dados-beira-rep'
    Executar 'docker compose up -d db-beira db-beira-rep 2>$null'
    Info "(a aguardar 25 s que o mestre arranque e a réplica faça a cópia inicial...)"
    Start-Sleep -Seconds 25
    Executar 'docker compose restart agente-beira robo-beira 2>$null'
    EsperarServidor "db-beira"
    Executar 'Invoke-RestMethod http://localhost:8082/estado | Format-List regiao, servidorBD, modo'
    Executar 'docker compose exec -T db-beira psql -U postgres -c "SELECT application_name AS replica, state FROM pg_stat_replication;"'
    Esperado "db-beira de novo como MESTRE e db-beira-rep em streaming."
}

Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " RESUMO PARA DIZER" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host " Cada região tem um mestre e uma réplica, com replicação assíncrona por streaming" -ForegroundColor Magenta
Write-Host " do WAL. A réplica é uma cópia exata, só de leitura. Se o mestre cair, o agente"   -ForegroundColor Magenta
Write-Host " passa automaticamente para a réplica (failover no driver JDBC) e os dados"        -ForegroundColor Magenta
Write-Host " continuam acessíveis; com pg_promote, a réplica passa a mestre e aceita escritas." -ForegroundColor Magenta
Write-Host " O antigo mestre não pode voltar diretamente, para evitar o split-brain."          -ForegroundColor Magenta
Write-Host ""
