package cn.alone.hamitv.esp.noise

/**
 * ESPHome's Noise handshake: `Noise_NNpsk0_25519_ChaChaPoly_SHA256`, two
 * messages, no static keys.
 *
 * The prologue is not just a constant: ESPHome prefixes `NoiseAPIInit` and then
 * the first (empty) frame the client sent, length included. That binding is why
 * the server has to read that frame before the handshake can start.
 */
class NoiseHandshake(
    psk: ByteArray,
    prologue: ByteArray,
    private val role: Role,
    ephemeralPrivateKey: ByteArray? = null,
) {

    enum class Role { INITIATOR, RESPONDER }

    private val psk = psk.copyOf()
    private val symmetric = SymmetricState(PROTOCOL_NAME, prologue)
    private val ephemeral = ephemeralPrivateKey?.let { X25519.KeyPair(it, X25519.publicKey(it)) }
        ?: X25519.generateKeyPair()
    private var remoteEphemeral: ByteArray? = null

    /** Set once [split] runs. */
    var sendCipher: CipherState? = null
        private set

    var receiveCipher: CipherState? = null
        private set

    /** Initiator: `-> psk, e` plus an encrypted empty payload. */
    fun writeMessage1(): ByteArray {
        check(role == Role.INITIATOR)
        symmetric.mixKeyAndHash(psk)
        symmetric.mixHash(ephemeral.publicKey)
        return ephemeral.publicKey + symmetric.encryptAndHash(ByteArray(0))
    }

    fun readMessage1(message: ByteArray) {
        check(role == Role.RESPONDER)
        val publicKey = takePublicKey(message)
        symmetric.mixKeyAndHash(psk)
        symmetric.mixHash(publicKey)
        remoteEphemeral = publicKey
        symmetric.decryptAndHash(message.copyOfRange(32, message.size))
    }

    /** Responder: `<- e, ee`. */
    fun writeMessage2(): ByteArray {
        check(role == Role.RESPONDER)
        val remote = requireNotNull(remoteEphemeral) { "message 1 was not read yet" }
        symmetric.mixHash(ephemeral.publicKey)
        symmetric.mixKey(X25519.scalarMult(ephemeral.privateKey, remote))
        return ephemeral.publicKey + symmetric.encryptAndHash(ByteArray(0))
    }

    fun readMessage2(message: ByteArray) {
        check(role == Role.INITIATOR)
        val publicKey = takePublicKey(message)
        symmetric.mixHash(publicKey)
        symmetric.mixKey(X25519.scalarMult(ephemeral.privateKey, publicKey))
        symmetric.decryptAndHash(message.copyOfRange(32, message.size))
    }

    /**
     * Derives the transport ciphers. The initiator sends on the first one, the
     * responder on the second; both read on the other.
     */
    fun split() {
        val (first, second) = symmetric.split()
        sendCipher = if (role == Role.INITIATOR) first else second
        receiveCipher = if (role == Role.INITIATOR) second else first
    }

    val handshakeHash: ByteArray get() = symmetric.handshakeHash

    private fun takePublicKey(message: ByteArray): ByteArray {
        require(message.size >= 48) { "handshake message too short: ${message.size}" }
        return message.copyOfRange(0, 32)
    }

    companion object {
        val PROTOCOL_NAME: ByteArray = "Noise_NNpsk0_25519_ChaChaPoly_SHA256".toByteArray()
        val PROLOGUE_PREFIX: ByteArray = "NoiseAPIInit".toByteArray()

        /** `NoiseAPIInit` plus the client's first frame, length-prefixed. */
        fun prologue(helloFrame: ByteArray): ByteArray =
            PROLOGUE_PREFIX + byteArrayOf((helloFrame.size shr 8).toByte(), helloFrame.size.toByte()) + helloFrame
    }
}
