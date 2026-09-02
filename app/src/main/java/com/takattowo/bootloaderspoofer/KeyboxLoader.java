package com.takattowo.bootloaderspoofer;

import android.security.keystore.KeyProperties;
import android.util.Log;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.util.io.pem.PemReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Loads keyboxes into a KeyboxRegistry from TrickyStore-format XML, with per-algorithm
 * fallback to bundled {@link KeyboxData} for any algorithm missing from the user file.
 *
 * Caller-agnostic - safe to use from module process (UI) or target process.
 */
final class KeyboxLoader {

    static final int MAX_XML_BYTES = 1024 * 1024;

    static final class Result {
        final KeyboxRegistry registry;
        final String source;
        final boolean userEC;
        final boolean userRSA;
        final String ecExpiry;
        final String rsaExpiry;
        final boolean ecExpired;
        final boolean rsaExpired;

        Result(KeyboxRegistry registry, String source,
               boolean userEC, boolean userRSA,
               String ecExpiry, String rsaExpiry,
               boolean ecExpired, boolean rsaExpired) {
            this.registry = registry;
            this.source = source;
            this.userEC = userEC;
            this.userRSA = userRSA;
            this.ecExpiry = ecExpiry;
            this.rsaExpiry = rsaExpiry;
            this.ecExpired = ecExpired;
            this.rsaExpired = rsaExpired;
        }
    }

    static Result loadFromXmlOrBundled(String xml) {
        KeyboxRegistry registry = null;
        boolean ec = false;
        boolean rsa = false;

        if (xml != null && !xml.trim().isEmpty()) {
            try {
                registry = parseUserXml(xml);
                ec = registry.has(KeyProperties.KEY_ALGORITHM_EC);
                rsa = registry.has(KeyProperties.KEY_ALGORITHM_RSA);
            } catch (Throwable t) {
                Log.w(ModuleMain.TAG, "parse keybox.xml failed; will fall back to bundled", t);
            }
        }
        if (registry == null) registry = new KeyboxRegistry();

        boolean userEC = ec;
        boolean userRSA = rsa;

        if (!registry.has(KeyProperties.KEY_ALGORITHM_EC)) {
            try {
                registry.put(KeyProperties.KEY_ALGORITHM_EC,
                        parseKeyPair(KeyboxData.EC.PRIVATE_KEY),
                        parseChain(KeyboxData.EC.CERTIFICATE_1, KeyboxData.EC.CERTIFICATE_2));
            } catch (Throwable t) {
                Log.e(ModuleMain.TAG, "bundled EC parse failed", t);
            }
        }
        if (!registry.has(KeyProperties.KEY_ALGORITHM_RSA)) {
            try {
                registry.put(KeyProperties.KEY_ALGORITHM_RSA,
                        parseKeyPair(KeyboxData.RSA.PRIVATE_KEY),
                        parseChain(KeyboxData.RSA.CERTIFICATE_1, KeyboxData.RSA.CERTIFICATE_2));
            } catch (Throwable t) {
                Log.e(ModuleMain.TAG, "bundled RSA parse failed", t);
            }
        }

        String source;
        if (userEC && userRSA) source = "user keybox.xml";
        else if (userEC || userRSA) source = "user keybox.xml + bundled (mixed)";
        else source = "bundled";

        Date now = new Date();
        ExpiryInfo ecInfo = expiry(registry.get(KeyProperties.KEY_ALGORITHM_EC), now);
        ExpiryInfo rsaInfo = expiry(registry.get(KeyProperties.KEY_ALGORITHM_RSA), now);

        if (ecInfo.expired) {
            Log.e(ModuleMain.TAG, "EC keybox chain is EXPIRED (" + ecInfo.text
                    + "). Attestation will be rejected. Supply a fresh keybox.xml via the UI.");
        }
        if (rsaInfo.expired) {
            Log.e(ModuleMain.TAG, "RSA keybox chain is EXPIRED (" + rsaInfo.text
                    + "). Attestation will be rejected. Supply a fresh keybox.xml via the UI.");
        }

        return new Result(registry, source, userEC, userRSA,
                ecInfo.text, rsaInfo.text, ecInfo.expired, rsaInfo.expired);
    }

    static KeyboxRegistry validateUserXml(String xml) throws Exception {
        return parseUserXml(xml);
    }

