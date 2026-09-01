package com.takattowo.bootloaderspoofer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Adapted from TrickyStore CertHack (yujincheng08/TrickyStore, GPL-3) for
 * libxposed app-level interception.
 */
final class CertHack {

    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    private static final CertificateFactory CERT_FACTORY;

    static {
        try {
            CERT_FACTORY = CertificateFactory.getInstance("X.509");
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static final class Result {
        final KeyPair keyPair;
        final Certificate[] chain;

        Result(KeyPair keyPair, Certificate[] chain) {
            this.keyPair = keyPair;
            this.chain = chain;
        }
    }

    /** Leaf-hack mode: rewrite RoT extension in real leaf, re-sign with keybox. */
    static Certificate[] hackCertificateChain(Certificate[] caList, KeyboxRegistry registry,
                                              String bootState) {
        if (caList == null || caList.length == 0) return caList;
        try {
            X509Certificate leaf = (X509Certificate) CERT_FACTORY.generateCertificate(
                    new ByteArrayInputStream(caList[0].getEncoded()));
            byte[] extBytes = leaf.getExtensionValue(OID.getId());
            if (extBytes == null) return caList;

            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            Extension ext = leafHolder.getExtension(OID);
            ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];
            ASN1EncodableVector vector = new ASN1EncodableVector();
            int attestationVersion = ASN1Integer.getInstance(encodables[0]).intValueExact();
            boolean replacedRootOfTrust = false;

            for (ASN1Encodable asn1Encodable : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
                if (taggedObject.getTagNo() == 704) {
                    byte[] verifiedBootHash = BootKey.getBootHash();
                    try {
                        ASN1Sequence original = ASN1Sequence.getInstance(taggedObject.getBaseObject());
                        if (original.size() >= 4) {
                            verifiedBootHash = ASN1OctetString.getInstance(original.getObjectAt(3)).getOctets();
                        }
                    } catch (Throwable t) {
                        Log.w(ModuleMain.TAG, "failed to extract original boot hash", t);
                    }
                    vector.add(new DERTaggedObject(true, 704,
                            createRootOfTrust(attestationVersion, verifiedBootHash, bootState)));
                    replacedRootOfTrust = true;
                    continue;
                }
                vector.add(taggedObject);
            }
            if (!replacedRootOfTrust) return caList;

            String pubAlgo = leaf.getPublicKey().getAlgorithm();
            KeyboxRegistry.Entry k = registry.get(pubAlgo);
            if (k == null) {
                Log.w(ModuleMain.TAG, "no keybox for algorithm " + pubAlgo);
                return caList;
            }
            LinkedList<Certificate> certificates = new LinkedList<>(k.certificates);

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    new X509CertificateHolder(certificates.get(0).getEncoded()).getSubject(),
                    leafHolder.getSerialNumber(),
                    leafHolder.getNotBefore(),
                    leafHolder.getNotAfter(),
                    leafHolder.getSubject(),
                    leafHolder.getSubjectPublicKeyInfo()
            );
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm(k.keyPair.getPrivate()))
                    .build(k.keyPair.getPrivate());

            ASN1Sequence hackEnforced = new DERSequence(vector);
            encodables[7] = hackEnforced;
            ASN1Sequence hackedSeq = new DERSequence(encodables);

            ASN1OctetString hackedSeqOctets = new DEROctetString(hackedSeq);
            Extension hackedExt = new Extension(OID, ext.isCritical(), hackedSeqOctets);
            builder.addExtension(hackedExt);

            for (ASN1ObjectIdentifier extensionOID : leafHolder.getExtensions().getExtensionOIDs()) {
                if (OID.equals(extensionOID) || Extension.authorityKeyIdentifier.equals(extensionOID)) continue;
                builder.addExtension(leafHolder.getExtension(extensionOID));
            }
            certificates.addFirst(new JcaX509CertificateConverter().getCertificate(builder.build(signer)));
            return certificates.toArray(new Certificate[0]);
        } catch (Throwable t) {
            Log.e(ModuleMain.TAG, "hackCertificateChain failed", t);
            return caList;
        }
    }

    /** Cert-generate mode: build new keypair + leaf entirely. Works on broken TEE. */
    static Result generateLeaf(KeyGenParameters params, KeyboxRegistry registry,
                               String bootState) {
        try {
            KeyboxRegistry.Entry k = registry.forAlgorithmInt(params.algorithm);
            if (k == null) {
                Log.e(ModuleMain.TAG, "no keybox for keymint algorithm " + params.algorithm);
                return null;
            }

            KeyPair kp;
            if (params.algorithm == KeymintConst.Algorithm.EC) {
                kp = buildECKeyPair(params);
            } else if (params.algorithm == KeymintConst.Algorithm.RSA) {
                kp = buildRSAKeyPair(params);
            } else {
                Log.e(ModuleMain.TAG, "unsupported algorithm " + params.algorithm);
                return null;
            }

            X500Name issuer = new X509CertificateHolder(k.certificates.get(0).getEncoded()).getSubject();

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    issuer,
                    params.certificateSerial,
                    params.certificateNotBefore,
                    params.certificateNotAfter,
                    params.certificateSubject,
                    kp.getPublic()
            );

            int keyUsage = keyUsage(params.purpose);
            if (keyUsage != 0) {
                certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
            }
            certBuilder.addExtension(createKeyDescriptionExtension(params, bootState));

            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm(k.keyPair.getPrivate()))
                    .build(k.keyPair.getPrivate());

            X509CertificateHolder certHolder = certBuilder.build(signer);
            X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(certHolder);

            List<Certificate> chain = new ArrayList<>(k.certificates.size() + 1);
            chain.add(leaf);
            chain.addAll(k.certificates);
            return new Result(kp, chain.toArray(new Certificate[0]));
        } catch (Throwable t) {
            Log.e(ModuleMain.TAG, "generateLeaf failed", t);
            return null;
        }
    }

    private static KeyPair buildECKeyPair(KeyGenParameters params) throws Exception {
        ECGenParameterSpec spec = new ECGenParameterSpec(params.ecCurveName);
        KeyPairGenerator kpg = pickKpg("EC");
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static KeyPair buildRSAKeyPair(KeyGenParameters params) throws Exception {
        RSAKeyGenParameterSpec spec = new RSAKeyGenParameterSpec(params.keySize, params.rsaPublicExponent);
        KeyPairGenerator kpg = pickKpg("RSA");
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    /**
     * Pick a {@link KeyPairGenerator} skipping AndroidKeyStore (we're spoofing it) so we
     * fall back to AndroidOpenSSL / Conscrypt. Avoids signed-JAR verification of BC under R8.
     */
    private static KeyPairGenerator pickKpg(String algorithm) throws Exception {
        for (String provider : Arrays.asList("AndroidOpenSSL", "Conscrypt")) {
            try {
                return KeyPairGenerator.getInstance(algorithm, provider);
            } catch (Throwable ignored) {
            }
        }
        return KeyPairGenerator.getInstance(algorithm);
    }

    private static Extension createKeyDescriptionExtension(KeyGenParameters params,
                                                           String bootState) throws IOException {
        if (params.attestationChallenge == null) {
            throw new IllegalArgumentException("attestation challenge is required");
        }

        int attestationVersion = BootKey.getAttestationVersion();
        ASN1OctetString applicationId = createApplicationId();
        if (applicationId == null) {
            throw new IllegalStateException("attestation application ID is unavailable");
        }

        List<ASN1Encodable> tee = new ArrayList<>();
        tee.add(tag(1, new DERSet(fromIntList(params.purpose))));
        tee.add(tag(2, new ASN1Integer(params.algorithm)));
        tee.add(tag(3, new ASN1Integer(params.keySize)));
        if (!params.digest.isEmpty()) {
            tee.add(tag(5, new DERSet(fromIntList(params.digest))));
        }
        if (params.algorithm == KeymintConst.Algorithm.RSA && !params.padding.isEmpty()) {
            tee.add(tag(6, new DERSet(fromIntList(params.padding))));
        }
        if (params.algorithm == KeymintConst.Algorithm.EC) {
            tee.add(tag(10, new ASN1Integer(params.ecCurve)));
        } else {
            tee.add(tag(200, new ASN1Integer(params.rsaPublicExponent)));
        }

        if (params.userAuthenticationRequired) {
            tee.add(tag(504, new ASN1Integer(params.userAuthenticationType)));
            if (params.userAuthenticationTimeoutSeconds > 0) {
                tee.add(tag(505, new ASN1Integer(params.userAuthenticationTimeoutSeconds)));
            }
            if (params.userAuthenticationValidWhileOnBody) tee.add(tag(506, DERNull.INSTANCE));
            if (params.userPresenceRequired) tee.add(tag(507, DERNull.INSTANCE));
            if (params.userConfirmationRequired) tee.add(tag(508, DERNull.INSTANCE));
            if (params.unlockedDeviceRequired) tee.add(tag(509, DERNull.INSTANCE));
        } else {
            tee.add(tag(503, DERNull.INSTANCE));
        }

        tee.add(tag(702, new ASN1Integer(0)));
        tee.add(tag(704, createRootOfTrust(attestationVersion, BootKey.getBootHash(), bootState)));
        tee.add(tag(705, new ASN1Integer(BootKey.getOsVersion())));
        tee.add(tag(706, new ASN1Integer(BootKey.getPatchLevel())));
        if (attestationVersion >= 3) {
            Long vendorPatchLevel = BootKey.getVendorPatchLevel();
            Long bootPatchLevel = BootKey.getBootPatchLevel();
            if (vendorPatchLevel != null) tee.add(tag(718, new ASN1Integer(vendorPatchLevel)));
            if (bootPatchLevel != null) tee.add(tag(719, new ASN1Integer(bootPatchLevel)));
        }

        ASN1Encodable[] software = {
                tag(701, new ASN1Integer(System.currentTimeMillis())),
                tag(709, applicationId)
        };
        return new Extension(OID, false, wrapKeyDescription(
                tee.toArray(new ASN1Encodable[0]), software, params));
    }

    private static ASN1OctetString wrapKeyDescription(ASN1Encodable[] tee, ASN1Encodable[] sw, KeyGenParameters params) throws IOException {
        ASN1Integer attestationVersion = new ASN1Integer(BootKey.getAttestationVersion());
        ASN1Enumerated attestationSecurityLevel = new ASN1Enumerated(1);
        ASN1Integer keymasterVersion = new ASN1Integer(BootKey.getKeymasterVersion());
        ASN1Enumerated keymasterSecurityLevel = new ASN1Enumerated(1);
        ASN1OctetString attestationChallenge = new DEROctetString(params.attestationChallenge);
        ASN1OctetString uniqueId = new DEROctetString(new byte[0]);
        ASN1Encodable softwareEnforced = new DERSequence(sw);
        ASN1Sequence teeEnforced = new DERSequence(tee);

        ASN1Encodable[] keyDescriptionEncodables = {
                attestationVersion, attestationSecurityLevel, keymasterVersion, keymasterSecurityLevel,
                attestationChallenge, uniqueId, softwareEnforced, teeEnforced
        };
        return new DEROctetString(new DERSequence(keyDescriptionEncodables));
    }

    private static ASN1Sequence createRootOfTrust(int attestationVersion, byte[] verifiedBootHash,
                                                  String bootState) {
        boolean unlocked = Config.BOOTSTATE_UNLOCKED.equals(bootState);
        ASN1EncodableVector values = new ASN1EncodableVector();
        values.add(new DEROctetString(BootKey.getBootKey()));
        values.add(ASN1Boolean.getInstance(!unlocked));
        values.add(new ASN1Enumerated(unlocked ? 2 : 0));
        if (attestationVersion >= 3) values.add(new DEROctetString(verifiedBootHash));
        return new DERSequence(values);
    }

    private static DERTaggedObject tag(int number, ASN1Encodable value) {
        return new DERTaggedObject(true, number, value);
    }

    private static int keyUsage(List<Integer> purposes) {
        int usage = 0;
        for (int purpose : purposes) {
            switch (purpose) {
                case KeymintConst.KeyPurpose.ENCRYPT, KeymintConst.KeyPurpose.DECRYPT ->
                        usage |= KeyUsage.keyEncipherment | KeyUsage.dataEncipherment;
                case KeymintConst.KeyPurpose.SIGN, KeymintConst.KeyPurpose.VERIFY ->
                        usage |= KeyUsage.digitalSignature;
                case KeymintConst.KeyPurpose.WRAP_KEY -> usage |= KeyUsage.keyEncipherment;
                case KeymintConst.KeyPurpose.AGREE_KEY -> usage |= KeyUsage.keyAgreement;
                case KeymintConst.KeyPurpose.ATTEST_KEY -> usage |= KeyUsage.keyCertSign;
                default -> {
                }
            }
        }
        return usage;
    }

    private static String signatureAlgorithm(PrivateKey key) {
        String algorithm = key.getAlgorithm();
        if ("EC".equalsIgnoreCase(algorithm) || "ECDSA".equalsIgnoreCase(algorithm)) {
            return "SHA256withECDSA";
        }
        if ("RSA".equalsIgnoreCase(algorithm)) return "SHA256withRSA";
        throw new IllegalArgumentException("Unsupported signing key algorithm " + algorithm);
    }

    private static ASN1OctetString createApplicationId() {
        try {
            Context ctx = currentApplication();
            if (ctx == null) return null;
            PackageManager pm = ctx.getPackageManager();
            int uid = Process.myUid();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) {
                packages = new String[]{ctx.getPackageName()};
            }
            ASN1Encodable[] packageInfoAA = new ASN1Encodable[packages.length];
            Set<Digest> signatures = new HashSet<>();
            MessageDigest dg = MessageDigest.getInstance("SHA-256");

            for (int i = 0; i < packages.length; i++) {
                String name = packages[i];
                PackageInfo info = loadPackageInfo(pm, name);
                ASN1Encodable[] arr = new ASN1Encodable[2];
                arr[0] = new DEROctetString(name.getBytes(StandardCharsets.UTF_8));
                long versionCode = info == null ? 0L : Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? info.getLongVersionCode() : info.versionCode;
                arr[1] = new ASN1Integer(versionCode);
                packageInfoAA[i] = new DERSequence(arr);

                for (byte[] sigBytes : extractSignatureBytes(info)) {
                    signatures.add(new Digest(dg.digest(sigBytes)));
                }
            }

            ASN1Encodable[] signaturesAA = new ASN1Encodable[signatures.size()];
            int i = 0;
            for (Digest d : signatures) {
                signaturesAA[i++] = new DEROctetString(d.digest);
            }
            ASN1Encodable[] applicationIdAA = {
                    new DERSet(packageInfoAA),
                    new DERSet(signaturesAA)
            };
            return new DEROctetString(new DERSequence(applicationIdAA).getEncoded());
        } catch (Throwable t) {
            Log.w(ModuleMain.TAG, "createApplicationId failed", t);
            return null;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> ath = Class.forName("android.app.ActivityThread");
            Method m = ath.getDeclaredMethod("currentApplication");
            Object app = m.invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            Log.w(ModuleMain.TAG, "currentApplication reflection failed: " + t);
            return null;
        }
    }

    private static PackageInfo loadPackageInfo(PackageManager pm, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
            }
            return pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<byte[]> extractSignatureBytes(PackageInfo info) {
        List<byte[]> out = new ArrayList<>();
        if (info == null) return out;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            Signature[] sigs = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            if (sigs != null) {
                for (Signature s : sigs) out.add(s.toByteArray());
            }
        } else if (info.signatures != null) {
            for (Signature s : info.signatures) out.add(s.toByteArray());
        }
        return out;
    }

    private static ASN1Encodable[] fromIntList(List<Integer> list) {
        ASN1Encodable[] result = new ASN1Encodable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = new ASN1Integer(list.get(i));
        }
        return result;
    }

    private record Digest(byte[] digest) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Digest d && Arrays.equals(digest, d.digest);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(digest);
        }
    }

    private CertHack() {}
}
