-- Dados de demonstracao (usados pelos scripts de testes/): carregados com zerar.ps1 -Demo
INSERT INTO produto (id, nome, unidade, preco) VALUES
  ('P1','Arroz 25 kg','saco',1850.00), ('P2','Óleo alimentar 5 L','garrafão',950.00),
  ('P3','Açúcar 10 kg','saco',780.00), ('P4','Farinha de milho 25 kg','saco',1450.00)
ON CONFLICT (id) DO NOTHING;
INSERT INTO stock VALUES ('P1','Nampula',70,50,'F1'), ('P2','Nampula',55,40,'F1'), ('P3','Nampula',45,30,'F2'), ('P4','Nampula',110,60,'F2')
ON CONFLICT DO NOTHING;
