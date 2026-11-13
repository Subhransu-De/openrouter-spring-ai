package de.subhransu.openrouter.springai.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Quantization levels that OpenRouter documents for {@code provider.quantizations}. The
 * String overloads accept values that OpenRouter adds later; an undocumented value is
 * sent unchanged and logged once as a warning.
 *
 * @author Subhransu De
 */
public enum OpenRouterQuantization {

	INT4("int4"), INT8("int8"), FP4("fp4"), MXFP4("mxfp4"), NVFP4("nvfp4"), FP6("fp6"), FP8("fp8"), MXFP8("mxfp8"),
	FP16("fp16"), BF16("bf16"), FP32("fp32"), UNKNOWN("unknown");

	static final List<String> VALUES = Arrays.stream(values()).map(OpenRouterQuantization::value).toList();

	private final String value;

	OpenRouterQuantization(String value) {
		this.value = value;
	}

	/**
	 * Returns the wire value.
	 * @return the value OpenRouter expects
	 */
	public String value() {
		return this.value;
	}

}
