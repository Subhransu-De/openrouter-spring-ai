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
		return map(response, response.data() == null ? 0 : response.data().size(), null);
	}

	public EmbeddingResponse map(EmbeddingsResponse response, int inputCount, @Nullable Integer dimensions) {
		Assert.state(response != null && response.data() != null && response.data().size() == inputCount,
				"Embedding response count must match input count");
		Embedding[] embeddings = new Embedding[inputCount];
		for (EmbeddingsResponse.EmbeddingData data : response.data()) {
			Assert.state(data != null && data.index() != null && data.index() >= 0 && data.index() < inputCount,
					"Embedding response index must be within input range");
			int index = data.index();
			Assert.state(embeddings[index] == null, "Embedding response contains duplicate index");
			float[] vector = data.embedding();
			Assert.state(vector != null && vector.length > 0, "Embedding response vector must not be empty");
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

	private EmbeddingResponseMetadata mapMetadata(EmbeddingsResponse response) {
		Usage usage = response.usage();
		if (usage == null) {
			return new EmbeddingResponseMetadata(response.model() != null ? response.model() : "", new EmptyUsage());
		}
		return new EmbeddingResponseMetadata(response.model() != null ? response.model() : "", UsageMapper.map(usage));
	}

}
