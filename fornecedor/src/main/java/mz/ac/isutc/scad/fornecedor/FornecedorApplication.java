package mz.ac.isutc.scad.fornecedor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Fornecedor externo simulado. O mesmo codigo corre como fornecedor REST ou SOAP (variavel MODO). */
@SpringBootApplication
public class FornecedorApplication {
    public static void main(String[] args) {
        SpringApplication.run(FornecedorApplication.class, args);
    }
}
