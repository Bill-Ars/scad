package mz.ac.isutc.scad.corba;

import SCAD.AlertaStock;
import SCAD.AuditoriaPOA;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Servant do modulo central de auditoria: recebe os alertas dos robos de stock. */
class AuditoriaImpl extends AuditoriaPOA {

    private final List<AlertaStock> alertas = Collections.synchronizedList(new ArrayList<AlertaStock>());

    @Override
    public void dispararAlerta(AlertaStock a) {
        alertas.add(a);
        System.out.println(String.format("[AUDITORIA] ALERTA via CORBA: produto %s em %s com %d unidades (minimo %d) - %s",
                a.produtoId, a.regiao, a.quantidadeAtual, a.limiteMinimo, new Date(a.timestamp)));
    }

    @Override
    public AlertaStock[] listarAlertas() {
        synchronized (alertas) {
            return alertas.toArray(new AlertaStock[0]);
        }
    }
}

/** Servidor CORBA da auditoria: ORB -> POA -> servant -> registo no Naming Service como "Auditoria". */
public class ServidorAuditoria {
    public static void main(String[] args) throws Exception {
        ORB orb = ORB.init(args, null);
        POA rootPoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPoa.the_POAManager().activate();

        org.omg.CORBA.Object ref = rootPoa.servant_to_reference(new AuditoriaImpl());
        NamingContextExt nc = Naming.contexto(orb);
        nc.rebind(nc.to_name("Auditoria"), ref);

        System.out.println("[AUDITORIA] Servidor CORBA registado como 'Auditoria' no Naming Service");
        orb.run();
    }
}
