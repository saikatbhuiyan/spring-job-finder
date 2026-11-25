/**
 * Shared kernel: cross-cutting code used by every feature module.
 *
 * <p>The top-level package is the module's public API (Spring Modulith): the
 * {@link com.shahabuddin.jobfinder.shared.DomainException} hierarchy that use cases throw, and the
 * paging contract ({@link com.shahabuddin.jobfinder.shared.PageQuery},
 * {@link com.shahabuddin.jobfinder.shared.SortableBy}) that controllers declare. The {@code api}
 * sub-package holds the HTTP infrastructure behind them (error handling, CORS, JSON and paging
 * binding, rate limiting, request tracing, idempotency keys, API documentation), which no other module
 * references.
 */
package com.shahabuddin.jobfinder.shared;
