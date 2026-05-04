package io.dbtower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DbtowerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DbtowerApplication.class, args);
	}

}
