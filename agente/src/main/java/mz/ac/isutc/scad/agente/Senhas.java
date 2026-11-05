package mz.ac.isutc.scad.agente;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Senhas dos clientes: guardadas so como PBKDF2 com sal aleatorio, nunca em texto. */
final class Senhas {

    private static final int ITERACOES = 120_000;
    private static final SecureRandom ALEATORIO = new SecureRandom();
    // Usada quando o email nao existe, para a resposta demorar o mesmo e nao revelar quais emails tem conta
    private static final String FICTICIA = gerar("senha-ficticia-para-tempo-constante");

    private Senhas() {
    }

    static String gerar(String senha) {
        byte[] sal = new byte[16];
        ALEATORIO.nextBytes(sal);
        return "pbkdf2$" + ITERACOES + "$" + b64(sal) + "$" + b64(derivar(senha, sal, ITERACOES));
    }

    static boolean confere(String senha, String guardada) {
        String[] p = (guardada == null ? FICTICIA : guardada).split("\\$");
        byte[] sal = Base64.getDecoder().decode(p[2]);
        byte[] esperado = Base64.getDecoder().decode(p[3]);
        boolean igual = MessageDigest.isEqual(esperado, derivar(senha, sal, Integer.parseInt(p[1])));
        return guardada != null && igual;
    }

    private static byte[] derivar(String senha, byte[] sal, int iteracoes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(senha.toCharArray(), sal, iteracoes, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
