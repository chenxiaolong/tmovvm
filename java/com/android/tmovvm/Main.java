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

package com.android.tmovvm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.os.TelephonyServiceManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;

import com.android.voicemail.impl.mstore.MStoreClient;
import com.android.voicemail.impl.mstore.MStoreObject;
import com.android.voicemail.impl.mstore.MStoreProfile;
import com.android.voicemail.impl.mstore.MStoreQuota;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("SameParameterValue")
public class Main {
    private static void showHelp(@NonNull PrintStream stream) {
        stream.println("Usage:");
        stream.println("   tmovvm profile show");
        stream.println("   tmovvm profile activate");
        stream.println("   tmovvm profile deactivate");
        stream.println("   tmovvm profile change-pin <old PIN> <new PIN>");
        stream.println("   tmovvm voicemail show");
        stream.println("   tmovvm voicemail list");
        stream.println("   tmovvm voicemail download <ID> [<FILE>]");
        stream.println("   tmovvm voicemail delete [<ID>...]");
        stream.println("   tmovvm voicemail mark-read [<ID>...]");
        stream.println("   tmovvm voicemail mark-unread [<ID>...]");
        stream.println("   tmovvm greeting show");
        stream.println("   tmovvm greeting list");
        stream.println("   tmovvm greeting upload <FILE>");
        stream.println("   tmovvm greeting download <ID> [<FILE>]");
        stream.println("   tmovvm greeting delete [<ID>...]");
        stream.println("   tmovvm greeting mark-active [<ID>...]");
        stream.println("   tmovvm greeting mark-inactive [<ID>...]");
    }

    static class ArgValidationException extends Exception {
        public ArgValidationException(String message) {
            super(message);
        }
    }

    private static void ensureArgsAtLeast(@NonNull String[] args, int n)
            throws ArgValidationException {
        if (args.length < n) {
            throw new ArgValidationException(
                    "Expected at least " + n + " arguments: " + Arrays.toString(args));
        }
    }

    private static void ensureArgsBetweenInclusive(@NonNull String[] args, int min, int max)
            throws ArgValidationException {
        if (args.length < min || args.length > max) {
            throw new ArgValidationException(
                    "Expected between " + min + " and " + max + "arguments: "
                            + Arrays.toString(args));
        }
    }

    private static void ensureArgsExactly(@NonNull String[] args, int n)
            throws ArgValidationException {
        if (args.length != n) {
            throw new ArgValidationException(
                    "Expected exactly " + n + " arguments: " + Arrays.toString(args));
        }
    }

    private static void showProfile(@NonNull MStoreProfile profile,
            @NonNull PrintStream stream) {
        stream.println("enabled=" + profile.enabled);
        stream.println("migration_timestamp=" + profile.migrationTimestamp);
        stream.println("migration_status=" + profile.migrationStatus);
        stream.println("new_user_tutorial=" + profile.newUserTutorial);
    }

    private static void showQuota(@NonNull MStoreQuota quota, @NonNull PrintStream stream) {
        if (quota.sizeKiBUsed != null) {
            stream.println("size_used=" + quota.sizeKiBUsed + "KiB");
        }
        if (quota.sizeKiBLimit != null) {
            stream.println("size_limit=" + quota.sizeKiBLimit + "KiB");
        }
        if (quota.faxMessagesCount != null) {
            stream.println("fax_messages_count=" + quota.faxMessagesCount);
        }
        if (quota.faxMessagesLimit != null) {
            stream.println("fax_messages_limit=" + quota.faxMessagesLimit);
        }
        if (quota.voicemailsCount != null) {
            stream.println("voicemails_count=" + quota.voicemailsCount);
        }
        if (quota.voicemailsLimit != null) {
            stream.println("voicemails_limit=" + quota.voicemailsLimit);
        }
        if (quota.greetingsCount != null) {
            stream.println("greetings_count=" + quota.greetingsCount);
        }
        if (quota.greetingsLimit != null) {
            stream.println("greetings_limit=" + quota.greetingsLimit);
        }
        if (quota.normalGreetingsCount != null) {
            stream.println("normal_greetings_count=" + quota.normalGreetingsCount);
        }
        if (quota.normalGreetingsLimit != null) {
            stream.println("normal_greetings_limit=" + quota.normalGreetingsLimit);
        }
        if (quota.voiceSignaturesCount != null) {
            stream.println("voice_signatures_count=" + quota.voiceSignaturesCount);
        }
        if (quota.voiceSignaturesLimit != null) {
            stream.println("voice_signatures_limit=" + quota.voiceSignaturesLimit);
        }
    }

