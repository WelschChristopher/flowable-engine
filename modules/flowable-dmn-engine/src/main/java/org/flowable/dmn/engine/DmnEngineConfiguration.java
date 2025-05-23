/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.dmn.engine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.TransactionFactory;
import org.flowable.common.engine.api.delegate.FlowableFunctionDelegate;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.common.engine.impl.AbstractBuildableEngineConfiguration;
import org.flowable.common.engine.impl.AbstractEngineConfiguration;
import org.flowable.common.engine.impl.HasExpressionManagerEngineConfiguration;
import org.flowable.common.engine.impl.cfg.BeansConfigurationHelper;
import org.flowable.common.engine.impl.db.DbSqlSessionFactory;
import org.flowable.common.engine.impl.db.SchemaManager;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.common.engine.impl.interceptor.CommandInterceptor;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.common.engine.impl.interceptor.SessionFactory;
import org.flowable.common.engine.impl.javax.el.ELResolver;
import org.flowable.common.engine.impl.persistence.deploy.DefaultDeploymentCache;
import org.flowable.common.engine.impl.persistence.deploy.DeploymentCache;
import org.flowable.common.engine.impl.persistence.entity.TableDataManager;
import org.flowable.common.engine.impl.runtime.Clock;
import org.flowable.common.engine.impl.tenant.ChangeTenantIdManager;
import org.flowable.common.engine.impl.tenant.MyBatisChangeTenantIdManager;
import org.flowable.dmn.api.DmnChangeTenantIdEntityTypes;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.api.DmnEngineConfigurationApi;
import org.flowable.dmn.api.DmnHistoryService;
import org.flowable.dmn.api.DmnManagementService;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.dmn.engine.impl.DmnDecisionServiceImpl;
import org.flowable.dmn.engine.impl.DmnEngineImpl;
import org.flowable.dmn.engine.impl.DmnEnginePostEngineBuildConsumer;
import org.flowable.dmn.engine.impl.DmnHistoryServiceImpl;
import org.flowable.dmn.engine.impl.DmnManagementServiceImpl;
import org.flowable.dmn.engine.impl.DmnRepositoryServiceImpl;
import org.flowable.dmn.engine.impl.RuleEngineExecutorImpl;
import org.flowable.dmn.engine.impl.agenda.DefaultDmnEngineAgendaFactory;
import org.flowable.dmn.engine.impl.agenda.DmnEngineAgendaFactory;
import org.flowable.dmn.engine.impl.agenda.DmnEngineAgendaSessionFactory;
import org.flowable.dmn.engine.impl.cfg.StandaloneDmnEngineConfiguration;
import org.flowable.dmn.engine.impl.cfg.StandaloneInMemDmnEngineConfiguration;
import org.flowable.dmn.engine.impl.db.DmnDbSchemaManager;
import org.flowable.dmn.engine.impl.db.EntityDependencyOrder;
import org.flowable.dmn.engine.impl.deployer.CachingAndArtifactsManager;
import org.flowable.dmn.engine.impl.deployer.DecisionRequirementsDiagramHelper;
import org.flowable.dmn.engine.impl.deployer.DmnDeployer;
import org.flowable.dmn.engine.impl.deployer.DmnDeploymentHelper;
import org.flowable.dmn.engine.impl.deployer.ParsedDeploymentBuilderFactory;
import org.flowable.dmn.engine.impl.el.CollectionsFunctionDelegate;
import org.flowable.dmn.engine.impl.el.FlowableAddDateFunctionDelegate;
import org.flowable.dmn.engine.impl.el.FlowableCurrentDateFunctionDelegate;
import org.flowable.dmn.engine.impl.el.FlowableSubtractDateFunctionDelegate;
import org.flowable.dmn.engine.impl.el.FlowableToDateFunctionDelegate;
import org.flowable.dmn.engine.impl.hitpolicy.AbstractHitPolicy;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyAny;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyCollect;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyFirst;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyOutputOrder;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyPriority;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyRuleOrder;
import org.flowable.dmn.engine.impl.hitpolicy.HitPolicyUnique;
import org.flowable.dmn.engine.impl.interceptor.DmnCommandInvoker;
import org.flowable.dmn.engine.impl.parser.DmnParseFactory;
import org.flowable.dmn.engine.impl.persistence.deploy.DecisionCacheEntry;
import org.flowable.dmn.engine.impl.persistence.deploy.Deployer;
import org.flowable.dmn.engine.impl.persistence.deploy.DeploymentManager;
import org.flowable.dmn.engine.impl.persistence.entity.DecisionEntityManager;
import org.flowable.dmn.engine.impl.persistence.entity.DefinitionEntityManagerImpl;
import org.flowable.dmn.engine.impl.persistence.entity.DmnDeploymentEntityManager;
import org.flowable.dmn.engine.impl.persistence.entity.DmnDeploymentEntityManagerImpl;
import org.flowable.dmn.engine.impl.persistence.entity.DmnResourceEntityManager;
import org.flowable.dmn.engine.impl.persistence.entity.DmnResourceEntityManagerImpl;
import org.flowable.dmn.engine.impl.persistence.entity.HistoricDecisionExecutionEntityManager;
import org.flowable.dmn.engine.impl.persistence.entity.HistoricDecisionExecutionEntityManagerImpl;
import org.flowable.dmn.engine.impl.persistence.entity.data.DecisionDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.DmnDeploymentDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.DmnResourceDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.HistoricDecisionExecutionDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.impl.MybatisDecisionDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.impl.MybatisDmnDeploymentDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.impl.MybatisDmnResourceDataManager;
import org.flowable.dmn.engine.impl.persistence.entity.data.impl.MybatisHistoricDecisionExecutionDataManager;
import org.flowable.dmn.image.DecisionRequirementsDiagramGenerator;
import org.flowable.dmn.image.impl.DefaultDecisionRequirementsDiagramGenerator;

