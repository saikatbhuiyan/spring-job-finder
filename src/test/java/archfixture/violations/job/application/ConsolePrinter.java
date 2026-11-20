package archfixture.violations.job.application;

/** VIOLATION: writes to System.out instead of using the logger. */
public class ConsolePrinter {
    public void print() {
        System.out.println("job posted");
    }
}
