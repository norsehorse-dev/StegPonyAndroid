# StegPony Android 1.3.0

Status: written, not yet built or run on a device. Starts from 1.2.0
(versionCode 9). Ships as versionCode 10.

## What is in it

One feature, asked for by a tester: when hiding for a public key, pick the
recipient from PGPony's keys, without first sharing the key into StegPony as a
contact. It is the hide-side twin of 1.2.0's reveal through PGPony.

No format change, no core change, and nothing new is needed from PGPony.

## How PGPony gives us a key

Two standard OpenPGP API actions, on the binding 1.2.0 already uses:

1. `GET_KEY_IDS` with no user ids. From PGPony 4.8.1 this answers
   `USER_INTERACTION_REQUIRED` with PGPony's own public key picker. On OK the
   picker returns the request plus the picked ids; calling again with that
   intent answers `RESULT_KEY_IDS` (a `long[]`).
2. `GET_KEY` with `EXTRA_KEY_ID`. PGPony writes the public key to an output
   pipe (`createOutputPipe`, then `execute` with the same pipe id). StegPony
   reads it and hands the bytes to the core's existing OpenPGP import, which
   finds the Curve25519 encryption subkey and gives the recipient.

Public keys only. No confirmation beyond PGPony's Connected apps consent and
the picker itself.

An older PGPony (before 4.8.1) answers `GET_KEY_IDS` with no user ids at once,
with an empty list and no picker. An empty answer that no picker produced is
read as "PGPony too old".

## In the app

- Hide, step 3, "A public key": under the saved keys and "Paste a key" there
  is "Pick from PGPony", shown only when PGPony is installed.
- PGPony's picker allows several keys. StegPony hides for one key, so more
  than one picked key is answered with a message asking for a single key.
- The picked key joins the list as "Name (from PGPony)" and is selected. It is
  used for this hide and is not saved; starting over forgets it. "Save as a contact" under it adds it to
  the keyring, name and fingerprint included.
- A key that is already a contact or one of your own keys just selects that
  row.
- Review names the recipient by the key's name, as for a contact.

## What can go wrong, and what the user is told

| Case | Shown |
|---|---|
| PGPony not installed | The action is not offered. |
| PGPony older than 4.8.1 | Update PGPony. |
| First use: Connected apps consent | PGPony's screen; on yes the step repeats. |
| Picker closed, or consent refused | Nothing: the user changed their mind. |
| More than one key ticked | StegPony hides for one key at a time. |
| The key has no Curve25519 encryption subkey (RSA, NIST, post-quantum only) | StegPony can't hide for that key, and why. |
| PGPony can't hand the picked key over | PGPony couldn't hand over that key; check it in PGPony. PGPony's own text is written for a mail app's user and is not shown. |
| PGPony turns StegPony away (signature not the one it allowed) | Remove StegPony under PGPony's Connected apps and try again. |
| PGPony stopped answering | PGPony didn't answer. |

## Tests

Unit tests (JVM): what an answer to the pick means (too old, none, several,
one).

On a device, with PGPony 4.8.2:

1. Pick a contact's Curve25519 key in PGPony, hide, reveal on the other side
   (or with that key's secret through "Use a key in PGPony").
2. Pick one of your own keys.
3. Pick an RSA key: the message, and the previous choice is kept. Pick a
   post-quantum key that also has an X25519 subkey and note what happens.
4. Tick two keys; close the picker; press back or choose "A passphrase"
   while PGPony is still being asked.
5. "Save as a contact", then check Keys and that the row no longer says
   "from PGPony".
6. Pick a key that is already a contact.
7. Text carrier as well as image.
8. A release (minified) build, step 1.

## Later

- The `reveal_points` core function noted in the 1.2.0 plan.
