package com.takattowo.bootloaderspoofer;

import android.security.keystore.KeyProperties;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class KeyboxRegistry {

    static final class Entry {
        final PEMKeyPair pemKeyPair;
        final KeyPair keyPair;
        final List<Certificate> certificates;

        Entry(PEMKeyPair pemKeyPair, KeyPair keyPair, List<Certificate> certificates) {
            this.pemKeyPair = pemKeyPair;
            this.keyPair = keyPair;
            this.certificates = certificates;
        }
    }

    private final Map<String, Entry> byAlgorithm = new HashMap<>();

    void put(String algorithmKey, PEMKeyPair pemKp, List<Certificate> chain) throws Exception {
        if (byAlgorithm.containsKey(algorithmKey)) {
            throw new IllegalArgumentException("Duplicate " + algorithmKey + " key");
        }
        KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKp);
        validate(algorithmKey, kp, chain);
        byAlgorithm.put(algorithmKey, new Entry(pemKp, kp,
                Collections.unmodifiableList(new ArrayList<>(chain))));
    }

    private static void validate(String algorithm, KeyPair keyPair,
                                 List<Certificate> chain) throws Exception {
        if (keyPair.getPrivate() == null || keyPair.getPublic() == null) {
            throw new CertificateException("Incomplete " + algorithm + " key pair");
        }
        String actualAlgorithm = keyPair.getPublic().getAlgorithm();
        boolean algorithmMatches = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? "EC".equalsIgnoreCase(actualAlgorithm) || "ECDSA".equalsIgnoreCase(actualAlgorithm)
                : KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm)
                && "RSA".equalsIgnoreCase(actualAlgorithm);
        if (!algorithmMatches) {
            throw new CertificateException(algorithm + " key contains " + actualAlgorithm + " material");
        }
        if (chain == null || chain.isEmpty() || !(chain.get(0) instanceof X509Certificate first)) {
            throw new CertificateException("Empty X.509 chain for " + algorithm);
        }
        if (!MessageDigest.isEqual(keyPair.getPublic().getEncoded(), first.getPublicKey().getEncoded())) {
            throw new CertificateException("Private key does not match first " + algorithm + " certificate");
        }

        for (Certificate certificate : chain) {
            if (!(certificate instanceof X509Certificate issuer) || issuer.getBasicConstraints() < 0) {
                throw new CertificateException("Non-CA certificate in " + algorithm + " chain");
            }
            boolean[] usage = issuer.getKeyUsage();
            if (usage != null && (usage.length <= 5 || !usage[5])) {
                throw new CertificateException("Certificate cannot sign in " + algorithm + " chain");
            }
        }

        for (int i = 0; i + 1 < chain.size(); i++) {
            if (!(chain.get(i) instanceof X509Certificate child)
                    || !(chain.get(i + 1) instanceof X509Certificate issuer)) {
                throw new CertificateException("Non-X.509 certificate in " + algorithm + " chain");
            }
            if (!child.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new CertificateException("Issuer mismatch at certificate " + i);
            }
            child.verify(issuer.getPublicKey());
        }
    }

    Entry get(String algorithmKey) {
        return byAlgorithm.get(algorithmKey);
    }

    Entry forAlgorithmInt(int keymintAlgorithm) {
        return byAlgorithm.get(keymintAlgorithm == KeymintConst.Algorithm.EC
                ? KeyProperties.KEY_ALGORITHM_EC
                : KeyProperties.KEY_ALGORITHM_RSA);
    }

    boolean isEmpty() {
        return byAlgorithm.isEmpty();
    }

    boolean has(String algorithmKey) {
        return byAlgorithm.containsKey(algorithmKey);
    }
}
