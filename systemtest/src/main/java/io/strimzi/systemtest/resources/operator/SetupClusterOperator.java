/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.operator;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.strimzi.systemtest.BeforeAllOnce;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.enums.ClusterOperatorRBACType;
import io.strimzi.systemtest.enums.OlmInstallationStrategy;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.kubernetes.ClusterRoleBindingResource;
import io.strimzi.systemtest.resources.kubernetes.NetworkPolicyResource;
import io.strimzi.systemtest.resources.kubernetes.RoleBindingResource;
import io.strimzi.systemtest.resources.kubernetes.RoleResource;
import io.strimzi.systemtest.resources.operator.specific.HelmResource;
import io.strimzi.systemtest.resources.operator.specific.OlmResource;
import io.strimzi.systemtest.templates.kubernetes.ClusterRoleBindingTemplates;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.executor.Exec;
import io.strimzi.test.logs.CollectorElement;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.k8s.cluster.OpenShift;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SetupClusterOperator encapsulates the whole installation process of Cluster Operator (i.e., RoleBinding, ClusterRoleBinding,
 * ConfigMap, Deployment, CustomResourceDefinition, preparation of the Namespace). Based on the @code{Environment}
 * values, this class decides how Cluster Operator should be installed (i.e., Olm, Helm, Bundle). Moreover, it provides
 * @code{rollbackToDefaultConfiguration()} method, which basically re-install Cluster Operator to the default values. In
 * case user wants to edit specific installation, one can use @code{defaultInstallation()}, which returns SetupClusterOperatorBuilder.
 */
public class SetupClusterOperator {

    private static final Logger LOGGER = LogManager.getLogger(SetupClusterOperator.class);
    public static final String CO_INSTALL_DIR = TestUtils.USER_PATH + "/../packaging/install/cluster-operator";

    private static SetupClusterOperator instanceHolder;

    private static KubeClusterResource cluster = KubeClusterResource.getInstance();
    private static HelmResource helmResource;
    private static OlmResource olmResource;

    private ExtensionContext extensionContext;
    private String clusterOperatorName;
    private String namespaceInstallTo;
    private String namespaceToWatch;
    private List<String> bindingsNamespaces;
    private long operationTimeout;
    private long reconciliationInterval;
    private List<EnvVar> extraEnvVars;
    private Map<String, String> extraLabels;
    private ClusterOperatorRBACType clusterOperatorRBACType;

    private String testClassName;
    private String testMethodName;

    private static final Predicate<String> IS_OLM_CLUSTER_WIDE = namespaceToWatch -> namespaceToWatch.equals(Constants.WATCH_ALL_NAMESPACES) || namespaceToWatch.split(",").length > 1;
    private static final Predicate<SetupClusterOperator> IS_EMPTY = co -> co.helmResource == null && co.olmResource == null &&
        co.extensionContext == null && co.clusterOperatorName == null && co.namespaceInstallTo == null &&
        co.namespaceToWatch == null && co.bindingsNamespaces == null && co.operationTimeout == 0 && co.reconciliationInterval == 0 &&
        co.extraEnvVars == null && co.clusterOperatorRBACType == null && co.testClassName == null && co.testMethodName == null;

    public synchronized static SetupClusterOperator getInstanceHolder() {
        if (instanceHolder == null) {
            // empty cluster operator
            instanceHolder = new SetupClusterOperator();
        }
        return instanceHolder;
    }

