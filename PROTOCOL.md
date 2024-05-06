# Protocol documentation

This page describes what is currently known about how the mstore API works. This is not a publicly documented API and the details below were found by experimentation.

The API is centered around a few types of reosurces: profiles, folders, and objects. The profile represents the state of the account, such as whether VVM is enabled. Folders contain objects of the same type, such as voicemails or custom greetings, and have their own quotas. Objects represent individual files within a folder.

There are many aspects of the mstore API that are similar to the old CVVM/OMTP APIs. For example, the flag for a voicemail marked as read is `\Seen` and the flag for an active custom greeting is `$CNS-Greeting-On`. Uploading a custom greeting also uses multipart messages with an attachment, similar to what would be done with IMAP. Internally, this API might potentially be a wrapper around the same IMAP backend as what CVVM used.

## Servers

The two known servers are:

* Production: https://wsg.t-mobile.com/
* Staging: https://wsg2.stg.sip.t-mobile.com/

These run a proxy ("mStoreRelay") that forwards requests to the actual application servers. An endpoint generally has the form:

```
https://wsg.t-mobile.com/phone20/mStoreRelay/<path>
```

However, some endpoints will return URLs that have the form:

```
https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/<path>
```

Because the proxy server does not rewrite these URLs, a client must parse out the path and append it to the proper base URL. These "extended" URLs do not work properly as-is.

The proxy server does not support anything newer than HTTP/1.0. A client does not need to bother with supporting HTTP keepalive. A new TCP connection is required for every request.

## Authentication

