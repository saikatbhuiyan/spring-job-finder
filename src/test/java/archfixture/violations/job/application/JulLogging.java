package archfixture.violations.job.application;

import java.util.logging.Logger;

/** VIOLATION: uses java.util.logging instead of SLF4J. */
public class JulLogging {
    private static final Logger LOG = Logger.getLogger("jobs");
}
