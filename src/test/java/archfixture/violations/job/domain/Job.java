package archfixture.violations.job.domain;

import archfixture.violations.job.api.JobResponse;

/** VIOLATION: the domain depends on the api layer (a DTO). */
public class Job {
    public JobResponse toResponse() {
        return new JobResponse("title");
    }
}
