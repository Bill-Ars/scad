# SCAD - Sistema de Compras Automaticas Distribuidas

Projeto do Teste 2 de **Sistemas Distribuidos e Paralelos** (LECC 31, ISUTC, 2026).

O SCAD gere o stock de tres armazens regionais (Maputo, Beira e Nampula). Os clientes (pessoas ou instituicoes, como
hospitais e bancos) fazem e pagam pedidos a um armazem; o stock desce e, quando um produto atinge o stock minimo, o
SCAD encomenda-o automaticamente ao fornecedor, de forma descentralizada e tolerante a falhas.

## Inicio rapido

Requisitos: Docker Desktop (Windows com WSL2, macOS ou Linux) com pelo menos **8 GB** de memoria. Nao e preciso Java
nem Maven: tudo e compilado dentro dos contentores.

```powershell
git clone https://github.com/Bill-Ars/scad.git
cd scad
docker compose up -d --build     # a primeira vez demora alguns minutos
docker compose ps                # 20 contentores em execucao
```

Esperar 1 a 2 minutos e abrir **http://localhost:8081**. O sistema arranca **vazio**: so existem os 3 armazens e os
2 fornecedores; os produtos registam-se na area de gestao.

## Enderecos e acessos

| O que | Endereco | Acesso |
|---|---|---|
| **Sistema** (clientes e gestao) | http://localhost:8081 · http://localhost:8082 · http://localhost:8083 | Um por armazem; qualquer um serve |
| Area de gestao | Na pagina de entrada, separador **Gestao** | Senha `admin1234` (variavel `PAINEL_SENHA`) |
| Area do cliente | Na pagina de entrada, **Sou cliente** | Criar conta ou entrar com email e senha |
| Outro computador da mesma rede | http://IP-deste-computador:8081 | Abrir as portas 8081-8083 na firewall do Windows |
| Fornecedor REST: ordens recebidas | http://localhost:8091/api/ordens | - |
| Fornecedor SOAP: contrato WSDL | http://localhost:8092/ws?wsdl | - |
| HDFS: faturas, relatorios e logs | http://localhost:9870 | - |
| Bases de dados (PostgreSQL) | localhost:5441-5443 (mestres), 5451-5453 (replicas) | Base `scad`, utilizador `postgres`, senha `scad` |

Para abrir as portas na firewall (PowerShell como administrador) e voltar a fecha-las no fim:

```powershell
New-NetFirewallRule -DisplayName "SCAD 8081-8083" -Direction Inbound -Protocol TCP -LocalPort 8081-8083 -RemoteAddress LocalSubnet -Action Allow
Remove-NetFirewallRule -DisplayName "SCAD 8081-8083"
```

## Como usar

| Area | Quem | O que faz |
|---|---|---|
| **Cliente** | Pessoas particulares ou instituicoes (hospitais, bancos, escolas...) | Cria conta, escolhe um armazem, junta um ou varios produtos, paga (M-Pesa, e-Mola, cartao ou transferencia), recebe o recibo e ve os seus pedidos |
| **Gestao** | Equipa do SCAD | Produtos (preco, armazens que o vendem e, em cada um, stock inicial e minimo), vendas e entradas, clientes e o que compraram, compras ao fornecedor, faturas, estado do sistema e guiao da apresentacao |

Demonstracao em 5 passos:

1. **Gestao > Produtos > Novo produto**: nome, preco por unidade, os armazens que o vendem e, em cada um, o stock
   inicial e o minimo (os armazens e o minimo podem ser alterados depois em **Editar**). Cada armazem tem os seus
   produtos: na area do cliente so aparecem os do armazem escolhido.
2. Noutro separador, **Sou cliente > Criar conta**.
3. Escolher um armazem, juntar produtos e pagar (por exemplo, M-Pesa 84 123 4567).
4. Na gestao, a receita sobe no **Inicio** e o pedido aparece em **Clientes** e em **Vendas e entradas**.
5. Pedir ate o stock chegar ao minimo: o SCAD encomenda sozinho (**Compras ao fornecedor**) e a mercadoria chega 30 s depois.

Como funciona por dentro:

1. O pedido baixa o stock do armazem escolhido numa so transacao (todos os produtos ou nenhum). O preco vem sempre da
   base de dados e o pagamento fica registado com uma referencia.
2. O agente de cada armazem verifica o stock de 10 em 10 s (e logo a seguir a cada pedido). Ao atingir o minimo,
   encomenda ao fornecedor o necessario para repor o stock ate ao dobro do minimo.
