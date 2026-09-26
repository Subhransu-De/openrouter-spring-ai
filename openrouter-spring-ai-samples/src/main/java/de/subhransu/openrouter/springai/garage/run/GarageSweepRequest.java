package de.subhransu.openrouter.springai.garage.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * JSON body of {@code POST /api/sweeps}. Embedding entries are {@code model[@providerTag]};
 * image entries are {@code model[@providerTag][?key=value&...]}.
 */
public record GarageSweepRequest(
    @Nullable List<String> embeddingModels,
    @Nullable List<String> imageModels,
    @Nullable String imageQuality,
    GarageRunRequest.@Nullable Provider provider) {}
