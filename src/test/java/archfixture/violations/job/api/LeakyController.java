package archfixture.violations.job.api;

import archfixture.violations.job.domain.Job;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** VIOLATION: endpoints accept and return a domain entity instead of DTOs. */
@RestController
public class LeakyController {

    /** Returns the entity directly. */
    @GetMapping("/jobs/{id}")
    public Job get() {
        return new Job();
    }

    /** Hides the entity inside generic type arguments. */
    @GetMapping("/jobs")
    public ResponseEntity<List<Job>> list() {
        return ResponseEntity.ok(List.of());
    }

    /** Binds the request body straight onto the entity (overposting). */
    @PostMapping("/jobs")
    public void create(@RequestBody Job job) {
    }
}
