package mz.ac.isutc.scad.agente;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Contas dos clientes (pessoas particulares ou instituicoes): criar conta, entrar e sair. */
@RestController
@RequestMapping("/conta")
public class ContaController {

    static final String ATRIBUTO_CLIENTE = "scad.cliente";
    static final List<String> TIPOS = List.of("Particular", "Hospital", "Banco", "Escola", "Empresa", "ONG", "Outro");
    private static final List<String> REGIOES = List.of("Maputo", "Beira", "Nampula");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private final JdbcTemplate jdbc;
    private final CoordenadorController coordenador;
    private final MotorCompras motor;

    public ContaController(JdbcTemplate jdbc, CoordenadorController coordenador, MotorCompras motor) {
        this.jdbc = jdbc;
        this.coordenador = coordenador;
        this.motor = motor;
    }

    /** A conta fica registada nos 3 armazens por 2PC: o cliente pode entrar a partir de qualquer um. */
    @PostMapping("/registar")
    public ResponseEntity<Map<String, Object>> registar(@RequestBody Map<String, Object> p, HttpServletRequest pedido) {
        String nome = texto(p, "nome"), tipo = texto(p, "tipo"), email = texto(p, "email").toLowerCase(), senha = senha(p);
        if (nome.length() < 2 || nome.length() > 60) return erro(HttpStatus.BAD_REQUEST, "Indique o seu nome ou o da instituição (2 a 60 caracteres).");
        if (!TIPOS.contains(tipo)) return erro(HttpStatus.BAD_REQUEST, "Escolha o tipo de cliente.");
        if (email.length() > 120 || !EMAIL.matcher(email).matches()) return erro(HttpStatus.BAD_REQUEST, "Indique um email válido.");
        if (senha.length() < 8 || senha.length() > 100) return erro(HttpStatus.BAD_REQUEST, "A senha tem de ter pelo menos 8 caracteres.");
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM cliente WHERE email = ?", Integer.class, email);
        if (existe != null && existe > 0) return erro(HttpStatus.CONFLICT, "Já existe uma conta com este email. Entre com a sua senha.");

        String id = "CLI-" + System.currentTimeMillis();
        Map<String, Map<String, Object>> partes = new LinkedHashMap<>();
        for (String r : REGIOES) {
            partes.put(r, Map.of("tipo", "CLIENTE_CRIAR", "id", id, "nome", nome, "tipoCliente", tipo,
                    "email", email, "senhaHash", Senhas.gerar(senha)));
        }
        Map<String, Object> tx = coordenador.executar2pc("cliente-" + System.currentTimeMillis(), partes, false);
        if (!"COMMIT".equals(tx.get("decisao"))) {
            boolean duplicado = String.valueOf(tx.get("fase1_votos")).contains("cliente_email_key");
            return erro(duplicado ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE, duplicado
                    ? "Já existe uma conta com este email. Entre com a sua senha."
                    : "Não foi possível criar a conta agora: um dos armazéns não respondeu. Tente daqui a pouco.");
        }
        motor.auditar("CLIENTE-REGISTADO", id + " " + nome + " (" + tipo + ")");
        return ResponseEntity.ok(iniciarSessao(pedido, id, nome, tipo, email));
    }

    @PostMapping("/entrar")
    public ResponseEntity<Map<String, Object>> entrar(@RequestBody Map<String, Object> p, HttpServletRequest pedido) {
        String email = texto(p, "email").toLowerCase();
        List<Map<String, Object>> c = jdbc.queryForList("SELECT id, nome, tipo, email, senha_hash FROM cliente WHERE email = ?", email);
        String guardada = c.isEmpty() ? null : (String) c.get(0).get("senha_hash");
        if (!Senhas.confere(senha(p), guardada)) return erro(HttpStatus.UNAUTHORIZED, "Email ou senha incorretos.");
        Map<String, Object> l = c.get(0);
        return ResponseEntity.ok(iniciarSessao(pedido, (String) l.get("id"), (String) l.get("nome"), (String) l.get("tipo"), email));
    }

    @PostMapping("/sair")
    public Map<String, Object> sair(HttpServletRequest pedido) {
        HttpSession s = pedido.getSession(false);
        if (s != null) s.removeAttribute(ATRIBUTO_CLIENTE);
        return Map.of("ok", true);
    }

    @GetMapping("/eu")
    public ResponseEntity<Map<String, Object>> eu(HttpServletRequest pedido) {
        Map<String, Object> c = clienteDaSessao(pedido);
        return c == null ? erro(HttpStatus.UNAUTHORIZED, "sem sessao") : ResponseEntity.ok(c);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> clienteDaSessao(HttpServletRequest pedido) {
        HttpSession s = pedido.getSession(false);
        return s == null ? null : (Map<String, Object>) s.getAttribute(ATRIBUTO_CLIENTE);
    }

    private static Map<String, Object> iniciarSessao(HttpServletRequest pedido, String id, String nome, String tipo, String email) {
        pedido.getSession(true);
        pedido.changeSessionId();                      // evita fixacao de sessao
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("nome", nome);
        c.put("tipo", tipo);
        c.put("email", email);
        pedido.getSession().setAttribute(ATRIBUTO_CLIENTE, c);
        return c;
    }

    private static String texto(Map<String, Object> p, String campo) {
        return p.get(campo) instanceof String s ? s.trim() : "";
    }

    private static String senha(Map<String, Object> p) {
        return p.get("senha") instanceof String s ? s : "";
    }

    private static ResponseEntity<Map<String, Object>> erro(HttpStatus estado, String mensagem) {
        return ResponseEntity.status(estado).body(Map.of("erro", mensagem));
    }
}
