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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A type that represents a response for a single object when performing a bulk update or delete.
 */
public class MStoreBulkResponseItem {
    /** Status code. Appears to reuse HTTP status codes. */
    public int code;

    /** Status message. Appears to reuse HTTP status messages. */
    @NonNull
    public String reason;

    /** mstore path for this object. */
    @NonNull
    public String objectPath;

    public MStoreBulkResponseItem(@NonNull JSONObject data) throws JSONException {
        this.code = data.getInt("code");
        this.reason = data.getString("reason");

        if (data.has("success")) {
            final var url = data
                    .getJSONObject("success")
                    .getString("resourceURL");
            this.objectPath = MStoreClient.getObjectPathFromUrl(url);
        } else if (data.has("failure")) {
            final var url = data
                    .getJSONObject("failure")
                    .getJSONObject("serviceException")
                    .getJSONArray("variables")
                    .getString(0);
            this.objectPath = MStoreClient.getObjectPathFromUrl(url);
        } else {
            throw new JSONException("Neither success nor failure found: " + data);
        }
    }
}
