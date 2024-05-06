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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;

/**
 * A type that represents both the input and output data structures for the /vvmserviceProfile
 * endpoint. When sending a request, only non-null fields are included.
 */
public class MStoreProfile {
    private static final String FIELD_COS_NAME = "cosname";
    private static final String FIELD_IS_BLOCKED = "isblocked";
    private static final String FIELD_LANGUAGE = "Language";
    private static final String FIELD_MIGRATION_TIMESTAMP = "MigrationDate";
    private static final String FIELD_MIGRATION_STATUS = "MigrationStatus";
    private static final String FIELD_NEW_USER_TUTORIAL = "nut";
    private static final String FIELD_SMS_DIRECT_LINK = "SMSDirectLink";
    private static final String FIELD_V2E = "V2E_ON";
    private static final String FIELD_V2T_LANGUAGE = "V2t_Language";
    private static final String FIELD_ENABLED = "vvmon";
    private static final String FIELD_OLD_PIN = "OLD_PWD";
    private static final String FIELD_NEW_PIN = "PWD";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy:MM:dd:HH:mm")
            .toFormatter()
            .withZone(ZoneOffset.UTC);

    /** [in,out?] Unknown. */
    @Nullable
    public String cosName;

    /** [in] Unknown: Whether activation is blocked? */
    @Nullable
    public Boolean isBlocked;

    /** [in,out?] Unknown: Language of what? Example value: "eng". */
    @Nullable
    public String language;

    /** [in] Unknown: Timestamp of migration away from CVVM? */
    @Nullable
    public Instant migrationTimestamp;

    /** [in] Unknown: Whether migrated away from CVVM? */
    @Nullable
    public Boolean migrationStatus;

    /** [in,out] Whether the new user tutorial should be shown. */
    @Nullable
    public Boolean newUserTutorial;

    /** [in,out?] Unknown: Whether to send an SMS link for downloading some app? */
    @Nullable
    public Boolean smsDirectLink;

    /** [in,out?] Unknown: Voice-to-something? */
    @Nullable
    public Boolean v2e;

    /**
     * [in,out?] Unknown: Voice-to-text language? Example value: "None". Unsure what the purpose of
     * this value is. T-Mobile's paid server-side transcription is only available in their Visual
     * Voicemail app, which uses a different API. Google Dialer's transcription is local and ignores
     * this value.
     */
    @Nullable
    public String v2tLanguage;

    /** [in,out] Whether visual voicemail is enabled. */
    @Nullable
    public Boolean enabled;

    /** [out] Old PIN (when changing the PIN). */
    @Nullable
    public String oldPin;

    /** [out] New PIN (when changing the PIN). */
    @Nullable
    public String newPin;

    /** Unrecognized attributes (if any). */
    @NonNull
    public HashMap<String, String> unknownAttrs = new HashMap<>();

    private static boolean parseBoolean(@NonNull String value) throws JSONException {
        // The API is inconsistent and sometimes returns booleans starting with an uppercase letter.
        return switch (value.toLowerCase(Locale.ENGLISH)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new JSONException("Invalid stringified boolean value: " + value);
        };
    }

    private static @NonNull Instant parseInstant(@NonNull String value) throws JSONException {
        try {
            return DATE_TIME_FORMATTER.parse(value, Instant::from);
        } catch (DateTimeParseException e) {
            throw new JSONException("Invalid timestamp value: " + value, e);
        }
    }

    private static @NonNull String formatInstant(@NonNull Instant instant) throws JSONException {
        try {
            return DATE_TIME_FORMATTER.format(instant);
        } catch (DateTimeException e) {
            throw new JSONException("Invalid timestamp value: " + instant, e);
        }
    }

    /**
     * Create an empty instance.
     */
    public MStoreProfile() {}

    /**
     * Create an instance from a JSON GET response of the /vvmserviceProfile endpoint.
     */
    public MStoreProfile(@NonNull JSONObject data) throws JSONException {
        final var profile = data.getJSONObject("vvmserviceProfile");
        final var attributes = profile.getJSONObject("attributes");
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
                case FIELD_COS_NAME -> this.cosName = value;
                case FIELD_IS_BLOCKED -> this.isBlocked = parseBoolean(value);
                case FIELD_LANGUAGE -> this.language = value;
                case FIELD_MIGRATION_TIMESTAMP -> this.migrationTimestamp = parseInstant(value);
                case FIELD_MIGRATION_STATUS -> this.migrationStatus = parseBoolean(value);
                case FIELD_NEW_USER_TUTORIAL -> this.newUserTutorial = parseBoolean(value);
                case FIELD_SMS_DIRECT_LINK -> this.smsDirectLink = parseBoolean(value);
                case FIELD_V2E -> this.v2e = parseBoolean(value);
                case FIELD_V2T_LANGUAGE -> this.v2tLanguage = value;
                case FIELD_ENABLED -> this.enabled = parseBoolean(value);
                default -> this.unknownAttrs.put(name, value);
            }
        }
    }

    private static @NonNull JSONObject newAttribute(@NonNull String name, @NonNull String value)
            throws JSONException {
        return new JSONObject()
                .put("name", name)
                .put("value", new JSONArray().put(value));
    }

    /**
     * Serialize to JSON for use with a PUT request to the /vvmserviceProfile endpoint.
     */
    public @NonNull JSONObject toJson() throws JSONException {
        final var attribute = new JSONArray();

        if (this.cosName != null) {
            attribute.put(newAttribute(FIELD_COS_NAME, this.cosName));
        }
        if (this.isBlocked != null) {
            attribute.put(newAttribute(FIELD_IS_BLOCKED, this.isBlocked.toString()));
        }
        if (this.language != null) {
            attribute.put(newAttribute(FIELD_LANGUAGE, this.language));
        }
        if (this.migrationTimestamp != null) {
            attribute.put(newAttribute(FIELD_MIGRATION_TIMESTAMP,
                    formatInstant(this.migrationTimestamp)));
        }
        if (this.migrationStatus != null) {
            attribute.put(newAttribute(FIELD_MIGRATION_STATUS, this.migrationStatus.toString()));
        }
        if (this.newUserTutorial != null) {
            attribute.put(newAttribute(FIELD_NEW_USER_TUTORIAL, this.newUserTutorial.toString()));
        }
        if (this.smsDirectLink != null) {
            attribute.put(newAttribute(FIELD_SMS_DIRECT_LINK, this.smsDirectLink.toString()));
        }
        if (this.v2e != null) {
            attribute.put(newAttribute(FIELD_V2E, this.v2e.toString()));
        }
        if (this.v2tLanguage != null) {
            attribute.put(newAttribute(FIELD_V2T_LANGUAGE, this.v2tLanguage));
        }
        if (this.enabled != null) {
            attribute.put(newAttribute(FIELD_ENABLED, this.enabled.toString()));
        }
        if (this.oldPin != null) {
            attribute.put(newAttribute(FIELD_OLD_PIN, this.oldPin));
        }
        if (this.newPin != null) {
            attribute.put(newAttribute(FIELD_NEW_PIN, this.newPin));
        }
        for (var entry : this.unknownAttrs.entrySet()) {
            attribute.put(newAttribute(entry.getKey(), entry.getValue()));
        }

        final var attributes = new JSONObject().put("attribute", attribute);
        final var profile = new JSONObject().put("attributes", attributes);

        return new JSONObject().put("vvmserviceProfile", profile);
    }
}
