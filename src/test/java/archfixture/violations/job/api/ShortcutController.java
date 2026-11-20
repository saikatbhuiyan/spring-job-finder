package archfixture.violations.job.api;

import archfixture.violations.job.domain.JobRepository;

/** VIOLATION: the api layer uses a repository directly, bypassing the application layer. */
public class ShortcutController {
    private final JobRepository repository;

    public ShortcutController(JobRepository repository) {
        this.repository = repository;
    }
}
