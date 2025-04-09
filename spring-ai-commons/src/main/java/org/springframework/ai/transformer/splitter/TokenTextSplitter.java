/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.transformer.splitter;

import java.util.ArrayList;
import java.util.List;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import org.springframework.util.Assert;

/**
 * A {@link TextSplitter} that splits text into chunks of a target size in tokens.
 *
 * @author Raphael Yu
 * @author Christian Tzolov
 * @author Ricken Bazolo
 */
public class TokenTextSplitter extends TextSplitter {

	private static final int DEFAULT_CHUNK_OVERLAP = 20;

	private static final int DEFAULT_CHUNK_SIZE = 800;

	private static final int MIN_CHUNK_SIZE_CHARS = 350;

	private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;

	private static final int MAX_NUM_CHUNKS = 10000;

	private static final boolean KEEP_SEPARATOR = true;

	private final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

	private final Encoding encoding = this.registry.getEncoding(EncodingType.CL100K_BASE);

	private final int chunkOverlap;

	// The target size of each text chunk in tokens
	private final int chunkSize;

	// The minimum size of each text chunk in characters
	private final int minChunkSizeChars;

	// Discard chunks shorter than this
	private final int minChunkLengthToEmbed;

	// The maximum number of chunks to generate from a text
	private final int maxNumChunks;

	private final boolean keepSeparator;

	public TokenTextSplitter() {
		this(DEFAULT_CHUNK_OVERLAP, DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, KEEP_SEPARATOR);
	}

	public TokenTextSplitter(boolean keepSeparator) {
		this(DEFAULT_CHUNK_OVERLAP, DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, keepSeparator);
	}

	public TokenTextSplitter(int chunkOverlap, int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks,
			boolean keepSeparator) {
		this.chunkOverlap = chunkOverlap;
		this.chunkSize = chunkSize;
		this.minChunkSizeChars = minChunkSizeChars;
		this.minChunkLengthToEmbed = minChunkLengthToEmbed;
		this.maxNumChunks = maxNumChunks;
		this.keepSeparator = keepSeparator;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	protected List<String> splitText(String text) {
		return doSplit(text, this.chunkSize);
	}

	protected List<String> doSplit(String text, int chunkSize) {
		if (text == null || text.trim().isEmpty()) {
			return new ArrayList<>();
		}

		List<Integer> allTokens = getEncodedTokens(text);
		List<String> chunks = new ArrayList<>();
		int position = 0;
		int chunkCount = 0;

		// First pass: determine chunk boundaries without overlap
		List<Integer> chunkStartPositions = new ArrayList<>();
		List<Integer> chunkEndPositions = new ArrayList<>();

		while (position < allTokens.size() && chunkCount < this.maxNumChunks) {
			int endPosition = Math.min(position + chunkSize, allTokens.size());

			// Get text for this range to find natural breakpoints
			List<Integer> chunkTokens = allTokens.subList(position, endPosition);
			String chunkText = decodeTokens(chunkTokens);

			// Find natural breakpoint
			int lastPunctuation = Math.max(chunkText.lastIndexOf('.'), Math.max(chunkText.lastIndexOf('?'),
					Math.max(chunkText.lastIndexOf('!'), chunkText.lastIndexOf('\n'))));

			if (lastPunctuation != -1 && lastPunctuation > this.minChunkSizeChars) {
				// Adjust the chunk to end at this punctuation
				String adjustedText = chunkText.substring(0, lastPunctuation + 1);
				int adjustedTokenCount = getEncodedTokens(adjustedText).size();
				endPosition = position + adjustedTokenCount;
			}

			chunkStartPositions.add(position);
			chunkEndPositions.add(endPosition);

			// Move to the next position
			position = endPosition;
			chunkCount++;
		}

		// Second pass: create chunks with overlap
		for (int i = 0; i < chunkStartPositions.size(); i++) {
			// Calculate overlapping range
			int startWithOverlap = Math.max(0, chunkStartPositions.get(i) - (i > 0 ? this.chunkOverlap : 0));
			int endWithOverlap = Math.min(allTokens.size(), chunkEndPositions.get(i) + (i < chunkStartPositions.size() - 1 ? this.chunkOverlap : 0));

			List<Integer> chunkWithOverlap = allTokens.subList(startWithOverlap, endWithOverlap);
			String chunkText = decodeTokens(chunkWithOverlap);

			String finalChunkText = this.keepSeparator ? chunkText.trim() : chunkText.replace(System.lineSeparator(), " ").trim();

			if (finalChunkText.length() > this.minChunkLengthToEmbed) {
				chunks.add(finalChunkText);
			}
		}

		return chunks;
	}

	private List<Integer> getEncodedTokens(String text) {
		Assert.notNull(text, "Text must not be null");
		return this.encoding.encode(text).boxed();
	}

	private String decodeTokens(List<Integer> tokens) {
		Assert.notNull(tokens, "Tokens must not be null");
		var tokensIntArray = new IntArrayList(tokens.size());
		tokens.forEach(tokensIntArray::add);
		return this.encoding.decode(tokensIntArray);
	}

	public static final class Builder {

		private int chunkOverlap;

		private int chunkSize;

		private int minChunkSizeChars;

		private int minChunkLengthToEmbed;

		private int maxNumChunks;

		private boolean keepSeparator;

		private Builder() {
		}

		public Builder withChunkOverlap(int chunkOverlap) {
			this.chunkOverlap = chunkOverlap;
			return this;
		}

		public Builder withChunkSize(int chunkSize) {
			this.chunkSize = chunkSize;
			return this;
		}

		public Builder withMinChunkSizeChars(int minChunkSizeChars) {
			this.minChunkSizeChars = minChunkSizeChars;
			return this;
		}

		public Builder withMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
			this.minChunkLengthToEmbed = minChunkLengthToEmbed;
			return this;
		}

		public Builder withMaxNumChunks(int maxNumChunks) {
			this.maxNumChunks = maxNumChunks;
			return this;
		}

		public Builder withKeepSeparator(boolean keepSeparator) {
			this.keepSeparator = keepSeparator;
			return this;
		}

		public TokenTextSplitter build() {
			return new TokenTextSplitter(this.chunkOverlap, this.chunkSize, this.minChunkSizeChars, this.minChunkLengthToEmbed,
					this.maxNumChunks, this.keepSeparator);
		}

	}

}