    public SetupClusterOperator() {}
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public SetupClusterOperator(SetupClusterOperatorBuilder builder) {
        this.extensionContext = builder.extensionContext;
        this.clusterOperatorName = builder.clusterOperatorName;
        this.namespaceInstallTo = builder.namespaceInstallTo;
        this.namespaceToWatch = builder.namespaceToWatch;
        this.bindingsNamespaces = builder.bindingsNamespaces;
        this.operationTimeout = builder.operationTimeout;
        this.reconciliationInterval = builder.reconciliationInterval;
        this.extraEnvVars = builder.extraEnvVars;
        this.extraLabels = builder.extraLabels;
        this.clusterOperatorRBACType = builder.clusterOperatorRBACType;

        // assign defaults is something is not specified
        if (this.clusterOperatorName == null || this.clusterOperatorName.isEmpty()) {
            this.clusterOperatorName = Constants.STRIMZI_DEPLOYMENT_NAME;
        }
        // if namespace is not set we install operator to 'infra-namespace'
        if (this.namespaceInstallTo == null || this.namespaceInstallTo.isEmpty()) {
            this.namespaceInstallTo = Constants.INFRA_NAMESPACE;
        }
        if (this.namespaceToWatch == null) {
            this.namespaceToWatch = this.namespaceInstallTo;
        }
        if (this.bindingsNamespaces == null) {
            this.bindingsNamespaces = new ArrayList<>();
            this.bindingsNamespaces.add(this.namespaceInstallTo);
        }
        if (this.operationTimeout == 0) {
            this.operationTimeout = Constants.CO_OPERATION_TIMEOUT_DEFAULT;
        }
        if (this.reconciliationInterval == 0) {
            this.reconciliationInterval = Constants.RECONCILIATION_INTERVAL;
        }
        if (this.extraEnvVars == null) {
            this.extraEnvVars = new ArrayList<>();
        }
        if (this.extraLabels == null) {
            this.extraLabels = new HashMap<>();
        }
        if (this.clusterOperatorRBACType == null) {
            this.clusterOperatorRBACType = ClusterOperatorRBACType.CLUSTER;
        }
        instanceHolder = this;
    }

