package mz.ac.isutc.scad.agente;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;   // NOVO

@SpringBootApplication
@EnableScheduling                                                    // NOVO
public class AgenteApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgenteApplication.class, args);
    }
}