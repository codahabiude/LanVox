package com.example.wifip2pchat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanVoxCryptoTest {

    private lateinit var context: Context
    private lateinit var testMasterKey: SecretKey

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lanvox_crypto_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PeerRegistry.clear()
        FileTransferManager.filePortOverrideForTest = null
        testMasterKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
        CryptoManager.injectTestMasterKey(testMasterKey)
        CryptoManager.ensureInitialized(context)
        PeerRegistry.selfId = "node_alice"
    }

    @After
    fun tearDown() {
        FileTransferManager.filePortOverrideForTest = null
        PeerRegistry.clear()
    }

    private fun signRaw(priv: PrivateKey, bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(priv)
            update(bytes)
        }.sign()

    private fun signFileReq(priv: PrivateKey, commandArgsWithoutSig: String): String =
        CryptoManager.b64UrlEncode(signRaw(priv, CryptoManager.fileRequestSignatureInput(commandArgsWithoutSig)))

    @Test
    fun testLocalStorageEncryptionAndDecryptionRoundtrip() {
        val plain = "Secure encrypted data at rest"
        val encrypted = CryptoManager.encryptLocalString(context, plain)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.startsWith(CryptoManager.PREFIX_LOCAL_ENC))

        val decrypted = CryptoManager.decryptLocalString(context, encrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testEncryptAndDecryptUsingRealPairwiseFunctions() {
        val peerId = "node_bob"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val bobPubB64 = CryptoManager.b64UrlEncode(kpBob.public.encoded)

        val regRes = CryptoManager.registerPeerPublicKey(context, peerId, bobPubB64, isFromThirdPartyBroadcast = false)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, regRes)

        val plaintext = "Hello from Alice to Bob"
        val encrypted = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, plaintext)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.startsWith(Constants.MSG_TYPE_E2E_DIRECT))

        // Alice decrypts a message from Bob encrypted with Bob's private key and Alice's public key
        val alicePub = CryptoManager.getSelfPublicKeyBytes(context)
        val alicePubKey = CryptoManager.decodeEcPublicKey(alicePub)
        val bobChatKey = CryptoManager.derivePairwiseKey(kpBob.private, kpBob.public.encoded, alicePubKey, "lanvox-e2ee-v1-chat")
        val aad = "$peerId|${PeerRegistry.selfId}".toByteArray(Charsets.UTF_8)
        val (iv, ct) = CryptoManager.aesGcmEncrypt(bobChatKey, plaintext.toByteArray(Charsets.UTF_8), aad)
        val incomingCipher = "${Constants.MSG_TYPE_E2E_DIRECT}${CryptoManager.b64UrlEncode(iv)}:${CryptoManager.b64UrlEncode(ct)}"

        val decrypted = CryptoManager.decryptFromPeer(context, peerId, peerId, PeerRegistry.selfId, incomingCipher)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testTamperedDataRejectedByDecryption() {
        val peerId = "node_bob"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val bobPubB64 = CryptoManager.b64UrlEncode(kpBob.public.encoded)
        CryptoManager.registerPeerPublicKey(context, peerId, bobPubB64, isFromThirdPartyBroadcast = false)

        val plaintext = "Sensitive data"
        val encrypted = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, plaintext)!!
        val body = encrypted.removePrefix(Constants.MSG_TYPE_E2E_DIRECT)
        val parts = body.split(':', limit = 2)
        val iv = parts[0]
        val ctBytes = CryptoManager.b64UrlDecode(parts[1])
        ctBytes[0] = (ctBytes[0].toInt() xor 0xFF).toByte()
        val tamperedCipher = "${Constants.MSG_TYPE_E2E_DIRECT}$iv:${CryptoManager.b64UrlEncode(ctBytes)}"

        val decrypted = CryptoManager.decryptFromPeer(context, peerId, PeerRegistry.selfId, peerId, tamperedCipher)
        assertNull(decrypted)
    }

    @Test
    fun testPeerKeyChangeBlocksAndOldKeyRemainsUntilAccepted() {
        val peerId = "node_charlie"
        val kp1 = CryptoManager.generateEcP256KeyPair()
        val kp2 = CryptoManager.generateEcP256KeyPair()

        val b64_1 = CryptoManager.b64UrlEncode(kp1.public.encoded)
        val b64_2 = CryptoManager.b64UrlEncode(kp2.public.encoded)

        val r1 = CryptoManager.registerPeerPublicKey(context, peerId, b64_1, false)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, r1)
        assertFalse(CryptoManager.isPeerKeyBlocked(context, peerId))

        val pub1 = CryptoManager.getPeerPublicKey(context, peerId)
        assertNotNull(pub1)

        val r2 = CryptoManager.registerPeerPublicKey(context, peerId, b64_2, false)
        assertEquals(CryptoManager.KeyRegistrationResult.CHANGED, r2)
        assertTrue(CryptoManager.isPeerKeyBlocked(context, peerId))

        val pubAfterChange = CryptoManager.getPeerPublicKey(context, peerId)
        assertEquals(CryptoManager.b64UrlEncode(pub1!!.encoded), CryptoManager.b64UrlEncode(pubAfterChange!!.encoded))

        val blockedEncrypt = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, "Test")
        assertNull(blockedEncrypt)

        CryptoManager.acceptPendingKey(context, peerId)
        assertFalse(CryptoManager.isPeerKeyBlocked(context, peerId))

        val pubAccepted = CryptoManager.getPeerPublicKey(context, peerId)
        assertEquals(b64_2, CryptoManager.b64UrlEncode(pubAccepted!!.encoded))
    }

    @Test
    fun testForgedGroupMessageRejected() {
        val aliceId = PeerRegistry.selfId
        val bobId = "node_bob"
        val malloryId = "node_mallory"
        val alicePubKey = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val kpMallory = CryptoManager.generateEcP256KeyPair()

        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        CryptoManager.registerPeerPublicKey(context, malloryId, CryptoManager.b64UrlEncode(kpMallory.public.encoded), false)

        // 1. Bob encrypts and signs a genuine group broadcast with Bob's own keys to Alice & Mallory
        val bobCipher = CryptoManager.encryptForBroadcastWithIdentity(
            senderId = bobId,
            senderKeyPair = kpBob,
            targetPeerKeys = mapOf(aliceId to alicePubKey, malloryId to kpMallory.public),
            plaintext = "Genuine broadcast from Bob"
        )
        assertNotNull(bobCipher)

        val decryptedValid = CryptoManager.decryptBroadcast(context, bobId, aliceId, bobCipher!!)
        assertEquals("Genuine broadcast from Bob", decryptedValid)

        // 2. Mallory (a real third group member) extracts the shared message key from Mallory's slot,
        // encrypts a forged message claiming to be Bob, and signs it with Mallory's private key.
        val parts = bobCipher.removePrefix(Constants.MSG_TYPE_E2E_MULTI).split(':', limit = 5)
        val encodedMallory = CryptoManager.b64UrlEncode(malloryId.toByteArray(Charsets.UTF_8))
        val mallorySlot = parts[4].split(';').first { it.startsWith("$encodedMallory=") }.substringAfter('=').split('.', limit = 2)
        val malloryBobKey = CryptoManager.derivePairwiseKey(kpMallory.private, kpMallory.public.encoded, kpBob.public, "lanvox-e2ee-v1-chat")
        val recoveredMsgKeyBytes = CryptoManager.aesGcmDecrypt(
            malloryBobKey,
            CryptoManager.b64UrlDecode(mallorySlot[0]),
            CryptoManager.b64UrlDecode(mallorySlot[1]),
            "$bobId|$malloryId|wrap".toByteArray(Charsets.UTF_8)
        )
        val forgedMsgId = UUID.randomUUID().toString()
        val (forgedIv, forgedCt) = CryptoManager.aesGcmEncrypt(
            SecretKeySpec(recoveredMsgKeyBytes, "AES"),
            "Forged message from Mallory pretending to be Bob".toByteArray(Charsets.UTF_8),
            "$bobId|${Constants.RECIPIENT_BROADCAST}".toByteArray(Charsets.UTF_8)
        )
        val forgedCtB64 = CryptoManager.b64UrlEncode(forgedCt)
        val mallorySig = signRaw(
            kpMallory.private,
            CryptoManager.groupMessageSignatureInput(bobId, Constants.RECIPIENT_BROADCAST, forgedMsgId, forgedCtB64)
        )

        val forgedCipher = "${Constants.MSG_TYPE_E2E_MULTI}${CryptoManager.b64UrlEncode(forgedIv)}:$forgedCtB64:$forgedMsgId:${CryptoManager.b64UrlEncode(mallorySig)}:${parts[4]}"

        val decryptedForged = CryptoManager.decryptBroadcast(context, bobId, aliceId, forgedCipher)
        assertNull(decryptedForged)
    }

    @Test
    fun testReplayRejectedWithAlteredSender() {
        val aliceId = PeerRegistry.selfId
        val bobId = "node_bob"
        val alicePubKey = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val kpMallory = CryptoManager.generateEcP256KeyPair()

        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        CryptoManager.registerPeerPublicKey(context, "node_mallory", CryptoManager.b64UrlEncode(kpMallory.public.encoded), false)

        // 1. Bob encrypts and signs a valid message with Bob's own keys; Alice decrypts it once
        val broadcastCipher = CryptoManager.encryptForBroadcastWithIdentity(
            senderId = bobId,
            senderKeyPair = kpBob,
            targetPeerKeys = mapOf(aliceId to alicePubKey),
            plaintext = "Original valid message"
        )!!

        val decryptedValid = CryptoManager.decryptBroadcast(context, bobId, aliceId, broadcastCipher)
        assertEquals("Original valid message", decryptedValid)

        // 2. Replaying the same signed group message ID as Bob must be rejected
        val replayedSameSender = CryptoManager.decryptBroadcast(context, bobId, aliceId, broadcastCipher)
        assertNull(replayedSameSender)

        // 3. Replaying the message with an altered sender ID must also be rejected
        val decryptedWithFakeSender = CryptoManager.decryptBroadcast(context, "node_mallory", aliceId, broadcastCipher)
        assertNull(decryptedWithFakeSender)
    }

    @Test
    fun testGroupMessageReplayRejectedAfterAppRestart() {
        val aliceId = PeerRegistry.selfId
        val bobId = "node_bob"
        val alicePubKey = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))
        val kpBob = CryptoManager.generateEcP256KeyPair()
        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)

        val broadcastCipher = CryptoManager.encryptForBroadcastWithIdentity(
            senderId = bobId,
            senderKeyPair = kpBob,
            targetPeerKeys = mapOf(aliceId to alicePubKey),
            plaintext = "Persistent replay check"
        )!!

        assertEquals(
            "Persistent replay check",
            CryptoManager.decryptBroadcast(context, bobId, aliceId, broadcastCipher)
        )

        // Simulate full app process restart (wipes CryptoManager in-memory state, keeps persisted prefs)
        CryptoManager.injectTestMasterKey(testMasterKey)
        CryptoManager.ensureInitialized(context)

        assertNull(CryptoManager.decryptBroadcast(context, bobId, aliceId, broadcastCipher))
    }

    @Test
    fun testThirdPartyPeerListKeyHeldAsPendingAndNotTrustedDirectly() {
        val peerId = "peer_third_party"
        val kp = CryptoManager.generateEcP256KeyPair()
        val pubB64 = CryptoManager.b64UrlEncode(kp.public.encoded)

        val r = CryptoManager.registerPeerPublicKey(context, peerId, pubB64, isFromThirdPartyBroadcast = true)
        assertEquals(CryptoManager.KeyRegistrationResult.CHANGED, r)
        assertTrue(CryptoManager.isPeerKeyBlocked(context, peerId))
        assertNull(CryptoManager.getPeerPublicKey(context, peerId))
    }

    @Test
    fun testHelloChallengeResponseAcceptedAndReplayRejected() {
        // Receiver (node_alice) issues a fresh challenge payload
        val challengePayload = CryptoManager.buildChallengePayload()
        assertTrue(challengePayload.startsWith(Constants.MSG_TYPE_CHALLENGE))
        val vNonce = challengePayload.removePrefix(Constants.MSG_TYPE_CHALLENGE)

        // App builds a signed HELLO response as node_bob using Alice's ID & issued challenge nonce
        PeerRegistry.selfId = "node_bob"
        val bobPubB64 = CryptoManager.getSelfPublicKeyB64(context)
        val appBuiltHello = CryptoManager.buildSignedHelloPayload(
            context = context,
            localIp = "192.168.49.5",
            pubKeyB64 = bobPubB64,
            verifierId = "node_alice",
            verifierNonce = vNonce
        )
        val parsed = CryptoManager.parseSignedHelloPayload(appBuiltHello)
        assertEquals(vNonce, parsed.verifierNonce)
        assertFalse(parsed.responderNonce.isNullOrBlank())
        assertNotEquals(parsed.verifierNonce, parsed.responderNonce)

        // Wrong verifierId ("node_charlie") must reject the HELLO bound to "node_alice"
        PeerRegistry.selfId = "node_charlie"
        assertNull(CryptoManager.verifyAndAcceptSignedHello(context, "node_bob", appBuiltHello, expectedVerifierId = "node_charlie"))

        // Intended receiver (node_alice) verifies and accepts the HELLO once
        PeerRegistry.selfId = "node_alice"
        val accepted = CryptoManager.verifyAndAcceptSignedHello(context, "node_bob", appBuiltHello)
        assertNotNull(accepted)
        assertEquals("192.168.49.5", accepted!!.ip)
        assertEquals(bobPubB64, accepted.pubKeyB64)
        assertTrue(CryptoManager.hasPeerKey(context, "node_bob"))

        // Replaying the exact same signed HELLO must be rejected (nonce was consumed)
        assertNull(CryptoManager.verifyAndAcceptSignedHello(context, "node_bob", appBuiltHello))

        // Signing an unissued nonce must also be rejected
        PeerRegistry.selfId = "node_bob"
        val unissuedHello = CryptoManager.buildSignedHelloPayload(context, "192.168.49.5", bobPubB64, "node_alice", "forged_nonce_123")
        PeerRegistry.selfId = "node_alice"
        assertNull(CryptoManager.verifyAndAcceptSignedHello(context, "node_bob", unissuedHello))

        // Cross-purpose replay: reusing a HELLO signature in a KEYX payload must fail even with an issued KEYX nonce
        val chal2 = CryptoManager.buildKeyxChallengePayload().removePrefix(Constants.MSG_TYPE_KEYX_CHALLENGE)
        val helloSig = CryptoManager.signHandshakeChallenge(
            privateKey = CryptoManager.generateEcP256KeyPair().private,
            purpose = "HELLO",
            signerId = "node_bob",
            verifierId = "node_alice",
            verifierNonce = chal2,
            responderNonce = "rnonce_xyz",
            signerKeyMaterialB64 = bobPubB64
        )
        val bobKey = CryptoManager.getPeerPublicKey(context, "node_bob")!!
        assertFalse(
            CryptoManager.verifyAndConsumeChallenge(
                publicKey = bobKey,
                purpose = "KEYX",
                verifierNonce = chal2,
                responderNonce = "rnonce_xyz",
                signerId = "node_bob",
                verifierId = "node_alice",
                pubKeyB64 = bobPubB64,
                signatureBytes = helloSig
            )
        )
    }

    @Test
    fun testKeyxChallengeResponseAcceptedAndReplayRejected() {
        val chalPayload = CryptoManager.buildKeyxChallengePayload()
        assertTrue(chalPayload.startsWith(Constants.MSG_TYPE_KEYX_CHALLENGE))
        val nonce = chalPayload.removePrefix(Constants.MSG_TYPE_KEYX_CHALLENGE)

        PeerRegistry.selfId = "node_bob"
        val appBuiltKeyx = CryptoManager.buildSignedKeyxPayload(context, "node_alice", nonce)

        // Wrong verifier must fail
        PeerRegistry.selfId = "node_charlie"
        assertNull(CryptoManager.verifyAndAcceptSignedKeyx(context, "node_bob", appBuiltKeyx, expectedVerifierId = "node_charlie"))

        // Intended verifier accepts once
        PeerRegistry.selfId = "node_alice"
        val accepted = CryptoManager.verifyAndAcceptSignedKeyx(context, "node_bob", appBuiltKeyx)
        assertNotNull(accepted)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, accepted!!.registrationResult)

        // Replaying the same KEYX must be rejected
        assertNull(CryptoManager.verifyAndAcceptSignedKeyx(context, "node_bob", appBuiltKeyx))
    }

    @Test
    fun testFileServerAuthenticationRequiresNonceAndValidSignature() {
        val bobId = "node_bob"
        val charlieId = "node_charlie"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val kpCharlie = CryptoManager.generateEcP256KeyPair()
        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        CryptoManager.registerPeerPublicKey(context, charlieId, CryptoManager.b64UrlEncode(kpCharlie.public.encoded), false)

        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val transferId = "tx_${UUID.randomUUID().toString().take(8)}"
        val payloadBytes = "EncryptedAttachmentPayloadBytes".toByteArray(Charsets.UTF_8)

        fun runServerOnce(block: (Socket) -> Unit) {
            val f = executor.submit {
                val client = server.accept()
                FileTransferManager.handleServerClient(context, client)
            }
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", server.localPort), 3000)
                block(s)
            }
            f.get()
        }

        fun readLine(s: Socket): String? {
            val buf = ByteArrayOutputStream()
            val inp = s.getInputStream()
            while (true) {
                val b = inp.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(b)
            }
        }

        fun writeLine(s: Socket, line: String) {
            s.getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
        }

        try {
            // 1. Unauthenticated bare GET must be rejected
            runServerOnce { s ->
                val nonceLine = readLine(s)!!
                assertTrue(nonceLine.startsWith("NONCE "))
                writeLine(s, "GET $transferId")
                assertEquals("ERR unauthorized", readLine(s))
            }

            // 2. Unknown sender must be rejected even with a valid self-signed signature
            val strangerKp = CryptoManager.generateEcP256KeyPair()
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "GET $transferId node_stranger $nonce"
                writeLine(s, "$cmd ${signFileReq(strangerKp.private, cmd)}")
                assertEquals("ERR unknown_sender", readLine(s))
            }

            // 3. Wrong/stale nonce must be rejected
            runServerOnce { s ->
                readLine(s)!! // ignore real server nonce
                val cmd = "GET $transferId $bobId wrong_nonce"
                writeLine(s, "$cmd ${signFileReq(kpBob.private, cmd)}")
                assertEquals("ERR bad_nonce", readLine(s))
            }

            // 4. Forged signature claiming Bob (signed with Charlie's key) must be rejected
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "GET $transferId $bobId $nonce"
                writeLine(s, "$cmd ${signFileReq(kpCharlie.private, cmd)}")
                assertEquals("ERR unauthorized", readLine(s))
            }

            // 4b. Cross-purpose signature without LANVOX-SIG-FILE-V1 domain label must be rejected
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "GET $transferId $bobId $nonce"
                val rawUnlabeledSig = CryptoManager.b64UrlEncode(signRaw(kpBob.private, cmd.toByteArray(Charsets.UTF_8)))
                writeLine(s, "$cmd $rawUnlabeledSig")
                assertEquals("ERR unauthorized", readLine(s))
            }

            // 5. Valid signed PUT from Bob succeeds
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "PUT $transferId ${payloadBytes.size} $bobId $nonce"
                writeLine(s, "$cmd ${signFileReq(kpBob.private, cmd)}")
                s.getOutputStream().write(payloadBytes)
                s.getOutputStream().flush()
                assertEquals("OK", readLine(s))
            }

            // 6. Another peer (Charlie) cannot overwrite Bob's transfer via PUT
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "PUT $transferId ${payloadBytes.size} $charlieId $nonce"
                writeLine(s, "$cmd ${signFileReq(kpCharlie.private, cmd)}")
                assertEquals("ERR transfer_exists", readLine(s))
            }

            // 7. Valid signed GET from Charlie retrieves Bob's staged transfer
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "GET $transferId $charlieId $nonce"
                writeLine(s, "$cmd ${signFileReq(kpCharlie.private, cmd)}")
                assertEquals("OK ${payloadBytes.size}", readLine(s))
                val received = ByteArray(payloadBytes.size)
                DataInputStream(s.getInputStream()).readFully(received)
                assertArrayEquals(payloadBytes, received)
            }

            // 8. RELAY_GET targeting an unknown peer IP is rejected
            runServerOnce { s ->
                val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                val cmd = "RELAY_GET 10.99.99.99 $transferId $bobId $nonce"
                writeLine(s, "$cmd ${signFileReq(kpBob.private, cmd)}")
                assertEquals("ERR unknown_relay_target", readLine(s))
            }

            // 8b. RELAY_GET targeting a known non-blocked peer forwards a signed GET using upstream nonce
            ServerSocket(0).use { upstreamServer ->
                FileTransferManager.filePortOverrideForTest = upstreamServer.localPort
                PeerRegistry.upsert(
                    PeerRegistry.Peer(
                        id = charlieId,
                        name = "Charlie",
                        isGroupOwner = false,
                        ipAddress = "127.0.0.1",
                        publicKeyB64 = CryptoManager.b64UrlEncode(kpCharlie.public.encoded)
                    )
                )
                val alicePubKeyForRelay = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))
                val relayBytes = "RelayedSecretPayload".toByteArray(Charsets.UTF_8)
                val relayTransferId = "rel_${UUID.randomUUID().toString().take(8)}"
                val relayPool = Executors.newFixedThreadPool(2)
                try {
                    val upstreamFuture = relayPool.submit {
                        upstreamServer.accept().use { upSock ->
                            val upNonce = "up_nonce_987"
                            writeLine(upSock, "NONCE $upNonce")
                            val upReq = readLine(upSock)!!.split(' ')
                            assertEquals("GET", upReq[0])
                            assertEquals(relayTransferId, upReq[1])
                            assertEquals(PeerRegistry.selfId, upReq[2])
                            assertEquals(upNonce, upReq[3])
                            val upSigOk = Signature.getInstance("SHA256withECDSA").apply {
                                initVerify(alicePubKeyForRelay)
                                update(CryptoManager.fileRequestSignatureInput(upReq.dropLast(1).joinToString(" ")))
                            }.verify(CryptoManager.b64UrlDecode(upReq[4]))
                            assertTrue(upSigOk)
                            writeLine(upSock, "OK ${relayBytes.size}")
                            upSock.getOutputStream().write(relayBytes)
                            upSock.getOutputStream().flush()
                        }
                    }
                    val serverFuture = relayPool.submit {
                        server.accept().use { client ->
                            FileTransferManager.handleServerClient(context, client)
                        }
                    }
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", server.localPort), 3000)
                        val nonce = readLine(s)!!.removePrefix("NONCE ").trim()
                        val cmd = "RELAY_GET 127.0.0.1 $relayTransferId $bobId $nonce"
                        writeLine(s, "$cmd ${signFileReq(kpBob.private, cmd)}")
                        assertEquals("OK ${relayBytes.size}", readLine(s))
                        val got = ByteArray(relayBytes.size)
                        DataInputStream(s.getInputStream()).readFully(got)
                        assertArrayEquals(relayBytes, got)
                    }
                    upstreamFuture.get()
                    serverFuture.get()
                } finally {
                    FileTransferManager.filePortOverrideForTest = null
                    relayPool.shutdownNow()
                }
            }

            // 9. Client fetchViaTcp signs GET with server nonce and verifies encrypted stream
            val alicePubKey = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))
            val fileKeyB64 = CryptoManager.generateRandomFileKeyB64()
            val srcPlain = "Hello encrypted file stream".toByteArray(Charsets.UTF_8)
            val encryptedFile = File(context.cacheDir, "test_enc_source.lvxenc")
            val (_, cipherLen) = CryptoManager.encryptStreamToFile(
                ByteArrayInputStream(srcPlain),
                encryptedFile,
                fileKeyB64,
                Constants.MAX_FILE_TRANSFER_BYTES
            )
            val offer = FileTransferManager.FileOffer(
                transferId = "fetch_${UUID.randomUUID().toString().take(8)}",
                fileName = "secret.txt",
                mimeType = "text/plain",
                fileSize = srcPlain.size.toLong(),
                senderHost = "127.0.0.1",
                cipherSize = cipherLen,
                encryptedKeyB64 = fileKeyB64
            )
            val destFile = File(context.cacheDir, "test_fetched.lvxenc")
            destFile.delete()

            val mockPeerFuture = executor.submit {
                server.accept().use { sock ->
                    val out = sock.getOutputStream()
                    val serverNonce = "srv_nonce_xyz"
                    writeLine(sock, "NONCE $serverNonce")
                    val reqParts = readLine(sock)!!.split(' ')
                    assertEquals("GET", reqParts[0])
                    assertEquals(offer.transferId, reqParts[1])
                    assertEquals(PeerRegistry.selfId, reqParts[2])
                    assertEquals(serverNonce, reqParts[3])
                    val sigOk = Signature.getInstance("SHA256withECDSA").apply {
                        initVerify(alicePubKey)
                        update(CryptoManager.fileRequestSignatureInput(reqParts.dropLast(1).joinToString(" ")))
                    }.verify(CryptoManager.b64UrlDecode(reqParts[4]))
                    assertTrue(sigOk)
                    val bytes = encryptedFile.readBytes()
                    writeLine(sock, "OK ${bytes.size}")
                    out.write(bytes)
                    out.flush()
                }
            }
            val downloaded = runBlocking {
                FileTransferManager.fetchViaTcp(
                    context = context,
                    connectHost = "127.0.0.1",
                    command = "GET ${offer.transferId}",
                    destFile = destFile,
                    offer = offer,
                    dir = context.cacheDir,
                    attempts = 1,
                    port = server.localPort
                )
            }
            mockPeerFuture.get()
            assertNotNull(downloaded)
            CryptoManager.saveAttachmentKey(context, offer.transferId, fileKeyB64)
            val decrypted = CryptoManager.decryptAttachmentToBytes(context, offer.transferId, downloaded!!)
            assertArrayEquals(srcPlain, decrypted)
            encryptedFile.delete()
            destFile.delete()
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun testCallKeysRequireSavedPeerKeyAndAreDirectional() {
        val ephA = CryptoManager.generateEcP256KeyPair()
        val ephB = CryptoManager.generateEcP256KeyPair()
        val ephSecret = CryptoManager.computeEcdhSecret(ephA.private, ephB.public)

        // 1. Missing saved peer key MUST throw (no ephemeral-only fallback)
        assertThrows(IllegalStateException::class.java) {
            CryptoManager.deriveAudioSignalingSessionKeys(
                context = context,
                peerId = "node_unknown",
                ephSecret = ephSecret,
                ephPubA = ephA.public.encoded,
                ephPubB = ephB.public.encoded
            )
        }
        assertThrows(IllegalStateException::class.java) {
            CryptoManager.deriveVideoStreamSessionKeys(
                context = context,
                peerId = "node_unknown",
                ephSecret = ephSecret,
                ephPubA = ephA.public.encoded,
                ephPubB = ephB.public.encoded
            )
        }

        // 2. Register Bob, then verify blocked key also refuses derivation
        val kpBob = CryptoManager.generateEcP256KeyPair()
        CryptoManager.registerPeerPublicKey(context, "node_bob", CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        val kpBob2 = CryptoManager.generateEcP256KeyPair()
        CryptoManager.registerPeerPublicKey(context, "node_bob", CryptoManager.b64UrlEncode(kpBob2.public.encoded), false)
        assertTrue(CryptoManager.isPeerKeyBlocked(context, "node_bob"))
        assertThrows(IllegalArgumentException::class.java) {
            CryptoManager.deriveAudioSignalingSessionKeys(
                context = context,
                peerId = "node_bob",
                ephSecret = ephSecret,
                ephPubA = ephA.public.encoded,
                ephPubB = ephB.public.encoded
            )
        }
        CryptoManager.rejectPendingKey(context, "node_bob")

        // 3. Valid saved key yields distinct send/recv directional keys
        val aliceAudioKeys = CryptoManager.deriveAudioSignalingSessionKeys(
            context = context,
            peerId = "node_bob",
            ephSecret = ephSecret,
            ephPubA = ephA.public.encoded,
            ephPubB = ephB.public.encoded
        )
        assertFalse(aliceAudioKeys.sendKey.encoded.contentEquals(aliceAudioKeys.recvKey.encoded))

        val aliceVideoKeys = CryptoManager.deriveVideoStreamSessionKeys(
            context = context,
            peerId = "node_bob",
            ephSecret = ephSecret,
            ephPubA = ephA.public.encoded,
            ephPubB = ephB.public.encoded
        )
        assertFalse(aliceVideoKeys.sendKey.encoded.contentEquals(aliceVideoKeys.recvKey.encoded))
        assertFalse(aliceAudioKeys.sendKey.encoded.contentEquals(aliceVideoKeys.sendKey.encoded))
    }

    @Test
    fun testAudioAndVideoCallChannelsAuthenticatePeerAndUseDirectionalKeys() {
        val bobId = "node_bob"
        val malloryId = "node_mallory"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val kpMallory = CryptoManager.generateEcP256KeyPair()
        val alicePubKey = CryptoManager.decodeEcPublicKey(CryptoManager.getSelfPublicKeyBytes(context))

        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        CryptoManager.registerPeerPublicKey(context, malloryId, CryptoManager.b64UrlEncode(kpMallory.public.encoded), false)

        val server = ServerSocket(0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // --- Audio channel: 1. Intended peer Bob succeeds & derives matching directional keys ---
            val bobAudioFuture = pool.submit<CryptoManager.DirectionalCallKeys> {
                Socket("127.0.0.1", server.localPort).use { sock ->
                    val writer = sock.getOutputStream().bufferedWriter()
                    val reader = sock.getInputStream().bufferedReader()
                    val ephBob = CryptoManager.generateEcP256KeyPair()
                    val bobNonce = CryptoManager.generateRandomNonce()

                    val aliceHs = org.json.JSONObject(reader.readLine())
                    assertEquals("ecdh", aliceHs.getString("type"))
                    assertEquals("node_alice", aliceHs.getString("peerId"))
                    val aliceNonce = aliceHs.getString("nonce")
                    val aliceEphPub = CryptoManager.b64UrlDecode(aliceHs.getString("pub"))

                    writer.write(
                        org.json.JSONObject()
                            .put("type", "ecdh")
                            .put("peerId", bobId)
                            .put("pub", CryptoManager.b64UrlEncode(ephBob.public.encoded))
                            .put("nonce", bobNonce)
                            .toString()
                    )
                    writer.newLine()
                    writer.flush()

                    val aliceAuth = org.json.JSONObject(reader.readLine())
                    assertEquals("ecdh_auth", aliceAuth.getString("type"))
                    val bobSig = CryptoManager.b64UrlEncode(
                        CryptoManager.signHandshakeChallenge(
                            privateKey = kpBob.private,
                            purpose = "AUDIO_CALL",
                            signerId = bobId,
                            verifierId = "node_alice",
                            verifierNonce = aliceNonce,
                            responderNonce = bobNonce,
                            signerKeyMaterialB64 = CryptoManager.b64UrlEncode(ephBob.public.encoded),
                            peerKeyMaterialB64 = CryptoManager.b64UrlEncode(aliceEphPub)
                        )
                    )
                    writer.write(org.json.JSONObject().put("type", "ecdh_auth").put("sig", bobSig).toString())
                    writer.newLine()
                    writer.flush()

                    val ephSecret = CryptoManager.computeEcdhSecret(ephBob.private, CryptoManager.decodeEcPublicKey(aliceEphPub))
                    val idSecret = CryptoManager.computeEcdhSecret(kpBob.private, alicePubKey)
                    val sortedSalt = java.security.MessageDigest.getInstance("SHA-256").digest(
                        if (ephBob.public.encoded.size != aliceEphPub.size) {
                            if (ephBob.public.encoded.size < aliceEphPub.size) ephBob.public.encoded + aliceEphPub else aliceEphPub + ephBob.public.encoded
                        } else {
                            var aFirst = true
                            for (i in ephBob.public.encoded.indices) {
                                val d = (ephBob.public.encoded[i].toInt() and 0xFF) - (aliceEphPub[i].toInt() and 0xFF)
                                if (d != 0) {
                                    aFirst = d < 0
                                    break
                                }
                            }
                            if (aFirst) ephBob.public.encoded + aliceEphPub else aliceEphPub + ephBob.public.encoded
                        }
                    )
                    val ikm = ephSecret + idSecret
                    CryptoManager.DirectionalCallKeys(
                        sendKey = SecretKeySpec(CryptoManager.hkdfSha256(sortedSalt, ikm, "lanvox-e2ee-v1-audio-sig|$bobId->node_alice".toByteArray(), 32), "AES"),
                        recvKey = SecretKeySpec(CryptoManager.hkdfSha256(sortedSalt, ikm, "lanvox-e2ee-v1-audio-sig|node_alice->$bobId".toByteArray(), 32), "AES")
                    )
                }
            }
            val aliceAudioKeys = server.accept().use { conn ->
                AudioCallService.establishAuthenticatedAudioChannel(context, conn, expectedPeerId = bobId)
            }
            val bobAudioKeys = bobAudioFuture.get()
            assertArrayEquals(aliceAudioKeys.sendKey.encoded, bobAudioKeys.recvKey.encoded)
            assertArrayEquals(bobAudioKeys.sendKey.encoded, aliceAudioKeys.recvKey.encoded)
            assertFalse(aliceAudioKeys.sendKey.encoded.contentEquals(aliceAudioKeys.recvKey.encoded))

            val encryptedOffer = CryptoManager.encryptSignalingLine(aliceAudioKeys.sendKey, 1L, "{\"type\":\"offer\"}")
            assertEquals(1L to "{\"type\":\"offer\"}", CryptoManager.decryptSignalingLine(bobAudioKeys.recvKey, encryptedOffer))
            assertNull(CryptoManager.decryptSignalingLine(aliceAudioKeys.recvKey, encryptedOffer))

            // --- Audio channel: 2. Unintended peer Mallory is rejected ---
            val malloryAudioFuture = pool.submit {
                Socket("127.0.0.1", server.localPort).use { sock ->
                    val writer = sock.getOutputStream().bufferedWriter()
                    val reader = sock.getInputStream().bufferedReader()
                    val ephMal = CryptoManager.generateEcP256KeyPair()
                    reader.readLine()
                    writer.write(
                        org.json.JSONObject()
                            .put("type", "ecdh")
                            .put("peerId", malloryId)
                            .put("pub", CryptoManager.b64UrlEncode(ephMal.public.encoded))
                            .put("nonce", CryptoManager.generateRandomNonce())
                            .toString()
                    )
                    writer.newLine()
                    writer.flush()
                }
            }
            assertThrows(SecurityException::class.java) {
                server.accept().use { conn ->
                    AudioCallService.establishAuthenticatedAudioChannel(context, conn, expectedPeerId = bobId)
                }
            }
            malloryAudioFuture.get()

            // --- Video channel: 3. Intended peer Bob succeeds & directional frame keys match ---
            val bobVideoFuture = pool.submit<CryptoManager.DirectionalCallKeys> {
                Socket("127.0.0.1", server.localPort).use { sock ->
                    val dos = DataOutputStream(sock.getOutputStream())
                    val dis = DataInputStream(sock.getInputStream())
                    val ephBob = CryptoManager.generateEcP256KeyPair()
                    val bobNonce = CryptoManager.generateRandomNonce()

                    dos.writeBytes("LVV2")
                    dos.writeUTF(bobId)
                    dos.writeShort(ephBob.public.encoded.size)
                    dos.write(ephBob.public.encoded)
                    dos.writeUTF(bobNonce)
                    dos.flush()

                    val magic = ByteArray(4)
                    dis.readFully(magic)
                    assertEquals("LVV2", String(magic, Charsets.US_ASCII))
                    assertEquals("node_alice", dis.readUTF())
                    val aLen = dis.readUnsignedShort()
                    val aliceEphPub = ByteArray(aLen).also { dis.readFully(it) }
                    val aliceNonce = dis.readUTF()
                    dis.readUTF() // Alice auth sig

                    val bobSig = CryptoManager.b64UrlEncode(
                        CryptoManager.signHandshakeChallenge(
                            privateKey = kpBob.private,
                            purpose = "VIDEO_CALL",
                            signerId = bobId,
                            verifierId = "node_alice",
                            verifierNonce = aliceNonce,
                            responderNonce = bobNonce,
                            signerKeyMaterialB64 = CryptoManager.b64UrlEncode(ephBob.public.encoded),
                            peerKeyMaterialB64 = CryptoManager.b64UrlEncode(aliceEphPub)
                        )
                    )
                    dos.writeUTF(bobSig)
                    dos.flush()

                    val ephSecret = CryptoManager.computeEcdhSecret(ephBob.private, CryptoManager.decodeEcPublicKey(aliceEphPub))
                    val idSecret = CryptoManager.computeEcdhSecret(kpBob.private, alicePubKey)
                    val sortedSalt = java.security.MessageDigest.getInstance("SHA-256").digest(
                        if (ephBob.public.encoded.size != aliceEphPub.size) {
                            if (ephBob.public.encoded.size < aliceEphPub.size) ephBob.public.encoded + aliceEphPub else aliceEphPub + ephBob.public.encoded
                        } else {
                            var aFirst = true
                            for (i in ephBob.public.encoded.indices) {
                                val d = (ephBob.public.encoded[i].toInt() and 0xFF) - (aliceEphPub[i].toInt() and 0xFF)
                                if (d != 0) {
                                    aFirst = d < 0
                                    break
                                }
                            }
                            if (aFirst) ephBob.public.encoded + aliceEphPub else aliceEphPub + ephBob.public.encoded
                        }
                    )
                    val ikm = ephSecret + idSecret
                    CryptoManager.DirectionalCallKeys(
                        sendKey = SecretKeySpec(CryptoManager.hkdfSha256(sortedSalt, ikm, "lanvox-e2ee-v1-video|$bobId->node_alice".toByteArray(), 32), "AES"),
                        recvKey = SecretKeySpec(CryptoManager.hkdfSha256(sortedSalt, ikm, "lanvox-e2ee-v1-video|node_alice->$bobId".toByteArray(), 32), "AES")
                    )
                }
            }
            val aliceVideoKeys = server.accept().use { conn ->
                VideoCallService.establishAuthenticatedVideoChannel(context, conn, expectedPeerId = bobId)
            }
            val bobVideoKeys = bobVideoFuture.get()
            assertArrayEquals(aliceVideoKeys.sendKey.encoded, bobVideoKeys.recvKey.encoded)
            assertArrayEquals(bobVideoKeys.sendKey.encoded, aliceVideoKeys.recvKey.encoded)
            assertFalse(aliceVideoKeys.sendKey.encoded.contentEquals(aliceVideoKeys.recvKey.encoded))

            val framePlain = byteArrayOf(1, 2, 3, 4, 5)
            val encFrame = CryptoManager.encryptVideoFrame(aliceVideoKeys.sendKey, 1L, framePlain)
            assertArrayEquals(framePlain, CryptoManager.decryptVideoFrame(bobVideoKeys.recvKey, 1L, encFrame))
            assertNull(CryptoManager.decryptVideoFrame(aliceVideoKeys.recvKey, 1L, encFrame))

            // --- Video channel: 4. Missing saved peer key is refused immediately ---
            val dummySocket = Socket()
            assertThrows(SecurityException::class.java) {
                VideoCallService.establishAuthenticatedVideoChannel(context, dummySocket, expectedPeerId = "node_unknown")
            }
            assertThrows(SecurityException::class.java) {
                AudioCallService.establishAuthenticatedAudioChannel(context, dummySocket, expectedPeerId = "node_unknown")
            }
        } finally {
            server.close()
            pool.shutdownNow()
        }

        // Verify expectedIp mismatch & forged signature on Video channel with explicit sockets
        val srv2 = ServerSocket(0)
        val pool2 = Executors.newSingleThreadExecutor()
        try {
            // 4b. expectedIp mismatch rejects immediately
            val ipMismatchClient = pool2.submit {
                Socket("127.0.0.1", srv2.localPort).use { /* just connect */ }
            }
            assertThrows(SecurityException::class.java) {
                srv2.accept().use { conn ->
                    VideoCallService.establishAuthenticatedVideoChannel(
                        context,
                        conn,
                        expectedPeerId = bobId,
                        expectedIp = "10.0.0.99"
                    )
                }
            }
            ipMismatchClient.get()

            // 4c. Forged ECDSA signature on Video channel is rejected
            val clientFuture = pool2.submit {
                Socket("127.0.0.1", srv2.localPort).use { sock ->
                    val dos = DataOutputStream(sock.getOutputStream())
                    val dis = DataInputStream(sock.getInputStream())
                    val ephBob = CryptoManager.generateEcP256KeyPair()
                    val bobNonce = CryptoManager.generateRandomNonce()

                    dos.writeBytes("LVV2")
                    dos.writeUTF(bobId)
                    dos.writeShort(ephBob.public.encoded.size)
                    dos.write(ephBob.public.encoded)
                    dos.writeUTF(bobNonce)
                    dos.flush()

                    val magic = ByteArray(4)
                    dis.readFully(magic)
                    dis.readUTF()
                    val aLen = dis.readUnsignedShort()
                    dis.readFully(ByteArray(aLen))
                    dis.readUTF()
                    dis.readUTF()
                    // Mallory impersonating Bob by signing with kpMallory.private
                    val forgedSig = CryptoManager.b64UrlEncode(signRaw(kpMallory.private, "bad".toByteArray()))
                    dos.writeUTF(forgedSig)
                    dos.flush()
                }
            }
            assertThrows(SecurityException::class.java) {
                srv2.accept().use { conn ->
                    VideoCallService.establishAuthenticatedVideoChannel(context, conn, expectedPeerId = bobId)
                }
            }
            clientFuture.get()
        } finally {
            srv2.close()
            pool2.shutdownNow()
        }
    }
}
