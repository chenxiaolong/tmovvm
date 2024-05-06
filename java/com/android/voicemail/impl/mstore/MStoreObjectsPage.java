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

import java.util.ArrayList;

/**
 * A type that represents one page of results from the response of a search API query.
 */
public class MStoreObjectsPage {
    /** Objects on this page. */
    @NonNull
    public ArrayList<MStoreObject> objects = new ArrayList<>();

    /** Cursor value to use for the next query to get the next page of results. */
    @Nullable
    public String cursor;

    public MStoreObjectsPage(@NonNull JSONObject data) throws JSONException {
        final var objectList = data.getJSONObject("objectList");
        final var object = objectList.getJSONArray("object");

        for (var i = 0; i < object.length(); i++) {
            final var item = object.getJSONObject(i);
            this.objects.add(new MStoreObject(item));
        }

        this.cursor = objectList.optString("cursor", null);
    }
}
