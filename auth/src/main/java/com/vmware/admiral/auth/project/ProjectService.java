/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.auth.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vmware.admiral.auth.project.ProjectRolesHandler.ProjectRoles;
import com.vmware.admiral.auth.util.ProjectUtil;
import com.vmware.admiral.common.util.AssertUtil;
import com.vmware.admiral.common.util.AuthUtils;
import com.vmware.admiral.common.util.PropertyUtils;
import com.vmware.photon.controller.model.ServiceUtils;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.ServiceUriPaths;
import com.vmware.xenon.services.common.UserGroupService;
import com.vmware.xenon.services.common.UserGroupService.UserGroupState;
import com.vmware.xenon.services.common.UserService.UserState;

/**
 * Project is a group sharing same resources.
 */
public class ProjectService extends StatefulService {

    public static final String FIELD_NAME_CUSTOM_PROPERTIES = "customProperties";

    public static final String CUSTOM_PROPERTY_HARBOR_ID = "__harborId";

    public static final String DEFAULT_PROJECT_ID = "default-project";
    public static final String DEFAULT_HARBOR_PROJECT_ID = "1";
    public static final String DEFAULT_PROJECT_LINK = UriUtils
            .buildUriPath(ProjectFactoryService.SELF_LINK, DEFAULT_PROJECT_ID);

    public static ProjectState buildDefaultProjectInstance() {
        ProjectState project = new ProjectState();
        project.documentSelfLink = DEFAULT_PROJECT_LINK;
        project.name = DEFAULT_PROJECT_ID;
        project.id = project.name;
        project.customProperties = new HashMap<>();
        project.customProperties.put(CUSTOM_PROPERTY_HARBOR_ID, DEFAULT_HARBOR_PROJECT_ID);

        return project;
    }

    /**
     * This class represents the document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ProjectState extends ResourceState {
        public static final String FIELD_NAME_PUBLIC = "isPublic";
        public static final String FIELD_NAME_DESCRIPTION = "description";

        @Documentation(description = "Used for define a public project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public boolean isPublic;

        @Documentation(description = "Used for descripe the purpose of the project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String description;

        /** Link to the group of administrators for this project. */
        @Documentation(description = "Link to the group of administrators for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK,
                PropertyUsageOption.SINGLE_ASSIGNMENT, PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String administratorsUserGroupLink;

        /** Link to the group of members for this project. */
        @Documentation(description = "Link to the group of members for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK,
                PropertyUsageOption.SINGLE_ASSIGNMENT, PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String membersUserGroupLink;

        public void copyTo(ProjectState destination) {
            super.copyTo(destination);
            destination.isPublic = this.isPublic;
            destination.description = this.description;
            destination.administratorsUserGroupLink = this.administratorsUserGroupLink;
            destination.membersUserGroupLink = this.membersUserGroupLink;
        }

        public static ProjectState copyOf(ProjectState source) {
            if (source == null) {
                return null;
            }

            ProjectState result = new ProjectState();
            source.copyTo(result);
            return result;
        }
    }

    /**
     * This class represents the expanded document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ExpandedProjectState extends ProjectState {

        /** List of administrators for this project. */
        @Documentation(description = "List of administrators for this project.")
        public List<UserState> administrators;

        /** List of members for this project. */
        @Documentation(description = "List of members for this project.")
        public List<UserState> members;

        /** List of cluster links for this project. */
        @Documentation(description = "List of cluster links for this project.")
        public List<String> clusterLinks;

        /** List of repository links for this project. */
        @Documentation(description = "List of repository links for this project.")
        public List<String> repositoryLinks;

        public void copyTo(ExpandedProjectState destination) {
            super.copyTo(destination);
            if (administrators != null) {
                destination.administrators = new ArrayList<>(administrators);
            }
            if (members != null) {
                destination.members = new ArrayList<>(members);
            }
        }
    }

    public ProjectService() {
        super(ProjectState.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        ProjectState createBody = post.getBody(ProjectState.class);
        validateState(createBody);

        if (AuthUtils.isDevOpsAdmin(post)) {
            createAdminAndMemberGroups(createBody, post.getAuthorizationContext())
                    .thenAccept((projectState) -> {
                        post.setBody(projectState);
                    })
                    .whenCompleteNotify(post);
        } else {
            post.complete();
        }
    }

    @Override
    public void handleGet(Operation get) {
        if (UriUtils.hasODataExpandParamValue(get.getUri())) {
            retrieveExpandedState(getState(get), get);
        } else {
            super.handleGet(get);
        }
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        if (ProjectRolesHandler.isProjectRolesUpdate(put)) {
            if (AuthUtils.isDevOpsAdmin(put)) {
                ProjectRoles rolesPut = put.getBody(ProjectRoles.class);
                // this is an update of the roles
                new ProjectRolesHandler(getHost(), getSelfLink()).handleRolesUpdate(rolesPut)
                        .thenAccept(
                                (ignore) -> put.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED))
                        .whenCompleteNotify(put);
            } else {
                put.fail(Operation.STATUS_CODE_FORBIDDEN);
            }
        } else {
            // this is an update of the state
            ProjectState projectPut = put.getBody(ProjectState.class);
            validateState(projectPut);
            this.setState(put, projectPut);
            put.setBody(projectPut).complete();
            return;
        }
    }

