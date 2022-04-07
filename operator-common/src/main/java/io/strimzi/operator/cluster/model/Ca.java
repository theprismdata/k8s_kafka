/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.strimzi.api.kafka.model.CertificateExpirationPolicy;
import io.strimzi.certs.CertAndKey;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.SecretCertProvider;
import io.strimzi.certs.Subject;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.PasswordGenerator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

/**
 * A Certificate Authority which can renew its own (self-signed) certificates, and generate signed certificates
 */
@SuppressWarnings("checkstyle:CyclomaticComplexity")
public abstract class Ca {

    protected static final ReconciliationLogger LOGGER = ReconciliationLogger.create(Ca.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
            .appendLiteral('-')
            .appendValue(MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(DAY_OF_MONTH, 2)
            .appendLiteral('T')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral('-')
            .appendValue(MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral('-')
            .appendValue(SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(NANO_OF_SECOND, 0, 9, true)
            .optionalStart()
            .appendOffsetId()
            .toFormatter().withChronology(IsoChronology.INSTANCE);
    public static final String CA_KEY = "ca.key";
    public static final String CA_CRT = "ca.crt";
    public static final String CA_STORE = "ca.p12";
    public static final String CA_STORE_PASSWORD = "ca.password";
    public static final String IO_STRIMZI = "io.strimzi";

    public static final String ANNO_STRIMZI_IO_FORCE_REPLACE = Annotations.STRIMZI_DOMAIN + "force-replace";
    public static final String ANNO_STRIMZI_IO_FORCE_RENEW = Annotations.STRIMZI_DOMAIN + "force-renew";
    public static final String ANNO_STRIMZI_IO_CA_KEY_GENERATION = Annotations.STRIMZI_DOMAIN + "ca-key-generation";
    public static final String ANNO_STRIMZI_IO_CA_CERT_GENERATION = Annotations.STRIMZI_DOMAIN + "ca-cert-generation";
    public static final String ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION = Annotations.STRIMZI_DOMAIN + "cluster-ca-cert-generation";
    public static final String ANNO_STRIMZI_IO_CLIENTS_CA_CERT_GENERATION = Annotations.STRIMZI_DOMAIN + "clients-ca-cert-generation";
    public static final int INIT_GENERATION = 0;

    private final PasswordGenerator passwordGenerator;
    protected final Reconciliation reconciliation;
    private Clock clock;

    /**
     * Set the {@code strimzi.io/force-renew} annotation on the given {@code caCert} if the given {@code caKey} has
     * the given {@code key}.
     *
     * This is used to force certificate renewal when upgrading from a Strimzi 0.6.0 Secret.
     */
    protected static Secret forceRenewal(Secret caCert, Secret caKey, String key) {
        if (caCert != null && caKey != null && caKey.getData() != null && caKey.getData().containsKey(key)) {
            caCert = new SecretBuilder(caCert).editMetadata().addToAnnotations(ANNO_STRIMZI_IO_FORCE_RENEW, "true").endMetadata().build();
        }
        return caCert;
    }

    enum RenewalType {
        NOOP() {
            @Override
            public String preDescription(String keySecretName, String certSecretName) {
                return "noop";
            }
            @Override
            public String postDescription(String keySecretName, String certSecretName) {
                return "noop";
            }
        },
        POSTPONED() {
            @Override
            public String preDescription(String keySecretName, String certSecretName) {
                return "CA operation was postponed and will be done in the next maintenance window";
            }
            @Override
            public String postDescription(String keySecretName, String certSecretName) {
                return "postponed";
            }
        },
        CREATE() {
            @Override
            public String preDescription(String keySecretName, String certSecretName) {
                return "CA key (in " + keySecretName + ") and certificate (in " + certSecretName + ") needs to be created";
            }
            @Override
            public String postDescription(String keySecretName, String certSecretName) {
                return "CA key (in " + keySecretName + ") and certificate (in " + certSecretName + ") created";
            }
        },
        RENEW_CERT() {
            @Override
            public String preDescription(String keySecretName, String certSecretName) {
                return "CA certificate (in " + certSecretName + ") needs to be renewed";
            }
            @Override
            public String postDescription(String keySecretName, String certSecretName) {
                return "CA certificate (in " + certSecretName + ") renewed";
            }
        },
        REPLACE_KEY() {
            @Override
            public String preDescription(String keySecretName, String certSecretName) {
                return "CA key (in " + keySecretName + ") needs to be replaced";
            }
            @Override
            public String postDescription(String keySecretName, String certSecretName) {
                return "CA key (in " + keySecretName + ") replaced";
            }
        };

        RenewalType() {
        }

        public abstract String preDescription(String keySecretName, String certSecretName);
        public abstract String postDescription(String keySecretName, String certSecretName);
    }

    protected final String commonName;
    protected final CertManager certManager;
    protected final int validityDays;
    protected final int renewalDays;
    private final boolean generateCa;
    protected String caCertSecretName;
    private Secret caCertSecret;
    protected String caKeySecretName;
    private Secret caKeySecret;
    private RenewalType renewalType;
    private boolean caCertsRemoved;
    private final CertificateExpirationPolicy policy;

    public Ca(Reconciliation reconciliation, CertManager certManager, PasswordGenerator passwordGenerator, String commonName,
              String caCertSecretName, Secret caCertSecret,
              String caKeySecretName, Secret caKeySecret,
              int validityDays, int renewalDays, boolean generateCa, CertificateExpirationPolicy policy) {
        this.reconciliation = reconciliation;
        this.commonName = commonName;
        this.caCertSecret = caCertSecret;
        this.caCertSecretName = caCertSecretName;
        this.caKeySecret = caKeySecret;
        this.caKeySecretName = caKeySecretName;
        this.certManager = certManager;
        this.passwordGenerator = passwordGenerator;
        this.validityDays = validityDays;
        this.renewalDays = renewalDays;
        this.generateCa = generateCa;
        this.policy = policy == null ? CertificateExpirationPolicy.RENEW_CERTIFICATE : policy;
        this.renewalType = RenewalType.NOOP;
        this.clock = Clock.systemUTC();
    }

    /* test */ protected void setClock(Clock clock) {
        this.clock = clock;
    }

    private static void delete(Reconciliation reconciliation, File file) {
        if (!file.delete()) {
            LOGGER.warnCr(reconciliation, "{} cannot be deleted", file.getName());
        }
    }

    /**
     * Returns the given {@code cert} and {@code key} values from the given {@code Secret} as a {@code CertAndKey},
     * or null if the given {@code secret} is null.
     * An exception is thrown if the given {@code secret} is non-null, but does not contain the given
     * entries in its {@code data}.
     *
     * @param secret The secret.
     * @param key The key.
     * @param cert The cert.
     * @param keyStore The keyStore.
     * @param keyStorePassword The store password.
     * @return The CertAndKey.
     */
    public static CertAndKey asCertAndKey(Secret secret, String key, String cert, String keyStore, String keyStorePassword) {
        Base64.Decoder decoder = Base64.getDecoder();
        if (secret == null || secret.getData() == null) {
            return null;
        } else {
            String keyData = secret.getData().get(key);
            if (keyData == null) {
                throw new RuntimeException("The Secret " + secret.getMetadata().getNamespace() + "/" + secret.getMetadata().getName() + " is missing the key " + key);
            }
            String certData = secret.getData().get(cert);
            if (certData == null) {
                throw new RuntimeException("The Secret " + secret.getMetadata().getNamespace() + "/" + secret.getMetadata().getName() + " is missing the key " + cert);
            }
            return new CertAndKey(
                    decoder.decode(keyData),
                    decoder.decode(certData),
                    null,
                    decoder.decode(secret.getData().get(keyStore)),
                    new String(decoder.decode(secret.getData().get(keyStorePassword)), StandardCharsets.US_ASCII));
        }
    }

    public CertAndKey addKeyAndCertToKeyStore(String alias, byte[] key, byte[] cert) throws IOException {

        File keyFile = File.createTempFile("tls", "key");
        File certFile = File.createTempFile("tls", "cert");
        File keyStoreFile = File.createTempFile("tls", "p12");

        Files.write(keyFile.toPath(), key);
        Files.write(certFile.toPath(), cert);

        String keyStorePassword = passwordGenerator.generate();
        certManager.addKeyAndCertToKeyStore(keyFile, certFile, alias, keyStoreFile, keyStorePassword);

        CertAndKey result = new CertAndKey(
                Files.readAllBytes(keyFile.toPath()),
                Files.readAllBytes(certFile.toPath()),
                null,
                Files.readAllBytes(keyStoreFile.toPath()),
                keyStorePassword);

        delete(reconciliation, keyFile);
        delete(reconciliation, certFile);
        delete(reconciliation, keyStoreFile);

        return result;
    }

    /*test*/ CertAndKey generateSignedCert(Subject subject,
                                           File csrFile, File keyFile, File certFile, File keyStoreFile) throws IOException {
        LOGGER.debugCr(reconciliation, "Generating certificate {} with SAN {}, signed by CA {}", subject, subject.subjectAltNames(), this);

        certManager.generateCsr(keyFile, csrFile, subject);
        certManager.generateCert(csrFile, currentCaKey(), currentCaCertBytes(),
                certFile, subject, validityDays);

        String keyStorePassword = passwordGenerator.generate();
        certManager.addKeyAndCertToKeyStore(keyFile, certFile, subject.commonName(), keyStoreFile, keyStorePassword);

        return new CertAndKey(
                Files.readAllBytes(keyFile.toPath()),
                Files.readAllBytes(certFile.toPath()),
                null,
                Files.readAllBytes(keyStoreFile.toPath()),
                keyStorePassword);
    }

    /**
     * Generates a certificate signed by this CA
     *
     * @param commonName The CN of the certificate to be generated.
     * @return The CertAndKey
     * @throws IOException If the cert could not be generated.
     */
    public CertAndKey generateSignedCert(String commonName) throws IOException {
        return generateSignedCert(commonName, null);
    }

    /**
     * Generates a certificate signed by this CA
     *
     * @param commonName The CN of the certificate to be generated.
     * @param organization The O of the certificate to be generated. May be null.
     * @return The CertAndKey
     * @throws IOException If the cert could not be generated.
     */
    public CertAndKey generateSignedCert(String commonName, String organization) throws IOException {
        File csrFile = File.createTempFile("tls", "csr");
        File keyFile = File.createTempFile("tls", "key");
        File certFile = File.createTempFile("tls", "cert");
        File keyStoreFile = File.createTempFile("tls", "p12");

        Subject.Builder subject = new Subject.Builder();

        if (organization != null) {
            subject.withOrganizationName(organization);
        }

        subject.withCommonName(commonName);

        CertAndKey result = generateSignedCert(subject.build(),
                csrFile, keyFile, certFile, keyStoreFile);

        delete(reconciliation, csrFile);
        delete(reconciliation, keyFile);
        delete(reconciliation, certFile);
        delete(reconciliation, keyStoreFile);
        return result;
    }

    /**
     * Copy already existing certificates from provided Secret based on number of effective replicas
     * and maybe generate new ones for new replicas (i.e. scale-up).
     */
    protected Map<String, CertAndKey> maybeCopyOrGenerateCerts(
           Reconciliation reconciliation,
           int replicas,
           Function<Integer, Subject> subjectFn,
           Secret secret,
           Function<Integer, String> podNameFn,
           boolean isMaintenanceTimeWindowsSatisfied) throws IOException {
        int replicasInSecret;
        if (secret == null || secret.getData() == null || this.certRenewed())   {
            replicasInSecret = 0;
        } else {
            replicasInSecret = (int) secret.getData().keySet().stream().filter(k -> k.contains(".crt")).count();
        }

        File brokerCsrFile = File.createTempFile("tls", "broker-csr");
        File brokerKeyFile = File.createTempFile("tls", "broker-key");
        File brokerCertFile = File.createTempFile("tls", "broker-cert");
        File brokerKeyStoreFile = File.createTempFile("tls", "broker-p12");

        int replicasInNewSecret = Math.min(replicasInSecret, replicas);
        Map<String, CertAndKey> certs = new HashMap<>(replicasInNewSecret);
        // copying the minimum number of certificates already existing in the secret
        // scale up -> it will copy all certificates
        // scale down -> it will copy just the requested number of replicas
        for (int i = 0; i < replicasInNewSecret; i++) {
            String podName = podNameFn.apply(i);
            LOGGER.debugCr(reconciliation, "Certificate for {} already exists", podName);
            Subject subject = subjectFn.apply(i);

            CertAndKey certAndKey;
            if (secret.getData().get(podName + ".p12") != null &&
                    !secret.getData().get(podName + ".p12").isEmpty() &&
                    secret.getData().get(podName + ".password") != null &&
                    !secret.getData().get(podName + ".password").isEmpty()) {

                certAndKey = asCertAndKey(secret,
                        podName + ".key", podName + ".crt",
                        podName + ".p12", podName + ".password");
            } else {
                // coming from an older operator version, the secret exists but without keystore and password
                certAndKey = addKeyAndCertToKeyStore(subject.commonName(),
                        Base64.getDecoder().decode(secret.getData().get(podName + ".key")),
                        Base64.getDecoder().decode(secret.getData().get(podName + ".crt")));
            }

            List<String> reasons = new ArrayList<>(2);

            if (certSubjectChanged(certAndKey, subject, podName))   {
                reasons.add("DNS names changed");
            }

            if (isExpiring(secret, podName + ".crt") && isMaintenanceTimeWindowsSatisfied)  {
                reasons.add("certificate is expiring");
            }

            if (renewalType.equals(RenewalType.CREATE)) {
                reasons.add("certificate added");
            }

            if (!reasons.isEmpty())  {
                LOGGER.debugCr(reconciliation, "Certificate for pod {} need to be regenerated because: {}", podName, String.join(", ", reasons));

                CertAndKey newCertAndKey = generateSignedCert(subject, brokerCsrFile, brokerKeyFile, brokerCertFile, brokerKeyStoreFile);
                certs.put(podName, newCertAndKey);
            }   else {
                certs.put(podName, certAndKey);
            }
        }

        // generate the missing number of certificates
        // scale up -> generate new certificates for added replicas
        // scale down -> does nothing
        for (int i = replicasInSecret; i < replicas; i++) {
            String podName = podNameFn.apply(i);

            LOGGER.debugCr(reconciliation, "Certificate for pod {} to generate", podName);
            CertAndKey k = generateSignedCert(subjectFn.apply(i),
                    brokerCsrFile, brokerKeyFile, brokerCertFile, brokerKeyStoreFile);
            certs.put(podName, k);
        }
        delete(reconciliation, brokerCsrFile);
        delete(reconciliation, brokerKeyFile);
        delete(reconciliation, brokerCertFile);
        delete(reconciliation, brokerKeyStoreFile);

        return certs;
    }

    /**
     * Returns whether the certificate is expiring or not
     *
     * @param secret  Secret with the certificate
     * @param certKey   Key under which is the certificate stored
     * @return  True when the certificate should be renewed. False otherwise.
     */
    public boolean isExpiring(Secret secret, String certKey)  {
        boolean isExpiring = false;

        try {
            X509Certificate currentCert = cert(secret, certKey);
            isExpiring = certNeedsRenewal(currentCert);
        } catch (RuntimeException e) {
            // TODO: We should mock the certificates properly so that this doesn't fail in tests (not now => long term :-o)
            LOGGER.debugCr(reconciliation, "Failed to parse existing certificate", e);
        }

        return isExpiring;
    }

    /**
     * Checks whether subject alternate names changed and certificate needs a renewal
     *
     * @param certAndKey    Current certificate
     * @param desiredSubject    Desired subject alternate names
     * @param podName   Name of the pod to which this certificate belongs (used for log messages)
     * @return  True if the subjects are different, false otherwise
     */
    /*test*/ boolean certSubjectChanged(CertAndKey certAndKey, Subject desiredSubject, String podName)    {
        Collection<String> desiredAltNames = desiredSubject.subjectAltNames().values();
        Collection<String> currentAltNames = getSubjectAltNames(certAndKey.cert());

        if (currentAltNames != null && desiredAltNames.containsAll(currentAltNames) && currentAltNames.containsAll(desiredAltNames))   {
            LOGGER.traceCr(reconciliation, "Alternate subjects match. No need to refresh cert for pod {}.", podName);
            return false;
        } else {
            LOGGER.debugCr(reconciliation, "Alternate subjects for pod {} differ - current: {}; desired: {}", podName, currentAltNames, desiredAltNames);
            return true;
        }
    }

    /**
     * Extracts the alternate subject names out of existing certificate
     *
     * @param certificate Existing X509 certificate as a byte array
     * @return
     */
    protected List<String> getSubjectAltNames(byte[] certificate) {
        List<String> subjectAltNames = null;

        try {
            X509Certificate cert = x509Certificate(certificate);
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            subjectAltNames = altNames.stream()
                    .filter(name -> name.get(1) instanceof String)
                    .map(item -> (String) item.get(1))
                    .collect(Collectors.toList());
        } catch (CertificateException | RuntimeException e) {
            // TODO: We should mock the certificates properly so that this doesn't fail in tests (not now => long term :-o)
            LOGGER.debugCr(reconciliation, "Failed to parse existing certificate", e);
        }

        return subjectAltNames;
    }

    /**
     * Create the CA {@code Secrets} if they don't exist, otherwise if within the renewal period then either renew the CA cert
     * or replace the CA cert and key, according to the configured policy.
     * After calling this method {@link #certRenewed()} and {@link #certsRemoved()}
     * will return whether the certificate was renewed and whether expired secrets were removed from the Secret.
     * @param namespace The namespace containing the cluster.
     * @param clusterName The name of the cluster.
     * @param labels The labels of the {@code Secrets} created.
     * @param additonalLabels The additional labels of the {@code Secrets} created.
     * @param additonalAnnotations The additional annotations of the {@code Secrets} created.
     * @param ownerRef The owner of the {@code Secrets} created.
     * @param maintenanceWindowSatisfied Flag indicating whether we are in the maintenance window
     */
    public void createRenewOrReplace(String namespace, String clusterName, Map<String, String> labels, Map<String, String> additonalLabels, Map<String, String> additonalAnnotations, OwnerReference ownerRef, boolean maintenanceWindowSatisfied) {
        X509Certificate currentCert = cert(caCertSecret, CA_CRT);
        Map<String, String> certData;
        Map<String, String> keyData;
        int caCertGeneration = certGeneration();
        int caKeyGeneration = keyGeneration();
        if (!generateCa) {
            certData = caCertSecret != null ? caCertSecret.getData() : emptyMap();
            keyData = caKeySecret != null ? singletonMap(CA_KEY, caKeySecret.getData().get(CA_KEY)) : emptyMap();
            renewalType = hasCaCertGenerationChanged() ? RenewalType.REPLACE_KEY : RenewalType.NOOP;
            caCertsRemoved = false;
        } else {
            this.renewalType = shouldCreateOrRenew(currentCert, namespace, clusterName, maintenanceWindowSatisfied);
            LOGGER.debugCr(reconciliation, "{} renewalType {}", this, renewalType);
            switch (renewalType) {
                case CREATE:
                    keyData = new HashMap<>(1);
                    certData = new HashMap<>(3);
                    generateCaKeyAndCert(nextCaSubject(caKeyGeneration), keyData, certData);
                    break;
                case REPLACE_KEY:
                    keyData = new HashMap<>(1);
                    certData = new HashMap<>(caCertSecret.getData());
                    if (certData.containsKey(CA_CRT)) {
                        String notAfterDate = DATE_TIME_FORMATTER.format(currentCert.getNotAfter().toInstant().atZone(ZoneId.of("Z")));
                        addCertCaToTrustStore("ca-" + notAfterDate + ".crt", certData);
                        certData.put("ca-" + notAfterDate + ".crt", certData.remove(CA_CRT));
                    }
                    ++caCertGeneration;
                    generateCaKeyAndCert(nextCaSubject(++caKeyGeneration), keyData, certData);
                    break;
                case RENEW_CERT:
                    keyData = caKeySecret.getData();
                    certData = new HashMap<>(3);
                    ++caCertGeneration;
                    renewCaCert(nextCaSubject(caKeyGeneration), certData);
                    break;
                default:
                    keyData = caKeySecret.getData();
                    certData = caCertSecret.getData();
                    // coming from an older version, the secret could not have the CA truststore
                    if (!certData.containsKey(CA_STORE)) {
                        addCertCaToTrustStore(CA_CRT, certData);
                    }
            }
            this.caCertsRemoved = removeExpiredCerts(certData) > 0;
        }
        SecretCertProvider secretCertProvider = new SecretCertProvider();

        if (caCertsRemoved) {
            LOGGER.infoCr(reconciliation, "{}: Expired CA certificates removed", this);
        }
        if (renewalType != RenewalType.NOOP && renewalType != RenewalType.POSTPONED) {
            LOGGER.debugCr(reconciliation, "{}: {}", this, renewalType.postDescription(caKeySecretName, caCertSecretName));
        }

        // cluster CA certificate annotation handling
        Map<String, String> certAnnotations = new HashMap<>(2);
        certAnnotations.put(ANNO_STRIMZI_IO_CA_CERT_GENERATION, String.valueOf(caCertGeneration));

        if (renewalType.equals(RenewalType.POSTPONED)
                && this.caCertSecret.getMetadata() != null
                && Annotations.hasAnnotation(caCertSecret, ANNO_STRIMZI_IO_FORCE_RENEW))   {
            certAnnotations.put(ANNO_STRIMZI_IO_FORCE_RENEW, Annotations.stringAnnotation(caCertSecret, ANNO_STRIMZI_IO_FORCE_RENEW, "false"));
        }

        Map<String, String> keyAnnotations = new HashMap<>(2);
        keyAnnotations.put(ANNO_STRIMZI_IO_CA_KEY_GENERATION, String.valueOf(caKeyGeneration));

        if (renewalType.equals(RenewalType.POSTPONED)
                && this.caKeySecret.getMetadata() != null
                && Annotations.hasAnnotation(caKeySecret, ANNO_STRIMZI_IO_FORCE_REPLACE))   {
            keyAnnotations.put(ANNO_STRIMZI_IO_FORCE_REPLACE, Annotations.stringAnnotation(caKeySecret, ANNO_STRIMZI_IO_FORCE_REPLACE, "false"));
        }

        caCertSecret = secretCertProvider.createSecret(namespace, caCertSecretName, certData, Util.mergeLabelsOrAnnotations(labels, additonalLabels),
                Util.mergeLabelsOrAnnotations(certAnnotations, additonalAnnotations), ownerRef);

        caKeySecret = secretCertProvider.createSecret(namespace, caKeySecretName, keyData, labels,
                keyAnnotations, ownerRef);
    }

    private Subject nextCaSubject(int version) {
        Subject result = new Subject.Builder()
        // Key replacements does not work if both old and new CA certs have the same subject DN, so include the
        // key generation in the DN so the certificates appear distinct during CA key replacement.
            .withCommonName(commonName + " v" + version)
            .withOrganizationName(IO_STRIMZI).build();
        return result;
    }

    private RenewalType shouldCreateOrRenew(X509Certificate currentCert, String namespace, String clusterName, boolean maintenanceWindowSatisfied) {
        String reason = null;
        RenewalType renewalType = RenewalType.NOOP;
        if (caKeySecret == null
                || caKeySecret.getData() == null
                || caKeySecret.getData().get(CA_KEY) == null) {
            reason = "CA key secret " + caKeySecretName + " is missing or lacking data." + CA_KEY.replace(".", "\\.");
            renewalType = RenewalType.CREATE;
        } else if (this.caCertSecret == null
                || this.caCertSecret.getData() == null
                || this.caCertSecret.getData().get(CA_CRT) == null) {
            reason = "CA certificate secret " + caCertSecretName + " is missing or lacking data." + CA_CRT.replace(".", "\\.");
            renewalType = RenewalType.RENEW_CERT;
        } else if (this.caCertSecret.getMetadata() != null
                && Annotations.booleanAnnotation(this.caCertSecret, ANNO_STRIMZI_IO_FORCE_RENEW, false)) {
            reason = "CA certificate secret " + caCertSecretName + " is annotated with " + ANNO_STRIMZI_IO_FORCE_RENEW;

            if (maintenanceWindowSatisfied) {
                renewalType = RenewalType.RENEW_CERT;
            } else {
                renewalType = RenewalType.POSTPONED;
            }
        } else if (this.caKeySecret.getMetadata() != null
                && Annotations.booleanAnnotation(this.caKeySecret, ANNO_STRIMZI_IO_FORCE_REPLACE, false)) {
            reason = "CA key secret " + caKeySecretName + " is annotated with " + ANNO_STRIMZI_IO_FORCE_REPLACE;

            if (maintenanceWindowSatisfied) {
                renewalType = RenewalType.REPLACE_KEY;
            } else {
                renewalType = RenewalType.POSTPONED;
            }
        } else if (currentCert != null
                && certNeedsRenewal(currentCert)) {
            reason = "Within renewal period for CA certificate (expires on " + currentCert.getNotAfter() + ")";

            if (maintenanceWindowSatisfied) {
                switch (policy) {
                    case REPLACE_KEY:
                        renewalType = RenewalType.REPLACE_KEY;
                        break;
                    case RENEW_CERTIFICATE:
                        renewalType = RenewalType.RENEW_CERT;
                        break;
                }
            } else {
                renewalType = RenewalType.POSTPONED;
            }
        }

        logRenewalState(currentCert, namespace, clusterName, renewalType, reason);
        return renewalType;
    }

    private void logRenewalState(X509Certificate currentCert, String namespace, String clusterName, RenewalType renewalType, String reason) {
        switch (renewalType) {
            case REPLACE_KEY:
            case RENEW_CERT:
            case CREATE:
                if (generateCa) {
                    LOGGER.debugCr(reconciliation, "{}: {}: {}", this, renewalType.preDescription(caKeySecretName, caCertSecretName), reason);
                } else {
                    LOGGER.warnCr(reconciliation, "{}: {}: {}", this, renewalType.preDescription(caKeySecretName, caCertSecretName), reason);
                }
                break;
            case POSTPONED:
                LOGGER.warnCr(reconciliation, "{}: {}: {}", this, renewalType.preDescription(caKeySecretName, caCertSecretName), reason);
                break;
            case NOOP:
                LOGGER.debugCr(reconciliation, "{}: The CA certificate in secret {} already exists and does not need renewing", this, caCertSecretName);
                break;
        }
        if (!generateCa) {
            if (renewalType.equals(RenewalType.RENEW_CERT)) {
                LOGGER.warnCr(reconciliation, "The certificate (data.{}) in Secret {} in namespace {} needs to be renewed " +
                                "and it is not configured to automatically renew. This needs to be manually updated before that date. " +
                                "Alternatively, configure Kafka.spec.tlsCertificates.generateCertificateAuthority=true in the Kafka resource with name {} in namespace {}.",
                        CA_CRT.replace(".", "\\."), this.caCertSecretName, namespace,
                        currentCert.getNotAfter());
            } else if (renewalType.equals(RenewalType.REPLACE_KEY)) {
                LOGGER.warnCr(reconciliation, "The private key (data.{}) in Secret {} in namespace {} needs to be renewed " +
                                "and it is not configured to automatically renew. This needs to be manually updated before that date. " +
                                "Alternatively, configure Kafka.spec.tlsCertificates.generateCertificateAuthority=true in the Kafka resource with name {} in namespace {}.",
                        CA_KEY.replace(".", "\\."), this.caKeySecretName, namespace,
                        currentCert.getNotAfter());
            } else if (caCertSecret == null) {
                LOGGER.warnCr(reconciliation, "The certificate (data.{}) in Secret {} and the private key (data.{}) in Secret {} in namespace {} " +
                                "needs to be configured with a Base64 encoded PEM-format certificate. " +
                                "Alternatively, configure Kafka.spec.tlsCertificates.generateCertificateAuthority=true in the Kafka resource with name {} in namespace {}.",
                        CA_CRT.replace(".", "\\."), this.caCertSecretName,
                        CA_KEY.replace(".", "\\."), this.caKeySecretName, namespace,
                        clusterName, namespace);
            }
        }
    }

    /**
     * @return the CA cert secret, which contains both the current CA cert and also previous, still valid certs.
     */
    public Secret caCertSecret() {
        return caCertSecret;
    }

    /**
     * @return the CA key secret, which contains the current CA private key.
     */
    public Secret caKeySecret() {
        return caKeySecret;
    }

    /**
     * @return The current CA certificate as bytes.
     */
    public byte[] currentCaCertBytes() {
        Base64.Decoder decoder = Base64.getDecoder();
        return decoder.decode(caCertSecret().getData().get(CA_CRT));
    }

    /**
     * @return The base64 encoded bytes of the current CA certificate.
     */
    public String currentCaCertBase64() {
        return caCertSecret().getData().get(CA_CRT);
    }

    /**
     * @return The current CA key as bytes.
     */
    public byte[] currentCaKey() {
        Base64.Decoder decoder = Base64.getDecoder();
        return decoder.decode(caKeySecret().getData().get(CA_KEY));
    }

    /**
     * True if the last call to {@link #createRenewOrReplace(String, String, Map, Map, Map, OwnerReference, boolean)}
     * resulted in expired certificates being removed from the CA {@code Secret}.
     * @return Whether any expired certificates were removed.
     */
    public boolean certsRemoved() {
        return this.caCertsRemoved;
    }

    /**
     * True if the last call to {@link #createRenewOrReplace(String, String, Map, Map, Map, OwnerReference, boolean)}
     * resulted in a renewed CA certificate.
     * @return Whether the certificate was renewed.
     */
    public boolean certRenewed() {
        return renewalType.equals(RenewalType.RENEW_CERT) || renewalType.equals(RenewalType.REPLACE_KEY);
    }

    /**
     * True if the last call to {@link #createRenewOrReplace(String, String, Map, Map, Map, OwnerReference, boolean)}
     * resulted in a replaced CA key.
     * @return Whether the key was replaced.
     */
    public boolean keyReplaced() {
        return renewalType.equals(RenewalType.REPLACE_KEY);
    }

    public boolean keyCreated() {
        return renewalType.equals(RenewalType.CREATE);
    }

    /**
     * @return the generation of the current CA certificate
     */
    public int certGeneration() {
        if (caCertSecret != null) {
            if (!Annotations.hasAnnotation(caCertSecret, ANNO_STRIMZI_IO_CA_CERT_GENERATION)) {
                LOGGER.warnOp("Secret {}/{} is missing generation annotation {}",
                        caCertSecret.getMetadata().getNamespace(), caCertSecret.getMetadata().getName(), ANNO_STRIMZI_IO_CA_CERT_GENERATION);
            }
            return Annotations.intAnnotation(caCertSecret, ANNO_STRIMZI_IO_CA_CERT_GENERATION, INIT_GENERATION);
        }
        return INIT_GENERATION;
    }

    /**
     * @return the generation of the current CA key
     */
    public int keyGeneration() {
        if (caKeySecret != null) {
            if (!Annotations.hasAnnotation(caKeySecret, ANNO_STRIMZI_IO_CA_KEY_GENERATION)) {
                LOGGER.warnOp("Secret {}/{} is missing generation annotation {}",
                        caKeySecret.getMetadata().getNamespace(), caKeySecret.getMetadata().getName(), ANNO_STRIMZI_IO_CA_KEY_GENERATION);
            }
            return Annotations.intAnnotation(caKeySecret, ANNO_STRIMZI_IO_CA_KEY_GENERATION, INIT_GENERATION);
        }
        return INIT_GENERATION;
    }

    private int removeExpiredCerts(Map<String, String> newData) {
        Iterator<Map.Entry<String, String>> iter = newData.entrySet().iterator();
        List<String> removed = new ArrayList<>();
        while (iter.hasNext()) {
            Map.Entry<String, String> entry = iter.next();
            String certName = entry.getKey();
            String certText = entry.getValue();
            boolean remove = false;
            try {
                X509Certificate cert = x509Certificate(Base64.getDecoder().decode(certText));
                Instant expiryDate = cert.getNotAfter().toInstant();
                remove = expiryDate.isBefore(clock.instant());
                if (remove) {
                    LOGGER.debugCr(reconciliation, "The certificate (data.{}) in Secret expired {}; removing it",
                            certName.replace(".", "\\."), expiryDate);
                }
            } catch (CertificateException e) {

                // doesn't remove stores and related password
                if (!certName.endsWith(".p12") && !certName.endsWith(".password")) {
                    remove = true;
                    LOGGER.debugCr(reconciliation, "The certificate (data.{}) in Secret is not an X.509 certificate; removing it",
                            certName.replace(".", "\\."));
                }
            }
            if (remove) {
                LOGGER.debugCr(reconciliation, "Removing data.{} from Secret",
                        certName.replace(".", "\\."));
                iter.remove();
                removed.add(certName);
            }
        }

        if (removed.size() > 0) {
            // the certificates removed from the Secret data has tobe removed from the store as well
            try {
                File trustStoreFile = File.createTempFile("tls", "-truststore");
                Files.write(trustStoreFile.toPath(), Base64.getDecoder().decode(newData.get(CA_STORE)));
                try {
                    String trustStorePassword = new String(Base64.getDecoder().decode(newData.get(CA_STORE_PASSWORD)), StandardCharsets.US_ASCII);
                    certManager.deleteFromTrustStore(removed, trustStoreFile, trustStorePassword);
                    newData.put(CA_STORE, Base64.getEncoder().encodeToString(Files.readAllBytes(trustStoreFile.toPath())));
                } finally {
                    delete(reconciliation, trustStoreFile);
                }
            } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        return removed.size();
    }

    public boolean certNeedsRenewal(X509Certificate cert)  {
        Instant notAfter = cert.getNotAfter().toInstant();
        Instant renewalPeriodBegin = notAfter.minus(renewalDays, ChronoUnit.DAYS);
        LOGGER.traceCr(reconciliation, "Certificate {} expires on {} renewal period begins on {}", cert.getSubjectDN(), notAfter, renewalPeriodBegin);
        return this.clock.instant().isAfter(renewalPeriodBegin);
    }

    /**
     * This is a no-static version of the `cert` method so that it can be mocked.
     *
     * @param secret    Secret with a certificates
     * @param key   Key under which the certificate is stored
     * @return  Decoced X509 certificate
     */
    public X509Certificate getAsX509Certificate(Secret secret, String key)  {
        return cert(secret, key);
    }

    public static X509Certificate cert(Secret secret, String key)  {
        if (secret == null || secret.getData() == null || secret.getData().get(key) == null) {
            return null;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] bytes = decoder.decode(secret.getData().get(key));
        try {
            return x509Certificate(bytes);
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to decode certificate in data." + key.replace(".", "\\.") + " of Secret " + secret.getMetadata().getName(), e);
        }
    }

    /**
     * Returns set of all public keys (all .crt records) from a secret
     *
     * @param secret    Kubernetes Secret with certificates
     *
     * @return          Set with X509Certificate instances
     */
    public static Set<X509Certificate> certs(Secret secret)  {
        if (secret == null || secret.getData() == null) {
            return Set.of();
        } else {
            Base64.Decoder decoder = Base64.getDecoder();

            return secret
                    .getData()
                    .entrySet()
                    .stream()
                    .filter(record -> record.getKey().endsWith(".crt"))
                    .map(record -> {
                        byte[] bytes = decoder.decode(record.getValue());
                        try {
                            return x509Certificate(bytes);
                        } catch (CertificateException e) {
                            throw new RuntimeException("Failed to decode certificate in data." + record.getKey().replace(".", "\\.") + " of Secret " + secret.getMetadata().getName(), e);
                        }
                    })
                    .collect(Collectors.toSet());
        }
    }

    static X509Certificate x509Certificate(byte[] bytes) throws CertificateException {
        CertificateFactory factory = certificateFactory();
        return x509Certificate(factory, bytes);
    }

    static X509Certificate x509Certificate(CertificateFactory factory, byte[] bytes) throws CertificateException {
        Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(bytes));
        if (certificate instanceof X509Certificate) {
            return (X509Certificate) certificate;
        } else {
            throw new CertificateException("Not an X509Certificate: " + certificate);
        }
    }

    static CertificateFactory certificateFactory() {
        CertificateFactory factory = null;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("No security provider with support for X.509 certificates", e);
        }
        return factory;
    }

    private void addCertCaToTrustStore(String alias, Map<String, String> certData) {
        try {
            File certFile = File.createTempFile("tls", "-cert");
            Files.write(certFile.toPath(), Base64.getDecoder().decode(certData.get(CA_CRT)));
            try {
                File trustStoreFile = File.createTempFile("tls", "-truststore");
                if (certData.containsKey(CA_STORE)) {
                    Files.write(trustStoreFile.toPath(), Base64.getDecoder().decode(certData.get(CA_STORE)));
                }
                try {
                    String trustStorePassword = certData.containsKey(CA_STORE_PASSWORD) ?
                            new String(Base64.getDecoder().decode(certData.get(CA_STORE_PASSWORD)), StandardCharsets.US_ASCII) :
                            passwordGenerator.generate();
                    certManager.addCertToTrustStore(certFile, alias, trustStoreFile, trustStorePassword);
                    certData.put(CA_STORE, Base64.getEncoder().encodeToString(Files.readAllBytes(trustStoreFile.toPath())));
                    certData.put(CA_STORE_PASSWORD, Base64.getEncoder().encodeToString(trustStorePassword.getBytes(StandardCharsets.US_ASCII)));
                } finally {
                    delete(reconciliation, trustStoreFile);
                }
            } finally {
                delete(reconciliation, certFile);
            }

        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateCaKeyAndCert(Subject subject, Map<String, String> keyData, Map<String, String> certData) {
        try {
            LOGGER.debugCr(reconciliation, "Generating CA with subject={}", subject);
            File keyFile = File.createTempFile("tls", subject.commonName() + "-key");
            try {
                File certFile = File.createTempFile("tls", subject.commonName() + "-cert");
                try {
                    File trustStoreFile = File.createTempFile("tls", subject.commonName() + "-truststore");
                    String trustStorePassword;
                    // if secret already contains the truststore, we have to reuse it without changing password
                    if (certData.containsKey(CA_STORE)) {
                        Files.write(trustStoreFile.toPath(), Base64.getDecoder().decode(certData.get(CA_STORE)));
                        trustStorePassword = new String(Base64.getDecoder().decode(certData.get(CA_STORE_PASSWORD)), StandardCharsets.US_ASCII);
                    } else {
                        trustStorePassword = passwordGenerator.generate();
                    }
                    try {
                        certManager.generateSelfSignedCert(keyFile, certFile, subject, validityDays);
                        certManager.addCertToTrustStore(certFile, CA_CRT, trustStoreFile, trustStorePassword);
                        CertAndKey ca = new CertAndKey(
                                Files.readAllBytes(keyFile.toPath()),
                                Files.readAllBytes(certFile.toPath()),
                                Files.readAllBytes(trustStoreFile.toPath()),
                                null,
                                trustStorePassword);
                        certData.put(CA_CRT, ca.certAsBase64String());
                        keyData.put(CA_KEY, ca.keyAsBase64String());
                        certData.put(CA_STORE, ca.trustStoreAsBase64String());
                        certData.put(CA_STORE_PASSWORD, ca.storePasswordAsBase64String());
                    } finally {
                        delete(reconciliation, trustStoreFile);
                    }
                } finally {
                    delete(reconciliation, certFile);
                }
            } finally {
                delete(reconciliation, keyFile);
            }
        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void renewCaCert(Subject subject, Map<String, String> certData) {
        try {
            LOGGER.debugCr(reconciliation, "Renewing CA with subject={}, org={}", subject);

            Base64.Decoder decoder = Base64.getDecoder();
            byte[] bytes = decoder.decode(caKeySecret.getData().get(CA_KEY));
            File keyFile = File.createTempFile("tls", subject.commonName() + "-key");
            try {
                Files.write(keyFile.toPath(), bytes);
                File certFile = File.createTempFile("tls", subject.commonName() + "-cert");
                try {
                    File trustStoreFile = File.createTempFile("tls", subject.commonName() + "-truststore");
                    try {
                        String trustStorePassword = passwordGenerator.generate();
                        certManager.renewSelfSignedCert(keyFile, certFile, subject, validityDays);
                        certManager.addCertToTrustStore(certFile, CA_CRT, trustStoreFile, trustStorePassword);
                        CertAndKey ca = new CertAndKey(
                                bytes,
                                Files.readAllBytes(certFile.toPath()),
                                Files.readAllBytes(trustStoreFile.toPath()),
                                null,
                                trustStorePassword);
                        certData.put(CA_CRT, ca.certAsBase64String());
                        certData.put(CA_STORE, ca.trustStoreAsBase64String());
                        certData.put(CA_STORE_PASSWORD, ca.storePasswordAsBase64String());
                    } finally {
                        delete(reconciliation, trustStoreFile);
                    }
                } finally {
                    delete(reconciliation, certFile);
                }
            } finally {
                delete(reconciliation, keyFile);
            }
        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return the name of the annotation bringing the generation of the specific CA certificate type (cluster or clients)
     *         on the Secrets containing certificates signed by that CA (i.e ZooKeeper nodes, Kafka brokers, ...)
     */
    protected abstract String caCertGenerationAnnotation();

    /**
     * @return if the current (cluster or clients) CA certificate generation is changed compared to the the one
     *         brought on Secrets containing certificates signed by that CA (i.e ZooKeeper nodes, Kafka brokers, ...)
     */
    protected abstract boolean hasCaCertGenerationChanged();

    /**
     * It checks if the current (cluster or clients) CA certificate generation is changed compared to the one
     * brought by the corresponding annotation on the provided Secret (i.e ZooKeeper nodes, Kafka brokers, ...)
     *
     * @param secret Secret containing certificates signed by the current (clients or cluster) CA
     * @return if the current (cluster or clients) CA certificate generation is changed compared to the one
     *         brought by the corresponding annotation on the provided Secret
     */
    protected boolean hasCaCertGenerationChanged(Secret secret) {
        if (secret != null) {
            String caCertGenerationAnno = Optional.ofNullable(secret.getMetadata().getAnnotations())
                    .map(annotations -> annotations.get(caCertGenerationAnnotation()))
                    .orElse(null);
            int currentCaCertGeneration = certGeneration();
            LOGGER.debugOp("Secret {}/{} generation anno = {}, current CA generation = {}",
                    secret.getMetadata().getNamespace(), secret.getMetadata().getName(), caCertGenerationAnno, currentCaCertGeneration);
            return caCertGenerationAnno != null && Integer.parseInt(caCertGenerationAnno) != currentCaCertGeneration;
        }
        return false;
    }
}
