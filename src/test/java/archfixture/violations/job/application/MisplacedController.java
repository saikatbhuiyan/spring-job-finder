package archfixture.violations.job.application;

import org.springframework.web.bind.annotation.RestController;

/** VIOLATION: a controller outside the api layer. */
@RestController
public class MisplacedController {
}
