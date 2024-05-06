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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;

/**
 * A type that represents the response data structure for folder quota API queries.
 */
public class MStoreQuota {
    /** [All folders] Storage used in KiB (rounded down to nearest integer). */
    @Nullable
    public Integer sizeKiBUsed;

    /** [All folders] Storage limit in KiB. */
    @Nullable
    public Integer sizeKiBLimit;

    /** [Voicemails folder] Number of fax messages. */
    @Nullable
    public Integer faxMessagesCount;

    /** [Voicemails folder] Maximum number of fax messages. */
    @Nullable
    public Integer faxMessagesLimit;

    /** [Voicemails folder] Number of voicemails. */
    @Nullable
    public Integer voicemailsCount;

    /** [Voicemails folder] Maximum number of voicemails. */
    @Nullable
    public Integer voicemailsLimit;

    /** [Greetings folder] Number of greetings of any type. */
    @Nullable
    public Integer greetingsCount;

    /** [Greetings folder] Maximum number of greetings of any type. */
    @Nullable
    public Integer greetingsLimit;

    /** [Greetings folder] Number of normal greetings. */
    @Nullable
    public Integer normalGreetingsCount;

    /** [Greetings folder] Maximum number of normal greetings. */
    @Nullable
    public Integer normalGreetingsLimit;

    /** [Greetings folder] Number of voice signatures. */
    @Nullable
    public Integer voiceSignaturesCount;

    /** [Greetings folder] Maximum number of voice signatures. */
    @Nullable
    public Integer voiceSignaturesLimit;

    /** Unrecognized attributes (if any). */
    @NonNull
    public HashMap<String, String> unknownAttrs = new HashMap<>();

    private static int parseInt(@NonNull String value) throws JSONException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid integer value: " + value, e);
        }
    }

    public MStoreQuota(@NonNull JSONObject data) throws JSONException {
        final var folder = data.getJSONObject("folder");
        final var attributes = folder.getJSONObject("attributes");
        final var attribute = attributes.getJSONArray("attribute");

        for (var i = 0; i < attribute.length(); i++) {
            final var item = attribute.getJSONObject(i);
            final var name = item.getString("name");
            final var values = item.getJSONArray("value");
            if (values.length() != 1) {
                throw new JSONException("Invalid attribute value: " + values);
            }
            final var value = values.getString(0);

            switch (name) {
                case "OccupiedStorage" -> this.sizeKiBUsed = parseInt(value);
                case "TotalStorage" -> this.sizeKiBLimit = parseInt(value);
                case "FaxOccupiedMessages" -> this.faxMessagesCount = parseInt(value);
                case "FaxMessagesQuota" -> this.faxMessagesLimit = parseInt(value);
                case "VMOccupiedMessages" -> this.voicemailsCount = parseInt(value);
                case "VMMessagesQuota" -> this.voicemailsLimit = parseInt(value);
                case "GreetingsOccupied" -> this.greetingsCount = parseInt(value);
                case "GreetingsQuota" -> this.greetingsLimit = parseInt(value);
                case "NormalGreetingOccupied" -> this.normalGreetingsCount = parseInt(value);
                case "NormalGreetingQuota" -> this.normalGreetingsLimit = parseInt(value);
                case "VoiceSignatureOccupied" -> this.voiceSignaturesCount = parseInt(value);
                case "VoiceSignatureQuota" -> this.voiceSignaturesLimit = parseInt(value);
                default -> this.unknownAttrs.put(name, value);
            }
        }
    }
}
