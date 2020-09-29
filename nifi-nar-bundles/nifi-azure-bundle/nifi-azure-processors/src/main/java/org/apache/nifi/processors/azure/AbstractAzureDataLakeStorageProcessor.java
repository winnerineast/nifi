/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.azure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;

public abstract class AbstractAzureDataLakeStorageProcessor extends AbstractProcessor {

    public static final PropertyDescriptor ACCOUNT_NAME = new PropertyDescriptor.Builder()
            .name("storage-account-name").displayName("Storage Account Name")
            .description("The storage account name.  There are certain risks in allowing the account name to be stored as a flowfile " +
                    "attribute. While it does provide for a more flexible flow by allowing the account name to " +
                    "be fetched dynamically from a flowfile attribute, care must be taken to restrict access to " +
                    "the event provenance data (e.g. by strictly controlling the policies governing provenance for this Processor). " +
                    "In addition, the provenance repositories may be put on encrypted disk partitions." +
                    " Instead of defining the Storage Account Name, Storage Account Key and SAS Token properties directly on the processor, " +
                    "the preferred way is to configure them through a controller service")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .sensitive(true).build();

    public static final PropertyDescriptor ACCOUNT_KEY = new PropertyDescriptor.Builder()
            .name("storage-account-key").displayName("Storage Account Key")
            .description("The storage account key. This is an admin-like password providing access to every container in this account. It is recommended " +
                    "one uses Shared Access Signature (SAS) token instead for fine-grained control with policies. " +
                    "There are certain risks in allowing the account key to be stored as a flowfile " +
                    "attribute. While it does provide for a more flexible flow by allowing the account key to " +
                    "be fetched dynamically from a flow file attribute, care must be taken to restrict access to " +
                    "the event provenance data (e.g. by strictly controlling the policies governing provenance for this Processor). " +
                    "In addition, the provenance repositories may be put on encrypted disk partitions.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(false)
            .sensitive(true).build();

    public static final PropertyDescriptor SAS_TOKEN = new PropertyDescriptor.Builder()
            .name("storage-sas-token").displayName("SAS Token")
            .description("Shared Access Signature token, including the leading '?'. Specify either SAS Token (recommended) or Account Key. " +
                    "There are certain risks in allowing the SAS token to be stored as a flowfile " +
                    "attribute. While it does provide for a more flexible flow by allowing the account name to " +
                    "be fetched dynamically from a flowfile attribute, care must be taken to restrict access to " +
                    "the event provenance data (e.g. by strictly controlling the policies governing provenance for this Processor). " +
                    "In addition, the provenance repositories may be put on encrypted disk partitions.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor USE_MANAGED_IDENTITY = new PropertyDescriptor.Builder()
            .name("use-managed-identity")
            .displayName("Use Azure Managed Identity")
            .description("Choose whether or not to use the managed identity of Azure VM/VMSS ")
            .required(false).defaultValue("false").allowableValues("true", "false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR).build();

    public static final PropertyDescriptor FILESYSTEM = new PropertyDescriptor.Builder()
            .name("filesystem-name").displayName("Filesystem Name")
            .description("Name of the Azure Storage File System. It is assumed to be already existing.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .build();

    public static final PropertyDescriptor DIRECTORY = new PropertyDescriptor.Builder()
            .name("directory-name").displayName("Directory Name")
            .description("Name of the Azure Storage Directory. In case of the PutAzureDatalakeStorage processor, it will be created if not already existing.")
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .build();

    public static final PropertyDescriptor FILE = new PropertyDescriptor.Builder()
            .name("file-name").displayName("File Name")
            .description("The filename")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .defaultValue("${azure.filename}")
            .build();

    public static final PropertyDescriptor ENDPOINT_SUFFIX = new PropertyDescriptor.Builder()
            .name("endpoint-suffix").displayName("Endpoint Suffix")
            .description("Endpoint Suffix")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(false)
            .defaultValue("dfs.core.windows.net")
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success").description(
            "Files that have been successfully written to Azure storage are transferred to this relationship")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure").description(
            "Files that could not be written to Azure storage for some reason are transferred to this relationship")
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(
            Arrays.asList(AbstractAzureDataLakeStorageProcessor.ACCOUNT_NAME,
                    AbstractAzureDataLakeStorageProcessor.ACCOUNT_KEY,
                    AbstractAzureDataLakeStorageProcessor.SAS_TOKEN,
                    AbstractAzureDataLakeStorageProcessor.USE_MANAGED_IDENTITY,
                    AbstractAzureDataLakeStorageProcessor.ENDPOINT_SUFFIX,
                    AbstractAzureDataLakeStorageProcessor.FILESYSTEM,
                    AbstractAzureDataLakeStorageProcessor.DIRECTORY,
                    AbstractAzureDataLakeStorageProcessor.FILE));

    private static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    AbstractAzureBlobProcessor.REL_SUCCESS,
                    AbstractAzureBlobProcessor.REL_FAILURE)));

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    public static Collection<ValidationResult> validateCredentialProperties(final ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>();

        final boolean useManagedIdentity = validationContext.getProperty(USE_MANAGED_IDENTITY).asBoolean();
        final boolean accountKeyIsSet  = validationContext.getProperty(ACCOUNT_KEY).isSet();
        final boolean sasTokenIsSet     = validationContext.getProperty(SAS_TOKEN).isSet();

        int credential_config_found = 0;
        if(useManagedIdentity) credential_config_found++;
        if(accountKeyIsSet) credential_config_found++;
        if(sasTokenIsSet) credential_config_found++;

        if(credential_config_found == 0){
            final String msg = String.format(
                "At least one of ['%s', '%s', '%s'] should be set",
                ACCOUNT_KEY.getDisplayName(),
                SAS_TOKEN.getDisplayName(),
                USE_MANAGED_IDENTITY.getDisplayName()
            );
            results.add(new ValidationResult.Builder().subject("Credentials config").valid(false).explanation(msg).build());
        } else if(credential_config_found > 1) {
            final String msg = String.format(
                "Only one of ['%s', '%s', '%s'] should be set",
                ACCOUNT_KEY.getDisplayName(),
                SAS_TOKEN.getDisplayName(),
                USE_MANAGED_IDENTITY.getDisplayName()
            );
            results.add(new ValidationResult.Builder().subject("Credentials config").valid(false).explanation(msg).build());
        }
        return results;
    }

    public static DataLakeServiceClient getStorageClient(PropertyContext context, FlowFile flowFile) {
        final Map<String, String> attributes = flowFile != null ? flowFile.getAttributes() : Collections.emptyMap();
        final String accountName = context.getProperty(ACCOUNT_NAME).evaluateAttributeExpressions(attributes).getValue();
        final String accountKey = context.getProperty(ACCOUNT_KEY).evaluateAttributeExpressions(attributes).getValue();
        final String sasToken = context.getProperty(SAS_TOKEN).evaluateAttributeExpressions(attributes).getValue();
        final String endpointSuffix = context.getProperty(ENDPOINT_SUFFIX).evaluateAttributeExpressions(attributes).getValue();
        final String endpoint = String.format("https://%s.%s", accountName,endpointSuffix);
        final boolean useManagedIdentity = context.getProperty(USE_MANAGED_IDENTITY).asBoolean();
        DataLakeServiceClient storageClient;
        if (StringUtils.isNotBlank(accountKey)) {
            final StorageSharedKeyCredential credential = new StorageSharedKeyCredential(accountName,
                    accountKey);
            storageClient = new DataLakeServiceClientBuilder().endpoint(endpoint).credential(credential)
                    .buildClient();
        } else if (StringUtils.isNotBlank(sasToken)) {
            storageClient = new DataLakeServiceClientBuilder().endpoint(endpoint).sasToken(sasToken)
                    .buildClient();
        } else if(useManagedIdentity){
            final ManagedIdentityCredential misCrendential = new ManagedIdentityCredentialBuilder()
                                                                .build();
            storageClient = new  DataLakeServiceClientBuilder()
                                    .endpoint(endpoint)
                                    .credential(misCrendential)
                                    .buildClient();
        } else {
            throw new IllegalArgumentException(String.format("Either '%s' or '%s' must be defined.",
                    ACCOUNT_KEY.getDisplayName(), SAS_TOKEN.getDisplayName()));
        }
        return storageClient;
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final Collection<ValidationResult> results = validateCredentialProperties(validationContext);
        return results;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }
}
