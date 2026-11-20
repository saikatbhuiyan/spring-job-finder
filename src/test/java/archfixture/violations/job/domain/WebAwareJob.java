package archfixture.violations.job.domain;

import org.springframework.http.ResponseEntity;

/** VIOLATION: the domain knows about HTTP. */
public class WebAwareJob {
    public ResponseEntity<String> asResponse() {
        return ResponseEntity.ok("job");
    }
}
