package org.matrix.TEESimulator.logging

import java.security.cert.Certificate
import java.security.cert.X509Certificate
import org.matrix.TEESimulator.attestation.AttestationPatcher
import org.matrix.TEESimulator.config.ConfigurationManager
import org.matrix.TEESimulator.util.AndroidDeviceUtils

/**
 * Assembles the per-UID "attestation dossier": for a targeted app, the full decoded attestation we
 * actually hand it, the identity of every certificate in the returned chain, and the source of each
 * device value that fed that attestation. Emitting all three where a chain is produced turns "the
 * app rejects us" into a field-by-field record that can be diffed against a genuine TEE.
 */
object AttestationDossier {

    /**
     * Records the dossier for [chain] under [uid], tagged with the [path] that produced it
     * (`FORGE-rust`, `FORGE-bouncycastle`, or `PATCH`). No-op for untargeted UIDs and release
     * builds; the expensive decoding is skipped entirely when the UID is out of scope.
     */
    fun log(uid: Int, txId: Long, path: String, chain: List<Certificate>) {
        if (!SystemLogger.isUidLogged(uid)) return
        val leaf = chain.firstOrNull() as? X509Certificate
        val extension =
            leaf?.let { AttestationPatcher.formatAttestationExtension(it) }
                ?: "<no attestation extension>"
        SystemLogger.uidLog(uid, txId, "attest", "path=$path depth=${chain.size} $extension")
        SystemLogger.uidLog(uid, txId, "keybox", "file=${ConfigurationManager.getKeyboxFileForUid(uid)}")
        SystemLogger.uidLog(uid, txId, "chain", AttestationPatcher.formatCertChain(chain))
        SystemLogger.uidLog(uid, txId, "props", AndroidDeviceUtils.describeSources(uid))
    }
}
