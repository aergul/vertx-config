package io.vertx.config.vault.utils;

import org.apache.commons.io.FileUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcContentSignerBuilder;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class Certificates {

  private static File SSL_DIRECTORY = new File("target/vault/config/ssl");
  private static final File CERT_PEMFILE = new File(SSL_DIRECTORY, "cert.pem");
  private static final File PRIVATE_KEY_PEMFILE = new File(SSL_DIRECTORY, "privatekey.pem");
  private static final File CLIENT_CERT_PEMFILE = new File(SSL_DIRECTORY, "client-cert.pem");
  private static final File CLIENT_PRIVATE_KEY_PEMFILE = new File(SSL_DIRECTORY, "client-privatekey.pem");
  private static final File CLIENT_KEYSTORE = new File(SSL_DIRECTORY, "keystore.jks");
  private static final File CLIENT_TRUSTSTORE = new File(SSL_DIRECTORY, "truststore.jks");

  private static X509Certificate vaultCertificate;

  /**
   * Called by the constructor method prior to configuring and launching the Vault instance.  Uses Bouncy Castle
   * (https://www.bouncycastle.org) to programmatically generate a private key and X509 certificate for use by
   * the Vault server instance in accepting SSL connections.
   */
  public static void createVaultCertAndKey() throws Exception {
    if (SSL_DIRECTORY.isDirectory() && CERT_PEMFILE.isFile()) {
      try (FileInputStream fis = new FileInputStream(CERT_PEMFILE)) {
        CertificateFactory fact = CertificateFactory.getInstance("X.509");
        vaultCertificate = (X509Certificate) fact.generateCertificate(fis);
      }
      return;
    }

    SSL_DIRECTORY.mkdirs();

    // Generate a certificate and private key for Vault, and write them to disk in PEM format.  Also store the
    // original X509Certificate object in a member variable, so it can later be used by "createClientCertAndKey()".
    final KeyPair keyPair = generateKeyPair();
    vaultCertificate = generateCert(keyPair, "C=AU, O=The Legion of the Bouncy Castle, OU=Vault Server Certificate, CN=localhost");
    writeCertToPem(vaultCertificate, CERT_PEMFILE);
    writePrivateKeyToPem(keyPair.getPrivate(), PRIVATE_KEY_PEMFILE);
  }

  /**
   * Constructs a Java truststore in JKS format, containing the Vault server certificate generated by
   * {@link #createVaultCertAndKey()}, so that Vault clients configured with this JKS will trust that
   * certificate.
   */
  public static void createClientCertAndKey() throws Exception {
    if (SSL_DIRECTORY.isDirectory() && CLIENT_CERT_PEMFILE.isFile()) {
      return;
    }

    // Store the Vault's server certificate as a trusted cert in the truststore
    final KeyStore trustStore = KeyStore.getInstance("jks");
    trustStore.load(null);
    trustStore.setCertificateEntry("cert", vaultCertificate);
    try (final FileOutputStream keystoreOutputStream = new FileOutputStream(CLIENT_TRUSTSTORE)) {
      trustStore.store(keystoreOutputStream, "password".toCharArray());
    }

    // Generate a client certificate, and store it in a Java keystore
    final KeyPair keyPair = generateKeyPair();
    final X509Certificate clientCertificate =
      generateCert(keyPair, "C=AU, O=The Legion of the Bouncy Castle, OU=Client Certificate, CN=localhost");
    final KeyStore keyStore = KeyStore.getInstance("jks");
    keyStore.load(null);
    keyStore.setKeyEntry("privatekey", keyPair.getPrivate(), "password".toCharArray(), new java.security.cert.Certificate[]{clientCertificate});
    keyStore.setCertificateEntry("cert", clientCertificate);
    try (final FileOutputStream keystoreOutputStream = new FileOutputStream(CLIENT_KEYSTORE)) {
      keyStore.store(keystoreOutputStream, "password".toCharArray());
    }

    // Also write the client certificate to a PEM file, so it can be registered with Vault
    writeCertToPem(clientCertificate, CLIENT_CERT_PEMFILE);
    writePrivateKeyToPem(keyPair.getPrivate(), CLIENT_PRIVATE_KEY_PEMFILE);
  }

  /**
   * See https://www.cryptoworkshop.com/guide/, chapter 3
   *
   * @return A 4096-bit RSA keypair
   * @throws NoSuchAlgorithmException
   */
  private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyPairGenerator.initialize(4096);
    return keyPairGenerator.genKeyPair();
  }

  /**
   * See http://www.programcreek.com/java-api-examples/index.php?api=org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
   *
   * @param keyPair The RSA keypair with which to generate the certificate
   * @param issuer  The issuer (and subject) to use for the certificate
   * @return An X509 certificate
   * @throws IOException
   * @throws OperatorCreationException
   * @throws CertificateException
   * @throws NoSuchProviderException
   * @throws NoSuchAlgorithmException
   * @throws InvalidKeyException
   * @throws SignatureException
   */
  private static X509Certificate generateCert(final KeyPair keyPair, final String issuer) throws IOException, OperatorCreationException,
    CertificateException, NoSuchProviderException, NoSuchAlgorithmException, InvalidKeyException,
    SignatureException {
    final String subject = issuer;
    final X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
      new X500Name(issuer),
      BigInteger.ONE,
      new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30),
      new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 30)),
      new X500Name(subject),
      SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded())
    );

    final GeneralNames subjectAltNames = new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
    certificateBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, subjectAltNames);

    final AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA1WithRSAEncryption");
    final AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);
    final BcContentSignerBuilder signerBuilder = new BcRSAContentSignerBuilder(sigAlgId, digAlgId);
    final AsymmetricKeyParameter keyp = PrivateKeyFactory.createKey(keyPair.getPrivate().getEncoded());
    final ContentSigner signer = signerBuilder.build(keyp);
    final X509CertificateHolder x509CertificateHolder = certificateBuilder.build(signer);

    final X509Certificate certificate = new JcaX509CertificateConverter()
      .getCertificate(x509CertificateHolder);
    certificate.checkValidity(new Date());
    certificate.verify(keyPair.getPublic());
    return certificate;
  }

  /**
   * See https://stackoverflow.com/questions/3313020/write-x509-certificate-into-pem-formatted-string-in-java
   *
   * @param certificate An X509 certificate
   * @param file        the file
   * @throws CertificateEncodingException
   * @throws FileNotFoundException
   */
  private static void writeCertToPem(final X509Certificate certificate, final File file)
    throws CertificateEncodingException, IOException {
    final Base64.Encoder encoder = Base64.getEncoder();

    final String certHeader = "-----BEGIN CERTIFICATE-----\n";
    final String certFooter = "\n-----END CERTIFICATE-----";
    final byte[] certBytes = certificate.getEncoded();
    final String certContents = new String(encoder.encode(certBytes));
    final String certPem = certHeader + certContents + certFooter;
    FileUtils.write(file, certPem);
  }

  /**
   * See https://stackoverflow.com/questions/3313020/write-x509-certificate-into-pem-formatted-string-in-java
   *
   * @param key  An RSA private key
   * @param file a file to which the private key will be written in PEM format
   * @throws FileNotFoundException
   */
  private static void writePrivateKeyToPem(final PrivateKey key, File file) throws IOException {
    final Base64.Encoder encoder = Base64.getEncoder();

    final String keyHeader = "-----BEGIN PRIVATE KEY-----\n";
    final String keyFooter = "\n-----END PRIVATE KEY-----";
    final byte[] keyBytes = key.getEncoded();
    final String keyContents = new String(encoder.encode(keyBytes));
    final String keyPem = keyHeader + keyContents + keyFooter;
    FileUtils.write(file, keyPem);
  }
}
