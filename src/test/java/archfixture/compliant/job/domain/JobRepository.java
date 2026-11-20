package archfixture.compliant.job.domain;

import java.util.Optional;

public interface JobRepository {
    Optional<Job> findById(long id);
}
