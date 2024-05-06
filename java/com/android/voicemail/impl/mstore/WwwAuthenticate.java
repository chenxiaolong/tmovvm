/*
 * Copyright 2024 Andrew Gunnerson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.voicemail.impl.mstore;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Helpers for parsing WWW-Authenticate and formatting Authorization headers.
 */
class WwwAuthenticate {
    /**
     * Represents one separated item in the header. If key is null, then there are no more items in
     * the header. If value is null, then key represents a challenge type. Otherwise, key and value
     * form a challenge parameter. The exclusive ending bound of this item is stored in endIndex.
     */
    private record Item(@Nullable String key, @Nullable String value, int endIndex) {}

    /**
     * Whether a character separates items (challenge type or parameter) in the header.
     */
    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == ',';
    }

    /**
     * Parse a single item (challenge type or key/value parameter) from the header.
     */
    private static @NonNull Item parseItem(@NonNull String s, int startOffset) {
        var i = startOffset;

        // Skip leading separators.
        //noinspection StatementWithEmptyBody
        for (; i < s.length() && isSeparator(s.charAt(i)); i++);
        final var keyBegin = i;

        // Parse key or challenge name.
        var hasValue = false;
        for (; i < s.length(); i++) {
            final var c = s.charAt(i);

            if (c == '=') {
                hasValue = true;
                break;
            } else if (isSeparator(c)) {
                break;
            }
        }
        final var keyEnd = i;

        if (keyBegin == keyEnd) {
            // No more items available.
            return new Item(null, null, i);
        }

        final var key = s.substring(keyBegin, keyEnd);
        String value = null;

        if (hasValue) {
            i++;

            if (i == s.length()) {
                throw new IllegalArgumentException("Missing value for " + key);
            }

            if (s.charAt(i) == '"') {
                i++;

                // Quoted values needs to be built one code unit at a time because they might
                // have escaped characters.
                final var sb = new StringBuilder();
                var escaped = false;
                var closedQuote = false;

                for (; i < s.length(); i++) {
                    final var c = s.charAt(i);

                    if (escaped) {
                        escaped = false;
                        sb.append(c);
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        closedQuote = true;
                        break;
                    } else {
                        sb.append(c);
                    }
                }

                if (!closedQuote) {
                    throw new IllegalArgumentException("No closing quote for value of " + key);
                }

                i++;

                value = sb.toString();
            } else {
                // Unquoted values can be used verbatim.
                final var valueBegin = i;

                for (; i < s.length(); i++) {
                    final var c = s.charAt(i);

                    if (isSeparator(c)) {
                        break;
                    }
                }
                final var valueEnd = i;

                value = s.substring(valueBegin, valueEnd);
            }
        }

        return new Item(key, value, i);
    }

    /**
     * Parse all of the challenges and their parameters from a WWW-Authenticate header. If a
     * challenge appears multiple times, the last instance takes effect.
     *
     * @throws IllegalArgumentException if the header value is invalid.
     */
    public static @NonNull HashMap<String, HashMap<String, String>> parse(@NonNull String s) {
        var challenges = new HashMap<String, HashMap<String, String>>();
        HashMap<String, String> parameters = null;
        var i = 0;

        while (true) {
            final var item = parseItem(s, i);
            if (item.key == null) {
                break;
            }

            if (item.value == null) {
                // This is a challenge type.
                parameters = new HashMap<>();
                challenges.put(item.key, parameters);
            } else {
                if (parameters == null) {
                    throw new IllegalArgumentException(
                            "Parameter specified without challenge type: " + item.key + "="
                                    + item.value);
                }
                parameters.put(item.key, item.value);
            }

            i = item.endIndex;
        }

        return challenges;
    }

    /**
     * Check whether a parameter must not be escaped. This is necessary to work around broken
     * parsers in the webserver.
     */
    private static boolean denyEscaping(@NonNull String key) {
        return "nc".equals(key);
    }

    /**
     * Check whether a parameter value contains characters that need to be escaped.
     */
    private static boolean valueRequiresEscaping(@NonNull String value) {
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);

            if (c == '"' || isSeparator(c)) {
                return true;
            }
        }

        return value.isEmpty();
    }

    /**
     * Format an authentication type and its parameters for use in a request's Authorization header.
     *
     * @throws IllegalArgumentException if a parameter key is not allowed to be escaped, but the
     * value contains characters that require escaping.
     */
    public static @NonNull String format(@NonNull String type,
            @NonNull Map<String, String> params) {
        final var sb = new StringBuilder();

        sb.append(type);

        if (!params.isEmpty()) {
            sb.append(' ');

            var first = true;

            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    sb.append(',');
                }

                final var key = entry.getKey();
                final var value = entry.getValue();

                sb.append(key);
                sb.append('=');

                // Try to use escaped values where possible unless it is known that the server will
                // fail to parse a specific escaped parameter.
                if (denyEscaping(key)) {
                    if (valueRequiresEscaping(value)) {
                        throw new IllegalArgumentException(
                                key + " must not be escaped, but contains invalid characters: "
                                        + value);
                    }

                    sb.append(value);
                } else {
                    sb.append('"');

                    for (var i = 0; i < value.length(); i++) {
                        final var c = value.charAt(i);

                        if (c == '\\' || c == '"') {
                            sb.append('\\');
                        }
                        sb.append(c);
                    }

                    sb.append('"');
                }
            }
        }

        return sb.toString();
    }
}