    private static KeyboxRegistry parseUserXml(String xml) throws Exception {
        if (xml == null || xml.trim().isEmpty()) throw new IllegalArgumentException("Empty keybox");
        if (xml.length() > MAX_XML_BYTES
                || xml.getBytes(StandardCharsets.UTF_8).length > MAX_XML_BYTES) {
            throw new IllegalArgumentException("Keybox exceeds 1 MiB");
        }
        if (xml.contains("<!DOCTYPE")) {
            throw new IllegalArgumentException("DOCTYPE is not allowed");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        Element root = document.getDocumentElement();
        if (root == null || !"AndroidAttestation".equals(root.getTagName())) {
            throw new IllegalArgumentException("Missing AndroidAttestation root");
        }
        int declaredKeyboxes = Integer.parseInt(text(requiredChild(root, "NumberOfKeyboxes")));
        List<Element> keyboxes = children(root, "Keybox");
        if (declaredKeyboxes != 1 || keyboxes.size() != 1) {
            throw new IllegalArgumentException("Exactly one Keybox is required");
        }

        List<Element> keys = children(keyboxes.get(0), "Key");
        if (keys.isEmpty()) throw new IllegalArgumentException("Keybox contains no keys");

        KeyboxRegistry registry = new KeyboxRegistry();
        for (Element keyElement : keys) {
            String declaredAlgorithm = keyElement.getAttribute("algorithm");
            String algorithm;
            if ("ecdsa".equalsIgnoreCase(declaredAlgorithm)
                    || "ec".equalsIgnoreCase(declaredAlgorithm)) {
                algorithm = KeyProperties.KEY_ALGORITHM_EC;
            } else if ("rsa".equalsIgnoreCase(declaredAlgorithm)) {
                algorithm = KeyProperties.KEY_ALGORITHM_RSA;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm " + declaredAlgorithm);
            }
            if (registry.has(algorithm)) {
                throw new IllegalArgumentException("Duplicate " + algorithm + " key");
            }

            String privateKey = requiredChild(keyElement, "PrivateKey").getTextContent();
            Element chainElement = requiredChild(keyElement, "CertificateChain");
            int declaredCertificates = Integer.parseInt(text(
                    requiredChild(chainElement, "NumberOfCertificates")));
            List<Element> certificateElements = children(chainElement, "Certificate");
            if (declaredCertificates <= 0 || declaredCertificates != certificateElements.size()) {
                throw new IllegalArgumentException("Certificate count mismatch for " + algorithm);
            }

            LinkedList<Certificate> chain = new LinkedList<>();
            for (Element certificateElement : certificateElements) {
                chain.add(parseCert(certificateElement.getTextContent()));
            }
            registry.put(algorithm, parseKeyPair(privateKey), chain);
        }
        return registry;
    }

    private static Element requiredChild(Element parent, String name) {
        List<Element> matches = children(parent, name);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Expected one " + name + " element");
        }
        return matches.get(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }

    private static final class ExpiryInfo {
        final String text;
        final boolean expired;

        ExpiryInfo(String text, boolean expired) {
            this.text = text;
            this.expired = expired;
        }
    }

    private static ExpiryInfo expiry(KeyboxRegistry.Entry e, Date now) {
        if (e == null || e.certificates == null || e.certificates.isEmpty()) {
            return new ExpiryInfo("n/a", false);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Date earliest = null;
        boolean anyExpired = false;
        for (Certificate c : e.certificates) {
            if (!(c instanceof X509Certificate x)) continue;
            Date notAfter = x.getNotAfter();
            if (notAfter.before(now)) anyExpired = true;
            if (earliest == null || notAfter.before(earliest)) earliest = notAfter;
        }
        if (earliest == null) return new ExpiryInfo("unknown", false);
        return new ExpiryInfo(fmt.format(earliest) + (anyExpired ? " EXPIRED" : ""), anyExpired);
    }

    private static LinkedList<Certificate> parseChain(String... pems) throws Exception {
        LinkedList<Certificate> out = new LinkedList<>();
        for (String pem : pems) out.add(parseCert(pem));
        return out;
    }

    private static PEMKeyPair parseKeyPair(String key) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(normalizePem(key)))) {
            Object parsed = parser.readObject();
            if (!(parsed instanceof PEMKeyPair keyPair)) {
                throw new IllegalArgumentException("PrivateKey is not an EC/RSA PEM key pair");
            }
            return keyPair;
        }
    }

    private static Certificate parseCert(String cert) throws Exception {
        try (PemReader reader = new PemReader(new StringReader(normalizePem(cert)))) {
            var pem = reader.readPemObject();
            if (pem == null) throw new IllegalArgumentException("Invalid certificate PEM");
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getContent()));
        }
    }

    private static String normalizePem(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        // Fast path: if no carriage returns, the value is already normalized
        if (trimmed.indexOf('\r') < 0) return trimmed;
        String[] lines = trimmed.split("\\r?\\n");
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (String line : lines) {
            if (normalized.length() > 0) normalized.append('\n');
            normalized.append(line.trim());
        }
        return normalized.toString();
    }

    private KeyboxLoader() {}
}