    @Override
    public void handlePatch(Operation patch) {
        if (!patch.hasBody()) {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            patch.complete();
            return;
        }

        // Patch project state properties
        ProjectState projectPatch = patch.getBody(ProjectState.class);
        if (projectPatch != null) {
            boolean stateModified = handleProjectPatch(getState(patch), projectPatch);
            if (!stateModified) {
                // if the signature hasn't change we shouldn't modify the state
                patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            }
        } else {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
        }

        // Patch roles if DevOps admin
        if (ProjectRolesHandler.isProjectRolesUpdate(patch)) {
            if (AuthUtils.isDevOpsAdmin(patch)) {
                new ProjectRolesHandler(getHost(), getSelfLink())
                        .handleRolesUpdate(patch.getBody(ProjectRoles.class))
                        .whenCompleteNotify(patch);
            } else {
                patch.fail(Operation.STATUS_CODE_FORBIDDEN);
            }
        } else {
            patch.complete();
        }

    }

    /** Returns whether the projects state signature was changed after the patch. */
    private boolean handleProjectPatch(ProjectState currentState, ProjectState patchState) {
        ServiceDocumentDescription docDesc = getDocumentTemplate().documentDescription;
        String currentSignature = Utils.computeSignature(currentState, docDesc);

        Map<String, String> mergedProperties = PropertyUtils
                .mergeCustomProperties(currentState.customProperties, patchState.customProperties);
        PropertyUtils.mergeServiceDocuments(currentState, patchState);
        currentState.customProperties = mergedProperties;

        String newSignature = Utils.computeSignature(currentState, docDesc);
        return !currentSignature.equals(newSignature);
    }

    @Override
    public void handleDelete(Operation delete) {
        ProjectState state = getState(delete);
        if (state == null || state.documentSelfLink == null) {
            delete.complete();
            return;
        }

        QueryTask queryTask = ProjectUtil.createQueryTaskForProjectAssociatedWithPlacement(state,
                null);

        sendRequest(Operation.createPost(getHost(), ServiceUriPaths.CORE_QUERY_TASKS)
                .setBody(queryTask)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logSevere("Failed to retrieve placements associated with project: "
                                + state.documentSelfLink);
                        delete.fail(e);
                        return;
                    } else {
                        ServiceDocumentQueryResult result = o.getBody(QueryTask.class).results;
                        long documentCount = result.documentCount;
                        if (documentCount != 0) {
                            delete.fail(new LocalizableValidationException(
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE,
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE_CODE,
                                    documentCount, documentCount > 1 ? "s" : ""));
                            return;
                        }

                        super.handleDelete(delete);
                    }
                }));
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ProjectState template = (ProjectState) super.getDocumentTemplate();
        ServiceUtils.setRetentionLimit(template);

        template.name = "resource-group-1";
        template.id = "project-id";
        template.description = "project1";
        template.isPublic = true;

        return template;
    }

    private void retrieveExpandedState(ProjectState simpleState, Operation get) {
        ProjectUtil.expandProjectState(getHost(), simpleState, getUri())
                .thenAccept((expandedState) -> get.setBody(expandedState))
                .whenCompleteNotify(get);
    }

    private void validateState(ProjectState state) {
        Utils.validateState(getStateDescription(), state);
        AssertUtil.assertNotNullOrEmpty(state.name, ProjectState.FIELD_NAME_NAME);
    }

    private DeferredResult<ProjectState> createAdminAndMemberGroups(ProjectState projectState,
            AuthorizationContext authContext) {

        if (projectState.administratorsUserGroupLink != null
                && projectState.membersUserGroupLink != null
                && !projectState.administratorsUserGroupLink.trim().isEmpty()
                && !projectState.membersUserGroupLink.trim().isEmpty()) {
            // No groups to create
            return DeferredResult.completed(projectState);
        }

        ArrayList<DeferredResult<Void>> deferredResults = new ArrayList<>();
        Query defaultQuery = createDefaultUserGroupQuery(authContext);

        if (projectState.administratorsUserGroupLink == null
                || projectState.administratorsUserGroupLink.trim().isEmpty()) {
            deferredResults.add(getHost()
                    .sendWithDeferredResult(buildCreateUserGroupOperation(defaultQuery),
                            UserGroupState.class)
                    .thenAccept((groupState) -> {
                        projectState.administratorsUserGroupLink = groupState.documentSelfLink;
                    }));
        }
        if (projectState.membersUserGroupLink == null
                || projectState.membersUserGroupLink.trim().isEmpty()) {
            deferredResults.add(getHost()
                    .sendWithDeferredResult(buildCreateUserGroupOperation(defaultQuery),
                            UserGroupState.class)
                    .thenAccept((groupState) -> {
                        projectState.membersUserGroupLink = groupState.documentSelfLink;
                    }));
        }

        return DeferredResult.allOf(deferredResults)
                .thenCompose((ignore) -> DeferredResult.completed(projectState));
    }

    private Operation buildCreateUserGroupOperation(Query userGroupQuery) {
        UserGroupState userGroupState = UserGroupState.Builder
                .create()
                .withQuery(userGroupQuery)
                .build();

        return Operation.createPost(getHost(), UserGroupService.FACTORY_LINK)
                .setReferer(getHost().getUri())
                .setBody(userGroupState);
    }

    private Query createDefaultUserGroupQuery(AuthorizationContext authContext) {
        return AuthUtils
                .buildUsersQuery(Collections.singletonList(getAuthorizedUserLink(authContext)));
    }

    private String getAuthorizedUserLink(AuthorizationContext authContext) {
        return authContext.getClaims().getSubject();
    }

}