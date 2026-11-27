package com.stegpony.android.pgpony

/**
 * PGPony Android's provider contract, as far as StegPony uses it: the OpenPGP
 * API's envelope (result code, error, PendingIntent) and PGPony's own X25519
 * actions. The names are PGPony's, from its docs/PROVIDER_PGPONY_ACTIONS.md
 * (API level 1, PGPony 4.8.2). They are a published contract and never change.
 */
object PgponyContract {
    const val PACKAGE = "com.pgpony.android"
    const val SERVICE_ACTION = "org.openintents.openpgp.IOpenPgpService2"

    // The OpenPGP API envelope.
    const val EXTRA_API_VERSION = "api_version"
    const val API_VERSION = 12
    const val RESULT_CODE = "result_code"
    const val RESULT_ERROR = "error"
    const val RESULT_INTENT = "intent"
    const val RESULT_CODE_ERROR = 0
    const val RESULT_CODE_SUCCESS = 1
    const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2

    /** OpenPgpError.errorId when the provider doesn't take our API version. */
    const val ERROR_INCOMPATIBLE_API_VERSIONS = 1

    // Standard OpenPGP API actions, for picking a recipient's public key.
    const val ACTION_GET_KEY_IDS = "org.openintents.openpgp.action.GET_KEY_IDS"
    const val ACTION_GET_KEY = "org.openintents.openpgp.action.GET_KEY"
    const val EXTRA_KEY_ID = "key_id"
    const val RESULT_KEY_IDS = "key_ids"

    /** The most a public key read from PGPony may be. Far above any real key. */
    const val MAX_KEY_BYTES = 1 * 1024 * 1024

    // PGPony's own actions.
    const val ACTION_GET_CAPABILITIES = "com.pgpony.android.action.GET_CAPABILITIES"
    const val ACTION_X25519_KEYS = "com.pgpony.android.action.X25519_KEYS"
    const val ACTION_X25519_AGREE = "com.pgpony.android.action.X25519_AGREE"

    const val EXTRA_CAPABILITIES = "com.pgpony.android.extra.CAPABILITIES"
    const val EXTRA_X25519_MAX_POINTS = "com.pgpony.android.extra.X25519_MAX_POINTS"
    const val EXTRA_X25519_SUBKEY_FINGERPRINTS = "com.pgpony.android.extra.X25519_SUBKEY_FINGERPRINTS"
    const val EXTRA_X25519_PRIMARY_FINGERPRINTS = "com.pgpony.android.extra.X25519_PRIMARY_FINGERPRINTS"
    const val EXTRA_X25519_PUBLIC_POINTS = "com.pgpony.android.extra.X25519_PUBLIC_POINTS"
    const val EXTRA_X25519_LABELS = "com.pgpony.android.extra.X25519_LABELS"
    const val EXTRA_X25519_ON_CARD = "com.pgpony.android.extra.X25519_ON_CARD"
    const val EXTRA_X25519_SUBKEY_FINGERPRINT = "com.pgpony.android.extra.X25519_SUBKEY_FINGERPRINT"
    const val EXTRA_X25519_POINTS = "com.pgpony.android.extra.X25519_POINTS"
    const val EXTRA_X25519_SECRETS = "com.pgpony.android.extra.X25519_SECRETS"
    const val EXTRA_ERROR_REASON = "com.pgpony.android.extra.ERROR_REASON"

    /** Bytes in a point and in a shared secret. */
    const val POINT_LEN = 32

    /** What one X25519_AGREE call takes when PGPony doesn't say. */
    const val DEFAULT_MAX_POINTS = 32

    // Error reasons, section 5 of the contract.
    const val REASON_INVALID_REQUEST = "invalid_request"
    const val REASON_TOO_MANY_POINTS = "too_many_points"
    const val REASON_LOW_ORDER_POINT = "low_order_point"
    const val REASON_UNKNOWN_KEY = "unknown_key"
    const val REASON_UNSUPPORTED_KEY = "unsupported_key"
    const val REASON_KEY_UNAVAILABLE = "key_unavailable"
    const val REASON_AGREEMENT_FAILED = "agreement_failed"
}
