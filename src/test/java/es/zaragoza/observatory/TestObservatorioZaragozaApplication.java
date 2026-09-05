package es.zaragoza.observatory;

import org.springframework.boot.SpringApplication;

public class TestObservatorioZaragozaApplication {

	public static void main(String[] args) {
		SpringApplication.from(ObservatorioZaragozaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
