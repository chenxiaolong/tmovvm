# tmovvm

tmovvm is an unofficial Android library and command line utility for interacting with the T-Mobile visual voicemail (VVM) service via the mstore API. It is meant to be easily integrated into other tools and thus, has no dependencies besides Android's system APIs.

T-Mobile formerly used the CVVM protocol, which is a variant of OMTP (a standard VVM specification based on SMS and IMAP). However, new VVM activations via CVVM are no longer permitted. It has been replaced by two HTTP-based protocols. The first is the mstore API, which is what third party apps, like the dialer shipped with Google and OnePlus devices, use. The other is the "cpaas" API, which is used by T-Mobile's first party Visual Voicemail app.

Unlike CVVM where after activation, the credentials to access voicemails are effectively valid forever, the mstore API uses 3GPP-GBA authentication, which requires communication with the SIM card for every API call.

NOTE: This tool requires access to Android's system APIs. Thus, it must be built within the AOSP tree. Building AOSP requires a significant amount of disk space (and patience)!

## Features

* Activate and deactivate visual voicemail
    * (Deactivation is currently not permitted by the server)
* Show voicemail and custom greeting quotas
* List, download, and delete voicemails
* List, download, upload, and delete custom greetings
* Change voicemail and greeting flags (whether a voicemail is marked as read, whether a greeting is active, etc.)
* Change voicemail PIN

## Building from source

1. [Download the AOSP source code](https://source.android.com/docs/setup/download). Note that this requires 100+ GiB of disk space! Custom AOSP-based Android OS's are also fine.

2. Clone this repo inside the AOSP source tree. It can be placed anywhere, including at the top level of the AOSP source tree.

3. Build tmovvm.

    ```bash
    source build/envsetup.sh
    m tmovvm
    ```

4. Push the tmovvm APK and wrapper script to the device:

    ```bash
    adb push \
        "${ANDROID_BUILD_TOP}"/out/target/product/husky/system/app/tmovvm/tmovvm.apk \
        tmovvm \
        /data/local/tmp
    ```

5. From an ADB shell, tmovvm commands can be run by executing the wrapper script at `/data/local/tmp/tmovvm`.

## Usage

* `tmovvm profile show`
  * Show the VVM profile information. Shows information like whether the VVM is enabled and whether a client (eg. a dialer app) should show the new user tutorial screen.
* `tmovvm profile activate`
  * Activate VVM.
* `tmovvm profile deactivate`
  * Deactivate VVM. This is currently not allowed by the server, but leaving VVM enabled has no detrimental impact. Calling the normal voicemail number will continue to work regardless.
* `tmovvm profile change-pin <old PIN> <new PIN>`
  * Change the PIN used when [accessing voicemails via T-Mobile's `1-805-637-7249` number](https://www.t-mobile.com/support/plans-features/voicemail). Resetting a lost PIN is not supported by the mstore API and must be done by dialing `#793#`, which resets the PIN to the last 4 digits of the phone number.
* `tmovvm voicemail show`
  * Show the quotas for the voicemails folder.
* `tmovvm voicemail list`
  * List all voicemails.
* `tmovvm voicemail download <ID> [<FILE>]`
  * Download a voicemail. If no output filename is specified, the server-provided filename as shown in the `list` subcommand is used. If the server does not provide a filename or if it is unsafe, then `voicemail.amr` is used.
* `tmovvm voicemail delete [<ID>...]` [bulk]
  * Delete voicemails.
* `tmovvm voicemail mark-read [<ID>...]` [bulk]
  * Mark voicemails as read.
* `tmovvm voicemail mark-unread [<ID>...]` [bulk]
  * Mark voicemails as unread.
* `tmovvm greeting show`
  * Show the quotas for the custom greetings folder.
* `tmovvm greeting list`
  * List all custom greetings.
* `tmovvm greeting upload <FILE>`
  * Upload a custom greeting. It will not be active until `mark-active` is run against it. Note that the server allows multiple greetings to be active. To avoid confusion, run `mark-inactive` against other existing custom greetings.
* `tmovvm greeting download <ID> [<FILE>]`
  * Download a custom greeting. If no output filename is specified, the server-provided filename as shown in the `list` subcommand is used. If the server does not provide a filename or if it is unsafe, then `greeting.amr` is used.
* `tmovvm greeting mark-active [<ID>...]`
  * Mark a custom greeting as active.
* `tmovvm greeting mark-inactive [<ID>...]`
  * Mark a custom greeting as inactive.

Commands marked as `[bulk]` above are implemented with an efficient bulk request API. For these actions, execution is much faster when specifying multiple items in a single command than running the command multiple times.

## Protocol

See [`PROTOCOL.md`](./PROTOCOL.md) for the protocol documentation.

## License

tmovvm is licensed under Apache 2.0. Please see [`LICENSE`](./LICENSE) for the full license text.
