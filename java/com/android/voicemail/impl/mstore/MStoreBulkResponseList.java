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

import java.util.ArrayList;

/**
 * A type that represents the response for a bulk update or delete.
 */
public class MStoreBulkResponseList {
    /** List of responses in this bulk response. */
    @NonNull
    public ArrayList<MStoreBulkResponseItem> responses = new ArrayList<>();

    public MStoreBulkResponseList(@NonNull JSONObject data) throws JSONException {
        final var bulkResponseList = data.getJSONObject("bulkResponseList");
        final var response = bulkResponseList.getJSONArray("response");

        for (var i = 0; i < response.length(); i++) {
            final var item = response.getJSONObject(i);

            this.responses.add(new MStoreBulkResponseItem(item));
        }
    }
}
