package archfixture.violations.job.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** VIOLATION: request bodies whose constraints are (partly) never checked. */
@RestController
public class UnvalidatedController {

    /** No @Valid: none of the body's constraints run. */
    @PostMapping("/applications")
    public void apply(@RequestBody ApplicationRequest request) {
    }

    public record ApplicationRequest(
            int yearsOfExperience,     // primitive: a missing value can't be reported with the other errors
            Address address,           // nested object without @Valid: its constraints are skipped
            List<Address> previous) {  // collection of objects without @Valid: same
    }

    public record Address(String city) {
    }
}
