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
package org.flowable.cmmn.engine.impl.cmd;

import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.cmmn.engine.impl.persistence.entity.CaseInstanceEntity;
import org.flowable.cmmn.engine.impl.util.CommandContextUtil;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;

public class SetVariableAsyncCmd extends AbstractSetVariableAsyncCmd implements Command<Void> {
    
    protected String caseInstanceId;
    protected String variableName;
    protected Object variableValue;
    
    public SetVariableAsyncCmd(String caseInstanceId, String variableName, Object variableValue) {
        this.caseInstanceId = caseInstanceId;
        this.variableName = variableName;
        this.variableValue = variableValue;
    }
    
    @Override
    public Void execute(CommandContext commandContext) {
        if (caseInstanceId == null) {
            throw new FlowableIllegalArgumentException("caseInstanceId is null");
        }
        if (variableName == null) {
            throw new FlowableIllegalArgumentException("variable name is null");
        }
     
        CmmnEngineConfiguration cmmnEngineConfiguration = CommandContextUtil.getCmmnEngineConfiguration(commandContext);
        CaseInstanceEntity caseInstanceEntity = cmmnEngineConfiguration.getCaseInstanceEntityManager().findById(caseInstanceId);
        if (caseInstanceEntity == null) {
            throw new FlowableObjectNotFoundException("No case instance found for id " + caseInstanceId, CaseInstanceEntity.class);
        }
        
        addVariable(false, caseInstanceId, null, variableName, variableValue, caseInstanceEntity.getTenantId(), 
                cmmnEngineConfiguration.getVariableServiceConfiguration().getVariableService());
        createSetAsyncVariablesJob(caseInstanceEntity, cmmnEngineConfiguration);
        
        return null;
    }

}
