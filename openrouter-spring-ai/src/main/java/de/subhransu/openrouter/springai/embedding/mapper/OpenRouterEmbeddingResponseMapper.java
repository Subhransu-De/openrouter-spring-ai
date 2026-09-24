package de.subhransu.openrouter.springai.embedding.mapper;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.mapper.UsageMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.util.Assert;

public final class OpenRouterEmbeddingResponseMapper {

	public EmbeddingResponse map(EmbeddingsResponse response) {
		var data = response.data();
		return map(response, data == null ? 0 : data.size(), null);
	}

	public EmbeddingResponse map(EmbeddingsResponse response, int inputCount, @Nullable Integer dimensions) {
		var items = response != null ? response.data() : null;
		if (items == null || items.size() != inputCount) {
			throw new IllegalStateException("Embedding response count must match input count");
		}
		Embedding[] embeddings = new Embedding[inputCount];
		for (EmbeddingsResponse.EmbeddingData data : items) {
			var itemIndex = data != null ? data.index() : null;
			if (data == null || itemIndex == null || itemIndex < 0 || itemIndex >= inputCount) {
				throw new IllegalStateException("Embedding response index must be within input range");
			}
			int index = itemIndex;
			Assert.state(embeddings[index] == null, "Embedding response contains duplicate index");
			float[] vector = validatedVector(data);
			if (dimensions == null) {
				dimensions = vector.length;
			}
			Assert.state(vector.length == dimensions, "Embedding response vector dimensions must match");
			for (float value : vector) {
				Assert.state(Float.isFinite(value), "Embedding response vector values must be finite");
			}
			embeddings[index] = new Embedding(vector, index);
		}
		return new EmbeddingResponse(List.of(embeddings), mapMetadata(response));
	}

	private float[] validatedVector(EmbeddingsResponse.EmbeddingData data) {
		float[] vector = data.embedding();
		if (vector == null || vector.length == 0) {
			throw new IllegalStateException("Embedding response vector must not be empty");
		}
		return vector;
	}

	private EmbeddingResponseMetadata mapMetadata(EmbeddingsResponse response) {
		Usage usage = response.usage();
		if (usage == null) {
			return new EmbeddingResponseMetadata(response.model() != null ? response.model() : "", new EmptyUsage());
		}
		return new EmbeddingResponseMetadata(response.model() != null ? response.model() : "", UsageMapper.map(usage));
	}

}