public class DmnEngineConfiguration extends AbstractBuildableEngineConfiguration<DmnEngine>
        implements DmnEngineConfigurationApi, HasExpressionManagerEngineConfiguration {

    public static final String DEFAULT_MYBATIS_MAPPING_FILE = "org/flowable/dmn/db/mapping/mappings.xml";

    protected String dmnEngineName = DmnEngines.NAME_DEFAULT;

    protected DmnEngineAgendaFactory dmnEngineAgendaFactory;

    // SERVICES
    // /////////////////////////////////////////////////////////////////

    protected DmnManagementService dmnManagementService = new DmnManagementServiceImpl(this);
    protected DmnRepositoryService dmnRepositoryService = new DmnRepositoryServiceImpl();
    protected DmnDecisionService ruleService = new DmnDecisionServiceImpl(this);
    protected DmnHistoryService dmnHistoryService = new DmnHistoryServiceImpl();
    protected RuleEngineExecutor ruleEngineExecutor;

    // DATA MANAGERS ///////////////////////////////////////////////////

    protected DmnDeploymentDataManager deploymentDataManager;
    protected DecisionDataManager decisionDataManager;
    protected DmnResourceDataManager resourceDataManager;
    protected HistoricDecisionExecutionDataManager historicDecisionExecutionDataManager;

    // ENTITY MANAGERS /////////////////////////////////////////////////
    protected DmnDeploymentEntityManager deploymentEntityManager;
    protected DecisionEntityManager decisionEntityManager;
    protected DmnResourceEntityManager resourceEntityManager;
    protected HistoricDecisionExecutionEntityManager historicDecisionExecutionEntityManager;

    protected ChangeTenantIdManager changeTenantIdManager;

    // EXPRESSION MANAGER /////////////////////////////////////////////
    protected ExpressionManager expressionManager;
    protected Collection<Consumer<ExpressionManager>> expressionManagerConfigurers;
    protected List<FlowableFunctionDelegate> flowableFunctionDelegates;
    protected List<FlowableFunctionDelegate> customFlowableFunctionDelegates;
    protected Collection<ELResolver> preDefaultELResolvers;
    protected Collection<ELResolver> preBeanELResolvers;
    protected Collection<ELResolver> postDefaultELResolvers;

    // DEPLOYERS
    // ////////////////////////////////////////////////////////////////

    protected DmnDeployer dmnDeployer;
    protected DmnParseFactory dmnParseFactory;
    protected ParsedDeploymentBuilderFactory parsedDeploymentBuilderFactory;
    protected DmnDeploymentHelper dmnDeploymentHelper;
    protected CachingAndArtifactsManager cachingAndArtifactsManager;
    protected List<Deployer> customPreDeployers;
    protected List<Deployer> customPostDeployers;
    protected List<Deployer> deployers;
    protected DeploymentManager deploymentManager;
    protected DecisionRequirementsDiagramHelper decisionRequirementsDiagramHelper;

    /**
     * Decision requirements diagram generator. Default value is DefaultDecisionRequirementsDiagramGenerator
     */
    protected DecisionRequirementsDiagramGenerator decisionRequirementsDiagramGenerator;

    protected boolean isCreateDiagramOnDeploy = true;

    protected String decisionFontName = "Arial";
    protected String labelFontName = "Arial";
    protected String annotationFontName = "Arial";

    protected boolean historyEnabled;

    protected int decisionCacheLimit = -1; // By default, no limit
    protected DeploymentCache<DecisionCacheEntry> definitionCache;

    // HIT POLICIES
    protected Map<String, AbstractHitPolicy> hitPolicyBehaviors;
    protected Map<String, AbstractHitPolicy> customHitPolicyBehaviors;


    /**
     * Set this to true if you want to have extra checks on the DMN xml that is parsed. See http://www.jorambarrez.be/blog/2013/02/19/uploading-a-funny-xml -can-bring-down-your-server/
     *
     * Unfortunately, this feature is not available on some platforms (JDK 6, JBoss), hence the reason why it is disabled by default. If your platform allows the use of StaxSource during XML parsing,
     * do enable it.
     */
    protected boolean enableSafeDmnXml;


    /**
     * Set this to false if you want to ignore the decision table hit policy validity checks to result in an failed decision table state.
     *
     * A result is that intermediate results created up to the point the validation error occurs are returned.
     */
    protected boolean strictMode = true;

    public static DmnEngineConfiguration createDmnEngineConfigurationFromResourceDefault() {
        return createDmnEngineConfigurationFromResource("flowable.dmn.cfg.xml", "dmnEngineConfiguration");
    }

    public static DmnEngineConfiguration createDmnEngineConfigurationFromResource(String resource) {
        return createDmnEngineConfigurationFromResource(resource, "dmnEngineConfiguration");
    }

    public static DmnEngineConfiguration createDmnEngineConfigurationFromResource(String resource, String beanName) {
        return (DmnEngineConfiguration) BeansConfigurationHelper.parseEngineConfigurationFromResource(resource, beanName);
    }

    public static DmnEngineConfiguration createDmnEngineConfigurationFromInputStream(InputStream inputStream) {
        return createDmnEngineConfigurationFromInputStream(inputStream, "dmnEngineConfiguration");
    }

    public static DmnEngineConfiguration createDmnEngineConfigurationFromInputStream(InputStream inputStream, String beanName) {
        return (DmnEngineConfiguration) BeansConfigurationHelper.parseEngineConfigurationFromInputStream(inputStream, beanName);
    }

    public static DmnEngineConfiguration createStandaloneDmnEngineConfiguration() {
        return new StandaloneDmnEngineConfiguration();
    }

    public static DmnEngineConfiguration createStandaloneInMemDmnEngineConfiguration() {
        return new StandaloneInMemDmnEngineConfiguration();
    }

    // buildDmnEngine
    // ///////////////////////////////////////////////////////

    @Override
    protected DmnEngine createEngine() {
        return new DmnEngineImpl(this);
    }

    @Override
    protected Consumer<DmnEngine> createPostEngineBuildConsumer() {
        return new DmnEnginePostEngineBuildConsumer();
    }

    public DmnEngine buildDmnEngine() {
        return buildEngine();
    }

    // init
    // /////////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        initEngineConfigurations();
        initClock();
        initObjectMapper();
        initFunctionDelegates();
        initBeans();
        initExpressionManager();
        initCommandContextFactory();
        initTransactionContextFactory();
        initCommandExecutors();
        initIdGenerator();
        initDmnEngineAgendaFactory();

        if (usingRelationalDatabase) {
            initDataSource();
        }
        
        if (usingRelationalDatabase || usingSchemaMgmt) {
            initSchemaManager();
            initSchemaManagementCommand();
        }

        initTransactionFactory();

        if (usingRelationalDatabase) {
            initSqlSessionFactory();
        }

        initSessionFactories();
        initServices();
        initChangeTenantIdManager();
        initDataManagers();
        initEntityManagers();
        initDeployers();
        initHitPolicyBehaviors();
        initRuleEngineExecutor();
        initDecisionRequirementsDiagramGenerator();
    }

    // services
    // /////////////////////////////////////////////////////////////////

    protected void initServices() {
        initService(dmnManagementService);
        initService(dmnRepositoryService);
        initService(ruleService);
        initService(dmnHistoryService);
    }

    // Data managers
    ///////////////////////////////////////////////////////////

    @Override
    public void initDataManagers() {
        super.initDataManagers();
        if (deploymentDataManager == null) {
            deploymentDataManager = new MybatisDmnDeploymentDataManager(this);
        }
        if (decisionDataManager == null) {
            decisionDataManager = new MybatisDecisionDataManager(this);
        }
        if (resourceDataManager == null) {
            resourceDataManager = new MybatisDmnResourceDataManager(this);
        }
        if (historicDecisionExecutionDataManager == null) {
            historicDecisionExecutionDataManager = new MybatisHistoricDecisionExecutionDataManager(this);
        }
    }

    @Override
    public void initEntityManagers() {
        super.initEntityManagers();
        if (deploymentEntityManager == null) {
            deploymentEntityManager = new DmnDeploymentEntityManagerImpl(this, deploymentDataManager);
        }
        if (decisionEntityManager == null) {
            decisionEntityManager = new DefinitionEntityManagerImpl(this, decisionDataManager);
        }
        if (resourceEntityManager == null) {
            resourceEntityManager = new DmnResourceEntityManagerImpl(this, resourceDataManager);
        }
        if (historicDecisionExecutionEntityManager == null) {
            historicDecisionExecutionEntityManager = new HistoricDecisionExecutionEntityManagerImpl(this, historicDecisionExecutionDataManager);
        }
    }

    // data model
    // ///////////////////////////////////////////////////////////////

    @Override
    protected SchemaManager createEngineSchemaManager() {
        return new DmnDbSchemaManager();
    }

    // session factories ////////////////////////////////////////////////////////

    @Override
    public void initDbSqlSessionFactory() {
        if (dbSqlSessionFactory == null) {
            dbSqlSessionFactory = createDbSqlSessionFactory();
            dbSqlSessionFactory.setDatabaseType(databaseType);
            dbSqlSessionFactory.setSqlSessionFactory(sqlSessionFactory);
            dbSqlSessionFactory.setDatabaseTablePrefix(databaseTablePrefix);
            dbSqlSessionFactory.setTablePrefixIsSchema(tablePrefixIsSchema);
            dbSqlSessionFactory.setDatabaseCatalog(databaseCatalog);
            dbSqlSessionFactory.setDatabaseSchema(databaseSchema);
            addSessionFactory(dbSqlSessionFactory);
        }
        initDbSqlSessionFactoryEntitySettings();
    }

    @Override
    public DbSqlSessionFactory createDbSqlSessionFactory() {
        return new DbSqlSessionFactory(usePrefixId);
    }

    @Override
    protected void initDbSqlSessionFactoryEntitySettings() {
        defaultInitDbSqlSessionFactoryEntitySettings(EntityDependencyOrder.INSERT_ORDER, EntityDependencyOrder.DELETE_ORDER);
    }

    // command executors
    // ////////////////////////////////////////////////////////

    @Override
    public void initCommandExecutors() {
        initDefaultCommandConfig();
        initSchemaCommandConfig();
        initCommandInvoker();
        initCommandInterceptors();
        initCommandExecutor();
    }

    @Override
    public String getEngineCfgKey() {
        return EngineConfigurationConstants.KEY_DMN_ENGINE_CONFIG;
    }
    
    @Override
    public String getEngineScopeType() {
        return ScopeTypes.DMN;
    }

    @Override
    public CommandInterceptor createTransactionInterceptor() {
        return null;
    }

    public void initFunctionDelegates() {
        if (this.flowableFunctionDelegates == null) {
            this.flowableFunctionDelegates = new ArrayList<>();
            // dates
            this.flowableFunctionDelegates.add(new FlowableToDateFunctionDelegate());
            this.flowableFunctionDelegates.add(new FlowableSubtractDateFunctionDelegate());
            this.flowableFunctionDelegates.add(new FlowableAddDateFunctionDelegate());
            this.flowableFunctionDelegates.add(new FlowableCurrentDateFunctionDelegate());
            // collections
            this.flowableFunctionDelegates.add(new CollectionsFunctionDelegate());
        }

        if (this.customFlowableFunctionDelegates != null) {
            this.flowableFunctionDelegates.addAll(this.customFlowableFunctionDelegates);
        }
    }

    public void initChangeTenantIdManager() {
        if (changeTenantIdManager == null) {
            changeTenantIdManager = new MyBatisChangeTenantIdManager(commandExecutor, ScopeTypes.DMN,
                    Collections.singleton(DmnChangeTenantIdEntityTypes.HISTORIC_DECISION_EXECUTIONS));
        }
    }

    public void initExpressionManager() {
        if (expressionManager == null) {
            DefaultExpressionManager dmnExpressionManager = new DefaultExpressionManager(beans);
            if (preDefaultELResolvers != null) {
                preDefaultELResolvers.forEach(dmnExpressionManager::addPreDefaultResolver);
            }

            if (preBeanELResolvers != null) {
                preBeanELResolvers.forEach(dmnExpressionManager::addPreBeanResolver);
            }

            if (postDefaultELResolvers != null) {
                postDefaultELResolvers.forEach(dmnExpressionManager::addPostDefaultResolver);
            }

            if (expressionManagerConfigurers != null) {
                expressionManagerConfigurers.forEach(configurer -> configurer.accept(dmnExpressionManager));
            }

            expressionManager = dmnExpressionManager;
        }

        expressionManager.setFunctionDelegates(flowableFunctionDelegates);
    }

    @Override
    public void initCommandInvoker() {
        if (commandInvoker == null) {
            commandInvoker = new DmnCommandInvoker(agendaOperationExecutionListeners);
        }
    }
    public void initDmnEngineAgendaFactory() {
        if (dmnEngineAgendaFactory == null) {
            dmnEngineAgendaFactory = new DefaultDmnEngineAgendaFactory();
        }
    }

    @Override
    public void initSessionFactories() {
        super.initSessionFactories();
        addSessionFactory(new DmnEngineAgendaSessionFactory(dmnEngineAgendaFactory));
    }

    // deployers
    // ////////////////////////////////////////////////////////////////

    protected void initDeployers() {
        if (dmnParseFactory == null) {
            dmnParseFactory = new DmnParseFactory();
        }

        if (this.dmnDeployer == null) {
            this.deployers = new ArrayList<>();
            if (customPreDeployers != null) {
                this.deployers.addAll(customPreDeployers);
            }
            this.deployers.addAll(getDefaultDeployers());
            if (customPostDeployers != null) {
                this.deployers.addAll(customPostDeployers);
            }
        }

        // Decision cache
        if (definitionCache == null) {
            if (decisionCacheLimit <= 0) {
                definitionCache = new DefaultDeploymentCache<>();
            } else {
                definitionCache = new DefaultDeploymentCache<>(decisionCacheLimit);
            }
        }

        deploymentManager = new DeploymentManager(definitionCache, this);
        deploymentManager.setDeployers(deployers);
        deploymentManager.setDeploymentEntityManager(deploymentEntityManager);
        deploymentManager.setDecisionEntityManager(decisionEntityManager);
    }

    public Collection<? extends Deployer> getDefaultDeployers() {
        List<Deployer> defaultDeployers = new ArrayList<>();

        if (dmnDeployer == null) {
            dmnDeployer = new DmnDeployer();
        }

        initDmnDeployerDependencies();

        dmnDeployer.setIdGenerator(idGenerator);
        dmnDeployer.setParsedDeploymentBuilderFactory(parsedDeploymentBuilderFactory);
        dmnDeployer.setDmnDeploymentHelper(dmnDeploymentHelper);
        dmnDeployer.setCachingAndArtifactsManager(cachingAndArtifactsManager);
        dmnDeployer.setUsePrefixId(usePrefixId);
        dmnDeployer.setDecisionRequirementsDiagramHelper(decisionRequirementsDiagramHelper);

        defaultDeployers.add(dmnDeployer);
        return defaultDeployers;
    }

    public void initDmnDeployerDependencies() {
        if (parsedDeploymentBuilderFactory == null) {
            parsedDeploymentBuilderFactory = new ParsedDeploymentBuilderFactory();
        }
        if (parsedDeploymentBuilderFactory.getDmnParseFactory() == null) {
            parsedDeploymentBuilderFactory.setDmnParseFactory(dmnParseFactory);
        }

        if (dmnDeploymentHelper == null) {
            dmnDeploymentHelper = new DmnDeploymentHelper();
        }

        if (cachingAndArtifactsManager == null) {
            cachingAndArtifactsManager = new CachingAndArtifactsManager();
        }

        if (decisionRequirementsDiagramHelper == null) {
            decisionRequirementsDiagramHelper = new DecisionRequirementsDiagramHelper();
        }
    }

    // myBatis SqlSessionFactory
    // ////////////////////////////////////////////////

    @Override
    public InputStream getMyBatisXmlConfigurationStream() {
        return getResourceAsStream(DEFAULT_MYBATIS_MAPPING_FILE);
    }


    // hit policy behaviors
    /////////////////////////////////////////////////////////
    public void initHitPolicyBehaviors() {
        if (hitPolicyBehaviors == null) {
            hitPolicyBehaviors = getDefaultHitPolicyBehaviors();
        }

        if (customHitPolicyBehaviors != null) {
            hitPolicyBehaviors.putAll(customHitPolicyBehaviors);
        }
    }

    public Map<String, AbstractHitPolicy> getDefaultHitPolicyBehaviors() {
        Map<String, AbstractHitPolicy> defaultHitPolicyBehaviors = new HashMap<>();

        // UNIQUE
        AbstractHitPolicy hitPolicyUniqueBehavior = new HitPolicyUnique();
        defaultHitPolicyBehaviors.put(hitPolicyUniqueBehavior.getHitPolicyName(), hitPolicyUniqueBehavior);

        // ANY
        AbstractHitPolicy hitPolicyAnyBehavior = new HitPolicyAny();
        defaultHitPolicyBehaviors.put(hitPolicyAnyBehavior.getHitPolicyName(), hitPolicyAnyBehavior);

        // FIRST
        AbstractHitPolicy hitPolicyFirstBehavior = new HitPolicyFirst();
        defaultHitPolicyBehaviors.put(hitPolicyFirstBehavior.getHitPolicyName(), hitPolicyFirstBehavior);

        // RULE ORDER
        AbstractHitPolicy HitPolicyRuleOrderBehavior = new HitPolicyRuleOrder();
        defaultHitPolicyBehaviors.put(HitPolicyRuleOrderBehavior.getHitPolicyName(), HitPolicyRuleOrderBehavior);

        // PRIORITY
        AbstractHitPolicy HitPolicyPriorityBehavior = new HitPolicyPriority();
        defaultHitPolicyBehaviors.put(HitPolicyPriorityBehavior.getHitPolicyName(), HitPolicyPriorityBehavior);

        // OUTPUT ORDER
        AbstractHitPolicy HitPolicyOutputOrderBehavior = new HitPolicyOutputOrder();
        defaultHitPolicyBehaviors.put(HitPolicyOutputOrderBehavior.getHitPolicyName(), HitPolicyOutputOrderBehavior);

        // COLLECT
        AbstractHitPolicy HitPolicyCollectBehavior = new HitPolicyCollect();
        defaultHitPolicyBehaviors.put(HitPolicyCollectBehavior.getHitPolicyName(), HitPolicyCollectBehavior);

        return defaultHitPolicyBehaviors;
    }

    // rule engine executor
    /////////////////////////////////////////////////////////////
    public void initRuleEngineExecutor() {
    	if (ruleEngineExecutor == null) {
	        ruleEngineExecutor = new RuleEngineExecutorImpl(hitPolicyBehaviors, expressionManager, objectMapper, this);
	        
    	} else {
    	    if (ruleEngineExecutor.getExpressionManager() == null) {
    	        ruleEngineExecutor.setExpressionManager(expressionManager);
    	    }
    	    
    	    if (ruleEngineExecutor.getHitPolicyBehaviors() == null) {
    	        ruleEngineExecutor.setHitPolicyBehaviors(hitPolicyBehaviors);
    	    }
    	    
    	    if (ruleEngineExecutor.getObjectMapper() == null) {
    	        ruleEngineExecutor.setObjectMapper(objectMapper);
    	    }
    	}
    }
    // decision requirements diagram
    /////////////////////////////////////////////////////////////
    public void initDecisionRequirementsDiagramGenerator() {
        if (decisionRequirementsDiagramGenerator == null) {
            decisionRequirementsDiagramGenerator = new DefaultDecisionRequirementsDiagramGenerator();
        }
    }

    public void initDecisionRequirementsDiagramHelper() {
        if (decisionRequirementsDiagramHelper == null) {
            decisionRequirementsDiagramHelper = new DecisionRequirementsDiagramHelper();
        }
    }

    // getters and setters
    // //////////////////////////////////////////////////////

    @Override
    public String getEngineName() {
        return dmnEngineName;
    }

    public DmnEngineConfiguration setEngineName(String dmnEngineName) {
        this.dmnEngineName = dmnEngineName;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcDriver(String jdbcDriver) {
        this.jdbcDriver = jdbcDriver;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcUsername(String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcMaxActiveConnections(int jdbcMaxActiveConnections) {
        this.jdbcMaxActiveConnections = jdbcMaxActiveConnections;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcMaxIdleConnections(int jdbcMaxIdleConnections) {
        this.jdbcMaxIdleConnections = jdbcMaxIdleConnections;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcMaxCheckoutTime(int jdbcMaxCheckoutTime) {
        this.jdbcMaxCheckoutTime = jdbcMaxCheckoutTime;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcMaxWaitTime(int jdbcMaxWaitTime) {
        this.jdbcMaxWaitTime = jdbcMaxWaitTime;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcPingEnabled(boolean jdbcPingEnabled) {
        this.jdbcPingEnabled = jdbcPingEnabled;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcPingConnectionNotUsedFor(int jdbcPingConnectionNotUsedFor) {
        this.jdbcPingConnectionNotUsedFor = jdbcPingConnectionNotUsedFor;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcDefaultTransactionIsolationLevel(int jdbcDefaultTransactionIsolationLevel) {
        this.jdbcDefaultTransactionIsolationLevel = jdbcDefaultTransactionIsolationLevel;
        return this;
    }

    @Override
    public DmnEngineConfiguration setJdbcPingQuery(String jdbcPingQuery) {
        this.jdbcPingQuery = jdbcPingQuery;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDataSourceJndiName(String dataSourceJndiName) {
        this.dataSourceJndiName = dataSourceJndiName;
        return this;
    }

    @Override
    public DmnManagementService getDmnManagementService() {
        return dmnManagementService;
    }

    public DmnEngineConfiguration setDmnManagementService(DmnManagementService dmnManagementService) {
        this.dmnManagementService = dmnManagementService;
        return this;
    }

    @Override
    public DmnRepositoryService getDmnRepositoryService() {
        return dmnRepositoryService;
    }

    public DmnEngineConfiguration setDmnRepositoryService(DmnRepositoryService dmnRepositoryService) {
        this.dmnRepositoryService = dmnRepositoryService;
        return this;
    }

    @Override
    public DmnDecisionService getDmnDecisionService() {
        return ruleService;
    }

    public DmnEngineConfiguration setDmnRuleService(DmnDecisionService ruleService) {
        this.ruleService = ruleService;
        return this;
    }

    @Override
    public DmnHistoryService getDmnHistoryService() {
        return dmnHistoryService;
    }

    public DmnEngineConfiguration setDmnHistoryService(DmnHistoryService dmnHistoryService) {
        this.dmnHistoryService = dmnHistoryService;
        return this;
    }

    public RuleEngineExecutor getRuleEngineExecutor() {
        return ruleEngineExecutor;
    }

    public DmnEngineConfiguration setRuleEngineExecutor(RuleEngineExecutor ruleEngineExecutor) {
        this.ruleEngineExecutor = ruleEngineExecutor;
        return this;
    }

    public DeploymentManager getDeploymentManager() {
        return deploymentManager;
    }

    public DmnEngineConfiguration getDmnEngineConfiguration() {
        return this;
    }

    public ChangeTenantIdManager getChangeTenantIdManager() {
        return changeTenantIdManager;
    }

    public DmnEngineConfiguration setChangeTenantIdManager(ChangeTenantIdManager changeTenantIdManager) {
        this.changeTenantIdManager = changeTenantIdManager;
        return this;
    }

    @Override
    public ExpressionManager getExpressionManager() {
        return expressionManager;
    }

    @Override
    public DmnEngineConfiguration setExpressionManager(ExpressionManager expressionManager) {
        this.expressionManager = expressionManager;
        return this;
    }

    public Collection<Consumer<ExpressionManager>> getExpressionManagerConfigurers() {
        return expressionManagerConfigurers;
    }

    @Override
    public AbstractEngineConfiguration addExpressionManagerConfigurer(Consumer<ExpressionManager> configurer) {
        if (this.expressionManagerConfigurers == null) {
            this.expressionManagerConfigurers = new ArrayList<>();
        }
        this.expressionManagerConfigurers.add(configurer);
        return this;
    }

    public List<FlowableFunctionDelegate> getFlowableFunctionDelegates() {
        return flowableFunctionDelegates;
    }

    public DmnEngineConfiguration setFlowableFunctionDelegates(List<FlowableFunctionDelegate> flowableFunctionDelegates) {
        this.flowableFunctionDelegates = flowableFunctionDelegates;
        return this;
    }

    public List<FlowableFunctionDelegate> getCustomFlowableFunctionDelegates() {
        return customFlowableFunctionDelegates;
    }

    public DmnEngineConfiguration setCustomFlowableFunctionDelegates(List<FlowableFunctionDelegate> customFlowableFunctionDelegates) {
        this.customFlowableFunctionDelegates = customFlowableFunctionDelegates;
        return this;
    }

    public Collection<ELResolver> getPreDefaultELResolvers() {
        return preDefaultELResolvers;
    }

    public DmnEngineConfiguration setPreDefaultELResolvers(Collection<ELResolver> preDefaultELResolvers) {
        this.preDefaultELResolvers = preDefaultELResolvers;
        return this;
    }

    public DmnEngineConfiguration addPreDefaultELResolver(ELResolver elResolver) {
        if (this.preDefaultELResolvers == null) {
            this.preDefaultELResolvers = new ArrayList<>();
        }

        this.preDefaultELResolvers.add(elResolver);
        return this;
    }

    public Collection<ELResolver> getPreBeanELResolvers() {
        return preBeanELResolvers;
    }

    public DmnEngineConfiguration setPreBeanELResolvers(Collection<ELResolver> preBeanELResolvers) {
        this.preBeanELResolvers = preBeanELResolvers;
        return this;
    }

    public DmnEngineConfiguration addPreBeanELResolver(ELResolver elResolver) {
        if (this.preBeanELResolvers == null) {
            this.preBeanELResolvers = new ArrayList<>();
        }

        this.preBeanELResolvers.add(elResolver);
        return this;
    }

    public Collection<ELResolver> getPostDefaultELResolvers() {
        return postDefaultELResolvers;
    }

    public DmnEngineConfiguration setPostDefaultELResolvers(Collection<ELResolver> postDefaultELResolvers) {
        this.postDefaultELResolvers = postDefaultELResolvers;
        return this;
    }

    public DmnEngineConfiguration addPostDefaultELResolver(ELResolver elResolver) {
        if (this.postDefaultELResolvers == null) {
            this.postDefaultELResolvers = new ArrayList<>();
        }

        this.postDefaultELResolvers.add(elResolver);
        return this;
    }

    public DmnDeployer getDmnDeployer() {
        return dmnDeployer;
    }

    public DmnEngineConfiguration setDmnDeployer(DmnDeployer dmnDeployer) {
        this.dmnDeployer = dmnDeployer;
        return this;
    }

    public DmnParseFactory getDmnParseFactory() {
        return dmnParseFactory;
    }

    public DmnEngineConfiguration setDmnParseFactory(DmnParseFactory dmnParseFactory) {
        this.dmnParseFactory = dmnParseFactory;
        return this;
    }

    public boolean isHistoryEnabled() {
        return historyEnabled;
    }

    public DmnEngineConfiguration setHistoryEnabled(boolean historyEnabled) {
        this.historyEnabled = historyEnabled;
        return this;
    }

    public int getDecisionCacheLimit() {
        return decisionCacheLimit;
    }

    public DmnEngineConfiguration setDecisionCacheLimit(int decisionCacheLimit) {
        this.decisionCacheLimit = decisionCacheLimit;
        return this;
    }

    public DeploymentCache<DecisionCacheEntry> getDefinitionCache() {
        return definitionCache;
    }

    public DmnEngineConfiguration setDefinitionCache(DeploymentCache<DecisionCacheEntry> definitionCache) {
        this.definitionCache = definitionCache;
        return this;
    }

    public DmnDeploymentDataManager getDeploymentDataManager() {
        return deploymentDataManager;
    }

    public DmnEngineConfiguration setDeploymentDataManager(DmnDeploymentDataManager deploymentDataManager) {
        this.deploymentDataManager = deploymentDataManager;
        return this;
    }

    public DecisionDataManager getDecisionDataManager() {
        return decisionDataManager;
    }

    public DmnEngineConfiguration setDecisionDataManager(DecisionDataManager decisionDataManager) {
        this.decisionDataManager = decisionDataManager;
        return this;
    }

    public DmnResourceDataManager getResourceDataManager() {
        return resourceDataManager;
    }

    public DmnEngineConfiguration setResourceDataManager(DmnResourceDataManager resourceDataManager) {
        this.resourceDataManager = resourceDataManager;
        return this;
    }

    public HistoricDecisionExecutionDataManager getHistoricDecisionExecutionDataManager() {
        return historicDecisionExecutionDataManager;
    }

    public DmnEngineConfiguration setHistoricDecisionExecutionDataManager(HistoricDecisionExecutionDataManager historicDecisionExecutionDataManager) {
        this.historicDecisionExecutionDataManager = historicDecisionExecutionDataManager;
        return this;
    }

    public DmnDeploymentEntityManager getDeploymentEntityManager() {
        return deploymentEntityManager;
    }

    public DmnEngineConfiguration setDeploymentEntityManager(DmnDeploymentEntityManager deploymentEntityManager) {
        this.deploymentEntityManager = deploymentEntityManager;
        return this;
    }

    public DecisionEntityManager getDecisionEntityManager() {
        return decisionEntityManager;
    }

    public DmnEngineConfiguration setDecisionEntityManager(DecisionEntityManager decisionEntityManager) {
        this.decisionEntityManager = decisionEntityManager;
        return this;
    }

    public HistoricDecisionExecutionEntityManager getHistoricDecisionExecutionEntityManager() {
        return historicDecisionExecutionEntityManager;
    }

    public DmnEngineConfiguration setHistoricDecisionExecutionEntityManager(HistoricDecisionExecutionEntityManager historicDecisionExecutionEntityManager) {
        this.historicDecisionExecutionEntityManager = historicDecisionExecutionEntityManager;
        return this;
    }

    public DmnResourceEntityManager getResourceEntityManager() {
        return resourceEntityManager;
    }

    public DmnEngineConfiguration setResourceEntityManager(DmnResourceEntityManager resourceEntityManager) {
        this.resourceEntityManager = resourceEntityManager;
        return this;
    }

    public DmnEngineAgendaFactory getDmnEngineAgendaFactory() {
        return dmnEngineAgendaFactory;
    }

    public DmnEngineConfiguration setDmnEngineAgendaFactory(DmnEngineAgendaFactory dmnEngineAgendaFactory) {
        this.dmnEngineAgendaFactory = dmnEngineAgendaFactory;
        return this;
    }

    @Override
    public TableDataManager getTableDataManager() {
        return tableDataManager;
    }

    @Override
    public DmnEngineConfiguration setTableDataManager(TableDataManager tableDataManager) {
        this.tableDataManager = tableDataManager;
        return this;
    }

    @Override
    public DmnEngineConfiguration setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
        return this;
    }

    @Override
    public DmnEngineConfiguration setTransactionFactory(TransactionFactory transactionFactory) {
        this.transactionFactory = transactionFactory;
        return this;
    }

    @Override
    public DmnEngineConfiguration setCustomMybatisMappers(Set<Class<?>> customMybatisMappers) {
        this.customMybatisMappers = customMybatisMappers;
        return this;
    }

    @Override
    public DmnEngineConfiguration setCustomMybatisXMLMappers(Set<String> customMybatisXMLMappers) {
        this.customMybatisXMLMappers = customMybatisXMLMappers;
        return this;
    }

    @Override
    public DmnEngineConfiguration setCustomSessionFactories(List<SessionFactory> customSessionFactories) {
        this.customSessionFactories = customSessionFactories;
        return this;
    }

    @Override
    public DmnEngineConfiguration setUsingRelationalDatabase(boolean usingRelationalDatabase) {
        this.usingRelationalDatabase = usingRelationalDatabase;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDatabaseTablePrefix(String databaseTablePrefix) {
        this.databaseTablePrefix = databaseTablePrefix;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDatabaseCatalog(String databaseCatalog) {
        this.databaseCatalog = databaseCatalog;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDatabaseSchema(String databaseSchema) {
        this.databaseSchema = databaseSchema;
        return this;
    }

    @Override
    public DmnEngineConfiguration setTablePrefixIsSchema(boolean tablePrefixIsSchema) {
        this.tablePrefixIsSchema = tablePrefixIsSchema;
        return this;
    }

    @Override
    public DmnEngineConfiguration setSessionFactories(Map<Class<?>, SessionFactory> sessionFactories) {
        this.sessionFactories = sessionFactories;
        return this;
    }

    public boolean isEnableSafeDmnXml() {
        return enableSafeDmnXml;
    }

    public DmnEngineConfiguration setEnableSafeDmnXml(boolean enableSafeDmnXml) {
        this.enableSafeDmnXml = enableSafeDmnXml;
        return this;
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public DmnEngineConfiguration setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
        return this;
    }

    @Override
    public DmnEngineConfiguration setClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public DmnEngineConfiguration setDatabaseSchemaUpdate(String databaseSchemaUpdate) {
        this.databaseSchemaUpdate = databaseSchemaUpdate;
        return this;
    }

    public void setHitPolicyBehaviors(Map<String, AbstractHitPolicy> hitPolicyBehaviors) {
        this.hitPolicyBehaviors = hitPolicyBehaviors;
    }

    public Map<String, AbstractHitPolicy> getHitPolicyBehaviors() {
        return hitPolicyBehaviors;
    }

    public void setCustomHitPolicyBehaviors(Map<String, AbstractHitPolicy> customHitPolicyBehaviors) {
        this.customHitPolicyBehaviors = customHitPolicyBehaviors;
    }

    public Map<String, AbstractHitPolicy> getCustomHitPolicyBehaviors() {
        return customHitPolicyBehaviors;
    }

    public DecisionRequirementsDiagramGenerator getDecisionRequirementsDiagramGenerator() {
        return decisionRequirementsDiagramGenerator;
    }

    public DmnEngineConfiguration setDecisionRequirementsDiagramGenerator(DecisionRequirementsDiagramGenerator decisionRequirementsDiagramGenerator) {
        this.decisionRequirementsDiagramGenerator = decisionRequirementsDiagramGenerator;
        return this;
    }

    public boolean isCreateDiagramOnDeploy() {
        return isCreateDiagramOnDeploy;
    }

    public DmnEngineConfiguration setCreateDiagramOnDeploy(boolean isCreateDiagramOnDeploy) {
        this.isCreateDiagramOnDeploy = isCreateDiagramOnDeploy;
        return this;
    }

    public String getDecisionFontName() {
        return decisionFontName;
    }

    public DmnEngineConfiguration setDecisionFontName(String decisionFontName) {
        this.decisionFontName = decisionFontName;
        return this;
    }

    public String getLabelFontName() {
        return labelFontName;
    }

    public DmnEngineConfiguration setLabelFontName(String labelFontName) {
        this.labelFontName = labelFontName;
        return this;
    }

    public String getAnnotationFontName() {
        return annotationFontName;
    }

    public DmnEngineConfiguration setAnnotationFontName(String annotationFontName) {
        this.annotationFontName = annotationFontName;
        return this;
    }
}
