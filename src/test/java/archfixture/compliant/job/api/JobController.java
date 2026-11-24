package archfixture.compliant.job.api;

import archfixture.compliant.job.application.JobService;
import archfixture.compliant.job.domain.Job;
import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.SortableBy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** Paging through the API contract: PageQuery in, PagedModel out. */
    @GetMapping
    public PagedModel<JobResponse> search(@SortableBy(value = {"title", "createdAt"}, defaultSort = "createdAt,desc") PageQuery page) {
        return new PagedModel<>(Page.<Job>empty(page.toPageable()).map(JobController::toResponse));
    }

    @PostMapping
    public JobResponse create(@Valid @RequestBody CreateJobRequest request) {
        return toResponse(service.post(request.title()));
    }

    /** A private mapping helper may take a domain type: it isn't part of the HTTP contract. */
    private static JobResponse toResponse(Job job) {
        return new JobResponse(job.id(), job.title());
    }

    /** Validated completely: wrapper types, and @Valid on every nested object and collection. */
    public record CreateJobRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull @Positive Integer openings,
            @NotNull @Valid CompanyRef company,
            @Size(max = 20) @Valid List<Tag> tags) {
    }

    public record CompanyRef(@NotBlank String name) {
    }

    public record Tag(@NotBlank @Size(max = 50) String name) {
    }

    /** Responses aren't request bodies: primitives are fine here. */
    public record JobResponse(long id, String title) {
    }
}