    private static void showObjects(@NonNull List<MStoreObject> objects,
            @NonNull PrintStream stream) {
        for (var object : objects) {
            final var flags = object.flags
                    .stream()
                    .map(f -> switch (f) {
                        case MStoreClient.FLAG_RECENT -> "recent";
                        case MStoreClient.FLAG_SEEN -> "read";
                        case MStoreClient.FLAG_VOICEMAIL_KEEP -> "keep";
                        case MStoreClient.FLAG_GREETING_ACTIVE -> "active";
                        default -> f;
                    })
                    .collect(Collectors.joining(","));

            stream.println(object.objectPath);
            stream.println("  created=" + object.creationTimestamp);
            stream.println("  expires=" + object.expiryTimestamp);
            stream.println("  duration=" + object.duration + "s");
            stream.println("  from=" + object.fromNumber);
            stream.println("  filename=" + object.getFilename());
            stream.println("  mimetype=" + object.payloadContentType);
            stream.println("  size=" + object.payloadSize + "B");
            stream.println("  flags=" + flags);
        }
    }

    private static @NonNull String sanitizeFilename(@NonNull String filename) {
        if ("..".equals(filename)) {
            return "__";
        }

        return filename.replaceAll("/\\\\", "_");
    }

    private static @NonNull String getFilename(@Nullable String userFilename,
            @NonNull MStoreObject object, @NonNull String fallback) {
        if (userFilename != null) {
            return userFilename;
        }

        final var filename = object.getFilename();
        if (filename != null) {
            return sanitizeFilename(filename);
        }

        return fallback;
    }

    private static long getAmrDurationMs(@NonNull String path) throws IOException {
        final var HEADER = new byte[]{'#', '!', 'A', 'M', 'R', '\n'};

        // MediaMetadataRetriever fails to parse the duration of AMR files, so parse it ourselves.
        // There is no header field containing the duration. We have to parse each frame.
        try (final var stream = new BufferedInputStream(new FileInputStream(path))) {
            final var header = stream.readNBytes(6);
            if (!Arrays.equals(header, HEADER)) {
                throw new IOException("Not an AMR-NB file: " + path);
            }

            int frame;

            for (frame = 0; ; frame++) {
                final var frameHeader = stream.read();
                if (frameHeader == -1) {
                    break;
                }

                final var frameSize = switch ((frameHeader >> 3) & 0xf) {
                    case 0 -> 13;
                    case 1 -> 14;
                    case 2 -> 16;
                    case 3 -> 18;
                    case 4 -> 20;
                    case 5 -> 21;
                    case 6 -> 27;
                    case 7 -> 32;
                    default -> throw new IOException(
                            "Invalid codec mode at frame #" + frame + ": " + path);
                };

                // Frame size includes the header.
                final var skipSize = frameSize - 1;

                if (stream.skip(skipSize) != skipSize) {
                    throw new EOFException("Hit EOF when skipping frame #" + frame + ": " + path);
                }
            }

            return frame * 20L;
        }
    }

