/**
 * Shared kernel: cross-cutting code used by every feature module.
 *
 * <p>The top-level package is the module's public API (Spring Modulith), e.g. the
 * {@link com.shahabuddin.jobfinder.shared.DomainException} hierarchy that use cases throw. The
 * {@code api} sub-package holds the global HTTP error handling, which no other module references.
 */
package com.shahabuddin.jobfinder.shared;
