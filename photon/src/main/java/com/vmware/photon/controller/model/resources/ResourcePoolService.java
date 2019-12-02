/*
 * Copyright (c) 2018-2019 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.photon.controller.model.resources;

import java.util.EnumSet;

import com.vmware.photon.controller.model.ServiceUtils;
import com.vmware.photon.controller.model.UriPaths;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState.ResourcePoolProperty;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask.Query;

/**
 * Describes a resource pool. A resource pool is a grouping of {@link ComputeState}s that can be
 * used as a single unit for planning and allocation purposes.
 *
 * <p>
 * {@link ComputeState}s that contribute capacity to this resource pool are found by executing the
 * {@link ResourcePoolState#query} query. For <b>non-elastic</b> resource pools the query is
 * auto-generated by using the {@link ComputeState#resourcePoolLink}. For <b>elastic</b> resource
 * pools the query is provided by the resource pool creator.
 *
 * <p>
 * Thus a resource may participate in at most one non-elastic resource pool and zero or more elastic
 * resource pools.
 */
public class ResourcePoolService extends StatefulService {

    public static final String FACTORY_LINK = UriPaths.RESOURCES + "/pools";

    /**
     * This class represents the document state associated with a {@link ResourcePoolService} task.
     */
    public static class ResourcePoolState extends ResourceState {

        public static final String FIELD_NAME_PROPERTIES = "properties";

        /**
         * Enumeration used to define properties of the resource pool.
         */
        public enum ResourcePoolProperty {
            /**
             * An elastic resource pool uses a dynamic query to find the participating resources.
             * The {@link ComputeState#resourcePoolLink} field of the returned resources may not
             * match this resource pool instance.
             */
            ELASTIC
        }

        /**
         * Project name of this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public String projectName;

        /**
         * Properties of this resource pool, if it is elastic, etc.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public EnumSet<ResourcePoolProperty> properties;

        /**
         * Minimum number of CPU Cores in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long minCpuCount;

        /**
         * Minimum number of GPU Cores in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long minGpuCount;

        /**
         * Minimum amount of memory (in bytes) in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long minMemoryBytes;

        /**
         * Minimum disk capacity (in bytes) in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long minDiskCapacityBytes;

        /**
         * Maximum number of CPU Cores in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long maxCpuCount;

        /**
         * Maximum number of GPU Cores in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long maxGpuCount;

        /**
         * Maximum amount of memory (in bytes) in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long maxMemoryBytes;

        /**
         * Maximum disk capacity (in bytes) in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Long maxDiskCapacityBytes;

        /**
         * Maximum CPU Cost (per minute) in this resource pool.
         */
        @Deprecated
        public Double maxCpuCostPerMinute;

        /**
         * Maximum Disk cost (per minute) in this resource pool.
         */
        @Deprecated
        public Double maxDiskCostPerMinute;

        /**
         * Currency unit used for pricing.
         */
        @Deprecated
        public String currencyUnit;

        /**
         * Query to use to retrieve resources in this resource pool.
         */
        @UsageOption(option = PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
        public Query query;
    }

    public ResourcePoolService() {
        super(ResourcePoolState.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    @Override
    public void handleDelete(Operation delete) {
        logInfo("Deleting ResourcePool, Path: %s, Operation ID: %d, Referrer: %s",
                delete.getUri().getPath(), delete.getId(),
                delete.getRefererAsString());
        super.handleDelete(delete);
    }

    @Override
    public void handleCreate(Operation createPost) {
        try {
            processInput(createPost);
            createPost.complete();
        } catch (Throwable t) {
            createPost.fail(t);
        }
    }

    @Override
    public void handlePut(Operation put) {
        try {
            ResourcePoolState returnState = processInput(put);
            returnState.copyTenantLinks(getState(put));
            setState(put, returnState);
            put.complete();
        } catch (Throwable t) {
            put.fail(t);
        }
    }

    private ResourcePoolState processInput(Operation op) {
        if (!op.hasBody()) {
            throw (new IllegalArgumentException("body is required"));
        }
        ResourcePoolState state = op.getBody(ResourcePoolState.class);
        validateState(state);

        if (!state.properties.contains(ResourcePoolProperty.ELASTIC)) {
            state.query = generateResourcePoolQuery(state);
        }
        return state;
    }

    @Override
    public void handlePatch(Operation patch) {
        ResourcePoolState currentState = getState(patch);
        if (!currentState.properties.contains(ResourcePoolProperty.ELASTIC)) {
            // clean auto-generated query to catch patches with unexpected query
            currentState.query = null;
        }

        // use standard resource merging with an additional custom handler for the query
        ResourceUtils.handlePatch(patch, currentState, getStateDescription(),
                ResourcePoolState.class,
                op -> {
                    // check state and re-generate the query, if needed
                    validateState(currentState);
                    if (!currentState.properties.contains(ResourcePoolProperty.ELASTIC)) {
                        currentState.query = generateResourcePoolQuery(currentState);
                    }

                    // don't report a state change, it is already reported if resource pool type has
                    // changed
                    return false;
                });
    }

    public void validateState(ResourcePoolState state) {
        Utils.validateState(getStateDescription(), state);

        if (state.name == null) {
            throw new IllegalArgumentException("Resource pool name is required.");
        }

        if (state.properties == null) {
            state.properties = EnumSet
                    .noneOf(ResourcePoolState.ResourcePoolProperty.class);
        }

        if (state.properties.contains(ResourcePoolProperty.ELASTIC)) {
            if (state.query == null) {
                throw new IllegalArgumentException("Query is required for elastic resource pools.");
            }
        }
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ServiceDocument template = super.getDocumentTemplate();
        ServiceUtils.setRetentionLimit(template);
        return template;
    }

    /**
     * Generates a query that finds all computes which resource pool link points to this resource
     * pool. Applicable to non-elastic pools only.
     */
    private Query generateResourcePoolQuery(ResourcePoolState initState) {
        Query query = Query.Builder.create()
                .addKindFieldClause(ComputeState.class)
                .addFieldClause(ComputeState.FIELD_NAME_RESOURCE_POOL_LINK, getSelfLink())
                .build();

        return query;
    }
}
