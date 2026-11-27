# StegPony Android 1.2.0

Status: in progress. Starts from 1.1.0 (versionCode 8) plus the new icon commit.
Ships as versionCode 9.

## What is in it

1. New icon (done): the picture frame with the Pony horse as a dashed outline
   behind the peaks. Launcher foreground, themed icon, app bar mark, listing icon.
2. Reveal through PGPony: open an image or text hidden for a Curve25519 key
   that lives in PGPony Android, on the phone or on a security key, without the
   key ever being imported into StegPony.

Nothing else. No format change, no core change.

## Reveal through PGPony

### What PGPony gives us

PGPony 4.8.2 answers three actions of its own on its OpenPGP provider binding
(`org.openintents.openpgp.IOpenPgpService2` in `com.pgpony.android`). The
contract is PGPony's `docs/PROVIDER_PGPONY_ACTIONS.md`, API level 1.

- `GET_CAPABILITIES`: which actions this PGPony has, and the most points one
  agreement call takes (32).
- `X25519_KEYS`: the user's usable Curve25519 encryption subkeys, with the
  32-byte public point, a label, and whether the subkey is on a security key.
- `X25519_AGREE`: X25519 of one subkey's secret with 1 to 32 points. Every
  call is confirmed by the user in PGPony (passphrase, or card PIN and a tap).

### Why a reveal needs a batch

The core asks an external key for one agreement per slot it tries, through the
synchronous `KeyDevice.agree(point)`. Each `X25519_AGREE` call is one
confirmation in PGPony, so answering the core one point at a time would ask
the user twice per reveal, and would need a blocking call out to another app
from inside the core.

The points do not depend on the secret. For a public-key slot the core finds
the slot from the recipient alone, reads the head, and maps its first 32
bytes to a point. So the points can be collected first, with no key at all.

### Shape: two passes, no core change

1. Collect. Run the core's reveal with a recording `KeyDevice` for the chosen
   key's recipient. Its `agree` notes the point and answers with 32 random
   bytes. The core treats that as a wrong key (the documented "nothing found"
   answer) and goes on to the next slot, so both slots' points are collected.
   The pass itself finds nothing.
2. Agree. Send the collected points to PGPony in one `X25519_AGREE` call,
   in chunks of the advertised maximum if there were ever more.
3. Open. Run the reveal again with a `KeyDevice` that answers each point from
   the batch. A point that is not in the batch fails the reveal.

This uses StegPonyCore 1.0.0 as released. The cost is reading the image
twice; the second pass does no key work that the first did not.

A cleaner core shape exists and is noted for later, not for 1.2.0: a
`reveal_points(image, recipient)` function that returns the points, and a
reveal that takes the agreed secrets as data. It would save the second read
and remove the random-answer step. It needs a StegPonyCore release and a new
reproducible build for no change the user can see, so it waits until the core
changes for another reason.

### In the app

- Reveal gets a third action under Reveal and Use a hardware key:
  "Use a key in PGPony". It is shown only when PGPony is installed.
- Tapping it asks PGPony for its keys and shows them in a list: the key's
  label, the subkey fingerprint, and "On a security key" or "On this phone".
  The user picks one. Nothing is remembered between reveals.
- PGPony then shows its own confirmation. StegPony shows that it is waiting.
- The result lands on the same found page as every other reveal.
- A typed passphrase is not used on this path, as on the hardware-key path.

StegPony's own card layer stays as it is. PGPony is a second way to reach a
key, for people whose key already lives there.

### What can go wrong, and what the user is told

| Case | Shown |
|---|---|
| PGPony not installed | The action is not offered. Help says what it needs. |
| PGPony 4.8.1 or older | Update PGPony to 4.8.2 or newer. |
| First use: PGPony's Connected apps consent | PGPony's own screen; on yes the step is repeated, on no a plain message. |
| No usable Curve25519 keys in PGPony | Says so, and that RSA keys can't be used. |
| Confirmation, passphrase or PIN dialog closed | Cancelled in PGPony. Nothing was opened. |
| `unknown_key`, `unsupported_key`, `key_unavailable` | The key can't be used now; check it in PGPony. |
| `agreement_failed` | PGPony or the security key could not finish. |
| An error with no reason (PGPony turns the app away, as when StegPony's signature is not the one it allowed) | Remove StegPony under PGPony's Connected apps and try again. |
| `low_order_point`, `invalid_request`, `too_many_points` | Should not happen; a plain "PGPony refused the request" with the reason. |
| PGPony stopped answering, or a call takes over 15 seconds | PGPony didn't answer. |
| Nothing opens | The usual Nothing found. |

### Privacy

PGPony learns that StegPony asked for agreements with a key, how many, and
the points. It does not get the image, the text or what was found. StegPony
still has no network permission. Shared secrets are wiped after the reveal.

### Build changes

- `aidl = true` and the one-interface AIDL file for the binding.
- `OpenPgpError` from the OpenPGP API (Apache-2.0), vendored: PGPony's error
  replies carry it, and without the class an error reply can't be read on
  Android 12 and older. A keep rule holds its name in release builds.
- A `<queries>` entry for the provider intent, so Android 11 and later let
  StegPony see PGPony.

## Tests

Unit tests (JVM): recording and batch devices, chunking, key list parsing,
and telling an old PGPony from a refusal (in any language PGPony runs in).

On a device, with PGPony 4.8.2 installed:

1. A key on the phone in PGPony: hide for it in StegPony (contact added from
   PGPony's public key), reveal with "Use a key in PGPony". One confirmation.
2. The same with a key on a security key (Token2, NFC and USB).
3. Text reveal, both text modes.
4. An image hidden for a different key: Nothing found, after one confirmation.
5. Cancel PGPony's confirmation; close the passphrase dialog; close the PIN
   dialog.
6. First use on a fresh install: PGPony's Connected apps consent, yes and no.
7. PGPony 4.8.1 installed, once in English and once in another language:
   the update message.
8. PGPony not installed: the action is absent.
9. A release (minified) build of StegPony, steps 1 and 5: the error and
   success replies must read correctly with R8 on.
10. The new icon in the launcher, as a themed icon, and in the app bar.

## Later

- The `reveal_points` core function described above.
- Add a PGPony key as a contact in one step from the key list.
