# SCAD - Sistema de Compras Automaticas Distribuidas

Projeto do Teste 2 de **Sistemas Distribuidos e Paralelos** (LECC 31, ISUTC, 2026).

O SCAD monitoriza o stock de tres armazens regionais (Maputo, Beira e Nampula) e, quando um produto
desce abaixo do stock minimo, efetua automaticamente uma ordem de compra ao fornecedor, de forma
descentralizada e tolerante a falhas.

## Cobertura dos requisitos

| Componente do enunciado | Implementacao | Onde |
|---|---|---|
| **SOD - exclusao mutua** | Algoritmo de **Ricart-Agrawala** (relogios de Lamport, REQUEST/REPLY, desempate por id, tolerancia a nos em falha) sobre o lote `fornecedor:produto` | `agente/.../RicartAgrawala.java` |
| **SOD - relogios** | Algoritmo de **Berkeley** (mestre = Maputo; compensacao do RTT; media com rejeicao de outliers; envio de ajustes). Cada no tem um desvio simulado | `agente/.../BerkeleyController.java`, `Relogio.java` |
| **SBDD - fragmentacao** | Fragmentacao **horizontal por regiao** em 3 nos (`CHECK regiao = ...`); tabelas de referencia replicadas; reconstrucao global por uniao | `db/*.sql`, `StockController.java` |
| **SBDD - replicacao** | **Mestre-escravo** por streaming de WAL (`pg_basebackup`), uma replica por regiao; failover no driver JDBC (`preferPrimary`) e promocao com `pg_promote()` | `db/00-replicacao.sh`, `db/replica.sh` |
| **SBDD - consistencia** | **Two-Phase Commit** com `PREPARE TRANSACTION` / `COMMIT PREPARED` do PostgreSQL; decisao gravada antes da fase 2; recuperacao do coordenador | `CoordenadorController.java`, `ParticipanteController.java` |
| **DFS** | **HDFS**: 1 NameNode + 3 DataNodes, fator de replicacao 3; faturas PDF, logs de erro e auditoria via WebHDFS | `HdfsCliente.java`, `DfsController.java` |
| **Java / CORBA** | **JacORB 3.9**: IDL `SCAD` (`consultarSaldo`, `dispararAlerta` oneway), Naming Service, auditoria central e robos de stock regionais | `corba/` |
| **Servicos Web** | Fornecedor **REST/JSON** (201/200/400, idempotente) e fornecedor **SOAP/XML** (envelope SOAP 1.1, WSDL, `soap:Fault`); cliente com timeouts e novas tentativas | `fornecedor/`, `FornecedorCliente.java` |

## Arquitetura

Todos os nos correm em contentores Docker numa rede virtual (`scad-net`). Cada contentor tem processo,
sistema de ficheiros e endereco IP proprios e pode falhar isoladamente.

| Servico | Funcao | Porta no anfitriao |
|---|---|---|
| `agente-maputo`, `agente-beira`, `agente-nampula` | Agentes regionais (Spring Boot 4, Java 21) | 8081, 8082, 8083 |
| `db-maputo`, `db-beira`, `db-nampula` | Fragmentos (PostgreSQL 16, mestres) | 5441, 5442, 5443 |
| `db-*-rep` | Replicas (escravos, so leitura) | 5451, 5452, 5453 |
| `namenode`, `datanode1..3` | HDFS | 9870 (interface web) |
| `corba-naming`, `auditoria`, `robo-*` | CORBA / JacORB (Java 8) | - |
| `fornecedor-rest`, `fornecedor-soap` | Fornecedores externos simulados | 8091, 8092 |

Bases de dados: base `scad`, utilizador `postgres`, palavra-passe `scad`.

## Requisitos

- Docker Desktop (Windows com WSL2, macOS ou Linux) com pelo menos **8 GB** de memoria atribuida.
- Nao e preciso Java nem Maven: tudo e compilado dentro dos contentores (builds multi-etapa).

## Deploy

```bash
git clone https://github.com/Bill-Ars/scad.git
cd scad
docker compose up -d --build      # a primeira vez demora alguns minutos (descarga de imagens e dependencias)
docker compose ps                 # 21 contentores em execucao
```

