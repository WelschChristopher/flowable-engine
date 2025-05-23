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
package org.flowable.cmmn.engine.impl.eventregistry;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.repository.CaseDefinition;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.api.runtime.CaseInstanceBuilder;
import org.flowable.cmmn.api.runtime.CaseInstanceQuery;
import org.flowable.cmmn.api.runtime.PlanItemInstanceState;
import org.flowable.cmmn.converter.CmmnXmlConstants;
import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.cmmn.engine.impl.persistence.entity.PlanItemInstanceEntity;
import org.flowable.cmmn.model.CmmnModel;
import org.flowable.cmmn.model.EventListener;
import org.flowable.cmmn.model.ExtensionElement;
import org.flowable.cmmn.model.PlanItem;
import org.flowable.common.engine.api.constant.ReferenceTypes;
import org.flowable.common.engine.api.lock.LockManager;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.eventregistry.api.EventConsumerInfo;
import org.flowable.eventregistry.api.EventRegistryProcessingInfo;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.impl.constant.EventConstants;
import org.flowable.eventregistry.impl.consumer.BaseEventRegistryEventConsumer;
import org.flowable.eventregistry.impl.consumer.CorrelationKey;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.eventsubscription.api.EventSubscriptionQuery;
import org.flowable.eventsubscription.service.impl.EventSubscriptionQueryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Joram Barrez
 * @author Filip Hrisafov
 */
