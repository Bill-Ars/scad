package mz.ac.isutc.scad.agente;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Exige login apenas no painel web. As restantes rotas (/tx, /ra, /relogio, /estado, /stock...) ficam
 * abertas porque os nos chamam-nas entre si sem sessao; o mesmo vale para ROTAS_ENTRE_NOS.
 */
@Component
public class LoginFiltro extends OncePerRequestFilter {

    private static final Set<String> ROTAS_ENTRE_NOS = Set.of("/painel/ordens", "/painel/auditoria", "/painel/movimentos");

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        // getServletPath (e nao getRequestURI) para ignorar parametros como /painel;x=1/resumo
        String caminho = pedido.getServletPath();
        boolean pagina = caminho.equals("/") || caminho.equals("/index.html");
        boolean api = caminho.startsWith("/painel/") && !ROTAS_ENTRE_NOS.contains(caminho);

        // Sem isto, o navegador pode mostrar o painel guardado em cache sem passar pelo login
        if (pagina) resposta.setHeader("Cache-Control", "no-store");

        // Area do cliente: exige uma conta de cliente (a senha da gestao nao serve)
        if (caminho.equals("/loja.html")) {
            resposta.setHeader("Cache-Control", "no-store");
            if (ContaController.clienteDaSessao(pedido) == null) {
                resposta.sendRedirect("/login.html?cliente");
                return;
            }
        }

        if ((pagina || api) && !autenticado(pedido)) {
            if (pagina) resposta.sendRedirect("/login.html?gestao");
            else resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        cadeia.doFilter(pedido, resposta);
    }

    private static boolean autenticado(HttpServletRequest pedido) {
        HttpSession sessao = pedido.getSession(false);
        return sessao != null && sessao.getAttribute(LoginController.ATRIBUTO_SESSAO) != null;
    }
}
