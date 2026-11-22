package archfixture.compliant.job.application;

import archfixture.compliant.job.domain.Job;
import archfixture.compliant.job.domain.JobRepository;

public class JobService {
    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public Job get(long id) {
        return repository.findById(id).orElseThrow();
    }

    public Job post(String title) {
        return new Job(0, title);
    }
}
