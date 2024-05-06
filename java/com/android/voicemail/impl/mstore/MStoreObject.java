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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * A type that represents a single object within an object list response from a search API query.
 */
public class MStoreObject {
    /**
     * Regex to match filenames in Content-Disposition headers. Names that include directory
     * separators are not matched.
     */
    private static final Pattern RE_FILENAME =
            Pattern.compile("^attachment; filename=\"([^/]+)\"$");

    private static final String FIELD_DURATION = "content-duration";
    private static final String FIELD_RESPONSE_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
    private static final String FIELD_RESPONSE_CONTENT_TYPE = "content-type";
    private static final String FIELD_CREATION_TIMESTAMP = "date";
    private static final String FIELD_DIRECTION = "Direction";
    private static final String FIELD_EXPIRY_TIMESTAMP = "expires";
    private static final String FIELD_FROM_NUMBER = "from";
    private static final String FIELD_IMPORTANCE = "importance";
    private static final String FIELD_CONTEXT = "message-context";
    private static final String FIELD_ID = "Message-Id";
    private static final String FIELD_MIME_VERSION = "mime-version";
    private static final String FIELD_RETURN_NUMBER = "return-number";
    private static final String FIELD_SENSITIVITY = "sensitivity";
    private static final String FIELD_SOURCE_NODE = "sourcenode";
    private static final String FIELD_SUBJECT = "subject";
    private static final String FIELD_TO_NUMBER = "to";
    private static final String FIELD_GREETING_TYPE = "x-cns-greeting-type";

    /** Audio duration in seconds. */
    @Nullable
    public Long duration;

    /** Content-Transfer-Encoding header value when downloading this object. */
    @Nullable
    public String responseContentTransferEncoding;

    /** MIME type of the HTTP response when downloading this object (Not MIME type of the file). */
    @Nullable
    public String responseContentType;

    /** Creation timestamp of this object. */
    @Nullable
    public Instant creationTimestamp;

    /** Call direction. (Always `In` for greetings.) */
    @Nullable
    public String direction;

    /** [Voicemails only] Expiration timestamp of this object. */
    public Instant expiryTimestamp;

    /**
     * Source phone number. This may contain a domain suffix, like "@tmo.com". For greetings, the
     * number matches the MSISDN.
     */
    @Nullable
    public String fromNumber;

    /** [Voicemails only] Message importance classification. */
    @Nullable
    public String importance;

    /** Unknown: Some internal file type classifier? "x-voice-grtng" or "voice-message". */
    @Nullable
    public String context;

    /**
     * Unique ID for this object. This is a timestamp for both voicemails and greetings, although
     * they are formatted very differently.
     */
    @Nullable
    public String id;

    /** Version of MIME specification. */
    @Nullable
    public String mimeVersion;

    /** [Voicemails only] Return phone number. This may contain a domain suffix, like "@tmo.com". */
    @Nullable
    public String returnNumber;

    /**
     * [Voicemails only] Unknown: Some sort of sensitivity classification. Example value:
     * "personal".
     */
    @Nullable
    public String sensitivity;

    /** [Voicemails only] Unknown. Example value: "VMAS". */
    @Nullable
    public String sourceNode;

    /**
     * [Greetings only] Message subject. This is an arbitrary string that can be set during upload.
     */
    @Nullable
    public String subject;

    /**
     * Target phone number. Unlike other number fields, this does not contain a domain suffix. For
     * greetings, the number matches the MSISDN.
     */
    @Nullable
    public String toNumber;

    /** [Greetings only] Type of greeting. See GSMA TS.46 v2.0 section 2.6.3. */
    @Nullable
    public String greetingType;

    /** Unrecognized attributes (if any). */
    @NonNull
    public HashMap<String, String> unknownAttrs = new HashMap<>();

    /** Flags currently set for this object. */
    @NonNull
    public ArrayList<String> flags = new ArrayList<>();

    /** MIME type of audio. */
    @Nullable
    public String payloadContentType;

    /** Byte size of audio. */
    public long payloadSize;

    /** Content-Encoding header value when downloading this object. */
    @Nullable
    public String payloadContentEncoding;

    /** Content-Disposition header value when downloading this object. */
    @Nullable
    public String payloadContentDisposition;

    /** mstore path for this object's payload. */
    @Nullable
    public String payloadPath;

    /** mstore path representing this object. */
    @Nullable
    public String objectPath;

