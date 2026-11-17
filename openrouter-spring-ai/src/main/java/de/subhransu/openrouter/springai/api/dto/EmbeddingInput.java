package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;

/**
 * One multimodal entry of an embeddings {@code input} array. Its {@code text} and
 * {@code image_url} parts produce a single embedding.
 *
 * @param content the parts of the entry
 * @author Subhransu De
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record EmbeddingInput(List<ContentPart> content) {
}