3. A ordem fica `ENVIADA` quando o fornecedor a aceita; se ele falhar, fica `FALHOU`, sem fatura, e a compra repete-se de 30 em 30 s.
4. A mercadoria chega 30 s depois (variavel `ENTREGA_S`): o stock sobe e a ordem passa a `RECEBIDA`.

As contas de cliente e a ficha de cada produto (nome, unidade, preco) ficam nos 3 armazens (tabelas replicadas,
gravadas por 2PC). O stock e um fragmento horizontal por regiao: um produto so tem linha de stock nos armazens que o
vendem, e e isso que define o catalogo de cada armazem. Registar, editar (incluindo acrescentar ou retirar um
armazem) e apagar um produto e uma unica transacao 2PC entre os 3 nos: a ficha fica em todos e o stock so nos
escolhidos, ou nada muda. Os pedidos e os pagamentos ficam no armazem escolhido. As senhas sao guardadas com PBKDF2.

O **pagamento e simulado**, para demonstracao: nao liga a nenhum operador (M-Pesa, e-Mola, banco), nao cobra dinheiro e
nao pede dados de cartao. Do numero de telemovel so ficam guardados os dois primeiros e os dois ultimos digitos.

## Scripts principais

Todos correm na pasta `scad`, no PowerShell do Windows.

| Script | Para que serve | Apaga dados? |
|---|---|---|
| `zerar.ps1` | Recomeca tudo do zero, com os armazens vazios (cerca de 7 min) | **Sim, tudo**, sem pedir confirmacao |
| `zerar.ps1 -Demo` | Igual, mas com 4 produtos de exemplo (necessarios para `testes/` e `demo/`) | **Sim, tudo** |
| `zerar-armazem.ps1 -Regiao Beira` | Recomeca so um armazem; os outros dois nao sao tocados | **So essa regiao** (pede confirmacao) |
| `testes\teste-2pc.ps1` | SBDD: 2PC (commit, abort, participante em falha, recuperacao do coordenador) | Nao |
| `testes\teste-sod.ps1` | SOD: Berkeley e Ricart-Agrawala | Nao |
| `testes\teste-dfs.ps1` | DFS: HDFS, leitura por outro no e queda de um DataNode | Nao |
| `testes\teste-corba.ps1` | CORBA: `consultarSaldo`, `dispararAlerta` e queda de um robo | Nao |
| `testes\teste-sws.ps1` | Servicos Web: REST, SOAP, novas tentativas e idempotencia | Nao |
| `testes\teste-integracao.ps1` | Compra automatica de ponta a ponta (REST e SOAP) | Nao |
| `demo\demo-0-distribuicao.ps1` a `demo-4-ricart-agrawala.ps1` | Guioes da defesa: distribuicao, fragmentacao, replicacao, 2PC e exclusao mutua | `demo-2` reconstroi a Beira |
| `demo\simulacao-real.ps1` | Um dia de utilizacao, contado passo a passo | Nao |

```powershell
powershell -ExecutionPolicy Bypass -File .\zerar.ps1                        # tudo do zero, armazens vazios
powershell -ExecutionPolicy Bypass -File .\zerar.ps1 -Demo                  # com produtos de exemplo
powershell -ExecutionPolicy Bypass -File .\zerar-armazem.ps1 -Regiao Beira  # so um armazem
powershell -ExecutionPolicy Bypass -File .\testes\teste-integracao.ps1      # exemplo de um teste
powershell -ExecutionPolicy Bypass -File .\demo\simulacao-real.ps1          # exemplo de um guiao
```

Os scripts de `testes/` e de `demo/` usam os produtos de exemplo: correr antes `zerar.ps1 -Demo`. Fazem pausas
("Enter para continuar") e indicam o resultado esperado em cada passo.

O `zerar-armazem.ps1` apaga o stock, as ordens, os pedidos e a auditoria dessa regiao, copia de outro armazem as
fichas dos produtos e as contas de clientes e volta a pôr os produtos que esse armazem vendia, com stock 0 (o SCAD
repoe-os sozinho). As faturas ja guardadas no
HDFS ficam.

## Simulacao de falhas

Com o sistema aberto, parar um servico e ver a reacao; no fim, voltar a liga-lo com `start`.

