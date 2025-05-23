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

package org.flowable.rest.service.api.runtime.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.rest.exception.FlowableContentNotSupportedException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.rest.service.api.BpmnRestApiInterceptor;
import org.flowable.rest.service.api.RestResponseFactory;
import org.flowable.rest.service.api.engine.variable.RestVariable;
import org.flowable.rest.service.api.engine.variable.RestVariable.RestVariableScope;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.servlet.http.HttpServletResponse;

/**
 * @author Frederik Heremans
 */
public class BaseExecutionVariableResource implements InitializingBean {

    @Autowired
    protected Environment env;

    @Autowired
    protected RestResponseFactory restResponseFactory;

    @Autowired
    protected RuntimeService runtimeService;
    
    @Autowired(required=false)
    protected BpmnRestApiInterceptor restApiInterceptor;

    protected boolean isSerializableVariableAllowed;

    protected final int variableType;

    public BaseExecutionVariableResource(int variableType) {
        this.variableType = variableType;
    }

    @Override
    public void afterPropertiesSet() {
        isSerializableVariableAllowed = env.getProperty("rest.variables.allow.serializable", Boolean.class, true);
    }

    protected byte[] getVariableDataByteArray(Execution execution, String variableName, String scope,
            HttpServletResponse response) {

        try {
            byte[] result = null;

            RestVariable variable = getVariableFromRequest(execution, variableName, scope, true);
            if (RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variable.getType())) {
                result = (byte[]) variable.getValue();
                response.setContentType("application/octet-stream");

            } else if (RestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variable.getType())) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ObjectOutputStream outputStream = new ObjectOutputStream(buffer);
                outputStream.writeObject(variable.getValue());
                outputStream.close();
                result = buffer.toByteArray();
                response.setContentType("application/x-java-serialized-object");

            } else {
                throw new FlowableObjectNotFoundException("The variable does not have a binary data stream.", null);
            }
            return result;

        } catch (IOException ioe) {
            throw new FlowableException("Error getting variable " + variableName, ioe);
        }
    }

    protected RestVariable setBinaryVariable(MultipartHttpServletRequest request, Execution execution, boolean isNew, boolean async) {

        // Validate input and set defaults
        if (request.getFileMap().size() == 0) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        // Get first file in the map, ignore possible other files
        MultipartFile file = request.getFile(request.getFileMap().keySet().iterator().next());

        if (file == null) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        String variableScope = null;
        String variableName = null;
        String variableType = null;

        Map<String, String[]> paramMap = request.getParameterMap();
        for (String parameterName : paramMap.keySet()) {

            if (paramMap.get(parameterName).length > 0) {

                if ("scope".equalsIgnoreCase(parameterName)) {
                    variableScope = paramMap.get(parameterName)[0];

                } else if ("name".equalsIgnoreCase(parameterName)) {
                    variableName = paramMap.get(parameterName)[0];

                } else if ("type".equalsIgnoreCase(parameterName)) {
                    variableType = paramMap.get(parameterName)[0];
                }
            }
        }

        try {

            // Validate input and set defaults
            if (variableName == null) {
                throw new FlowableIllegalArgumentException("No variable name was found in request body.");
            }

            if (variableType != null) {
                if (!RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variableType) && !RestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variableType)) {
                    throw new FlowableIllegalArgumentException("Only 'binary' and 'serializable' are supported as variable type.");
                }
            } else {
                variableType = RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE;
            }

            RestVariableScope scope = RestVariableScope.LOCAL;
            if (variableScope != null) {
                scope = RestVariable.getScopeFromString(variableScope);
            }

            if (variableType.equals(RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE)) {
                // Use raw bytes as variable value
                byte[] variableBytes = IOUtils.toByteArray(file.getInputStream());
                setVariable(execution, variableName, variableBytes, scope, isNew, async);

            } else if (isSerializableVariableAllowed) {
                // Try deserializing the object
                ObjectInputStream stream = new ObjectInputStream(file.getInputStream());
                Object value = stream.readObject();
                setVariable(execution, variableName, value, scope, isNew, async);
                stream.close();
                
            } else {
                throw new FlowableContentNotSupportedException("Serialized objects are not allowed");
            }

            RestVariable variable = null;
            
            if (!async) {
                variable = getVariableFromRequestWithoutAccessCheck(execution, variableName, scope, false);
                
                // We are setting the scope because the fetched variable does not have it
                variable.setVariableScope(scope);
            }
            
            return variable;

        } catch (IOException ioe) {
            throw new FlowableIllegalArgumentException("Could not process multipart content", ioe);
        } catch (ClassNotFoundException ioe) {
            throw new FlowableContentNotSupportedException("The provided body contains a serialized object for which the class was not found: " + ioe.getMessage());
        }

    }

    protected RestVariable setSimpleVariable(RestVariable restVariable, Execution execution, boolean isNew, boolean async) {
        if (restVariable.getName() == null) {
            throw new FlowableIllegalArgumentException("Variable name is required");
        }

        // Figure out scope, revert to local if omitted
        RestVariableScope scope = restVariable.getVariableScope();
        if (scope == null) {
            scope = RestVariableScope.LOCAL;
        }

        Object actualVariableValue = restResponseFactory.getVariableValue(restVariable);
        setVariable(execution, restVariable.getName(), actualVariableValue, scope, isNew, async);

        RestVariable newRestVariable = null;
        if (!async) {
            newRestVariable = getVariableFromRequestWithoutAccessCheck(execution, restVariable.getName(), scope, false);
        }
        
        return newRestVariable;
    }

    protected void setVariable(Execution execution, String name, Object value, RestVariableScope scope, boolean isNew, boolean async) {
        // Create can only be done on new variables. Existing variables should
        // be updated using PUT
        boolean hasVariable = hasVariableOnScope(execution, name, scope);
        if (isNew && hasVariable) {
            throw new FlowableException("Variable '" + name + "' is already present on execution '" + execution.getId() + "'.");
        }

        if (!isNew && !hasVariable) {
            throw new FlowableObjectNotFoundException("Execution '" + execution.getId() + "' does not have a variable with name: '" + name + "'.", null);
        }

        if (restApiInterceptor != null) {
            if (isNew) {
                restApiInterceptor.createExecutionVariables(execution, Collections.singletonMap(name, value), scope);
            } else {
                restApiInterceptor.updateExecutionVariables(execution, Collections.singletonMap(name, value), scope);
            }
        }

        if (scope == RestVariableScope.LOCAL) {
            if (async) {
                runtimeService.setVariableLocalAsync(execution.getId(), name, value);
            } else {
                runtimeService.setVariableLocal(execution.getId(), name, value);
            }
        } else {
            String executionId = null;
            if (execution.getParentId() != null) {
                executionId = execution.getParentId();
                
            } else {
                executionId = execution.getId();
            }
            
            if (async) {
                runtimeService.setVariableAsync(executionId, name, value);
            } else {
                runtimeService.setVariable(executionId, name, value);
            }
        }
    }

    protected boolean hasVariableOnScope(Execution execution, String variableName, RestVariableScope scope) {
        boolean variableFound = false;

        if (scope == RestVariableScope.GLOBAL) {
            if (execution.getParentId() != null && runtimeService.hasVariable(execution.getParentId(), variableName)) {
                variableFound = true;
            }

        } else if (scope == RestVariableScope.LOCAL) {
            if (runtimeService.hasVariableLocal(execution.getId(), variableName)) {
                variableFound = true;
            }
        }
        return variableFound;
    }

    public RestVariable getVariableFromRequest(Execution execution, String variableName, String scope, boolean includeBinary) {

        if (execution == null) {
            throw new FlowableObjectNotFoundException("Could not find an execution", Execution.class);
        }

        RestVariableScope variableScope = RestVariable.getScopeFromString(scope);
        if (restApiInterceptor != null) {
            restApiInterceptor.accessExecutionVariable(execution, variableName, scope);
        }

        return getVariableFromRequestWithoutAccessCheck(execution, variableName, variableScope, includeBinary);
    }

    public RestVariable getVariableFromRequestWithoutAccessCheck(Execution execution, String variableName, RestVariableScope variableScope, boolean includeBinary) {

        boolean variableFound = false;
        Object value = null;

        if (variableScope == null) {
            // First, check local variables (which have precedence when no scope
            // is supplied)
            if (runtimeService.hasVariableLocal(execution.getId(), variableName)) {
                value = runtimeService.getVariableLocal(execution.getId(), variableName);
                variableScope = RestVariableScope.LOCAL;
                variableFound = true;
            } else {
                if (execution.getParentId() != null) {
                    value = runtimeService.getVariable(execution.getParentId(), variableName);
                    variableScope = RestVariableScope.GLOBAL;
                    variableFound = true;
                }
            }
        } else if (variableScope == RestVariableScope.GLOBAL) {
            // Use parent to get variables
            if (execution.getParentId() != null) {
                value = runtimeService.getVariable(execution.getParentId(), variableName);
                variableScope = RestVariableScope.GLOBAL;
                variableFound = true;
            }
        } else if (variableScope == RestVariableScope.LOCAL) {

            value = runtimeService.getVariableLocal(execution.getId(), variableName);
            variableScope = RestVariableScope.LOCAL;
            variableFound = true;
        }

        if (!variableFound) {
            throw new FlowableObjectNotFoundException("Execution '" + execution.getId() + "' does not have a variable with name: '" + variableName + "'.", VariableInstanceEntity.class);
        } else {
            return constructRestVariable(variableName, value, variableScope, execution.getId(), includeBinary);
        }
    }

    protected RestVariable constructRestVariable(String variableName, Object value, RestVariableScope variableScope, String executionId, boolean includeBinary) {

        return restResponseFactory.createRestVariable(variableName, value, variableScope, executionId, variableType, includeBinary);
    }

    protected Execution getExecutionFromRequestWithoutAccessCheck(String executionId) {
        Execution execution = runtimeService.createExecutionQuery().executionId(executionId).singleResult();
        if (execution == null) {
            throw new FlowableObjectNotFoundException("Could not find an execution with id '" + executionId + "'.", Execution.class);
        }
        
        return execution;
    }

    protected String getExecutionIdParameter() {
        return "executionId";
    }

    protected boolean allowProcessInstanceUrl() {
        return false;
    }
}