public class CmmnEventRegistryEventConsumer extends BaseEventRegistryEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CmmnEventRegistryEventConsumer.class);

    protected CmmnEngineConfiguration cmmnEngineConfiguration;

    public CmmnEventRegistryEventConsumer(CmmnEngineConfiguration cmmnEngineConfiguration) {
        super(cmmnEngineConfiguration);
        this.cmmnEngineConfiguration = cmmnEngineConfiguration;
    }
    
    @Override
    public String getConsumerKey() {
        return "cmmnEventConsumer";
    }
    
    @Override
    public String findDefinitionKeyById(String definitionId) {
        String caseefinitionKey = null;
        CaseDefinition caseDefinition = cmmnEngineConfiguration.getCaseDefinitionEntityManager().findById(definitionId);
        if (caseDefinition != null) {
            caseefinitionKey = caseDefinition.getKey();
        }
        
        return caseefinitionKey;
    }

    @Override
    protected EventRegistryProcessingInfo eventReceived(EventInstance eventInstance) {

        // Fetching the event subscriptions happens in one transaction,
        // executing them one per subscription. There is no overarching transaction.
        // The reason for this is that the handling of one event subscription
        // should not influence (i.e. roll back) the handling of another.
        
        EventRegistryProcessingInfo eventRegistryProcessingInfo = new EventRegistryProcessingInfo();

        Collection<CorrelationKey> correlationKeys = generateCorrelationKeys(eventInstance.getCorrelationParameterInstances());
        List<EventSubscription> eventSubscriptions = findEventSubscriptions(ScopeTypes.CMMN, eventInstance, correlationKeys);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Found {} for {}", eventSubscriptions, eventInstance);
        }
        CmmnRuntimeService cmmnRuntimeService = cmmnEngineConfiguration.getCmmnRuntimeService();
        for (EventSubscription eventSubscription : eventSubscriptions) {
            EventConsumerInfo eventConsumerInfo = new EventConsumerInfo(eventSubscription.getId(), eventSubscription.getSubScopeId(), 
                    eventSubscription.getScopeDefinitionId(), ScopeTypes.CMMN);
            boolean eventSubscriptionHandled = handleEventSubscription(cmmnRuntimeService, eventSubscription, eventInstance, correlationKeys, eventConsumerInfo);
            
            if (eventSubscriptionHandled) {
                eventRegistryProcessingInfo.addEventConsumerInfo(eventConsumerInfo);
            }
        }

        return eventRegistryProcessingInfo;
    }

    protected boolean handleEventSubscription(CmmnRuntimeService cmmnRuntimeService, EventSubscription eventSubscription,
            EventInstance eventInstance, Collection<CorrelationKey> correlationKeys, EventConsumerInfo eventConsumerInfo) {

        String planItemInstanceId = eventSubscription.getSubScopeId();
        if (planItemInstanceId != null) {

            // When a subscope id is set, this means that a plan item instance is waiting for the event

            PlanItemInstanceEntity planItemInstanceEntity = (PlanItemInstanceEntity) cmmnRuntimeService.createPlanItemInstanceQuery().planItemInstanceId(
                    planItemInstanceId).singleResult();
            CmmnModel cmmnModel = cmmnEngineConfiguration.getCmmnRepositoryService().getCmmnModel(planItemInstanceEntity.getCaseDefinitionId());
            PlanItem planItem = cmmnModel.findPlanItemByPlanItemDefinitionId(planItemInstanceEntity.getPlanItemDefinitionId());
            if (PlanItemInstanceState.ACTIVE.equals(planItemInstanceEntity.getState())
                    || (planItem != null && planItem.getPlanItemDefinition() instanceof EventListener
                    && PlanItemInstanceState.AVAILABLE.equals(planItemInstanceEntity.getState()))) {

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Triggering {} with {}", planItemInstanceEntity, eventInstance);
                }
                cmmnRuntimeService.createPlanItemInstanceTransitionBuilder(planItemInstanceId)
                    .transientVariable(EventConstants.EVENT_INSTANCE, eventInstance)
                    .trigger();
                
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Ignoring {} because {} was not in the right state", eventInstance, planItemInstanceEntity);
                }
                return false;
            }

        } else if (eventSubscription.getScopeDefinitionId() != null && eventSubscription.getScopeId() == null) {

            // If there is no scope/subscope id set, but there is a scope definition id set, it's an event that starts a case

            if (correlationKeys != null) {
                String startCorrelationConfiguration = getStartCorrelationConfiguration(eventSubscription);

                if (Objects.equals(startCorrelationConfiguration, CmmnXmlConstants.START_EVENT_CORRELATION_STORE_AS_UNIQUE_REFERENCE_ID)) {

                    CorrelationKey correlationKeyWithAllParameters = getCorrelationKeyWithAllParameters(correlationKeys, eventInstance);

                    CaseDefinition caseDefinition = cmmnEngineConfiguration.getCmmnRepositoryService().getCaseDefinition(eventSubscription.getScopeDefinitionId());

                    long caseInstanceCount = countCaseInstances(cmmnRuntimeService, eventInstance, correlationKeyWithAllParameters, caseDefinition);
                    if (caseInstanceCount > 0) {
                        // Returning, no new instance should be started
                        eventConsumerInfo.setHasExistingInstancesForUniqueCorrelation(true);
                        LOGGER.debug("Event received to start a new case instance, but a unique instance already exists.");
                        return true;

                    } else if (cmmnEngineConfiguration.isEventRegistryUniqueCaseInstanceCheckWithLock()) {

                        /*
                         * When multiple threads/transactions are querying concurrently, it could happen
                         * that multiple times zero is returned as result of the count.
                         *
                         * To make sure only one unique instance is created, a lock is acquired for the reference correlation value,
                         * which means that the current logic can now act on it when that's successful.
                         *
                         * Once the lock is acquired, the query is repeated (similar reasoning as when using synchronized methods).
                         * If the result is again zero, the case instance can be started.
                         *
                         * Transitionally, there are 4 transactions at play here:
                         * - tx 1 for acquiring a lock
                         * - tx 2 for doing the case instance count
                         * - tx 3 for starting the case instance (if tx 1 was successful and tx 2 returned 0)
                         * - tx 4 for unlocking (if tx 1 was successful)
                         *
                         * The counting + case instance starting happens exclusively for a given event correlation value
                         * and due to using separate transactions for the count and the start, it's guaranteed
                         * other engine nodes or other threads will always see any other instance started.
                         */

                        String countLockName = "celock" + correlationKeyWithAllParameters.getValue() + caseDefinition.getKey() ;
                        LockManager lockManager = cmmnEngineConfiguration.getLockManager(countLockName);

                        boolean lockAcquired = lockManager.acquireLock(cmmnEngineConfiguration.getEventSubscriptionServiceConfiguration().getEventSubscriptionLockTime());

                        if (lockAcquired) {

                            try {

                                caseInstanceCount = countCaseInstances(cmmnRuntimeService, eventInstance, correlationKeyWithAllParameters, caseDefinition);
                                if (caseInstanceCount > 0) {
                                    // Returning, no new instance should be started
                                    eventConsumerInfo.setHasExistingInstancesForUniqueCorrelation(true);
                                    LOGGER.debug("Event received to start a new case instance, but a unique instance already exists.");
                                    return true;
                                }

                                startCaseInstance(cmmnRuntimeService, eventSubscription, eventInstance, correlationKeyWithAllParameters);
                                return true;

                            } finally {
                                lockManager.releaseAndDeleteLock();

                            }

                        } else {
                            LOGGER.info("Lock for {} was not acquired. This means that another event has already acquired that lock and will start a new case instance. Ignoring this one.", countLockName);
                            return true;

                        }

                    } else {
                        startCaseInstance(cmmnRuntimeService, eventSubscription, eventInstance, correlationKeyWithAllParameters);
                        return true;
                    }

                }
            }

            startCaseInstance(cmmnRuntimeService, eventSubscription, eventInstance, null);
        }
        
        return true;
    }

    protected long countCaseInstances(CmmnRuntimeService cmmnRuntimeService, EventInstance eventInstance,
            CorrelationKey correlationKey, CaseDefinition caseDefinition) {

        CaseInstanceQuery caseInstanceQuery = cmmnRuntimeService.createCaseInstanceQuery()
                .caseDefinitionKey(caseDefinition.getKey())
                .caseInstanceReferenceId(correlationKey.getValue())
                .caseInstanceReferenceType(ReferenceTypes.EVENT_CASE);

        if (eventInstance.getTenantId() != null && !Objects.equals(CmmnEngineConfiguration.NO_TENANT_ID, eventInstance.getTenantId())) {
            caseInstanceQuery.caseInstanceTenantId(eventInstance.getTenantId());
        }

        return caseInstanceQuery.count();
    }

    protected void startCaseInstance(CmmnRuntimeService cmmnRuntimeService, EventSubscription eventSubscription, EventInstance eventInstance,
            CorrelationKey correlationKey) {
        CaseInstanceBuilder caseInstanceBuilder = cmmnRuntimeService.createCaseInstanceBuilder()
                .caseDefinitionId(eventSubscription.getScopeDefinitionId())
                .transientVariable(EventConstants.EVENT_INSTANCE, eventInstance);

        if (eventInstance.getTenantId() != null && !Objects.equals(CmmnEngineConfiguration.NO_TENANT_ID, eventInstance.getTenantId())) {
            caseInstanceBuilder.overrideCaseDefinitionTenantId(eventInstance.getTenantId());
        }

        if (correlationKey != null) {
            caseInstanceBuilder.referenceId(correlationKey.getValue())
                    .referenceType(ReferenceTypes.EVENT_CASE);
        }

        boolean debugLoggingEnabled = LOGGER.isDebugEnabled();
        if (cmmnEngineConfiguration.isEventRegistryStartCaseInstanceAsync()) {
            if (debugLoggingEnabled) {
                LOGGER.debug("Async starting case instance for {} with {}", eventSubscription, eventInstance);
            }

            CaseInstance caseInstance = caseInstanceBuilder.startAsync();

            if (debugLoggingEnabled) {
                LOGGER.debug("Started {} async for {} with {}", caseInstance, eventSubscription, eventInstance);
            }
        } else {
            if (debugLoggingEnabled) {
                LOGGER.debug("Starting case instance for {} with {}", eventSubscription, eventInstance);
            }

            CaseInstance caseInstance = caseInstanceBuilder.start();

            if (debugLoggingEnabled) {
                LOGGER.debug("Started {} for {} with {}", caseInstance, eventSubscription, eventInstance);
            }
        }
    }

    protected String getStartCorrelationConfiguration(EventSubscription eventSubscription) {
        CmmnModel cmmnModel = cmmnEngineConfiguration.getCmmnRepositoryService().getCmmnModel(eventSubscription.getScopeDefinitionId());
        if (cmmnModel != null) {
            List<ExtensionElement> correlationCfgExtensions = cmmnModel.getPrimaryCase().getExtensionElements()
                .getOrDefault(CmmnXmlConstants.START_EVENT_CORRELATION_CONFIGURATION, Collections.emptyList());
            if (!correlationCfgExtensions.isEmpty()) {
                return correlationCfgExtensions.get(0).getElementText();
            }
        }
        return null;
    }

    @Override
    protected EventSubscriptionQuery createEventSubscriptionQuery() {
        return new EventSubscriptionQueryImpl(commandExecutor, cmmnEngineConfiguration.getEventSubscriptionServiceConfiguration());
    }

}