| Falha | Parar | Voltar a ligar | Comportamento |
|---|---|---|---|
| Armazem (agente) | `docker compose stop agente-beira` | `docker compose start agente-beira` | Os outros armazens continuam; a Beira deixa de vender; registar produtos ou contas e recusado em todos (2PC aborta) |
| Base de dados principal | `docker compose stop db-beira` | `docker compose start db-beira` | A Beira le da replica e recusa vendas; ao voltar a principal, regressa a ela em cerca de 1 minuto |
| Promover a replica | `docker compose exec -T db-beira-rep psql -U postgres -d scad -c "SELECT pg_promote();"` | - | A Beira volta a vender, na antiga replica; o antigo mestre nao deve voltar sem ser reconstruido (evita *split-brain*) |
| DataNode | `docker compose stop datanode1` | `docker compose start datanode1` | As faturas continuam acessiveis (replicacao 3) |
| NameNode | `docker compose stop namenode` | `docker compose start namenode` | As faturas e os erros ficam em fila e sao gravados quando ele volta |
| Fornecedor | `docker compose stop fornecedor-rest` | `docker compose start fornecedor-rest` | 3 tentativas; ordem `FALHOU` sem fatura; erro no HDFS; a compra repete-se sozinha |
| Robo CORBA | `docker compose stop robo-beira` | `docker compose start robo-beira` | `TRANSIENT` para esse robo; os restantes respondem |

## Cobertura dos requisitos

| Componente do enunciado | Implementacao | Onde |
|---|---|---|
| **SOD - exclusao mutua** | Algoritmo de **Ricart-Agrawala** (relogios de Lamport, REQUEST/REPLY, desempate por id, tolerancia a nos em falha) sobre o lote `fornecedor:produto` | `agente/.../RicartAgrawala.java` |
| **SOD - relogios** | Algoritmo de **Berkeley** (mestre = Maputo; compensacao do RTT; media com rejeicao de outliers; ronda automatica de 30 em 30 s). Cada no tem um desvio simulado | `BerkeleyController.java`, `Relogio.java` |
| **SBDD - fragmentacao** | Fragmentacao **horizontal por regiao** em 3 nos (`CHECK regiao = ...`) do stock, das ordens, dos pedidos e dos pagamentos; tabelas de referencia replicadas; reconstrucao global por uniao | `db/*.sql`, `StockController.java`, `EsquemaClientes.java` |
| **SBDD - replicacao** | **Mestre-escravo** por streaming de WAL (`pg_basebackup`), uma replica por regiao; failover no driver JDBC (`preferPrimary`) e promocao com `pg_promote()` | `db/00-replicacao.sh`, `db/replica.sh` |
| **SBDD - consistencia** | **Two-Phase Commit** com `PREPARE TRANSACTION` / `COMMIT PREPARED`; decisao gravada antes da fase 2; recuperacao do coordenador; terminacao automatica de transacoes presas. Usado nas compras, nos produtos e nas contas de clientes | `CoordenadorController.java`, `ParticipanteController.java` |
| **DFS** | **HDFS**: 1 NameNode + 3 DataNodes, fator de replicacao 3; faturas PDF, logs de erros e relatorios de auditoria (CSV por no e por dia), via WebHDFS | `HdfsCliente.java`, `DfsController.java`, `RegistoDfs.java` |
| **Java / CORBA** | **JacORB 3.9**: IDL `SCAD` (`consultarSaldo`, `dispararAlerta` oneway), Naming Service, auditoria central e robos de stock regionais | `corba/` |
| **Servicos Web** | Fornecedor **REST/JSON** (201/200/400, idempotente) e fornecedor **SOAP/XML** (envelope SOAP 1.1, WSDL, `soap:Fault`); cliente com timeouts e novas tentativas | `fornecedor/`, `FornecedorCliente.java` |

## Arquitetura

Todos os nos correm em contentores Docker numa rede virtual (`scad-net`). Cada contentor tem processo, sistema de
ficheiros e endereco IP proprios e pode falhar isoladamente.

| Servico | Funcao | Porta no anfitriao |
|---|---|---|
| `agente-maputo`, `agente-beira`, `agente-nampula` | Agentes regionais e sistema web (Spring Boot 4, Java 21) | 8081, 8082, 8083 |
| `db-maputo`, `db-beira`, `db-nampula` | Fragmentos (PostgreSQL 16, mestres) | 5441, 5442, 5443 |
| `db-*-rep` | Replicas (so leitura) | 5451, 5452, 5453 |
| `namenode`, `datanode1..3` | HDFS | 9870 (interface web) |
| `corba-naming`, `auditoria`, `robo-*` | CORBA / JacORB (Java 8) | - |
| `fornecedor-rest`, `fornecedor-soap` | Fornecedores externos simulados | 8091, 8092 |

