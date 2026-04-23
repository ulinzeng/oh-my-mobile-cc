package io.ohmymobilecc.core.crypto

/**
 * iOS actual for [Ed25519] — **W1.5 stub**.
 *
 * Ships as a `NotImplementedError`-throwing stub so the iOS framework keeps
 * compiling during W1.5 while the iOS client is not yet wired. The real iOS
 * actual lands in W2.1 under OpenSpec change id `add-ios-ed25519-actual`
 * (to be created when the iOS Inbox shell is scheduled).
 *
 * Rationale: an explicit throw makes the gap visible in CI if any iOS path
 * accidentally reaches this code, as opposed to a silent `false` / empty
 * `ByteArray` which would be a security bug pattern.
 */
public actual object Ed25519 {
    private const val STUB_MESSAGE: String =
        "iOS Ed25519 actual ships in W2.1 under change id 'add-ios-ed25519-actual'"

    public actual fun keypair(seed: ByteArray): Ed25519KeyPair = throw NotImplementedError(STUB_MESSAGE)

    public actual fun sign(
        secretKey: ByteArray,
        message: ByteArray,
    ): ByteArray = throw NotImplementedError(STUB_MESSAGE)

    public actual fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = throw NotImplementedError(STUB_MESSAGE)
}