Esperar cerca de 60 segundos ate todos os servicos arrancarem. Para recomecar do zero:
`docker compose down -v && docker compose up -d`.

## Testes e demonstracao

Os scripts em `testes/` (PowerShell, Windows) executam cada demonstracao com pausas e indicam o resultado esperado:

```powershell
powershell -ExecutionPolicy Bypass -File .\testes\teste-2pc.ps1         # SBDD: 2PC (commit, abort, falha, recuperacao)
powershell -ExecutionPolicy Bypass -File .\testes\teste-sod.ps1         # SOD: Berkeley e Ricart-Agrawala
powershell -ExecutionPolicy Bypass -File .\testes\teste-dfs.ps1         # DFS: HDFS e queda de DataNode
powershell -ExecutionPolicy Bypass -File .\testes\teste-corba.ps1       # CORBA: consultarSaldo e dispararAlerta
powershell -ExecutionPolicy Bypass -File .\testes\teste-sws.ps1         # Servicos Web: REST, SOAP, retries, idempotencia
powershell -ExecutionPolicy Bypass -File .\testes\teste-integracao.ps1  # Compra automatica de ponta a ponta
```

### Endpoints principais (agentes 8081-8083)

| Endpoint | Descricao |
|---|---|
| `GET /estado`, `GET /pares` | Estado do no e da base de dados; heartbeat dos outros nos |
| `GET /stock`, `GET /stock/global` | Fragmento local; reconstrucao global |
| `POST /compras/automatica?produtoId=P2` | Fluxo completo de compra automatica |
| `POST /compras/multi` | Compra multi-armazem com 2PC |
| `GET /tx/pendentes` | Transacoes preparadas a espera da decisao |
| `GET /relogio`, `POST /relogio/sincronizar` | Relogio do no; ronda de Berkeley (no mestre) |
| `POST /ra/demo?recurso=F1:P2` | Demonstracao do Ricart-Agrawala |
| `POST /fornecedores/enviar` | Envio de ordem por REST ou SOAP |
| `GET /dfs/listar`, `GET /dfs/ler?caminho=...` | Ficheiros no HDFS |

### Simulacao de falhas

| Falha | Comando | Comportamento esperado |
|---|---|---|
| Agente regional | `docker compose stop agente-beira` | Heartbeat deteta a falha; Ricart-Agrawala e 2PC continuam/abortam de forma segura |
| Mestre de uma regiao | `docker compose stop db-beira` | Leituras passam para `db-beira-rep`; `pg_promote()` repoe as escritas |
| Participante do 2PC | `docker compose stop agente-beira` durante uma compra | Voto NAO por timeout -> ABORT global |
| DataNode | `docker compose stop datanode1` | Faturas continuam legiveis (replicacao 3) |
| Robo de stock | `docker compose stop robo-beira` | `TRANSIENT` para esse robo; restantes respondem |
| Fornecedor | `docker compose stop fornecedor-rest` | 3 tentativas com espera crescente; reenvio idempotente |

## Limitacoes conhecidas

- Todos os nos correm numa so maquina: simula-se a falha de processos, nao a falha fisica do anfitriao.
- O coordenador do 2PC e o NameNode do HDFS sao pontos unicos de falha (mitigados pelo log da decisao e pela
  replicacao dos blocos, respetivamente).
- Apos promover uma replica, o antigo mestre nao deve voltar sem ser reconstruido (evita *split-brain*).
- O mestre de Berkeley e fixo (Maputo); nao foi implementado um algoritmo de eleicao.

## Estrutura do repositorio

```
scad/
├── docker-compose.yml
├── agente/        Spring Boot: SOD, SBDD (2PC), DFS (WebHDFS), cliente REST/SOAP, fluxo de compra
├── corba/         JacORB: IDL, Naming Service, auditoria e robos de stock
├── fornecedor/    Fornecedores externos simulados (REST/JSON e SOAP/XML + WSDL)
├── db/            Scripts SQL dos fragmentos e scripts de replicacao
└── testes/        Scripts de teste e demonstracao
```
