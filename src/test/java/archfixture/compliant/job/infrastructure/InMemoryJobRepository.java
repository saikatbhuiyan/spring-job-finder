package archfixture.compliant.job.infrastructure;

import archfixture.compliant.job.domain.Job;
import archfixture.compliant.job.domain.JobRepository;
import java.util.Optional;

public class InMemoryJobRepository implements JobRepository {
    @Override
    public Optional<Job> findById(long id) {
        return Optional.of(new Job(id, "Java Developer"));
    }
}
