package archfixture.violations.job.domain;

public interface JobRepository {
    Job findById(long id);
}
