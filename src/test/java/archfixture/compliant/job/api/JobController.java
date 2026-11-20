package archfixture.compliant.job.api;

import archfixture.compliant.job.application.JobService;
import archfixture.compliant.job.domain.Job;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {
    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping("/api/{version}/jobs/{id}")
    public JobResponse get(@PathVariable long id) {
        Job job = service.get(id);
        return new JobResponse(job.id(), job.title());
    }

    public record JobResponse(long id, String title) {
    }
}
