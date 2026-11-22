package archfixture.compliant.job.api;

import archfixture.compliant.job.application.JobService;
import archfixture.compliant.job.domain.Job;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/jobs")
public class JobController {
    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable long id) {
        return toResponse(service.get(id));
    }

    @PostMapping
    public JobResponse create(@RequestBody CreateJobRequest request) {
        return toResponse(service.post(request.title()));
    }

    /** A private mapping helper may take a domain type: it isn't part of the HTTP contract. */
    private static JobResponse toResponse(Job job) {
        return new JobResponse(job.id(), job.title());
    }

    public record CreateJobRequest(String title) {
    }

    public record JobResponse(long id, String title) {
    }
}
