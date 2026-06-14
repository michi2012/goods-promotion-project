package aiops.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiopsApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiopsApplication.class, args);
	}

}
