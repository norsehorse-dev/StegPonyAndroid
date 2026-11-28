# StegPony Android 1.3.1 and format version 2

Status: plan only. Nothing here is built, and the two findings below come from
reading FORMAT.md and the core's code, not from running an attack. Step 0
measures them before anything is changed.

A tester asked two questions: what does someone learn who has only the public
key a message was hidden for, and what does someone learn who has the decoy
passphrase. Answering them from the format turned up two weak points.
SECURITY.md counts both as security bugs.

## Finding 1: the public key is enough to tell that something is hidden

In public-key mode the locate key is derived from the recipient's public key
alone (FORMAT.md 3.2), so anyone holding that key can compute the slot's
positions and read its head and body trits. The 1.0 plan knew this and called
it targeted steganalysis. It was to be written into Help and was not.

It is worse than targeted steganalysis. Bytes are packed 11 bits to 7 trits
(FORMAT.md 4.3). Seven trits have 2187 values and only 2048 are ever written,
so a written slot never has a group of 2048 or more. Trits read from an
untouched image land there about 6.4% of the time (139 of 2187). The last
group's padding bits are always zero as well.

Someone with the public key reads the head (27 groups) and then tries each of
the 37 bucket sizes for the body. For the right bucket every group is below
2048. A body of 64 bytes is 47 groups, which an untouched image passes about
4.5% of the time; 96 bytes, 1%; anything larger, effectively never. That is
close to a yes or no test for "something is hidden here for this key", and it
gives the size class too. It does not reveal the contents or which elements
were changed.

Passphrase mode is not affected: the positions need the passphrase. A filler
slot is not affected: its locate key is random and thrown away.

## Finding 2: a short decoy shows that a second message exists

Both slots use one bucket, the larger of the payload's and the decoy's
(FORMAT.md section 2). A writer otherwise picks the smallest bucket that fits.
Someone who opens the decoy sees its real length inside a body padded to the
bucket. If the decoy would have fit a smaller bucket, something else needed
the larger one, so a second, larger payload exists. They learn its size class.
They learn nothing of its contents or positions.

The app only says "make the decoy believable". It does not warn about length,
and the decoy can only be text, so a decoy can never match a large file.

## Step 0: measure (done)

A scratch test in StegPonyCore read both slots of a cover through the positions
a public key gives, over one photo and three vector covers, 40 random keys per
cover (80 slot reads per cover), then again over 13 covers with something
hidden for a known key (10 bytes to 30 KB).

Untouched covers:
- head groups of 2048 or more: 5.4% to 6.7% (the reasoning said 6.4%);
- a head with none at all: 10% to 20% of slot reads, so the head alone is a
  weak test;
- a clean head and a clean body at some bucket size: 0% to 2.5% of slot reads,
  all at the smallest buckets.

With the recipient's key, in all 13 hidden cases the message's slot had no
group of 2048 or more in the head, and exactly one bucket size passed, the true
one. The other slot and other keys looked like an untouched cover.

So finding 1 holds as written, and it gives more than presence: the public key
shows that something is hidden for it, in which slot, and in which size class.
Finding 2 needs no measurement; it follows from both slots using the larger
bucket.

## 1.3.1: what can be done without a new format

1. Decoy size guard (app only; shipped in 1.3.1).
   - The bucket sizes are fixed for format version 1, so the app carries them
     (`format/Buckets.kt`, with tests against FORMAT.md) and 1.3.1 needs no core
     release. If the core ever exposes the bucket of a length, the app should
     use that.
   - On the key step, when a decoy is on and its bucket is smaller than the
     message's, say so and say how long the decoy needs to be. Recommended:
     block Next for a text message, where a longer decoy is easy to write, and
     warn with a way to go on for a file, where it may not be.
   - Let the decoy be a file as well as text, so a large payload can have a
     decoy of its own size.
2. Help says what is true, in all seven languages (shipped in 1.3.1):
   - someone who has the recipient's public key can find where a public-key
     message sits and can tell that one is there, though not read it; use a
     passphrase when that matters;
   - the decoy must be at least as long as the real message.
3. Soften the packing tell on the writing side (core, readers unchanged).
   A reader keeps the low 11 bits of a group, so a value below 139 can be
   written as itself or as itself plus 2048, picked at random, and the padding
   bits of the last group can be random. Every released reader opens the
   result. This does not remove the test: groups of 2048 or more would then
   appear about 3.4% of the time against 6.4% in an untouched image. It takes
   the certainty out of short messages and does little for long ones. Worth
   doing only if step 0 shows it helps the message sizes people use. Step 0
   says it does not help enough: the body test found the true bucket in every
   case, and a lower rate of high groups would still stand out over a body of
   any real length. Not planned for 1.3.1.

Items 1 and 2 need no format change. Item 3 changes what a writer emits, so it
gets new test vectors and a core release.

## Format version 2: the fix for finding 1

Goal: someone who knows the positions reads trits that an untouched image
could have produced.

- Body: add a trit keystream to the packed body, modulo 3, derived from the
  body key. The body key comes from the shared secret (or the passphrase), so
  the public key holder cannot take the keystream off, and what they read is
  uniform whatever the packing wastes.
- Head: the recipient needs the head before the shared secret exists, so it
  cannot be masked with a secret. Pack its 36 bytes (20 for a passphrase) as
  one integer in base 3: 288 bits fit 182 trits with under half a bit to
  spare, so an untouched image passes the "is this a valid head" test about
  three times in four and the test says almost nothing.
- Readers try version 2 first, then version 1. Apps before the version 2
  release cannot read version 2.

Open questions for version 2:

1. Whether the app keeps a "write the old format" switch for recipients on
   older versions, and for how long.
2. Whether to also offer the 1.0 plan's optional shared passphrase mixed into
   the public-key locate key, which hides the positions themselves at the cost
   of needing a shared secret.
3. Which release carries it. It is a core change with new vectors and the
   independent checker, so it is not a patch release.

## Order

1. Step 0.
2. 1.3.1: decoy guard, decoy as a file, Help.
3. Item 3 only if step 0 says it is worth a core release on its own; otherwise
   it is folded into version 2.
4. Format version 2, as its own release.
