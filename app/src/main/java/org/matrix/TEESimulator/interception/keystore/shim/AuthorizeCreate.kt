package org.matrix.TEESimulator.interception.keystore.shim

import android.hardware.security.keymint.Algorithm
import android.hardware.security.keymint.KeyPurpose
import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.Tag
import org.matrix.TEESimulator.attestation.KeyMintAttestation

object AuthorizeCreate {

    fun check(
        keyParams: KeyMintAttestation?,
        opParams: KeyMintAttestation,
        rawOpParams: Array<KeyParameter>? = null,
    ): Int? {
        if (keyParams == null) return null
        return checkPurpose(keyParams, opParams)
            ?: checkAlgorithmPurpose(keyParams, opParams)
            ?: checkTemporalValidity(keyParams, opParams)
            ?: checkCallerNonce(keyParams, rawOpParams)
    }

    private fun checkPurpose(keyParams: KeyMintAttestation, opParams: KeyMintAttestation): Int? {
        val requestedPurpose = opParams.purpose.firstOrNull() ?: return null
        if (requestedPurpose == KeyPurpose.WRAP_KEY)
            return KeystoreErrorCodes.incompatiblePurpose
        if (requestedPurpose !in keyParams.purpose)
            return KeystoreErrorCodes.incompatiblePurpose
        return null
    }

    private fun checkAlgorithmPurpose(keyParams: KeyMintAttestation, opParams: KeyMintAttestation): Int? {
        val purpose = opParams.purpose.firstOrNull() ?: return null
        return when (keyParams.algorithm) {
            Algorithm.EC -> when (purpose) {
                KeyPurpose.ENCRYPT, KeyPurpose.DECRYPT -> KeystoreErrorCodes.unsupportedPurpose
                KeyPurpose.AGREE_KEY -> null
                else -> null
            }
            Algorithm.RSA -> when (purpose) {
                KeyPurpose.AGREE_KEY -> KeystoreErrorCodes.unsupportedPurpose
                else -> null
            }
            else -> null
        }
    }

    private fun checkTemporalValidity(keyParams: KeyMintAttestation, opParams: KeyMintAttestation): Int? {
        val now = System.currentTimeMillis()
        val purpose = opParams.purpose.firstOrNull()

        keyParams.activeDateTime?.let { activeDate ->
            if (now < activeDate.time) return KeystoreErrorCodes.keyNotYetValid
        }

        keyParams.originationExpireDateTime?.let { expireDate ->
            if (purpose == KeyPurpose.SIGN || purpose == KeyPurpose.ENCRYPT) {
                if (now > expireDate.time) return KeystoreErrorCodes.keyExpired
            }
        }

        keyParams.usageExpireDateTime?.let { expireDate ->
            if (purpose == KeyPurpose.VERIFY || purpose == KeyPurpose.DECRYPT) {
                if (now > expireDate.time) return KeystoreErrorCodes.keyExpired
            }
        }

        return null
    }

    private fun checkCallerNonce(keyParams: KeyMintAttestation, rawOpParams: Array<KeyParameter>?): Int? {
        if (keyParams.callerNonce == true) return null
        val hasNonce = rawOpParams?.any { it.tag == Tag.NONCE } == true
        if (hasNonce) return KeystoreErrorCodes.callerNonceProhibited
        return null
    }
}