    /**
     * Auxiliary method, which check if Cluster Operator namespace is created.
     * @return true if Cluster Operator namespace not created, otherwise false (i.e., namespace already created)
     */
    private boolean isClusterOperatorNamespaceNotCreated() {
        return extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo) == null;
    }

    /**
     * Auxiliary method, which provides default Cluster Operator instance. By default we mean using in all cases
     * @code{BeforeAllOnce.getSharedExtensionContext())} and other attributes are dependent base on the installation type
     * (i.e., Olm, Helm, Bundle) and RBAC setup (i.e., Cluster, Namespace). This method used across whole systemtest
     * module, with combination of {@link #rollbackToDefaultConfiguration()} or in @IsolatedSuites where we are forced
     * to build more comprehensive deployment of CLuster Operator.
     *
     * @return default Cluster Operator builder
     */
    public SetupClusterOperatorBuilder defaultInstallation() {
        // default initialization
        SetupClusterOperatorBuilder clusterOperatorBuilder = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(BeforeAllOnce.getSharedExtensionContext());

        // RBAC set to `NAMESPACE`
        if (Environment.isNamespaceRbacScope() && !Environment.isHelmInstall()) {
            clusterOperatorBuilder = clusterOperatorBuilder.withNamespace(Constants.INFRA_NAMESPACE);
            return clusterOperatorBuilder;
        // OLM cluster wide must use KubeClusterResource.getInstance().getDefaultOlmNamespace() namespace
        } else if (Environment.isOlmInstall() && !Environment.isNamespaceRbacScope()) {
            clusterOperatorBuilder = clusterOperatorBuilder
                .withNamespace(cluster.getDefaultOlmNamespace())
                .withBindingsNamespaces(Collections.emptyList())
                .withWatchingNamespaces(Constants.WATCH_ALL_NAMESPACES);
            return clusterOperatorBuilder;
        }
        // otherwise
        return clusterOperatorBuilder
            .withNamespace(Constants.INFRA_NAMESPACE)
            .withWatchingNamespaces(Constants.WATCH_ALL_NAMESPACES);
    }

    /**
     * This method install Strimzi Cluster Operator based on environment variable configuration.
     * It can install operator by classic way (apply bundle yamls) or use OLM. For OLM you need to set all other OLM env variables.
     * Don't use this method in tests, where specific configuration of CO is needed.
     */
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public SetupClusterOperator runInstallation() {
        LOGGER.info("Cluster operator installation configuration:\n{}", this::prettyPrint);
        LOGGER.debug("Cluster operator installation configuration:\n{}", this::toString);
        // if it's shared context (before suite) skip
        if (BeforeAllOnce.getSharedExtensionContext() != extensionContext) {
            testClassName = extensionContext.getRequiredTestClass() != null ? extensionContext.getRequiredTestClass().getName() : "";
            testMethodName = extensionContext.getDisplayName() != null ? extensionContext.getDisplayName() : "";
        }

        if (Environment.isOlmInstall()) {
            runOlmInstallation();
        } else if (Environment.isHelmInstall()) {
            LOGGER.info("Install ClusterOperator via Helm");
            helmResource = new HelmResource(namespaceInstallTo, namespaceToWatch);
            if (isClusterOperatorNamespaceNotCreated()) {
                cluster.setNamespace(namespaceInstallTo);
                cluster.createNamespaces(CollectorElement.createCollectorElement(testClassName, testMethodName), namespaceInstallTo, bindingsNamespaces);
                extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, false);
            }
            helmResource.create(extensionContext, operationTimeout, reconciliationInterval);
        } else {
            bundleInstallation();
        }
        return this;
    }

    public SetupClusterOperator runBundleInstallation() {
        LOGGER.info("Cluster operator installation configuration:\n{}", this::toString);
        bundleInstallation();
        return this;
    }

    public SetupClusterOperator runOlmInstallation() {
        LOGGER.info("Cluster operator installation configuration:\n{}", this::toString);
        olmInstallation();
        return this;
    }

    public SetupClusterOperator runManualOlmInstallation(final String fromVersion, final String channelName) {
        if (isClusterOperatorNamespaceNotCreated()) {
            cluster.setNamespace(namespaceInstallTo);
            cluster.createNamespaces(CollectorElement.createCollectorElement(testClassName, testMethodName), namespaceInstallTo, bindingsNamespaces);
            extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, false);
        }
        olmResource = new OlmResource(this.namespaceInstallTo);
        olmResource.create(this.namespaceInstallTo, OlmInstallationStrategy.Manual, fromVersion, channelName);

        return this;
    }


    private void bundleInstallation() {
        LOGGER.info("Install ClusterOperator via Yaml bundle");
        // check if namespace is already created
        if (isClusterOperatorNamespaceNotCreated()) {
            cluster.createNamespaces(CollectorElement.createCollectorElement(testClassName, testMethodName), namespaceInstallTo, bindingsNamespaces);
            extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, false);
        } else {
            LOGGER.info("Environment for ClusterOperator was already prepared! Going to install it now.");
        }
        prepareEnvForOperator(extensionContext, namespaceInstallTo, bindingsNamespaces);
        applyBindings();

        // copy image-pull secret
        if (Environment.SYSTEM_TEST_STRIMZI_IMAGE_PULL_SECRET != null && !Environment.SYSTEM_TEST_STRIMZI_IMAGE_PULL_SECRET.isEmpty()) {
            StUtils.copyImagePullSecret(namespaceInstallTo);
        }
        // 060-Deployment
        ResourceManager.setCoDeploymentName(clusterOperatorName);
        ResourceManager.getInstance().createResource(extensionContext,
            new BundleResource.BundleResourceBuilder()
                .withName(clusterOperatorName)
                .withNamespace(namespaceInstallTo)
                .withWatchingNamespaces(namespaceToWatch)
                .withOperationTimeout(operationTimeout)
                .withReconciliationInterval(reconciliationInterval)
                .withExtraEnvVars(extraEnvVars)
                .withExtraLabels(extraLabels)
                .buildBundleInstance()
                .buildBundleDeployment()
                .build());
    }

    private void olmInstallation() {
        LOGGER.info("Install ClusterOperator via OLM");
        // cluster-wide olm co-operator or multi-namespaces in once we also deploy cluster-wide
        if (IS_OLM_CLUSTER_WIDE.test(namespaceToWatch)) {
            // if RBAC is enable we don't run tests in parallel mode and with that said we don't create another namespaces
            if (!Environment.isNamespaceRbacScope()) {
                bindingsNamespaces = bindingsNamespaces.contains(Constants.INFRA_NAMESPACE) ? Collections.singletonList(cluster.getDefaultOlmNamespace()) : bindingsNamespaces;
                namespaceInstallTo = cluster.getDefaultOlmNamespace();
                if (isClusterOperatorNamespaceNotCreated()) {
                    cluster.setNamespace(namespaceInstallTo);
                    // if OLM is cluster-wide we do not re-create default OLM namespace because it will also delete
                    // the global OperatorGroup resource. Thus we remove such namespace from the collection avoiding failing tests.
                    if (bindingsNamespaces.contains(namespaceInstallTo)) {
                        bindingsNamespaces = new ArrayList<>(bindingsNamespaces).stream()
                            .filter(n -> !n.equals(namespaceInstallTo))
                            .collect(Collectors.toList());
                    }
                    cluster.createNamespaces(CollectorElement.createCollectorElement(testClassName, testMethodName), namespaceInstallTo, bindingsNamespaces);
                    extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, false);
                }
                createClusterRoleBindings();
                olmResource = new OlmResource(namespaceInstallTo);
                olmResource.create(extensionContext, operationTimeout, reconciliationInterval);
            }
            // single-namespace olm co-operator
        } else {
            if (isClusterOperatorNamespaceNotCreated()) {
                cluster.setNamespace(namespaceInstallTo);
                cluster.createNamespaces(CollectorElement.createCollectorElement(testClassName, testMethodName), namespaceInstallTo, bindingsNamespaces);
                extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, false);
            }
            olmResource = new OlmResource(namespaceInstallTo);
            olmResource.create(extensionContext, operationTimeout, reconciliationInterval);
        }
    }

    private void createClusterRoleBindings() {
        // Create ClusterRoleBindings that grant cluster-wide access to all OpenShift projects
        List<ClusterRoleBinding> clusterRoleBindingList = ClusterRoleBindingTemplates.clusterRoleBindingsForAllNamespaces(namespaceInstallTo);
        clusterRoleBindingList.forEach(clusterRoleBinding ->
            ResourceManager.getInstance().createResource(extensionContext, clusterRoleBinding));
    }

    public SetupClusterOperatorBuilder editInstallation() {
        return new SetupClusterOperatorBuilder()
            .withExtensionContext(this.extensionContext)
            .withClusterOperatorName(this.clusterOperatorName)
            .withNamespace(this.namespaceInstallTo)
            .withWatchingNamespaces(this.namespaceToWatch)
            .withBindingsNamespaces(this.bindingsNamespaces)
            .withOperationTimeout(this.operationTimeout)
            .withReconciliationInterval(this.reconciliationInterval)
            .withExtraEnvVars(this.extraEnvVars)
            .withExtraLabels(this.extraLabels)
            .withClusterOperatorRBACType(this.clusterOperatorRBACType);
    }

    public static class SetupClusterOperatorBuilder {

        private ExtensionContext extensionContext;
        private String clusterOperatorName;
        private String namespaceInstallTo;
        private String namespaceToWatch;
        private List<String> bindingsNamespaces;
        private long operationTimeout;
        private long reconciliationInterval;
        private List<EnvVar> extraEnvVars;
        private Map<String, String> extraLabels;
        private ClusterOperatorRBACType clusterOperatorRBACType;

        public SetupClusterOperatorBuilder withExtensionContext(ExtensionContext extensionContext) {
            this.extensionContext = extensionContext;
            return self();
        }
        public SetupClusterOperatorBuilder withClusterOperatorName(String clusterOperatorName) {
            this.clusterOperatorName = clusterOperatorName;
            return self();
        }
        public SetupClusterOperatorBuilder withNamespace(String namespaceInstallTo) {
            this.namespaceInstallTo = namespaceInstallTo;
            return self();
        }
        public SetupClusterOperatorBuilder withWatchingNamespaces(String namespaceToWatch) {
            this.namespaceToWatch = namespaceToWatch;
            return self();
        }

        public SetupClusterOperatorBuilder addToTheWatchingNamespaces(String namespaceToWatch) {
            if (this.namespaceToWatch != null) {
                if (!this.namespaceToWatch.equals("*")) {
                    this.namespaceToWatch += "," + namespaceToWatch;
                }
            } else {
                this.namespaceToWatch = namespaceToWatch;
            }
            return self();
        }

        public SetupClusterOperatorBuilder withBindingsNamespaces(List<String> bindingsNamespaces) {
            this.bindingsNamespaces = bindingsNamespaces;
            return self();
        }

        public SetupClusterOperatorBuilder addToTheBindingsNamespaces(String bindingsNamespace) {
            if (this.bindingsNamespaces != null) {
                this.bindingsNamespaces = new ArrayList<>(this.bindingsNamespaces);
            } else {
                this.bindingsNamespaces = new ArrayList<>(Arrays.asList(bindingsNamespace));
            }
            return self();
        }

        public SetupClusterOperatorBuilder withOperationTimeout(long operationTimeout) {
            this.operationTimeout = operationTimeout;
            return self();
        }
        public SetupClusterOperatorBuilder withReconciliationInterval(long reconciliationInterval) {
            this.reconciliationInterval = reconciliationInterval;
            return self();
        }

        // currently supported only for Bundle installation
        public SetupClusterOperatorBuilder withExtraEnvVars(List<EnvVar> envVars) {
            this.extraEnvVars = envVars;
            return self();
        }

        // currently supported only for Bundle installation
        public SetupClusterOperatorBuilder withExtraLabels(Map<String, String> extraLabels) {
            this.extraLabels = extraLabels;
            return self();
        }

        // currently supported only for Bundle installation
        public SetupClusterOperatorBuilder withClusterOperatorRBACType(ClusterOperatorRBACType clusterOperatorRBACType) {
            this.clusterOperatorRBACType = clusterOperatorRBACType;
            return self();
        }

        private SetupClusterOperatorBuilder self() {
            return this;
        }

        public SetupClusterOperator createInstallation() {
            return new SetupClusterOperator(this);
        }
    }

    /**
     * Prepare environment for cluster operator which includes creation of namespaces, custom resources and operator
     * specific config files such as ServiceAccount, Roles and CRDs.
     * @param clientNamespace namespace which will be created and used as default by kube client
     * @param namespaces list of namespaces which will be created
     * @param resources list of path to yaml files with resources specifications
     */
    public void prepareEnvForOperator(ExtensionContext extensionContext, String clientNamespace, List<String> namespaces, String... resources) {
        assumeTrue(!Environment.isHelmInstall() && !Environment.isOlmInstall());
        applyClusterOperatorInstallFiles(clientNamespace);
        NetworkPolicyResource.applyDefaultNetworkPolicySettings(extensionContext, namespaces);

        if (cluster.cluster() instanceof OpenShift) {
            // This is needed in case you are using internal kubernetes registry and you want to pull images from there
            if (kubeClient().getNamespace(Environment.STRIMZI_ORG) != null) {
                for (String namespace : namespaces) {
                    LOGGER.debug("Setting group policy for Openshift registry in namespace: " + namespace);
                    Exec.exec(null, Arrays.asList("oc", "policy", "add-role-to-group", "system:image-puller", "system:serviceaccounts:" + namespace, "-n", Environment.STRIMZI_ORG), 0, Level.DEBUG, false);
                }
            }
        }
    }

    /**
     * Prepare environment for cluster operator which includes creation of namespaces, custom resources and operator
     * specific config files such as ServiceAccount, Roles and CRDs.
     * @param clientNamespace namespace which will be created and used as default by kube client
     */
    public void prepareEnvForOperator(ExtensionContext extensionContext, String clientNamespace) {
        prepareEnvForOperator(extensionContext, clientNamespace, Collections.singletonList(clientNamespace));
    }

    /**
     * Perform application of ServiceAccount, Roles and CRDs needed for proper cluster operator deployment.
     * Configuration files are loaded from packaging/install/cluster-operator directory.
     */
    public void applyClusterOperatorInstallFiles(String namespace) {
        List<File> operatorFiles = Arrays.stream(new File(CO_INSTALL_DIR).listFiles()).sorted()
            .filter(File::isFile)
            .filter(file ->
                !file.getName().matches(".*(Binding|Deployment)-.*"))
            .collect(Collectors.toList());

        for (File operatorFile : operatorFiles) {
            File createFile = operatorFile;

            if (createFile.getName().contains(Constants.CLUSTER_ROLE + "-")) {
                createFile = switchClusterRolesToRolesIfNeeded(createFile);
            }

            final String resourceType = createFile.getName().split("-")[1];
            LOGGER.debug("Installation resource type: {}", resourceType);

            switch (resourceType) {
                case Constants.ROLE:
                    Role role = TestUtils.configFromYaml(createFile, Role.class);
                    ResourceManager.getInstance().createResource(extensionContext, new RoleBuilder(role)
                        .editMetadata()
                            .withNamespace(namespace)
                        .endMetadata()
                        .build());
                    break;
                case Constants.CLUSTER_ROLE:
                    ClusterRole clusterRole = TestUtils.configFromYaml(createFile, ClusterRole.class);
                    ResourceManager.getInstance().createResource(extensionContext, clusterRole);
                    break;
                case Constants.SERVICE_ACCOUNT:
                    ServiceAccount serviceAccount = TestUtils.configFromYaml(createFile, ServiceAccount.class);
                    ResourceManager.getInstance().createResource(extensionContext, new ServiceAccountBuilder(serviceAccount)
                        .editMetadata()
                            .withNamespace(namespace)
                        .endMetadata()
                        .build());
                    break;
                case Constants.CONFIG_MAP:
                    ConfigMap configMap = TestUtils.configFromYaml(createFile, ConfigMap.class);
                    ResourceManager.getInstance().createResource(extensionContext, new ConfigMapBuilder(configMap)
                        .editMetadata()
                            .withNamespace(namespace)
                        .endMetadata()
                        .build());
                    break;
                case Constants.CUSTOM_RESOURCE_DEFINITION_SHORT:
                    CustomResourceDefinition customResourceDefinition = TestUtils.configFromYaml(createFile, CustomResourceDefinition.class);
                    ResourceManager.getInstance().createResource(extensionContext, customResourceDefinition);
                    break;
                default:
                    LOGGER.error("Unknown installation resource type: {}", resourceType);
                    throw new RuntimeException("Unknown installation resource type:" + resourceType);
            }
        }
    }

    /**
     * Replace all references to ClusterRole to Role.
     * This includes ClusterRoles themselves as well as RoleBindings that reference them.
     */
    public File switchClusterRolesToRolesIfNeeded(File oldFile) {
        boolean isRbacScope = this.extraEnvVars.stream().anyMatch(it -> it.getName().equals(Environment.STRIMZI_RBAC_SCOPE_ENV) && it.getValue().equals(Environment.STRIMZI_RBAC_SCOPE_NAMESPACE));

        if (Environment.isNamespaceRbacScope() || isRbacScope || this.clusterOperatorRBACType == ClusterOperatorRBACType.NAMESPACE) {
            try {
                final String[] fileNameArr = oldFile.getName().split("-");
                // change ClusterRole to Role
                fileNameArr[1] = "Role";
                final String changeFileName = Arrays.stream(fileNameArr).map(item -> "-" + item).collect(Collectors.joining()).substring(1);
                File tmpFile = File.createTempFile(changeFileName.replace(".yaml", ""), ".yaml");
                TestUtils.writeFile(tmpFile.getAbsolutePath(), TestUtils.readFile(oldFile).replace("ClusterRole", "Role"));
                LOGGER.info("Replaced ClusterRole for Role in {}", oldFile.getAbsolutePath());

                return tmpFile;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return oldFile;
        }
    }

    public void applyRoleBindings(ExtensionContext extensionContext, String namespace, String bindingsNamespace) {
        // 020-RoleBinding
        File roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/020-RoleBinding-strimzi-cluster-operator.yaml");
        RoleBindingResource.roleBinding(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace, bindingsNamespace);
        // 031-RoleBinding
        roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/031-RoleBinding-strimzi-cluster-operator-entity-operator-delegation.yaml");
        RoleBindingResource.roleBinding(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace, bindingsNamespace);
    }

    public void applyRoles(ExtensionContext extensionContext, String namespace) {
        File roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/020-ClusterRole-strimzi-cluster-operator-role.yaml");
        RoleResource.role(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace);

        roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/021-ClusterRole-strimzi-cluster-operator-role.yaml");
        RoleResource.role(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace);

        roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/030-ClusterRole-strimzi-kafka-broker.yaml");
        RoleResource.role(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace);

        roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/031-ClusterRole-strimzi-entity-operator.yaml");
        RoleResource.role(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace);

        roleFile = new File(Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/033-ClusterRole-strimzi-kafka-client.yaml");
        RoleResource.role(extensionContext, switchClusterRolesToRolesIfNeeded(roleFile).getAbsolutePath(), namespace);
    }

    /**
     * Method to apply Strimzi cluster operator specific RoleBindings and ClusterRoleBindings for specific namespaces.
     */
    public void applyBindings() {
        if (Environment.isNamespaceRbacScope() || this.clusterOperatorRBACType.equals(ClusterOperatorRBACType.NAMESPACE)) {
            // if roles only, only deploy the rolebindings
            for (String bindingsNamespace : bindingsNamespaces) {
                applyRoles(extensionContext, bindingsNamespace);
                applyRoleBindings(extensionContext, namespaceInstallTo, bindingsNamespace);
            }
            // RoleBindings also deployed in CO namespace
            applyRoleBindings(extensionContext, namespaceInstallTo, namespaceInstallTo);
        } else {
            for (String bindingsNamespace : bindingsNamespaces) {
                applyClusterRoleBindings(extensionContext, this.namespaceInstallTo);
                applyRoleBindings(extensionContext, this.namespaceInstallTo, bindingsNamespace);
            }
        }
        // cluster-wide installation
        if (namespaceToWatch.equals(Constants.WATCH_ALL_NAMESPACES)) {
            if (Environment.isNamespaceRbacScope()) {
                // we override namespaceToWatch to where cluster operator is installed because RBAC is
                // enabled and we have use only single namespace
                namespaceToWatch = namespaceInstallTo;
            } else {
                // if RBAC is enable we don't run tests in parallel mode and with that said we don't create another namespaces
                createClusterRoleBindings();
            }
        }
    }

    private static void applyClusterRoleBindings(ExtensionContext extensionContext, String namespace) {
        // 021-ClusterRoleBinding
        ClusterRoleBindingResource.clusterRoleBinding(extensionContext, Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/021-ClusterRoleBinding-strimzi-cluster-operator.yaml", namespace);
        // 030-ClusterRoleBinding
        ClusterRoleBindingResource.clusterRoleBinding(extensionContext, Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/030-ClusterRoleBinding-strimzi-cluster-operator-kafka-broker-delegation.yaml", namespace);
        // 033-ClusterRoleBinding
        ClusterRoleBindingResource.clusterRoleBinding(extensionContext, Constants.PATH_TO_PACKAGING_INSTALL_FILES + "/cluster-operator/033-ClusterRoleBinding-strimzi-cluster-operator-kafka-client-delegation.yaml", namespace);
    }

    public synchronized void unInstall() {
        if (IS_EMPTY.test(this)) {
            LOGGER.info(String.join("", Collections.nCopies(76, "=")));
            LOGGER.info("Skip un-installation of the cluster operator");
            LOGGER.info(String.join("", Collections.nCopies(76, "=")));
        } else {
            LOGGER.info(String.join("", Collections.nCopies(76, "=")));
            LOGGER.info("Un-installing cluster operator from {} namespace", namespaceInstallTo);
            LOGGER.info(String.join("", Collections.nCopies(76, "=")));
            BeforeAllOnce.getSharedExtensionContext().getStore(ExtensionContext.Namespace.GLOBAL).put(Constants.PREPARE_OPERATOR_ENV_KEY + namespaceInstallTo, null);

            // trigger that we will again create namespace
            if (Environment.isHelmInstall()) {
                helmResource.delete();
            } else if (Environment.isOlmInstall()) {
                olmResource.delete();
            } else {
                // clear all resources related to the extension context
                try {
                    if (!Environment.SKIP_TEARDOWN) {
                        ResourceManager.getInstance().deleteResources(BeforeAllOnce.getSharedExtensionContext());
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    e.printStackTrace();
                }

                KubeClusterResource.getInstance().deleteAllSetNamespaces();
            }
        }
    }

    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public synchronized void rollbackToDefaultConfiguration() {
        // un-install old cluster operator
        unInstall();

        // install new one with default configuration
        instanceHolder = defaultInstallation().createInstallation().runInstallation();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        SetupClusterOperator otherInstallation = (SetupClusterOperator) other;

        return operationTimeout == otherInstallation.operationTimeout &&
            reconciliationInterval == otherInstallation.reconciliationInterval &&
            Objects.equals(cluster, otherInstallation.cluster) &&
            Objects.equals(clusterOperatorName, otherInstallation.clusterOperatorName) &&
            Objects.equals(namespaceInstallTo, otherInstallation.namespaceInstallTo) &&
            Objects.equals(namespaceToWatch, otherInstallation.namespaceToWatch) &&
            Objects.equals(bindingsNamespaces, otherInstallation.bindingsNamespaces) &&
            Objects.equals(extraEnvVars, otherInstallation.extraEnvVars) &&
            Objects.equals(extraLabels, otherInstallation.extraLabels) &&
            clusterOperatorRBACType == otherInstallation.clusterOperatorRBACType;
    }
    @Override
    public int hashCode() {
        return Objects.hash(cluster, helmResource, olmResource, extensionContext,
            clusterOperatorName, namespaceInstallTo, namespaceToWatch, bindingsNamespaces, operationTimeout,
            extraEnvVars, extraLabels, clusterOperatorRBACType);
    }

    /**
     * Auxiliary method, which compares @{this} CO configuration with @code{otherClusterOperator} and return string of
     * differences.
     * @param otherClusterOperator cluster operator configuration, which is compared
     * @return string of attributes which differs from @code{otherClusterOperator} configuration each of separate row
     */
    public String diff(final SetupClusterOperator otherClusterOperator) {
        final StringBuilder diffString = new StringBuilder();

        if (this.operationTimeout != otherClusterOperator.operationTimeout) {
            diffString.append("operationTimeout=").append(this.operationTimeout).append(", ");
        }
        if (this.reconciliationInterval != otherClusterOperator.reconciliationInterval) {
            diffString.append("reconciliationInterval=").append(this.reconciliationInterval).append(", ");
        }
        if (!this.clusterOperatorName.equals(otherClusterOperator.clusterOperatorName)) {
            diffString.append("clusterOperatorName=").append(this.clusterOperatorName).append(", ");
        }
        if (!this.namespaceInstallTo.equals(otherClusterOperator.namespaceInstallTo)) {
            diffString.append("namespaceInstallTo=").append(this.namespaceInstallTo).append(", ");
        }
        if (!this.namespaceToWatch.equals(otherClusterOperator.namespaceToWatch)) {
            diffString.append("namespaceToWatch=").append(this.namespaceToWatch).append(", ");
        }
        if (this.bindingsNamespaces != otherClusterOperator.bindingsNamespaces) {
            diffString.append("bindingsNamespaces=").append(this.bindingsNamespaces).append(", ");
        }
        if (!this.extraEnvVars.equals(otherClusterOperator.extraEnvVars)) {
            diffString.append("extraEnvVars=").append(this.extraEnvVars).append(", ");
        }
        if (!this.extraLabels.equals(otherClusterOperator.extraLabels)) {
            diffString.append("extraLabels=").append(this.extraLabels).append(", ");
        }
        if (this.clusterOperatorRBACType != otherClusterOperator.clusterOperatorRBACType) {
            diffString.append("clusterOperatorRBACType=").append(this.clusterOperatorRBACType).append(", ");
        }

        return diffString.toString();
    }

    @Override
    public String toString() {
        return "SetupClusterOperator{" +
            "cluster=" + cluster +
            ", extensionContext=" + extensionContext +
            ", clusterOperatorName='" + clusterOperatorName + '\'' +
            ", namespaceInstallTo='" + namespaceInstallTo + '\'' +
            ", namespaceToWatch='" + namespaceToWatch + '\'' +
            ", bindingsNamespaces=" + bindingsNamespaces +
            ", operationTimeout=" + operationTimeout +
            ", reconciliationInterval=" + reconciliationInterval +
            ", extraEnvVars=" + extraEnvVars +
            ", extraLabels=" + extraLabels +
            ", clusterOperatorRBACType=" + clusterOperatorRBACType +
            ", testClassName='" + testClassName + '\'' +
            ", testMethodName='" + testMethodName + '\'' +
            '}';
    }

    public String prettyPrint() {
        return String.format("extensionContext=%s\n" +
                "clusterOperatorName=%s\n" +
                "namespaceInstallTo=%s\n" +
                "namespaceToWatch=%s\n" +
                "bindingsNamespaces=%s\n" +
                "operationTimeout=%s\n" +
                "reconciliationInterval=%s\n" +
                "clusterOperatorRBACType=%s\n" +
                "%s%s%s%s", extensionContext,
                clusterOperatorName,
                namespaceInstallTo,
                namespaceToWatch,
                bindingsNamespaces,
                operationTimeout,
                reconciliationInterval,
                clusterOperatorRBACType,
                extraEnvVars.isEmpty() ? "" : ", extraEnvVars=" + extraEnvVars + "\n",
                extraLabels.isEmpty() ? "" : "extraLabels=" + extraLabels + "\n",
                testClassName == null ? "" : "testClassName='" + testClassName + '\'' + "\n",
                testMethodName == null ? "" : "testMethodName='" + testMethodName + '\'' + "\n").trim();
    }

    /**
     * Auxiliary method for retrieve namespace where Cluster Operator installed.
     *
     * @return return value may vary on following:
     *    1. Olm installation           :   return KubeClusterResource.getInstance().getDefaultOlmNamespace() (based on platform)
     *    2. Helm &amp; installation    :   return @code{namespaceInstallTo}
     */
    public String getDeploymentNamespace() {
        if (Environment.isOlmInstall() && !Environment.isNamespaceRbacScope()) {
            return cluster.getDefaultOlmNamespace();
        }
        return namespaceInstallTo == null ? Constants.INFRA_NAMESPACE : namespaceInstallTo;
    }

    public OlmResource getOlmResource() {
        return olmResource;
    }
}
