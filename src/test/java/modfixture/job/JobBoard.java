package modfixture.job;

import modfixture.company.CompanyDirectory;
import modfixture.company.internal.CompanyRatingCalculator;

/**
 * VIOLATIONS: reaches into another module's internal package, and depends on a module
 * (company) that depends back on this one (cycle).
 */
public class JobBoard {
    private final CompanyRatingCalculator calculator = new CompanyRatingCalculator();
    private CompanyDirectory directory;
}
