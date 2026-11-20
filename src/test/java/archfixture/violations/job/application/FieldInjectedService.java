package archfixture.violations.job.application;

import org.springframework.beans.factory.annotation.Autowired;

/** VIOLATION: field injection instead of constructor injection. */
public class FieldInjectedService {
    @Autowired
    private Object dependency;
}
