package mz.ac.isutc.scad.agente;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Gera um PDF simples (texto, fonte Helvetica) sem bibliotecas externas. */
public final class FaturaPdf {

    private FaturaPdf() { }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    public static byte[] gerar(List<String> linhas) {
        StringBuilder c = new StringBuilder("BT /F1 12 Tf 60 780 Td 18 TL\n");
        boolean primeira = true;
        for (String l : linhas) {
            if (primeira) { c.append("/F1 16 Tf (").append(esc(l)).append(") Tj T* /F1 12 Tf T*\n"); primeira = false; }
            else c.append("(").append(esc(l)).append(") Tj T*\n");
        }
        c.append("ET");
        String conteudo = c.toString();

        List<String> objs = List.of(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + conteudo.length() + " >>\nstream\n" + conteudo + "\nendstream");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) pdf.append(String.format("%010d 00000 n \n", off));
        pdf.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\n")
           .append("startxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