### Endpoints principais (agentes 8081-8083)

| Endpoint | Descricao |
|---|---|
| `GET /estado`, `GET /pares` | Estado do no e da base de dados; heartbeat dos outros nos |
| `GET /stock`, `GET /stock/global` | Fragmento local; reconstrucao global |
| `POST /conta/registar`, `/conta/entrar`, `/conta/sair` | Contas de cliente (registo por 2PC) |
| `GET /loja/catalogo?regiao=Beira`, `POST /loja/pedido?regiao=Beira` | Catalogo de um armazem; pedido com pagamento (exige sessao de cliente) |
| `GET /loja/meus-pedidos` | Pedidos do cliente nos 3 armazens |
| `POST /stock/movimento?produtoId=P1&tipo=SAIDA&quantidade=10` | Venda (`SAIDA`) ou entrada (`ENTRADA`) no fragmento local |
| `POST`, `PUT`, `DELETE /painel/produtos` | Registar, editar e apagar produtos, com 2PC (exige login da gestao) |
| `GET /painel/clientes` | Clientes, totais e pedidos dos 3 armazens (exige login da gestao) |
| `POST /compras/automatica?produtoId=P2` | Fluxo completo de compra automatica |
| `POST /compras/multi` | Compra multi-armazem com 2PC |
| `GET /tx/pendentes`, `GET /tx/decisao/{base}` | Transacoes preparadas; decisao conhecida por este no |
| `GET /relogio`, `POST /relogio/sincronizar` | Relogio do no; ronda de Berkeley (no mestre) |
| `POST /ra/demo?recurso=F1:P2` | Demonstracao do Ricart-Agrawala |
| `POST /fornecedores/enviar` | Envio de ordem por REST ou SOAP |
| `GET /dfs/listar`, `GET /dfs/ler?caminho=...`, `POST /dfs/auditoria/exportar` | Ficheiros no HDFS; exportar ja o relatorio de auditoria |

## Limitacoes conhecidas

- Todos os nos correm numa so maquina: simula-se a falha de processos, nao a falha fisica do anfitriao.
- O coordenador do 2PC e o NameNode do HDFS sao pontos unicos de falha (mitigados pela decisao gravada e pela
  replicacao dos blocos). Sem coordenador nem decisao conhecida, o participante espera: o 2PC bloqueia, nao adivinha.
- A promocao de uma replica e manual: ate la, as vendas dessa regiao sao recusadas.
- Com um armazem em baixo, nao e possivel criar contas nem alterar produtos (2PC), mas as contas existentes continuam a entrar.
- Se o HDFS estiver em baixo, as faturas e os erros ficam em fila na memoria do agente: reiniciar esse agente antes de o HDFS voltar perde o que estava em fila.
- As rotas entre nos (`/stock/movimento`, `/loja/local/*`, `/tx/*`...) nao exigem login: o sistema assume uma rede interna.
- O pagamento e simulado e a entrega da mercadoria e simulada pelo SCAD, 30 s depois de o fornecedor aceitar.
- O mestre de Berkeley e fixo (Maputo); nao foi implementado um algoritmo de eleicao.

## Estrutura do repositorio

```
scad/
├── docker-compose.yml     Os 20 servicos
├── agente/                Spring Boot: SOD, SBDD (2PC), DFS (WebHDFS), cliente REST/SOAP, compras, sistema web
├── corba/                 JacORB: IDL, Naming Service, auditoria e robos de stock
├── fornecedor/            Fornecedores externos simulados (REST/JSON e SOAP/XML + WSDL)
├── db/                    SQL dos fragmentos, replicacao e dados de exemplo (db/demo)
├── testes/                Scripts de teste por componente
├── demo/                  Guioes da defesa
├── docs/                  Relatorio tecnico
├── zerar.ps1              Recomeca tudo do zero
└── zerar-armazem.ps1      Recomeca so um armazem
```

Os ficheiros `docker-compose.pc1.yml`, `docker-compose.pc2.yml` e a pasta `multi/` sao uma configuracao para dividir o
sistema por dois computadores; precisam de um ficheiro `multi/ips.env` (nao incluido) e nao foram verificados.
