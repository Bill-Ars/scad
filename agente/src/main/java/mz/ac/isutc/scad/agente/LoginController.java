package mz.ac.isutc.scad.agente;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Login do painel web: uma unica senha partilhada, guardada na sessao HTTP do no. */
@RestController
public class LoginController {

    static final String ATRIBUTO_SESSAO = "scad.autenticado";

    private final byte[] senha;

    public LoginController(@Value("${scad.painel.senha}") String senha) {
        this.senha = senha.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestParam String senha, HttpServletRequest pedido) {
        if (!MessageDigest.isEqual(this.senha, senha.getBytes(StandardCharsets.UTF_8))) {
            return redirecionar("/login.html?erro");
        }
        pedido.getSession(true).setAttribute(ATRIBUTO_SESSAO, true);
        pedido.changeSessionId();
        return redirecionar("/");
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest pedido) {
        HttpSession sessao = pedido.getSession(false);
        if (sessao != null) sessao.invalidate();
        return redirecionar("/login.html");
    }

    private static ResponseEntity<Void> redirecionar(String destino) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destino)).build();
    }
}
