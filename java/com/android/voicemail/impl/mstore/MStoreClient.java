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
import android.net.Network;
import android.net.Uri;
import android.telecom.PhoneAccount;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.BootstrapAuthenticationCallback;
import android.telephony.gba.TlsParams;
import android.telephony.gba.UaSecurityProtocolIdentifier;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main entrypoint for interacting with the mstore API.
 */
public class MStoreClient {
    /**
     * Regex to match the object path within an object URL. This is necessary because the mstore API
     * relay returns URLs in the form:
     *
     * <code>https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/...</code>
     *
     * instead of:
     *
     * <code>https://wsg.t-mobile.com/phone20/mStoreRelay/...</code>
     *
     * The former is not accepted by API calls that require an object URL (like bulk requests).
     */
    private static final Pattern RE_OBJECT_PATH = Pattern.compile("^.*/objects/(.+)$");

    private static final int TIMEOUT_CONNECT_MS = 10 * 1000;
    private static final int TIMEOUT_READ_MS = 60 * 1000;

    /**
     * Production base URL for the API. The staging instance is wsg2.stg.sip.t-mobile.com.
     */
    private static final String BASE_URL = "https://wsg.t-mobile.com";

    /**
     * This is not an arbitrary string. Per 3GPP TS 33.222 v18.0.0 section 5.3.0 bullet point 2, the
     * client shall specify this exact value to indicate to the NAF that GBA-based authentication is
     * supported by the client. Note that the specification says "shall", but T-Mobile's backend
     * will time out and return HTTP 504 if another value is specified.
     */
    private static final String USER_AGENT = "3gpp-gba";

    private static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * UUID of the folder containing voicemails. Uploads to this folder are not permitted.
     */
    public static final String FOLDER_VOICEMAILS = "27a29814-dd8f-43ee-b768-19af98bf1d07";

    /**
     * UUID of the folder containing custom greetings. Uploads to this folder are permitted.
     */
    public static final String FOLDER_GREETINGS = "c1a7c823-fdd1-4857-8d44-b315444d2a83";

    /**
     * Flag indicating whether an object is marked as read.
     */
    public static final String FLAG_SEEN = "\\Seen";

    /**
     * Flag indicating whether an object was recently created. This flag is read only. It is present
     * when {@code FLAG_SEEN} is absent and vice versa.
     */
    public static final String FLAG_RECENT = "\\Recent";

    /**
     * Flag indicating whether a voicemail object is kept forever (not allowed to expire). Setting
     * this flag is via the mstore API is not permitted, but an object may potentially still have
     * this flag (perhaps set via interactions with the CVVM or CPAAS APIs).
     */
    public static final String FLAG_VOICEMAIL_KEEP = "$MarkNotToDelete";

    /**
     * Flag indicating whether a greeting object is active. Bulk requests will fail hard and abort
     * early with HTTP 400 if setting this flag is attempted on an object that is not a greeting.
     */
    public static final String FLAG_GREETING_ACTIVE = "$CNS-Greeting-On";

    private final @NonNull TelephonyManager manager;
    private final @NonNull Network network;
    private final @NonNull String msisdnUri;

    /**
     * Create a new mstore API client.
     *
     * @param manager Used to obtain the MSISDN.
     * @param network All API calls are performed on this network.
     */
    public MStoreClient(@NonNull TelephonyManager manager, @NonNull Network network) {
        this.manager = manager;
        this.network = network;

        // This does not use Uri.fromParts() because otherwise, the + would be double encoded.
        this.msisdnUri = Uri.encode(PhoneAccount.SCHEME_TEL + ":+" + manager.getLine1Number());
    }

    private @NonNull String getBaseUrl() {
        return BASE_URL + "/phone20/mStoreRelay/oemclient/nms/v1/ums/" + msisdnUri;
    }

    private @NonNull String getProfileUrl() {
        return getBaseUrl() + "/vvmserviceProfile";
    }

    private @NonNull String getFolderUrl(@NonNull String folder) {
        return getBaseUrl() + "/folders/" + folder;
    }

    private @NonNull String getQuotaUrl(@NonNull String folder) {
        // The query parameters must be URL-encoded or else the server will return HTTP 504.
        return getFolderUrl(folder) + Uri.encode("?attrFilter=Quota");
    }

    private @NonNull String getObjectsBaseUrl() {
        return getBaseUrl() + "/objects";
    }