    private static void runCommand(@NonNull String[] args, @NonNull MStoreClient client)
            throws Exception {
        ensureArgsAtLeast(args, 2);

        switch (args[0]) {
            case "profile" -> {
                switch (args[1]) {
                    case "show" -> {
                        ensureArgsExactly(args, 2);
                        final var profile = client.getProfile();
                        showProfile(profile, System.out);
                    }
                    case "activate" -> {
                        ensureArgsExactly(args, 2);
                        final var profile = new MStoreProfile();
                        profile.enabled = true;
                        client.updateProfile(profile);
                    }
                    case "deactivate" -> {
                        ensureArgsExactly(args, 2);
                        final var profile = new MStoreProfile();
                        profile.enabled = false;
                        client.updateProfile(profile);
                    }
                    case "change-pin" -> {
                        ensureArgsExactly(args, 4);
                        final var profile = new MStoreProfile();
                        profile.oldPin = args[2];
                        profile.newPin = args[3];
                        client.updateProfile(profile);
                    }
                    default -> throw new Exception("Unknown profile command: " + args[1]);
                }
            }
            case "voicemail" -> {
                switch (args[1]) {
                    case "show" -> {
                        ensureArgsExactly(args, 2);
                        final var quota = client.getQuota(MStoreClient.FOLDER_VOICEMAILS);
                        showQuota(quota, System.out);
                    }
                    case "list" -> {
                        ensureArgsExactly(args, 2);
                        final var objects = client.getFolderObjects(MStoreClient.FOLDER_VOICEMAILS);
                        showObjects(objects, System.out);
                    }
                    case "download" -> {
                        ensureArgsBetweenInclusive(args, 3, 4);
                        final var object = client.getObject(args[2]);
                        try (final var input = client.downloadObject(object)) {
                            final var filename = getFilename(args.length == 4 ? args[3] : null,
                                    object, "voicemail.amr");

                            try (final var output = new FileOutputStream(filename)) {
                                input.transferTo(output);
                            }
                        }
                    }
                    case "delete" -> {
                        ensureArgsAtLeast(args, 3);
                        client.bulkDelete(Arrays.asList(args).subList(2, args.length));
                    }
                    case "mark-read" -> {
                        ensureArgsAtLeast(args, 3);
                        client.bulkSetFlag(Arrays.asList(args).subList(2, args.length),
                                Collections.singletonList(MStoreClient.FLAG_SEEN), true);
                    }
                    case "mark-unread" -> {
                        ensureArgsAtLeast(args, 3);
                        client.bulkSetFlag(Arrays.asList(args).subList(2, args.length),
                                Collections.singletonList(MStoreClient.FLAG_SEEN), false);
                    }
                    default -> throw new Exception("Unknown voicemail command: " + args[1]);
                }
            }
            case "greeting" -> {
                switch (args[1]) {
                    case "show" -> {
                        ensureArgsExactly(args, 2);
                        final var quota = client.getQuota(MStoreClient.FOLDER_GREETINGS);
                        showQuota(quota, System.out);
                    }
                    case "list" -> {
                        ensureArgsExactly(args, 2);
                        final var objects = client.getFolderObjects(MStoreClient.FOLDER_GREETINGS);
                        showObjects(objects, System.out);
                    }
                    case "upload" -> {
                        ensureArgsExactly(args, 3);

                        final var object = new MStoreObject();
                        object.creationTimestamp = Instant.now();
                        object.id = Long.toString(object.creationTimestamp.toEpochMilli());
                        object.greetingType = "normal-greeting";
                        object.subject = "custom greeting";
                        object.mimeVersion = "1.0";
                        object.duration = getAmrDurationMs(args[2]) / 1000;
                        object.flags.add(MStoreClient.FLAG_SEEN);
                        // We intentionally don't mark the greeting as active. The user should do
                        // that manually and mark existing active greetings as inactive.

                        try (final var input = new FileInputStream(args[2])) {
                            final var data = input.readAllBytes();
                            client.uploadObject(MStoreClient.FOLDER_GREETINGS, object, data);
                        }
                    }
                    case "download" -> {
                        ensureArgsBetweenInclusive(args, 3, 4);
                        final var object = client.getObject(args[2]);
                        try (final var input = client.downloadObject(object)) {
                            final var filename = getFilename(args.length == 4 ? args[3] : null,
                                    object, "greeting.amr");

                            try (final var output = new FileOutputStream(filename)) {
                                input.transferTo(output);
                            }
                        }
                    }
                    case "delete" -> {
                        ensureArgsAtLeast(args, 3);
                        client.bulkDelete(Arrays.asList(args).subList(2, args.length));
                    }
                    case "mark-active" -> {
                        ensureArgsExactly(args, 3);
                        client.setFlag(args[2], MStoreClient.FLAG_GREETING_ACTIVE, true);
                    }
                    case "mark-inactive" -> {
                        ensureArgsExactly(args, 3);
                        client.setFlag(args[2], MStoreClient.FLAG_GREETING_ACTIVE, false);
                    }
                    default -> throw new Exception("Unknown greeting command: " + args[1]);
                }
            }
            default -> throw new Exception("Unknown command: " + args[0]);
        }
    }

    private static void init(@NonNull String[] args) throws Exception {
        // A looper is required for creating a context. This is deprecated for application usage,
        // but not for system usage. ActivityThread also does this during initialization.
        //noinspection deprecation
        Looper.prepareMainLooper();

        // Create a minimal context. The only reason we have a context at all is because the
        // services we need registered with SystemServiceRegistry.registerContextAwareService().
        final Context context = ActivityThread.systemMain().getSystemContext();
        TelephonyFrameworkInitializer.setTelephonyServiceManager(new TelephonyServiceManager());

        final var telephonyManager = context.getSystemService(TelephonyManager.class);
        if (telephonyManager == null) {
            throw new IllegalStateException("Failed to get TelephonyManager service");
        }

        final var connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            throw new IllegalStateException("Failed to get ConnectivityManager service");
        }

        final var network = connectivityManager.getActiveNetwork();
        if (network == null) {
            throw new IllegalStateException("No active network connection");
        }

        final var client = new MStoreClient(telephonyManager, network);

        runCommand(args, client);
    }

    public static void main(String[] args) {
        try {
            init(args);

            // Explicitly exit because we have no way of killing the "OkHttp ConnectionPool" thread
            // created by Network.openConnection().
            System.exit(0);
        } catch (ArgValidationException e) {
            System.err.println(e.getMessage());
            showHelp(System.err);
            System.exit(2);
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.exit(1);
        }
    }
}
