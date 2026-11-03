-- Fragmento horizontal: STOCK_Beira = sigma regiao='Beira' (STOCK)
CREATE TABLE stock (
  produto_id     VARCHAR(10) NOT NULL REFERENCES produto(id),
  regiao         VARCHAR(20) NOT NULL CHECK (regiao = 'Beira'),
  quantidade     INT NOT NULL CHECK (quantidade >= 0),
  limite_minimo  INT NOT NULL CHECK (limite_minimo >= 0),
  fornecedor_id  VARCHAR(10) NOT NULL REFERENCES fornecedor(id),
  PRIMARY KEY (produto_id, regiao)
);
CREATE TABLE ordem_compra (
  id             VARCHAR(40) PRIMARY KEY,
  produto_id     VARCHAR(10) NOT NULL REFERENCES produto(id),
  fornecedor_id  VARCHAR(10) NOT NULL REFERENCES fornecedor(id),
  regiao         VARCHAR(20) NOT NULL CHECK (regiao = 'Beira'),
  quantidade     INT NOT NULL CHECK (quantidade > 0),
  estado         VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
  criada_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auditoria (
  id         BIGSERIAL PRIMARY KEY,
  no_origem  VARCHAR(30) NOT NULL,
  hora       TIMESTAMPTZ NOT NULL DEFAULT now(),
  operacao   VARCHAR(40) NOT NULL,
  detalhe    TEXT
);
INSERT INTO stock VALUES ('P1','Beira',90,50,'F1'), ('P2','Beira',35,40,'F1'), ('P3','Beira',60,30,'F2'), ('P4','Beira',150,60,'F2');
