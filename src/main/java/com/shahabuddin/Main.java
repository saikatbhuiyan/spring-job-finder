package com.shahabuddin;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.shahabuddin.beans.Vehicle;
import com.shahabuddin.config.ProjectConfig;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main() {

        // try-with-resources closes the context even if getBean throws, so the
        // beans' destroy callbacks (DisposableBean.destroy, @PreDestroy) always run.
        try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
            var veh = context.getBean(Vehicle.class);
            System.out.println("Vehicle name from Spring Context is: " + veh.getName());
        }
    }
}
