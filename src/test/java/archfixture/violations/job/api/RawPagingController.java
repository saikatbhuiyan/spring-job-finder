package archfixture.violations.job.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** VIOLATION: Spring Data's paging types used directly as the HTTP contract. */
@RestController
public class RawPagingController {

    /** Pageable binding: invalid values silently become defaults, any property is sortable. */
    @GetMapping("/jobs/raw")
    public List<String> list(Pageable pageable) {
        return List.of();
    }

    /** Sort binding: same problem, without paging. */
    @GetMapping("/jobs/sorted")
    public List<String> sorted(Sort sort) {
        return List.of();
    }

    /** Page in the response, hidden in a generic type argument: its JSON isn't a stable contract. */
    @GetMapping("/jobs/page")
    public ResponseEntity<Page<String>> page() {
        return ResponseEntity.ok(Page.empty());
    }
}
