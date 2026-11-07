package mz.ac.isutc.scad.corba;

import SCAD.AgenteStockPOA;
import SCAD.AlertaStock;
import SCAD.Auditoria;
import SCAD.AuditoriaHelper;
import SCAD.ProdutoInexistente;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Servant do robo de stock: responde a consultarSaldo com o stock da base de dados regional. */
class AgenteStockImpl extends AgenteStockPOA {

    private final String regiao;

    AgenteStockImpl(String regiao) {
        this.regiao = regiao;
    }

    @Override
    public String regiao() {
        return regiao;
    }

    @Override
    public int consultarSaldo(String produtoId) throws ProdutoInexistente {
        try (Connection c = RoboStock.ligar();
             PreparedStatement ps = c.prepareStatement("SELECT quantidade FROM stock WHERE produto_id = ?")) {
            ps.setString(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int q = rs.getInt(1);
                    System.out.println("[ROBO " + regiao + "] consultarSaldo(" + produtoId + ") = " + q);
                    return q;
                }
            }
        } catch (SQLException e) {
            throw new org.omg.CORBA.TRANSIENT("base de dados de " + regiao + " indisponivel: " + e.getMessage());
        }
        throw new ProdutoInexistente("produto " + produtoId + " nao existe em " + regiao);
    }
}

/**
 * Robo/agente de stock local: e servidor CORBA (AgenteStock) e cliente CORBA da Auditoria.
 * De 10 em 10 segundos verifica o stock e dispara um alerta (oneway) quando um produto atinge o minimo.
 */
public class RoboStock {

    static final String REGIAO = System.getenv().getOrDefault("REGIAO", "Maputo");
    static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://db-maputo:5432/scad");
    static final String DB_USER = System.getenv().getOrDefault("DB_USER", "postgres");
    static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "scad");

    static Connection ligar() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    public static void main(String[] args) throws Exception {
        final ORB orb = ORB.init(args, null);
        POA rootPoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPoa.the_POAManager().activate();

        // 1. Servidor: regista-se no Naming Service como AgenteStock-<Regiao>
        org.omg.CORBA.Object ref = rootPoa.servant_to_reference(new AgenteStockImpl(REGIAO));
        NamingContextExt nc = Naming.contexto(orb);
        nc.rebind(nc.to_name("AgenteStock-" + REGIAO), ref);
        System.out.println("[ROBO " + REGIAO + "] registado como 'AgenteStock-" + REGIAO + "'");

        Thread orbThread = new Thread(new Runnable() { public void run() { orb.run(); } });
        orbThread.setDaemon(true);
        orbThread.start();

        // 2. Cliente: obtem a referencia da Auditoria
        Auditoria auditoria = AuditoriaHelper.narrow(Naming.resolver(nc, "Auditoria"));
        System.out.println("[ROBO " + REGIAO + "] ligado a Auditoria central");

        // 3. Monitorizacao do stock
        Set<String> jaAlertados = new HashSet<String>();
        while (true) {
            try (Connection c = ligar();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT produto_id, quantidade, limite_minimo FROM stock ORDER BY produto_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String p = rs.getString(1);
                    int q = rs.getInt(2);
                    int min = rs.getInt(3);
                    if (q <= min && !jaAlertados.contains(p)) {
                        auditoria.dispararAlerta(new AlertaStock(p, REGIAO, q, min, System.currentTimeMillis()));
                        jaAlertados.add(p);
                        System.out.println("[ROBO " + REGIAO + "] dispararAlerta(" + p + ": " + q + " <= " + min + ")");
                    } else if (q > min) {
                        jaAlertados.remove(p);
                    }
                }
            } catch (Exception e) {
                System.out.println("[ROBO " + REGIAO + "] erro na monitorizacao: " + e.getMessage());
            }
            Thread.sleep(10000);
        }
    }
}