    private @NonNull String getObjectUrl(@NonNull String objectPath) {
        return getObjectsBaseUrl() + "/" + objectPath;
    }

    private @NonNull String getObjectFlagUrl(@NonNull String objectPath, @NonNull String flag) {
        return getObjectUrl(objectPath) + "/flags/" + Uri.encode(flag);
    }

    private @NonNull String getSearchUrl() {
        return getObjectsBaseUrl() + "/operations/search";
    }

    private @NonNull String getBulkUpdateUrl() {
        return getObjectsBaseUrl() + "/operations/bulkUpdate";
    }

    private @NonNull String getBulkDeleteUrl() {
        return getObjectsBaseUrl() + "/operations/bulkDelete";
    }

    /**
     * Extract the object path from an object URL. This is necessary because the proxy sitting in
     * front of the real servers (mstorerelay) mangles the URLs returned by the API. Clients need to
     * manually reconstruct URLs using information from these mangled URLs.
     */
    public static @NonNull String getObjectPathFromUrl(@NonNull String url) {
        final var matcher = RE_OBJECT_PATH.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        throw new IllegalStateException("Failed to obtain object path from: " + url);
    }

    /**
     * Perform 3GPP GBA bootstrapping to obtain the credentials for digest authentication. This must
     * be called for every request. The result must not be cached and reused.
     *
     * @return The username and password for use with HTTP digest auth.
     */
    private @NonNull Pair<String, String> performGbaBootstrap(@NonNull Uri uri)
            throws MStoreException {
        final var nafId = new Uri.Builder()
                .scheme(uri.getScheme())
                .encodedAuthority("3GPP-bootstrapping@" + uri.getEncodedAuthority())
                .build();
        final var securityProtocol = new UaSecurityProtocolIdentifier.Builder()
                .setOrg(UaSecurityProtocolIdentifier.ORG_3GPP)
                .setProtocol(UaSecurityProtocolIdentifier.UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT)
                .setTlsCipherSuite(TlsParams.TLS_RSA_WITH_AES_128_CBC_SHA)
                .build();

        final var future = new CompletableFuture<Pair<String, String>>();
        final var callback = new BootstrapAuthenticationCallback() {
            @Override
            public void onKeysAvailable(@NonNull byte[] gbaKey, @NonNull String transactionId) {
                // The encoding must match exactly what the server expects, since the client never
                // sends the actual password to the server with HTTP digest auth.
                String gbaKeyBase64 = Base64.encodeToString(gbaKey, Base64.NO_WRAP);
                future.complete(new Pair<>(transactionId, gbaKeyBase64));
            }

            @Override
            public void onAuthenticationFailure(int reason) {
                final var reasonMsg = switch (reason) {
                    case TelephonyManager.GBA_FAILURE_REASON_FEATURE_NOT_SUPPORTED ->
                            "Not supported";
                    case TelephonyManager.GBA_FAILURE_REASON_FEATURE_NOT_READY ->
                            "Not ready";
                    case TelephonyManager.GBA_FAILURE_REASON_NETWORK_FAILURE ->
                            "Network failure";
                    case TelephonyManager.GBA_FAILURE_REASON_INCORRECT_NAF_ID ->
                            "Incorrect NAF URL";
                    case TelephonyManager.GBA_FAILURE_REASON_SECURITY_PROTOCOL_NOT_SUPPORTED ->
                            "Security protocol not supported";
                    default -> "Unknown error: " + reason;
                };

                future.completeExceptionally(new MStoreException(
                        "Failed to perform GBA: " + reasonMsg));
            }
        };

        manager.bootstrapAuthenticationRequest(
                TelephonyManager.APPTYPE_ISIM,
                nafId,
                securityProtocol,
                false,
                Runnable::run,
                callback
        );

        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (MStoreException) e.getCause();
        } catch (InterruptedException e) {
            // Not reachable.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Compute the lowercase hex digest of the specified string encoded as ASCII. Currently, only
     * MD5 is supported because support for SHA-256 in HTTP digest auth is, in general, extremely
     * limited. It is definitely not supported by the mstore backend.
     */
    private static @NonNull String computeHexDigest(@NonNull String algorithm,
            @NonNull String asciiData) throws NoSuchAlgorithmException {
        if (!"MD5".equals(algorithm)) {
            throw new NoSuchAlgorithmException("Unsupported digest algorithm: " + algorithm);
        }

        final var md = MessageDigest.getInstance(algorithm);
        final var data = asciiData.getBytes(StandardCharsets.US_ASCII);

        md.update(data);

        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Generate a random hex string for use with the HTTP digest auth cnonce parameter.
     */
    private static @NonNull String generateHexCnonce() {
        final var random = new SecureRandom();
        final var data = new byte[16];

        random.nextBytes(data);

        return HexFormat.of().formatHex(data);
    }

    /**
     * Compute the Authorization header value in response to the WWW-Authenticate challenge from
     * an unauthenticated request. Only the 3GPP-GBA flavor of HTTP digest auth is supported.
     */
    private @NonNull String processDigestAuth(@NonNull HttpURLConnection connection)
            throws MStoreException, IOException {
        final var url = connection.getURL();

        if (connection.getResponseCode() != 401) {
            throw new MStoreException(url + ": Expected HTTP 401, but have "
                    + connection.getResponseCode() + " " + connection.getResponseMessage());
        }

        var wwwAuthenticate = connection.getHeaderField("WWW-Authenticate");
        if (wwwAuthenticate == null) {
            throw new MStoreException(url + ": Missing WWW-Authenticate header");
        }

        final HashMap<String, HashMap<String, String>> parsed;
        try {
            parsed = WwwAuthenticate.parse(wwwAuthenticate);
        } catch (IllegalArgumentException e) {
            throw new MStoreException(url + ": Invalid WWW-Authenticate header: " + wwwAuthenticate,
                    e);
        }

        final var digestParams = parsed.get("Digest");
        if (digestParams == null) {
            throw new MStoreException(url + ": Digest auth not supported: " + parsed.keySet());
        }

        final var algorithm = digestParams.getOrDefault("algorithm", "MD5");
        if (!"MD5".equals(algorithm)) {
            throw new MStoreException(url + ": Digest: Unsupported algorithm: " + algorithm);
        }

        final var qop = digestParams.getOrDefault("qop", "auth");
        if (!"auth".equals(qop)) {
            throw new MStoreException(url + ": Digest: Unsupported qop: " + qop);
        }

        final var realm = digestParams.get("realm");
        if (realm == null) {
            throw new MStoreException(url + ": Digest: Missing realm");
        }

        final var nonce = digestParams.get("nonce");
        if (nonce == null) {
            throw new MStoreException(url + ": Digest: Missing nonce");
        }

        final var uri = Uri.parse(url.toString());
        final var credentials = performGbaBootstrap(uri);
        final var cnonce = generateHexCnonce();
        final var urlDecoded = Uri.decode(url.toString());

        digestParams.put("username", credentials.first);
        digestParams.put("uri", urlDecoded);
        digestParams.put("cnonce", cnonce);
        digestParams.put("nc", "00000001");

        try {
            final var ha1 = computeHexDigest(algorithm,
                    credentials.first + ":" + realm + ":" + credentials.second);
            final var ha2 = computeHexDigest(algorithm,
                    connection.getRequestMethod() + ":" + urlDecoded);
            final var response = computeHexDigest(algorithm,
                    ha1 + ":" + nonce + ":00000001:" + cnonce + ":" + qop + ":" + ha2);

            digestParams.put("response", response);
        } catch (NoSuchAlgorithmException e) {
            // There is no version of Android that doesn't support MD5.
            throw new IllegalStateException(e);
        }

        try {
            return WwwAuthenticate.format("Digest", digestParams);
        } catch (IllegalArgumentException e) {
            throw new MStoreException(url + ": Failed create Authorization header value");
        }
    }

    /**
     * Set all HTTP connection parameters: timeouts, method, headers, and an optional body.
     */
    private void setConnectionParams(@NonNull HttpURLConnection connection, @NonNull String method,
            @Nullable String authorization, @Nullable String contentType, @Nullable byte[] body)
            throws IOException {
        connection.setConnectTimeout(TIMEOUT_CONNECT_MS);
        connection.setReadTimeout(TIMEOUT_READ_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            connection.getOutputStream().write(body);
        }
    }

    /**
     * Send a request, automatically performing 3GPP GBA authentication. The entire body, if any,
     * must be known upfront because it needs to be sent twice.
     */
    private @NonNull HttpURLConnection sendRequest(@NonNull String url, @NonNull String method,
            @Nullable String contentType, @Nullable byte[] body)
            throws MStoreException, IOException {
        final var urlObj = new URL(url);
        var connection = (HttpURLConnection) network.openConnection(urlObj);
        setConnectionParams(connection, method, null, contentType, body);

        final String authorization;

        try {
            authorization = processDigestAuth(connection);
        } finally {
            // The server does not support keep-alive anyway.
            connection.disconnect();
        }

        connection = (HttpURLConnection) network.openConnection(urlObj);
        setConnectionParams(connection, method, authorization, contentType, body);

        return connection;
    }

    /**
     * Throw an exception if the response status is not 2xx. The socket will be disconnected instead
     * of reading the body to drain the TCP receive buffer since the mstore backend does not support
     * keepalive anyway (HTTP/1.0 only).
     */
    private static void throwAndDisconnectOnBadStatus(@NonNull HttpURLConnection connection)
            throws MStoreException, IOException {
        if (connection.getResponseCode() / 100 != 2) {
            connection.disconnect();
            throw new MStoreException(connection.getURL() + ": "
                    + connection.getRequestMethod() + " error response: "
                    + connection.getResponseCode() + " " + connection.getResponseMessage());
        }
    }

    /**
     * Get the visual voicemail profile information.
     */
    public @NonNull MStoreProfile getProfile() throws MStoreException, IOException {
        final var connection = sendRequest(getProfileUrl(), "GET", null, null);
        throwAndDisconnectOnBadStatus(connection);

        try (final var stream = connection.getInputStream()) {
            final var data = new JSONObject(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            return new MStoreProfile(data);
        } catch (JSONException e) {
            throw new MStoreException("Failed to parse JSON response", e);
        }
    }

    /**
     * Update the visual voicemail profile information. See {@code MStoreProfile} for details on
     * which fields can be set as some of them are read-only. When changing the voicemail PIN, other
     * fields should not be specified.
     */
    public void updateProfile(@NonNull MStoreProfile profile) throws MStoreException, IOException {
        final byte[] body;
        try {
            body = profile.toJson().toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new MStoreException("Failed to serialize profile as JSON", e);
        }

        final var connection = sendRequest(getProfileUrl(), "PUT", CONTENT_TYPE_JSON, body);

        if (profile.oldPin != null || profile.newPin != null) {
            if (connection.getResponseCode() == 403) {
                connection.disconnect();

                final var reason = connection.getHeaderField(PinChangeStatus.HEADER_REASON_PHRASE);
                if (reason == null) {
                    throw new MStoreException("PIN change failed with no reason given");
                }

                final var status = PinChangeStatus.fromReasonPhrase(reason);
                throw new MStoreException("PIN change failed: " + status);
            }
        }

        throwAndDisconnectOnBadStatus(connection);

        // This endpoint never sends a body on success.
        connection.disconnect();
    }

    /**
     * Get the quota and usage statistics for the specified folder.
     */
    public @NonNull MStoreQuota getQuota(@NonNull String folder)
            throws MStoreException, IOException {
        final var connection = sendRequest(getQuotaUrl(folder), "GET", null, null);
        throwAndDisconnectOnBadStatus(connection);

        try (final var stream = connection.getInputStream()) {
            final var data = new JSONObject(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            return new MStoreQuota(data);
        } catch (JSONException e) {
            throw new MStoreException("Failed to parse JSON response", e);
        }
    }

    /**
     * Get the object at the specified object path.
     */
    public @NonNull MStoreObject getObject(@NonNull String objectPath)
            throws MStoreException, IOException {
        final var connection = sendRequest(getObjectUrl(objectPath), "GET", null, null);
        throwAndDisconnectOnBadStatus(connection);

        try (final var stream = connection.getInputStream()) {
            final var data = new JSONObject(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            return new MStoreObject(data.getJSONObject("object"));
        } catch (JSONException e) {
            throw new MStoreException("Failed to parse JSON response", e);
        }
    }

    /**
     * Get a search filter for obtaining the list of objects in a folder sorted by the creation
     * timestamp in descending order.
     */
    private @NonNull JSONObject getSelectionCriteria(@NonNull String folder,
            @Nullable String fromCursor) throws JSONException {
        // This is hardcoded because it suits all of our needs and there's no documentation for what
        // this query language supports to create a proper wrapper.
        return new JSONObject()
                .put("selectionCriteria", new JSONObject()
                        .put("maxEntries", 100)
                        .putOpt("fromCursor", fromCursor)
                        .put("searchScope", new JSONObject()
                                .put("resourceURL", getFolderUrl(folder))
                        )
                        .put("searchCriteria", new JSONObject()
                                .put("operator", "Not")
                                .put("criterion", new JSONArray()
                                        .put(new JSONObject()
                                                .put("type", "PurgedObject")
                                                .put("value", "")
                                        )
                                )
                        )
                        .put("sortCriteria", new JSONObject()
                                .put("criterion", new JSONArray()
                                        .put(new JSONObject()
                                                .put("type", "Date")
                                                .put("order", "Descending")
                                        )
                                )
                        )
                );
    }

    /**
     * Get a list of all objects in the specified folder, sorted by creation date in descending
     * order.
     */
    public @NonNull ArrayList<MStoreObject> getFolderObjects(@NonNull String folder)
            throws MStoreException, IOException {
        final ArrayList<MStoreObject> objects = new ArrayList<>();
        String fromCursor = null;

        do {
            final byte[] body;
            try {
                body = getSelectionCriteria(folder, fromCursor).toString()
                        .getBytes(StandardCharsets.UTF_8);
            } catch (JSONException e) {
                throw new MStoreException("Failed to serialize selection criteria", e);
            }

            final var connection = sendRequest(getSearchUrl(), "POST", CONTENT_TYPE_JSON, body);
            throwAndDisconnectOnBadStatus(connection);

            if (connection.getResponseCode() == 204) {
                // The server returns this instead of JSON data containing no objects when there are
                // no search results.
                break;
            }

            try (final var stream = connection.getInputStream()) {
                final var data = new JSONObject(
                        new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                final var page = new MStoreObjectsPage(data);

                objects.addAll(page.objects);
                fromCursor = page.cursor;
            } catch (JSONException e) {
                throw new MStoreException("Failed to parse JSON response", e);
            }
        } while (fromCursor != null);

        return objects;
    }

    /**
     * Set or clear flag on an object. This is only compatible with {@link FLAG_GREETING_ACTIVE}.
     */
    public void setFlag(@NonNull String objectPath, @NonNull String flag, boolean value)
            throws MStoreException, IOException {
        final var method = value ? "PUT" : "DELETE";
        final var connection = sendRequest(getObjectFlagUrl(objectPath, flag), method, null, null);
        throwAndDisconnectOnBadStatus(connection);
        connection.disconnect();
    }

    /**
     * Create the JSON structure for specifying the list of resources in all bulk requests.
     */
    private @NonNull JSONArray getBulkResourceUrls(@NonNull List<String> objectPaths)
            throws JSONException {
        final var objectReference = new JSONArray();
        for (var objectPath : objectPaths) {
            objectReference.put(new JSONObject().put("resourceURL", getObjectUrl(objectPath)));
        }

        return objectReference;
    }

    /**
     * Parse the response of a bulk response and throw if the operation failed for any individual
     * object.
     */
    private static void throwOnBadBulkResponse(@NonNull HttpURLConnection connection)
            throws MStoreException, IOException {
        var inputStream = connection.getErrorStream();
        if (inputStream == null) {
            inputStream = connection.getInputStream();
        }

        final MStoreBulkResponseList bulkResponseList;

        try (final var stream = inputStream) {
            var data = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            // If the server returns an HTTP 5xx, then the response data is stringified inside the
            // NWK_RSP field.
            if (data.has("NWK_RSP")) {
                data = new JSONObject(data.getString("NWK_RSP"));
            }

            bulkResponseList = new MStoreBulkResponseList(data);
        } catch (JSONException e) {
            throw new MStoreException("Failed to parse JSON response", e);
        }

        // We intentionally do not look at the HTTP response. An HTTP error is returned if there's
        // a mixture of successes and failures.
        final var failures = bulkResponseList.responses
                .stream()
                .filter(r -> r.code / 100 != 2)
                .map(r -> r.objectPath + " (" + r.code + " " + r.reason + ")")
                .collect(Collectors.joining(", "));
        if (!failures.isEmpty()) {
            throw new MStoreException("Bulk request failed for: " + failures);
        }
    }

    /**
     * Set or clear flags on objects. This does not fail fast. Setting the flag is always attempted
     * on every object. This is not compatible with {@link FLAG_GREETING_ACTIVE}.
     */
    public void bulkSetFlag(@NonNull List<String> objectPaths, @NonNull List<String> flags,
            boolean value) throws MStoreException, IOException {
        final byte[] body;
        try {
            body = new JSONObject()
                    .put("bulkUpdate", new JSONObject()
                            .put("objects", new JSONObject()
                                    .put("objectReference", getBulkResourceUrls(objectPaths))
                            )
                            .put("operation", value ? "AddFlag" : "RemoveFlag")
                            .put("flags", new JSONObject()
                                    .put("flag", new JSONArray(flags))
                            )
                    )
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new MStoreException("Failed to serialize bulk request as JSON", e);
        }

        final var connection = sendRequest(getBulkUpdateUrl(), "POST", CONTENT_TYPE_JSON, body);
        throwOnBadBulkResponse(connection);
    }

    /**
     * Delete objects. This does not fail fast. Deletion is always attempted for every object.
     */
    public void bulkDelete(@NonNull List<String> objectPaths) throws MStoreException, IOException {
        final byte[] body;
        try {
            body = new JSONObject()
                    .put("bulkDelete", new JSONObject()
                            .put("objects", new JSONObject()
                                    .put("objectReference", getBulkResourceUrls(objectPaths))
                            )
                    )
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new MStoreException("Failed to serialize bulk request as JSON", e);
        }

        final var connection = sendRequest(getBulkDeleteUrl(), "DELETE", CONTENT_TYPE_JSON, body);
        throwOnBadBulkResponse(connection);
    }

    /**
     * Download the payload of the specified object. The data is streamed and decoded on demand.
     */
    public @NonNull InputStream downloadObject(@NonNull MStoreObject object)
            throws MStoreException, IOException {
        final var payloadPath = Objects.requireNonNull(object.payloadPath);
        final var connection = sendRequest(getObjectUrl(payloadPath), "GET", null, null);
        throwAndDisconnectOnBadStatus(connection);

        InputStream stream = connection.getInputStream();

        if ("base64".equals(connection.getHeaderField("Content-Transfer-Encoding"))) {
            stream = new Base64InputStream(stream, Base64.DEFAULT);
        }

        return stream;
    }

    /**
     * Get the payload path for uploading a new object.
     */
    private static @NonNull String getPayloadPath(@NonNull String folder) throws MStoreException {
        switch (folder) {
            case FOLDER_VOICEMAILS -> {
                return "/VV-Mail/Inbox";
            }
            case FOLDER_GREETINGS -> {
                return "/VV-Mail/Greetings";
            }
            default -> throw new MStoreException("Unrecognized folder ID: " + folder);
        }
    }

    /**
     * Upload an object to the specified folder.
     */
    public void uploadObject(@NonNull String folder, @NonNull MStoreObject object,
            @NonNull byte[] data) throws MStoreException, IOException {
        final var boundaryInner = "voicemail-greeting-inner";
        final var boundaryOuter = "voicemail-greeting-outer";

        // The first multipart body is the object metadata.
        final byte[] objectJson;
        try {
            objectJson = new JSONObject()
                    .put("object", object.toJson()
                            .put("parentFolderPath", getPayloadPath(folder))
                    )
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new MStoreException("Failed to serialize object as JSON", e);
        }

        final var objectHeader = ("--" + boundaryOuter + "\r\n"
                + "Content-Disposition: form-data; name=\"root-fields\"\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + objectJson.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        // The second multipart body is the data, which itself is a multipart message.
        final var dataBase64 = Base64.encode(data, Base64.DEFAULT);
        final var dataHeader = ("--" + boundaryInner + "\r\n"
                + "Content-Disposition: attachment;filename=\"audio.amr\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "Content-Type: audio/amr\r\n"
                + "Content-Length: " + dataBase64.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final var dataFooter = ("\r\n--" + boundaryInner + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        final var attachmentHeader = ("\r\n--" + boundaryOuter + "\r\n"
                + "Content-Disposition: form-data;name=\"attachments\"\r\n"
                + "Content-Type: multipart/mixed; boundary=" + boundaryInner + "\r\n"
                + "Content-Length: " + (dataHeader.length + dataBase64.length + dataFooter.length)
                + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final var outerFooter = ("\r\n--" + boundaryOuter + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);

        final var body = new ByteArrayOutputStream();
        body.write(objectHeader);
        body.write(objectJson);
        body.write(attachmentHeader);
        body.write(dataHeader);
        body.write(dataBase64);
        body.write(dataFooter);
        body.write(outerFooter);

        final var connection = sendRequest(getObjectsBaseUrl(), "POST",
                "multipart/form-data; boundary=" + boundaryOuter, body.toByteArray());
        throwAndDisconnectOnBadStatus(connection);
    }
}
