package com.shahabuddin;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.shahabuddin.beans.Vehicle;
import com.shahabuddin.config.ProjectConfig;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main() {
        //TIP Press <shortcut actionId="ShowIntentionActions"/> with your caret at the highlighted text
        // to see how IntelliJ IDEA suggests fixing it.
        IO.println(String.format("Hello and welcome!"));

             Vehicle vehicle = new Vehicle();
        vehicle.setName("Audi");
        IO.println("Vehicle name from non-spring context is: " + vehicle.getName());

        /*
         * try-with-resources: the context is AutoCloseable, so close() is called
         * automatically when this block ends (even if an exception is thrown).
         * Closing lets Spring run bean destroy callbacks and release resources.
         */
        try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
            var veh = context.getBean("vehicle1", Vehicle.class);
            IO.println("Vehicle name from Spring Context is: " + veh.getName());

            var veh2 = context.getBean("vehicle2", Vehicle.class);
            IO.println("Vehicle name from Spring Context is: " + veh2.getName());

            /*
             * We don’t need to do any explicit casting while fetching a bean from context.
             * Spring is smart enough to look for a bean of the type you requested in its
             * context.
             * If such a bean doesn’t exist,Spring will throw an exception.
             */
            String hello = context.getBean(String.class);
            IO.println("String value  from Spring Context is: " + hello);

            Integer num = context.getBean(Integer.class);
            IO.println("Integer value  from Spring Context is: " + num);

            // Lookup by name AND type: no cast needed. A wrong type fails fast with
            // BeanNotOfRequiredTypeException instead of a ClassCastException later.
            String hello1 = context.getBean("hello", String.class);
            IO.println("String value  from Spring Context is: " + hello1);
        }
    }
}
