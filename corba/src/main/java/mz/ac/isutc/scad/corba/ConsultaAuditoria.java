package mz.ac.isutc.scad.corba;

import SCAD.AgenteStock;
import SCAD.AgenteStockHelper;
import SCAD.AlertaStock;
import SCAD.Auditoria;
import SCAD.AuditoriaHelper;
import SCAD.ProdutoInexistente;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;

import java.util.Date;

/** Cliente da auditoria: consulta o saldo em cada robo regional e lista os alertas recebidos. */
public class ConsultaAuditoria {
    public static void main(String[] args) throws Exception {
        String produto = args.length > 0 ? args[0] : "P2";
        ORB orb = ORB.init(new String[0], null);
        NamingContextExt nc = Naming.contexto(orb);

        System.out.println("=== consultarSaldo(\"" + produto + "\") em cada robo, via CORBA/IIOP ===");
        for (String regiao : new String[]{"Maputo", "Beira", "Nampula"}) {
            long t0 = System.nanoTime();
            try {
                AgenteStock robo = AgenteStockHelper.narrow(nc.resolve_str("AgenteStock-" + regiao));
                int q = robo.consultarSaldo(produto);
                System.out.println(String.format("  %-8s -> %4d unidades   (%.2f ms)",
                        robo.regiao(), q, (System.nanoTime() - t0) / 1e6));
            } catch (ProdutoInexistente e) {
                System.out.println(String.format("  %-8s -> ProdutoInexistente: %s", regiao, e.motivo));
            } catch (Exception e) {
                System.out.println(String.format("  %-8s -> INDISPONIVEL (%s)", regiao, e.getClass().getSimpleName()));
            }
        }

        System.out.println("=== alertas recebidos pela Auditoria (dispararAlerta) ===");
        Auditoria auditoria = AuditoriaHelper.narrow(nc.resolve_str("Auditoria"));
        for (AlertaStock a : auditoria.listarAlertas()) {
            System.out.println(String.format("  %-8s %-3s %4d < %4d   %s",
                    a.regiao, a.produtoId, a.quantidadeAtual, a.limiteMinimo, new Date(a.timestamp)));
        }
        System.exit(0);
    }
}
