-- Tabelas REPLICADAS integralmente em todos os nos
CREATE TABLE armazem (
  id      SERIAL PRIMARY KEY,
  nome    VARCHAR(60) NOT NULL,
  regiao  VARCHAR(20) NOT NULL UNIQUE
);
CREATE TABLE produto (
  id       VARCHAR(10) PRIMARY KEY,
  nome     VARCHAR(80) NOT NULL,
  unidade  VARCHAR(20) NOT NULL
);
CREATE TABLE fornecedor (
  id               VARCHAR(10) PRIMARY KEY,
  nome             VARCHAR(80) NOT NULL,
  tipo_integracao  VARCHAR(4)  NOT NULL CHECK (tipo_integracao IN ('REST','SOAP')),
  endpoint         VARCHAR(200) NOT NULL
);
INSERT INTO armazem (nome, regiao) VALUES
  ('Armazem Sul','Maputo'), ('Armazem Centro','Beira'), ('Armazem Norte','Nampula');
INSERT INTO produto VALUES
  ('P1','Arroz 25 kg','saco'), ('P2','Oleo alimentar 5 L','garrafao'),
  ('P3','Acucar 10 kg','saco'), ('P4','Farinha de milho 25 kg','saco');
INSERT INTO fornecedor VALUES
  ('F1','Distribuidora Moderna','REST','http://fornecedor-rest:8080/api/ordens'),
  ('F2','Grossista Legado','SOAP','http://fornecedor-soap:8080/ws');
