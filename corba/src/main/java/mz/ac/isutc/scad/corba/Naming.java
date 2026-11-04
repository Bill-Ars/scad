package mz.ac.isutc.scad.corba;

import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

/** Acesso ao Naming Service do JacORB, com novas tentativas enquanto ele arranca. */
final class Naming {

    private Naming() { }

    static NamingContextExt contexto(ORB orb) throws InterruptedException {
        for (int i = 1; ; i++) {
            try {
                return NamingContextExtHelper.narrow(orb.resolve_initial_references("NameService"));
            } catch (Exception e) {
                if (i >= 60) throw new RuntimeException("Naming Service indisponivel", e);
                System.out.println("A aguardar o Naming Service... (" + i + ")");
                Thread.sleep(2000);
            }
        }
    }

    static org.omg.CORBA.Object resolver(NamingContextExt nc, String nome) throws InterruptedException {
        for (int i = 1; ; i++) {
            try {
                return nc.resolve_str(nome);
            } catch (Exception e) {
                if (i >= 60) throw new RuntimeException("Objeto '" + nome + "' nao registado", e);
                System.out.println("A aguardar o registo de '" + nome + "'... (" + i + ")");
                Thread.sleep(2000);
            }
        }
    }
}
