package modfixture.company;

import modfixture.job.JobBoard;

/** Part of a CYCLE: company -> job -> company. */
public class CompanyDirectory {
    private final JobBoard jobBoard;

    public CompanyDirectory(JobBoard jobBoard) {
        this.jobBoard = jobBoard;
    }
}
