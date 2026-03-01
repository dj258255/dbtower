package io.dbhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DbhubApplication {

	public static void main(String[] args) {
		SpringApplication.run(DbhubApplication.class, args);
	}

}