    private static long parseLong(@NonNull String value) throws JSONException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid integer value: " + value, e);
        }
    }

    private static @NonNull Instant parseInstant(@NonNull String value) throws JSONException {
        try {
            return DateTimeFormatter.ISO_INSTANT.parse(value, Instant::from);
        } catch (DateTimeParseException e) {
            throw new JSONException("Invalid timestamp value: " + value, e);
        }
    }

    private static @NonNull String formatInstant(@NonNull Instant instant) throws JSONException {
        try {
            return DateTimeFormatter.ISO_INSTANT.format(instant);
        } catch (DateTimeException e) {
            throw new JSONException("Invalid timestamp value: " + instant, e);
        }
    }

    /**
     * Create an empty instance.
     */
    public MStoreObject() {}

    public MStoreObject(@NonNull JSONObject data) throws JSONException {
        final var attributes = data.getJSONObject("attributes");
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
                case FIELD_DURATION -> this.duration = parseLong(value);
                case FIELD_RESPONSE_CONTENT_TRANSFER_ENCODING ->
                        this.responseContentTransferEncoding = value;
                case FIELD_RESPONSE_CONTENT_TYPE -> this.responseContentType = value;
                case FIELD_CREATION_TIMESTAMP -> this.creationTimestamp = parseInstant(value);
                case FIELD_DIRECTION -> this.direction = value;
                case FIELD_EXPIRY_TIMESTAMP -> this.expiryTimestamp = parseInstant(value);
                case FIELD_FROM_NUMBER -> this.fromNumber = value;
                case FIELD_IMPORTANCE -> this.importance = value;
                case FIELD_CONTEXT -> this.context = value;
                case FIELD_ID -> this.id = value;
                case FIELD_MIME_VERSION -> this.mimeVersion = value;
                case FIELD_RETURN_NUMBER -> this.returnNumber = value;
                case FIELD_SENSITIVITY -> this.sensitivity = value;
                case FIELD_SOURCE_NODE -> this.sourceNode = value;
                case FIELD_SUBJECT -> this.subject = value;
                case FIELD_TO_NUMBER -> this.toNumber = value;
                case FIELD_GREETING_TYPE -> this.greetingType = value;
                default -> this.unknownAttrs.put(name, value);
            }
        }

        final var flags = data.optJSONObject("flags");
        if (flags != null) {
            final var flag = flags.getJSONArray("flag");

            for (var i = 0; i < flag.length(); i++) {
                this.flags.add(flag.getString(i));
            }
        }

        final var payloadPart = data.getJSONArray("payloadPart");
        if (payloadPart.length() != 1) {
            throw new JSONException("Expected single payload: " + payloadPart);
        }
        final var payload = payloadPart.getJSONObject(0);

        this.payloadContentType = payload.getString("contentType");
        this.payloadSize = parseLong(payload.getString("size"));
        this.payloadContentEncoding = payload.getString("contentEncoding");
        this.payloadContentDisposition = payload.getString("contentDisposition");
        this.payloadPath = MStoreClient.getObjectPathFromUrl(payload.getString("href"));

        this.objectPath = MStoreClient.getObjectPathFromUrl(data.getString("resourceURL"));
    }

    private static @NonNull JSONObject newAttribute(@NonNull String name, @NonNull String value)
            throws JSONException {
        return new JSONObject()
                .put("name", name)
                .put("value", new JSONArray().put(value));
    }

    /**
     * Serialize to JSON for use when uploading a new object.
     */
    public @NonNull JSONObject toJson() throws JSONException {
        final var attribute = new JSONArray();

        if (this.duration != null) {
            attribute.put(newAttribute(FIELD_DURATION, this.duration.toString()));
        }
        if (this.responseContentTransferEncoding != null) {
            attribute.put(newAttribute(FIELD_RESPONSE_CONTENT_TRANSFER_ENCODING,
                    this.responseContentTransferEncoding));
        }
        if (this.responseContentType != null) {
            attribute.put(newAttribute(FIELD_RESPONSE_CONTENT_TYPE, this.responseContentType));
        }
        if (this.creationTimestamp != null) {
            attribute.put(newAttribute(FIELD_CREATION_TIMESTAMP,
                    formatInstant(this.creationTimestamp)));
        }
        if (this.direction != null) {
            attribute.put(newAttribute(FIELD_DIRECTION, this.direction));
        }
        if (this.expiryTimestamp != null) {
            attribute.put(newAttribute(FIELD_EXPIRY_TIMESTAMP,
                    formatInstant(this.expiryTimestamp)));
        }
        if (this.fromNumber != null) {
            attribute.put(newAttribute(FIELD_FROM_NUMBER, this.fromNumber));
        }
        if (this.importance != null) {
            attribute.put(newAttribute(FIELD_IMPORTANCE, this.importance));
        }
        if (this.context != null) {
            attribute.put(newAttribute(FIELD_CONTEXT, this.context));
        }
        if (this.id != null) {
            attribute.put(newAttribute(FIELD_ID, this.id));
        }
        if (this.mimeVersion != null) {
            attribute.put(newAttribute(FIELD_MIME_VERSION, this.mimeVersion));
        }
        if (this.returnNumber != null) {
            attribute.put(newAttribute(FIELD_RETURN_NUMBER, this.returnNumber));
        }
        if (this.sensitivity != null) {
            attribute.put(newAttribute(FIELD_SENSITIVITY, this.sensitivity));
        }
        if (this.sourceNode != null) {
            attribute.put(newAttribute(FIELD_SOURCE_NODE, this.sourceNode));
        }
        if (this.subject != null) {
            attribute.put(newAttribute(FIELD_SUBJECT, this.subject));
        }
        if (this.toNumber != null) {
            attribute.put(newAttribute(FIELD_TO_NUMBER, this.toNumber));
        }
        if (this.greetingType != null) {
            attribute.put(newAttribute(FIELD_GREETING_TYPE, this.greetingType));
        }
        for (var entry : this.unknownAttrs.entrySet()) {
            attribute.put(newAttribute(entry.getKey(), entry.getValue()));
        }

        final var attributes = new JSONObject().put("attribute", attribute);
        final var flags = new JSONObject().put("flag", new JSONArray(this.flags));

        return new JSONObject()
                .put("attributes", attributes)
                .put("flags", flags);
    }

    public @Nullable String getFilename() {
        final var matcher = RE_FILENAME.matcher(payloadContentDisposition);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        return null;
    }
}
