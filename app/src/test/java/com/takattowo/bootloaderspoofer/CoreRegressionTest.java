package com.takattowo.bootloaderspoofer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.cert.CertificateException;

public class CoreRegressionTest {

    @Test
    public void mapsReleaseNativeAttestationVersions() {
        int[][] expected = {
                {26, 80000, 2, 3},
                {27, 81000, 2, 3},
                {28, 90000, 3, 4},
                {29, 100000, 4, 41},
                {30, 110000, 4, 41},
                {31, 120000, 100, 100},
                {32, 120000, 100, 100},
                {33, 130000, 200, 200},
                {34, 140000, 300, 300},
                {35, 150000, 300, 300},
                {36, 160000, 400, 400},
                {37, 170000, 500, 500}
        };

        for (int[] row : expected) {
            assertEquals(row[1], BootKey.getOsVersion(row[0]));
            assertEquals(row[2], BootKey.getAttestationVersion(row[0]));
            assertEquals(row[3], BootKey.getKeymasterVersion(row[0]));
        }
    }

    @Test
    public void loadsBothKeysFromOneKeybox() throws Exception {
        String xml = "<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes>"
                + "<Keybox DeviceID=\"any\">"
                + key("ecdsa", KeyboxData.EC.PRIVATE_KEY,
                KeyboxData.EC.CERTIFICATE_1, KeyboxData.EC.CERTIFICATE_2)
                + key("rsa", KeyboxData.RSA.PRIVATE_KEY,
                KeyboxData.RSA.CERTIFICATE_1, KeyboxData.RSA.CERTIFICATE_2)
                + "</Keybox></AndroidAttestation>";

        KeyboxRegistry registry = KeyboxLoader.validateUserXml(xml);

        assertTrue(registry.has("EC"));
        assertTrue(registry.has("RSA"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDoctype() throws Exception {
        KeyboxLoader.validateUserXml("<!DOCTYPE AndroidAttestation><AndroidAttestation/>");
    }

    @Test
    public void rejectsMislabeledKeyMaterial() throws Exception {
        String xml = "<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes>"
                + "<Keybox DeviceID=\"any\">"
                + key("rsa", KeyboxData.EC.PRIVATE_KEY,
                KeyboxData.EC.CERTIFICATE_1, KeyboxData.EC.CERTIFICATE_2)
                + "</Keybox></AndroidAttestation>";

        try {
            KeyboxLoader.validateUserXml(xml);
            fail("mislabeled key material was accepted");
        } catch (CertificateException expected) {
        }
    }

    private static String key(String algorithm, String privateKey,
                              String certificate1, String certificate2) {
        return "<Key algorithm=\"" + algorithm + "\"><PrivateKey format=\"pem\">"
                + privateKey + "</PrivateKey><CertificateChain>"
                + "<NumberOfCertificates>2</NumberOfCertificates>"
                + "<Certificate format=\"pem\">" + certificate1 + "</Certificate>"
                + "<Certificate format=\"pem\">" + certificate2 + "</Certificate>"
                + "</CertificateChain></Key>";
    }
}