The API uses [3GPP-GBA](https://en.wikipedia.org/wiki/Generic_Bootstrapping_Architecture) (generic bootstrapping architecture) for authentication. This authentication scheme relies on a shared secret stored inside the SIM card that is also known to the carrier. The bootstrapping process provides the credentials necessary to perform standard HTTP digest auth.

When performing HTTP digest auth, the username is the GBA transaction identifier and the password is the base64-encoded GBA key. Since HTTP digest auth revolves around proving knowledge of the password without actually sending the password across the wire, the password must be encoded exactly as the server expects. In this case, it is standard base64 with padding, but with no line wrapping.

Additionally, the `User-Agent` header must be set to `3gpp-gba`. This is required by the specification to indicate that GBA authentication is supported by the client. T-Mobile's servers will time out with HTTP 504 if a different user agent is specified.

The GBA parameters are:

* UICC application type: ISIM
* NAF ID: `3GPP-bootstrapping@wsg.t-mobile.com`
* Security protocol identifier: `0x01`, `0x00`, `0x01`, `0x00`, `0x2f`
    * [Byte 0] Organization: 3GPP
    * [Bytes 1-2] Protocol: "Shared key-based UE authentication with
certificate-based NAF authentication"
    * [Bytes 3-4] TLS cipher suite: `TLS_RSA_WITH_AES_128_CBC_SHA`

(The GBA specification can be found at 3GPP TS 33.220.)

Note that the GBA credentials should **never** be cached as they are time limited. Bootstrapping should be done for every request.

The general flow for each request is:

1. Send the request with no `Authorization` header. The server should return HTTP 401 with the `Www-Authenticate` header containing the HTTP digest auth challenge. If the server does not return HTTP 401, something is likely wrong with the request. Note that if the request has a body, it must also be sent for this initial unauthenticated request.
2. Perform GBA boostrapping to obtain the username and password for authentication.
3. Using the nonce and other values from the `Www-Authenticate` challenge, compute the `Authorization` value for the authenticated request using the normal rules for HTTP digest auth. Note that the `uri` challenge parameter must not be URL-encoded. Also, the `nc` challenge parameter must not be quoted due to server-side parsing bugs.
4. Send the authenticated request.

## Endpoints

The base URL for all of the endpoints below is:

```
/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>
```

`<MSISDN URI>` is the `tel` URI of the MDISDN (phone number) in E.164 form. For example, the URI for 123-456-7890 is `tel:+11234567890`. **NOTE**: The MSISDN URI **must** be URL-encoded for the HTTP request, but **must not** be URL-encoded for the `uri` field when performing HTTP digest auth with 3GPP-GBA.

All requests that send JSON data as part of the request body must specify the `Content-Type` header as `application/json`.

When a request is malformed, the server will likely return HTTP 400 with the following body (and without any indication of what might be wrong):

```json
{
    "error": "MSTORE_RLY-MST_NWKERR"
}
```

### `/vvmserviceProfile` [GET, PUT]

This endpoint is for querying and updating the VVM profile settings. The profile object returned by the server for the GET request has the structure below. The PUT request for changing profile settings also uses the same structure, though attributes that do not need to be updated should be omitted. Despite being a `PUT` request, it has `POST` semantics. Also, note that some boolean values are camelcased.

```jsonc
{
    "vvmserviceProfile": {
        "attributes": {
            "attribute": [
                // [in,out?] Unknown.
                {
                    "name": "cosname",
                    "value": [
                        "1234"
                    ]
                },
                // [in] Unknown: Whether activation is blocked?
                {
                    "name": "isblocked",
                    "value": [
                        "false"
                    ]
                },
                // [in,out?] Unknown: Language of what?
                {
                    "name": "Language",
                    "value": [
                        "eng"
                    ]
                },
                // [in] Unknown: Timestamp of migration away from CVVM?
                {
                    "name": "MigrationDate",
                    "value": [
                        "2019:01:01:00:00"
                    ]
                },
                // [in] Unknown: Whether migrated away from CVVM?
                {
                    "name": "MigrationStatus",
                    "value": [
                        "True"
                    ]
                },
                // [in,out] Whether the new user tutorial should be shown.
                {
                    "name": "nut",
                    "value": [
                        "false"
                    ]
                },
                // [in,out?] Unknown: Whether to send an SMS link for
                // downloading some app?
                {
                    "name": "SMSDirectLink",
                    "value": [
                        "false"
                    ]
                },
                // [in,out?] Unknown: Voice-to-something?
                {
                    "name": "V2E_ON",
                    "value": [
                        "False"
                    ]
                },
                // [in,out?] Unknown: Voice-to-text language? Unsure what the
                // purpose of this value is. T-Mobile's paid server-side
                // transcription is only available in their Visual Voicemail
                // app, which uses a different API. Google Dialer's
                // transcription is local and ignores this value.
                {
                    "name": "V2t_Language",
                    "value": [
                        "None"
                    ]
                },
                // [in,out] Whether visual voicemail is enabled.
                {
                    "name": "vvmon",
                    "value": [
                        "true"
                    ]
                },
                // [out] Old PIN (when changing the PIN).
                {
                    "name": "OLD_PWD",
                    "value": [
                        "0000"
                    ]
                },
                // [out] New PIN (when changing the PIN).
                {
                    "name": "PWD",
                    "value": [
                        "1234"
                    ]
                }
            ]
        }
    }
}
```

When changing the PIN, only the `OLD_PWD` and `PWD` attributes should be sent. If the PIN change fails, the server will return HTTP 403 and set the `reason-phrase` response header to one of the following values:

* `PIN has Repeated Digits`
* `PIN has Consecutive Numbers`
* `PIN recently Changed`
* `PIN last 4 digit match MSISDN`
* `PIN has invalid Character`
* `PIN in Use`
* `PIN length too Long`
* `PIN length too Short`
* `Invalid OLD PIN`
* `VVM Not Active`

### `/folders/<FOLDER ID>` [GET]

The endpoint is for querying attributes about a folder. There are two known folder IDs:

* `27a29814-dd8f-43ee-b768-19af98bf1d07`: Contains voicemails
* `c1a7c823-fdd1-4857-8d44-b315444d2a83`: Contains custom greetings

The endpoint takes an `attrFilter` query parameter. Its value can be set to `Quota` to get the quota information for the folder. This is **effectively required** as the server will time out with HTTP 504 if the query parameter is not set.

The response body has the following structure:

```jsonc
{
    "folder": {
        "parentFolder": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/folders/<FOLDER ID>",
        "attributes": {
            "attribute": [
                // [All folders] Storage used in KiB (rounded down to nearest
                // integer).
                {
                    "name": "OccupiedStorage",
                    "value": [
                        "0"
                    ]
                },
                // [All folders] Storage limit in KiB.
                {
                    "name": "TotalStorage",
                    "value": [
                        "167770"
                    ]
                },
                // [Voicemails folder] Number of fax messages.
                {
                    "name": "FaxOccupiedMessages",
                    "value": [
                        "0"
                    ]
                },
                // [Voicemails folder] Maximum number of fax messages.
                {
                    "name": "FaxMessagesQuota",
                    "value": [
                        "45"
                    ]
                },
                // [Voicemails folder] Number of voicemails.
                {
                    "name": "VMOccupiedMessages",
                    "value": [
                        "0"
                    ]
                },
                // [Voicemails folder] Maximum number of voicemails.
                {
                    "name": "VMMessagesQuota",
                    "value": [
                        "45"
                    ]
                },
                // [Greetings folder] Number of greetings of any type.
                {
                    "name": "GreetingsOccupied",
                    "value": [
                        "0"
                    ]
                },
                // [Greetings folder] Maximum number of greetings of any type.
                {
                    "name": "GreetingsQuota",
                    "value": [
                        "9"
                    ]
                },
                // [Greetings folder] Number of normal greetings.
                {
                    "name": "NormalGreetingOccupied",
                    "value": [
                        "0"
                    ]
                },
                // [Greetings folder] Maximum number of normal greetings.
                {
                    "name": "NormalGreetingQuota",
                    "value": [
                        "9"
                    ]
                },
                // [Greetings folder] Number of voice signatures.
                {
                    "name": "VoiceSignatureOccupied",
                    "value": [
                        "0"
                    ]
                },
                // [Greetings folder] Maximum number of voice signatures.
                {
                    "name": "VoiceSignatureQuota",
                    "value": [
                        "9"
                    ]
                }
            ],
            "resourceURL": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/folders/<FOLDER ID>"
        }
    }
}
```

### `/objects/<OBJECT PATH>` [GET]

This endpoint is for querying metadata about a specific object in a folder. The response body has the following structure:

```jsonc
{
    // The value of this `object` key is also what's returned for each item in
    // the response of the /objects/operations/search endpoint.
    "object": {
        "parentFolder": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/folders/<FOLDER ID>",
        "attributes": {
            "attribute": [
                // Audio duration in seconds.
                {
                    "name": "content-duration",
                    "value": [
                        "5"
                    ]
                },
                // Content-Transfer-Encoding header value when downloading this
                // object.
                {
                    "name": "Content-Transfer-Encoding",
                    "value": [
                        "base64"
                    ]
                },
                // MIME type of the HTTP response when downloading this object
                // (Not MIME type of the file).
                {
                    "name": "content-type",
                    "value": [
                        "multipart/mixed; boundary=\"====outer123==\""
                    ]
                },
                // Creation timestamp of this object.
                {
                    "name": "date",
                    "value": [
                        "2024-01-01T00:00:00Z"
                    ]
                },
                // Call direction. (Always `In` for greetings.)
                {
                    "name": "Direction",
                    "value": [
                        "In"
                    ]
                },
                // [Voicemails only] Expiration timestamp of this object.
                {
                    "name": "expires",
                    "value": [
                        "2024-04-01T00:00:00Z"
                    ]
                },
                // Source phone number. This may contain a domain suffix, like
                // "@tmo.com". For greetings, the number matches the MSISDN.
                {
                    "name": "from",
                    "value": [
                        "11234567890@tmo.com"
                    ]
                },
                // [Voicemails only] Message importance classification.
                {
                    "name": "importance",
                    "value": [
                        "normal"
                    ]
                },
                // Unknown: Some internal file type classifier? "x-voice-grtng"
                // or "voice-message".
                {
                    "name": "message-context",
                    "value": [
                        "voice-message"
                    ]
                },
                // Unique ID for this object. This is a timestamp for both
                // voicemails and greetings, although they are formatted very
                // differently.
                {
                    "name": "Message-Id",
                    "value": [
                        "2024-01-01T00:00:00Z-conn:0123456789abcdef"
                    ]
                },
                // Version of MIME specification.
                {
                    "name": "mime-version",
                    "value": [
                        "1.0"
                    ]
                },
                // [Voicemails only] Return phone number. This may contain a
                // domain suffix, like "@tmo.com".
                {
                    "name": "return-number",
                    "value": [
                        "11234567890@tmo.com"
                    ]
                },
                // [Voicemails only] Unknown: Some sort of sensitivity
                // classification.
                {
                    "name": "sensitivity",
                    "value": [
                        "personal"
                    ]
                },
                // [Voicemails only] Unknown.
                {
                    "name": "sourcenode",
                    "value": [
                        "VMAS"
                    ]
                },
                // [Greetings only] Message subject. This is an arbitrary string
                // that can be set during upload.
                {
                    "name": "subject",
                    "value": [
                        "custom greeting"
                    ]
                },
                // Target phone number. Unlike other number fields, this does
                // not contain a domain suffix. For greetings, the number
                // matches the MSISDN.
                {
                    "name": "to",
                    "value": [
                        "11234567890"
                    ]
                },
                // [Greetings only] Type of greeting. See GSMA TS.46 v2.0
                // section 2.6.3.
                {
                    "name": "x-cns-greeting-type",
                    "value": [
                        "normal-greeting"
                    ]
                }
            ]
        },
        "flags": {
            // List of flags.
            "flag": [
                "\\Seen"
            ],
            "resourceURL": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>/flags"
        },
        "resourceURL": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>",
        "path": "/VV-Mail/Inbox/<OBJECT PATH>",
        "payloadURL": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/objects/<PAYLOAD PATH>",
        "payloadPart": [
            {
                // MIME type of audio file.
                "contentType": "audio/amr",
                // Byte size of audio file.
                "size": "1234",
                // Content-Transfer-Encoding header value when downloading.
                "contentEncoding": "base64",
                // Content-Disposition header value when downloading.
                "contentDisposition": "attachment; filename=\"voicemail-20240101000000.amr\"",
                // Proxied download URL (requires rewriting) for audio file.
                "href": "https://wsg.t-mobile.com:443/phone20/mStoreRelay?path=http://wsg.mstore.msg.eng.t-mobile.com:8082/oemclient/nms/v1/ums/<MSISDN URI>/objects/<PAYLOAD PATH>/payloadParts/part1"
            }
        ],
        // Same as the `Message-Id` attribute.
        "correlationId": "2024-01-01T00:00:00Z-conn:0123456789abcdef"
    }
}
```

For the flags field, there are several known flags:

* `\Seen`: Whether an object is marked as read. For voicemails, the client should set this in response to the user opening/playing the voicemail. For greetings, this should be (but is not required to be) set during uploads.
* `\Recent`: Whether an object was recently created. This has the opposite meaning of `\Seen` and is automatically added and removed from the flags list when the presence of `\Seen` changes. This cannot be set or unset via the API.
* `$MarkNotToDelete`: Whether a voicemail object is kept forever (not allowed to expire). This cannot be set or unset via the API.
* `$CNS-Greeting-On`: Whether a greeting object is currently active. Multiple greetings can have this flag set at the same time, but it appears that the most recently uploaded object has priority.

Setting or clearing these flags appear to take different code paths in the backend depending on the flag. The `$CNS-Greeting-On` flag can only be changed with the [object flag endpoint](#objectsobject-pathflagsflag-putdelete), while `\Seen` can only be changed with the [bulk update endpoint](#objectsoperationsbulkupdate-post).

### `/objects/<OBJECT PATH>/flags/<FLAG>` [PUT,DELETE]

This endpoint is for setting or clearing the `$CNS-Greeting-On` flag. Setting other known flags will fail. This endpoint is idempotent and does not return a body.

### `/objects/operations/search` [POST]

This endpoint is for listing and searching the contents of a folder. This endpoint is paginated and may require calling multiple times to retrieve all results.

The request body must contain the search parameters, filtering criteria, and sorting criteria. The list of supported fields and operators is currently not known. The following example will query for all objects sorted by creation date in descending order.

```jsonc
{
    "selectionCriteria": {
        // Maximum number of entries to return in one page of results.
        "maxEntries": 10,
        // When there are too many objects, the response for the previous page
        // will include a `cursor` key. This should be set to that value. When
        // querying for the first page of results, omit this key entirely.
        "fromCursor": "1704085200000:D",
        "searchScope": {
            "resourceURL": "https://wsg.t-mobile.com/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>/folders/<FOLDER ID>"
        },
        "searchCriteria": {
            "operator": "Not",
            "criterion": [
                {
                    "type": "PurgedObject",
                    "value": ""
                }
            ]
        },
        "sortCriteria": {
            "criterion": [
                {
                    "type": "Date",
                    "order": "Descending"
                }
            ]
        }
    }
}
```

The response will contain a single page of results:

```jsonc
{
    "objectList": {
        // List of objects with the exact same structure as what is returned in
        // the `object` key of a /objects/<OBJECT PATH> request.
        "object": [],
        // Cursor value to use for obtaining the next page of results. If there
        // are no more results, this key will not be present.
        "cursor": "1704085200000:D"
    }
}
```

### `/objects/operations/bulkUpdate` [POST]

This endpoint is for bulk updating fields for a set of objects. The specified operation is always applied to all objects. There is no fail-fast/short-circuiting behavior.

The request body must list the objects to modify and specify the operation to perform. The only known operations are `AddFlag` and `RemoveFlag`, which can be used to set or clear the `\Seen` flag on objects.

```jsonc
{
    "bulkUpdate": {
        "objects": {
            // List of object URLs that the action should be applied to.
            "objectReference": [
                {
                    "resourceURL": "https://wsg.t-mobile.com/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>"
                }
            ]
        },
        // The operation to apply to all of the objects.
        "operation": "RemoveFlag",
        // Parameters for the operation.
        "flags": {
            "flag": [
                "\\Seen"
            ]
        }
    }
}
```

The server will generally reply with the following structure. The order of the results is arbitrary and may not match the order specified in the request. If the same object was specified multiple times in the request, there will only be one corresponding result in the response.

```jsonc
{
    "bulkResponseList": {
        "response": [
            // Example of a successful change to an object.
            {
                "code": "200",
                "reason": "OK",
                "success": {
                    "resourceURL": "https://wsg.t-mobile.com/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>"
                }
            },
            // Example of a failed change to an object.
            {
                "code": "400",
                "reason": "Internal server error",
                "failure": {
                    "serviceException": {
                        "messageId": "SVC3000",
                        "text": "Internal Server Error for %1 Address",
                        "variables": [
                            "https://wsg.t-mobile.com/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>"
                        ]
                    }
                }
            }
        ]
    }
}
```

In certain error scenarios, the server may return HTTP 5xx with a JSON body containing the key `NWK_RSP`. The value of this key is stringified JSON with the structure above. The client should unpack the data from this response and ignore the HTTP 5xx status.

Finally, if one of the object paths in the request is semantically invalid (eg. the literal string `test`), the server may sometimes respond with:

```json
{
    "error": "MSTORE_RLY-MST_NWKERR"
}
```

When this happens, valid objects still have the specified operation applied, but it's not possible to get the results.

### `/objects/operations/bulkDelete` [DELETE]

This endpoint is for bulk deleting a set of objects. The deletion is always attempted for each object. There is no fail-fast/short-circuiting behavior.

The request body must specify the list of objects to delete.

```jsonc
{
    "bulkDelete": {
        "objects": {
            // List of object URLs to delete.
            "objectReference": [
                {
                    "resourceURL": "https://wsg.t-mobile.com/phone20/mStoreRelay/oemclient/nms/v1/ums/<MSISDN URI>/objects/<OBJECT PATH>"
                }
            ]
        }
    }
}
```

The possible responses are identical to what the [bulk update endpoint](#objectsoperationsbulkupdate-post) returns.

### `/objects/<PAYLOAD PATH>` [GET]

This endpoint is for downloading an object's audio file. The payload path should be parsed from the `object`.`payloadPart`.`href` URL returned by the [object endpoint](#objectsobject-path-get).

### `/objects` [POST]

This endpoint is for uploading a new object, most commonly a custom greeting. The request data is a multipart message containing two documents.

The first document is a JSON structure describing the object. The structure is identical to what is documented for the [object endpoint](#objectsobject-path-get). The following fields should be included:

* [Required] `object`.`parentFolderPath`
  * `/VV-Mail/Inbox` for voicemails
  * `/VV-Mail/Greetings` for custom greetings
* [Recommended] `content-duration` attribute
* [Recommended] `date` attribute
* [Recommended] `Message-Id` attribute
* [Recommended] `mime-version` attribute
* [Recommended] `subject` attribute
* [Recommended] `x-cns-greeting-type` attribute
* [Recommended] `\Seen` flag

This document should have the following headers set:

```
Content-Disposition: form-data; name="root-fields"
Content-Type: application/json; charset=utf-8
Content-Length: <length of JSON data>
```

The second document is a nested multipart message. The inner message must use a different boundary string to ensure that the server's parser won't confuse the inner and outer messages.

The inner message contains a single document, which is the base64 encoded (padded and line wrapped) form of the AMR audio file. The inner document should have the following headers:

```
Content-Disposition: attachment;filename="audio.amr"
Content-Transfer-Encoding: base64
Content-Type: audio/amr
Content-Length: <length of base64-encoded data>
```

The outer document should have the following headers:

```
Content-Disposition: form-data;name="attachments"
Content-Type: multipart/mixed; boundary=<inner boundary string>
Content-Length: <length of inner message>
```

The HTTP request should have the following headers:

```
Content-Type: multipart/form-data; boundary=<outer boundary string>
Content-Length: <length of entire body>
```
