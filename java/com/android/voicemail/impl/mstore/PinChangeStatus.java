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

/**
 * A type representing the possible failure reasons when a PIN change fails.
 */
public enum PinChangeStatus {
    REPEATED_DIGITS,
    CONSECUTIVE_DIGITS,
    RECENTLY_CHANGED,
    MATCHES_LAST_4_MSISDN,
    INVALID_CHARACTER,
    MATCHES_CURRENT,
    TOO_LONG,
    TOO_SHORT,
    INVALID_OLD_PIN,
    NOT_ACTIVE,
    UNKNOWN;

    public static final String HEADER_REASON_PHRASE = "reason-phrase";

    /**
     * Parse the failure reason from the {@link HEADER_REASON_PHRASE} value in an HTTP 403 response
     * from a PIN change request.
     */
    public static @NonNull PinChangeStatus fromReasonPhrase(@NonNull String reason) {
        return switch (reason) {
            case "PIN has Repeated Digits" -> REPEATED_DIGITS;
            case "PIN has Consecutive Numbers" -> CONSECUTIVE_DIGITS;
            case "PIN recently Changed" -> RECENTLY_CHANGED;
            case "PIN last 4 digit match MSISDN" -> MATCHES_LAST_4_MSISDN;
            case "PIN has invalid Character" -> INVALID_CHARACTER;
            case "PIN in Use" -> MATCHES_CURRENT;
            case "PIN length too Long" -> TOO_LONG;
            case "PIN length too Short" -> TOO_SHORT;
            case "Invalid OLD PIN" -> INVALID_OLD_PIN;
            case "VVM Not Active" -> NOT_ACTIVE;
            default -> UNKNOWN;
        };
    }
}
