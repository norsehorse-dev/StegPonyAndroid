# Keep rules for the core's JNA bindings come with the :core module
# (core/consumer-rules.pro).

# The OpenPGP API contract StegPony shares with PGPony (pgpony/PgponyClient.kt):
# the binder interface, and OpenPgpError, which PGPony's error replies carry by
# class name. R8 renaming either breaks reveal through PGPony in release
# builds only.
-keep class org.openintents.openpgp.** { *; }
-keep interface org.openintents.openpgp.** { *; }
