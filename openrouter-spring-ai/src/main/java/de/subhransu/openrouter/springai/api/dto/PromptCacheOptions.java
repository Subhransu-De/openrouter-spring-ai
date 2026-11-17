package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.jspecify.annotations.Nullable;

/**
 * Request-level prompt-cache controls, sent as {@code prompt_cache_options}.
 *
 * @param mode the cache mode, such as {@code explicit}
 * @param ttl the minimum cache lifetime, such as {@code 30m}; {@code null} keeps the
 * provider default
 * @author Subhransu De
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record PromptCacheOptions(String mode, @Nullable String ttl) {
}
