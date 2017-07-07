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

package com.vmware.admiral.auth.util;

import static com.vmware.admiral.common.util.AssertUtil.PROPERTY_CANNOT_BE_EMPTY_MESSAGE_FORMAT;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.isNullOrEmpty;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.google.gson.annotations.SerializedName;

import com.vmware.admiral.auth.idm.AuthRole;
import com.vmware.admiral.auth.idm.Principal;
import com.vmware.admiral.auth.project.ProjectFactoryService;
import com.vmware.admiral.auth.project.ProjectService;
import com.vmware.admiral.auth.project.ProjectService.ExpandedProjectState;
import com.vmware.admiral.auth.project.ProjectService.ProjectState;
import com.vmware.admiral.common.util.OperationUtil;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.cluster.ClusterService;
import com.vmware.admiral.compute.container.CompositeDescriptionFactoryService;
import com.vmware.admiral.compute.container.GroupResourcePlacementService.GroupResourcePlacementState;
import com.vmware.admiral.service.common.HbrApiProxyService;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.QueryTask.Query.Occurance;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification;
import com.vmware.xenon.services.common.QueryTask.QueryTerm.MatchType;
import com.vmware.xenon.services.common.UserGroupService;
import com.vmware.xenon.services.common.UserGroupService.UserGroupState;
import com.vmware.xenon.services.common.UserService.UserState;

public class ProjectUtil {
    public static final String PROJECT_IN_USE_MESSAGE = "Project is associated with %s placement%s";
    public static final String PROJECT_IN_USE_MESSAGE_CODE = "host.resource-group.in.use";

    private static class HbrRepositoriesResponse {
        public static final String FIELD_NAME_RESPONSE_ENTRIES = "responseEntries";

        /** List of response entries returned by Harbor */
        public List<HbrRepositoriesResponseEntry> responseEntries;
    }

    private static class HbrRepositoriesResponseEntry {
        /** Name of the repository. */
        public String name;

        @SerializedName("tags_count")
        /** Number of tags for this repository. */
        public long tagsCount;
    }

    public static final long PROJECT_INDEX_ORIGIN = 2L;
    public static final long PROJECT_INDEX_BOUND = (long)Integer.MAX_VALUE * 2; // simulate uint


    public static QueryTask createQueryTaskForProjectAssociatedWithPlacement(ResourceState project, Query query) {
        QueryTask queryTask = null;
        if (query != null) {
            queryTask = QueryTask.Builder.createDirectTask().setQuery(query).build();
        } else if (project != null && project.documentSelfLink != null) {
            queryTask = QueryUtil.buildQuery(GroupResourcePlacementState.class, true, QueryUtil.addTenantAndGroupClause(Arrays.asList(project.documentSelfLink)));
        }

        if (queryTask != null) {
            QueryUtil.addCountOption(queryTask);
        }

        return queryTask;
    }

    /**
     * Creates a {@link ExpandedProjectState} based on the provided simple state additionally
     * building the lists of administrators and members.
     *
     * @param service a {@link Service} that can be used to retrieve service documents
     * @param simpleState the {@link ProjectState} that needs to be expanded
     * @param referer the {@link URI} of the service that issues the expand
     */
    public static DeferredResult<ExpandedProjectState> expandProjectState(Service service,
            ProjectState simpleState, URI referer) {
        ExpandedProjectState expandedState = new ExpandedProjectState();
        simpleState.copyTo(expandedState);
        expandedState.administrators = new ArrayList<>();
        expandedState.members = new ArrayList<>();
        expandedState.viewers = new ArrayList<>();

        if (isNullOrEmpty(simpleState.membersUserGroupLinks)
                && isNullOrEmpty(simpleState.administratorsUserGroupLinks)
                && isNullOrEmpty(simpleState.viewersUserGroupLinks)) {
            return DeferredResult.completed(expandedState);
        }

        String projectId = Service.getId(simpleState.documentSelfLink);

        String adminsGroupLink = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                AuthRole.PROJECT_ADMIN.buildRoleWithSuffix(projectId));
        String membersGroupLink = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                AuthRole.PROJECT_MEMBER.buildRoleWithSuffix(projectId));
        String viewersGroupLink = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                AuthRole.PROJECT_VIEWER.buildRoleWithSuffix(projectId));

        Map<String, UserState> userStates = new ConcurrentHashMap<>();
        Map<String, Principal> userLinkToPrincipal = new ConcurrentHashMap<>();
        Map<AuthRole, List<String>> roleToUsersLinks = new ConcurrentHashMap<>();

        DeferredResult<Void> retrieveAdmins = retrieveUserGroupMembers(service,
                adminsGroupLink, referer)
                .thenAccept((adminsList) -> {
                    adminsList.forEach(a -> userStates.put(a.documentSelfLink, a));
                    roleToUsersLinks.put(AuthRole.PROJECT_ADMIN, adminsList.stream().map(a ->
                            a.documentSelfLink).collect(Collectors.toList()));
                });

        DeferredResult<Void> retrieveMembers = retrieveUserGroupMembers(service,
                membersGroupLink, referer)
                .thenAccept((membersList) -> {
                    membersList.forEach(m -> userStates.put(m.documentSelfLink, m));
                    roleToUsersLinks.put(AuthRole.PROJECT_MEMBER, membersList.stream().map(m ->
                            m.documentSelfLink).collect(Collectors.toList()));
                });

        DeferredResult<Void> retrieveViewers = retrieveUserGroupMembers(service,
                viewersGroupLink, referer)
                .thenAccept((viewersList) -> {
                    viewersList.forEach(v -> userStates.put(v.documentSelfLink, v));
                    roleToUsersLinks.put(AuthRole.PROJECT_VIEWER, viewersList.stream().map(m ->
                            m.documentSelfLink).collect(Collectors.toList()));
                });

        DeferredResult<Void> retrieveUserStatePrincipals = DeferredResult.allOf(retrieveAdmins,
                retrieveMembers, retrieveViewers)
                .thenCompose(ignore -> {
                    List<DeferredResult<Void>> results = new ArrayList<>();

                    for (Entry<String, UserState> entry : userStates.entrySet()) {
                        DeferredResult<Void> tempResult = PrincipalUtil.getPrincipal(service,
                                Service.getId(entry.getValue().documentSelfLink))
                                .thenAccept(p -> userLinkToPrincipal.put(entry.getKey(), p));
                        results.add(tempResult);
                    }

                    return DeferredResult.allOf(results);
                })
                .thenAccept(ignore -> {
                    List<String> admins = roleToUsersLinks.get(AuthRole.PROJECT_ADMIN);
                    List<String> members = roleToUsersLinks.get(AuthRole.PROJECT_MEMBER);
                    List<String> viewers = roleToUsersLinks.get(AuthRole.PROJECT_VIEWER);

                    admins.forEach(a -> expandedState.administrators.add(
                            userLinkToPrincipal.get(a)));

                    members.forEach(m -> expandedState.members.add(userLinkToPrincipal.get(m)));

                    viewers.forEach(v -> expandedState.viewers.add(userLinkToPrincipal.get(v)));
                });

        DeferredResult<Void> retrieveAdminsGroupPrincipals = getGroupPrincipals(service,
                simpleState.administratorsUserGroupLinks, projectId, AuthRole.PROJECT_ADMIN)
                .thenAccept(principals -> expandedState.administrators.addAll(principals));

        DeferredResult<Void> retrieveMembersGroupPrincipals = getGroupPrincipals(service,
                simpleState.membersUserGroupLinks, projectId, AuthRole.PROJECT_MEMBER)
                .thenAccept(principals -> expandedState.members.addAll(principals));

        DeferredResult<Void> retrieveViewersGroupPrincipals = getGroupPrincipals(service,
                simpleState.viewersUserGroupLinks, projectId, AuthRole.PROJECT_VIEWER)
                .thenAccept(principals -> expandedState.viewers.addAll(principals));

        DeferredResult<Void> retrieveClusterLinks = retrieveClusterLinks(service,
                simpleState.documentSelfLink)
                        .thenAccept((clusterLinks) -> expandedState.clusterLinks = clusterLinks);

        DeferredResult<Void> retrieveTemplateLinks = retrieveTemplateLinks(service,
                simpleState.documentSelfLink)
                        .thenAccept((templateLinks) -> expandedState.templateLinks = templateLinks);

        DeferredResult<Void> retrieveRepositoriesAndImagesCount = retrieveRepositoriesAndTagsCount(service,
                simpleState.documentSelfLink, getProjectIndex(simpleState))
                        .thenAccept(
                                (repositories) -> {
                                    expandedState.repositories = new ArrayList<>(repositories.size());
                                    expandedState.numberOfImages = 0L;

                                    repositories.forEach((entry) -> {
                                        expandedState.repositories.add(entry.name);
                                        expandedState.numberOfImages += entry.tagsCount;
                                    });
                                });

        return DeferredResult.allOf(retrieveUserStatePrincipals, retrieveAdminsGroupPrincipals,
                retrieveMembersGroupPrincipals, retrieveViewersGroupPrincipals,
                retrieveClusterLinks, retrieveTemplateLinks, retrieveRepositoriesAndImagesCount)
                .thenApply((ignore) -> expandedState);
    }

    public static String getProjectIndex(ProjectState state) {
        if (state == null || state.customProperties == null) {
            return null;
        }
        return state.customProperties.get(ProjectService.CUSTOM_PROPERTY_PROJECT_INDEX);
    }

    private static DeferredResult<List<Principal>> getGroupPrincipals(Service service,
            List<String> groupLinks, String projectId, AuthRole role) {

        if (projectId == null || projectId.isEmpty()) {
            return DeferredResult.failed(new LocalizableValidationException(
                    String.format(PROPERTY_CANNOT_BE_EMPTY_MESSAGE_FORMAT, "projectId"),
                    "common.assertion.property.not.empty", "projectId"));
        }

        if (groupLinks == null || groupLinks.isEmpty()) {
            return DeferredResult.completed(new ArrayList<>());
        }

        if (!EnumSet.of(AuthRole.PROJECT_ADMIN, AuthRole.PROJECT_MEMBER, AuthRole.PROJECT_VIEWER)
                .contains(role)) {
            return DeferredResult.failed(new IllegalArgumentException(role.name() + "is not "
                    + "project role."));
        }

        String defaultProjectGroupLink = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                role.buildRoleWithSuffix(projectId));

        groupLinks.remove(defaultProjectGroupLink);

        List<DeferredResult<Principal>> results = new ArrayList<>();

        for (String groupLink : groupLinks) {
            results.add(PrincipalUtil.getPrincipal(service, Service.getId(groupLink)));
        }

        return DeferredResult.allOf(results);
    }

    private static DeferredResult<List<String>> retrieveClusterLinks(Service service,
            String projectLink) {
        return retrieveProjectRelatedDocumentLinks(service, projectLink, ClusterService.SELF_LINK);
    }

    private static DeferredResult<List<String>> retrieveTemplateLinks(Service service,
            String projectLink) {
        return retrieveProjectRelatedDocumentLinks(service, projectLink,
                CompositeDescriptionFactoryService.SELF_LINK);
    }

    private static DeferredResult<List<String>> retrieveProjectRelatedDocumentLinks(
            Service service, String projectLink, String factoryLink) {

        Operation get = Operation.createGet(service, factoryLink)
                .setReferer(service.getUri())
                .addRequestHeader(OperationUtil.PROJECT_ADMIRAL_HEADER, projectLink);
        authorizeOperationIfProjectService(service, get);

        return service.sendWithDeferredResult(get, ServiceDocumentQueryResult.class)
                .thenApply(result -> result.documentLinks);
    }

    private static DeferredResult<List<HbrRepositoriesResponseEntry>> retrieveRepositoriesAndTagsCount(
            Service service, String projectLink, String harborId) {
        if (harborId == null || harborId.isEmpty()) {
            service.getHost().log(Level.WARNING,
                    "harborId not set for project %s. Skipping repository retrieval", projectLink);
            return DeferredResult.completed(Collections.emptyList());
        }

        Operation getRepositories = Operation
                .createGet(UriUtils.buildUri(service.getHost(),
                        UriUtils.buildUriPath(HbrApiProxyService.SELF_LINK,
                                HbrApiProxyService.HARBOR_ENDPOINT_REPOSITORIES),
                        UriUtils.buildUriQuery(HbrApiProxyService.HARBOR_QUERY_PARAM_PROJECT_ID,
                                harborId,
                                HbrApiProxyService.HARBOR_QUERY_PARAM_DETAIL,
                                Boolean.toString(true))))
                .setReferer(ProjectFactoryService.SELF_LINK);

        authorizeOperationIfProjectService(service, getRepositories);
        return service.sendWithDeferredResult(getRepositories)
                .thenApply((op) -> {
                    Object body = op.getBodyRaw();
                    String stringBody = body instanceof String ? (String) body : Utils.toJson(body);

                    // Harbor is returning a list of JSON objects and since in java generic types
                    // are runtime only, the only types that we can get the response body are String
                    // and List of maps. If we try to do a Utils.fromJson later on for each list
                    // entry, we are very likely to get GSON parsing errors since Map.toString
                    // method (called by Utils.fromJson) does not produce valid JSON. For
                    // repositories, the forward slash in the name of the repository breaks
                    // everything.
                    // The following is a workaround: manually wrap the raw output in a
                    // valid JSON object with a single property (list of entries) and parse that.
                    String json = String.format("{\"%s\": %s}",
                            HbrRepositoriesResponse.FIELD_NAME_RESPONSE_ENTRIES, stringBody);

                    HbrRepositoriesResponse response = Utils.fromJson(json, HbrRepositoriesResponse.class);
                    return response.responseEntries;
                })
                .exceptionally((ex) -> {
                    service.getHost().log(Level.WARNING,
                            "Could not retrieve repositories for project %s with harborId %s: %s",
                            projectLink, harborId, Utils.toString(ex));
                    return Collections.emptyList();
                });
    }

    /**
     * Retrieves the list of members for the specified by document link user group.
     */
    private static DeferredResult<List<UserState>> retrieveUserGroupMembers(Service service,
            String groupLink, URI referer) {

        if (groupLink == null || groupLink.isEmpty()) {
            return DeferredResult.failed(new LocalizableValidationException(
                    String.format(PROPERTY_CANNOT_BE_EMPTY_MESSAGE_FORMAT, "groupLink"),
                    "common.assertion.property.not.empty", "groupLink"));
        }

        Operation groupGet = Operation.createGet(service, groupLink).setReferer(referer);
        authorizeOperationIfProjectService(service, groupGet);
        return service.sendWithDeferredResult(groupGet, UserGroupState.class)
                .thenCompose(groupState -> retrieveUserStatesForGroup(service, groupState));
    }

    /**
     * Retrieves the list of members for the specified user group.
     */
    public static DeferredResult<List<UserState>> retrieveUserStatesForGroup(Service service,
            UserGroupState groupState) {
        DeferredResult<List<UserState>> deferredResult = new DeferredResult<>();
        ArrayList<UserState> resultList = new ArrayList<>();

        QueryTask queryTask = QueryUtil.buildQuery(UserState.class, true, groupState.query);
        QueryUtil.addExpandOption(queryTask);
        new ServiceDocumentQuery<UserState>(service.getHost(), UserState.class)
                .query(queryTask, (r) -> {
                    if (r.hasException()) {
                        service.getHost().log(Level.WARNING,
                                "Failed to retrieve members of UserGroupState %s: %s",
                                groupState.documentSelfLink, Utils.toString(r.getException()));
                        deferredResult.fail(r.getException());
                    } else if (r.hasResult()) {
                        resultList.add(r.getResult());
                    } else {
                        deferredResult.complete(resultList);
                    }
                });

        return deferredResult;
    }

    /**
     * Builds a {@link Query} that selects all projects that contain any of the specified groups in
     * one of the administrators or members group lists
     */
    public static Query buildQueryProjectsFromGroups(Collection<String> groupLinks) {
        Query query = new Query();

        Query kindQuery = QueryUtil.createKindClause(ProjectState.class);
        kindQuery.setOccurance(Occurance.MUST_OCCUR);
        query.addBooleanClause(kindQuery);

        Query groupQuery = new Query();
        groupQuery.setOccurance(Occurance.MUST_OCCUR);
        query.addBooleanClause(groupQuery);

        Query adminGroupQuery = QueryUtil.addListValueClause(
                QuerySpecification.buildCollectionItemName(
                        ProjectState.FIELD_NAME_ADMINISTRATORS_USER_GROUP_LINKS),
                groupLinks, MatchType.TERM);
        adminGroupQuery.setOccurance(Occurance.SHOULD_OCCUR);
        groupQuery.addBooleanClause(adminGroupQuery);

        Query membersGroupQuery = QueryUtil.addListValueClause(
                QuerySpecification
                        .buildCollectionItemName(ProjectState.FIELD_NAME_MEMBERS_USER_GROUP_LINKS),
                groupLinks, MatchType.TERM);
        membersGroupQuery.setOccurance(Occurance.SHOULD_OCCUR);
        groupQuery.addBooleanClause(membersGroupQuery);

        Query viewersGroupQuery = QueryUtil.addListValueClause(
                QuerySpecification
                        .buildCollectionItemName(ProjectState.FIELD_NAME_VIEWERS_USER_GROUP_LINKS),
                groupLinks, MatchType.TERM);
        viewersGroupQuery.setOccurance(Occurance.SHOULD_OCCUR);
        groupQuery.addBooleanClause(viewersGroupQuery);

        return query;
    }

    public static Query buildQueryForProjectsFromProjectIndex(long projectIndex) {
        return QueryUtil.addCaseInsensitiveListValueClause(QuerySpecification
                .buildCompositeFieldName(ProjectState.FIELD_NAME_CUSTOM_PROPERTIES,
                        ProjectService.CUSTOM_PROPERTY_PROJECT_INDEX),
                Collections.singletonList(Long.toString(projectIndex)), MatchType.TERM);
    }

    public static Query buildQueryForProjectsFromName(String name, String documentSelfLink) {
        Query query = new Query();

        Query nameClause = QueryUtil.addCaseInsensitiveListValueClause(ProjectState.FIELD_NAME_NAME,
                Collections.singletonList(name), MatchType.TERM);

        Query selfLinkClause = QueryUtil.addCaseInsensitiveListValueClause(ProjectState
                .FIELD_NAME_SELF_LINK, Collections.singletonList(documentSelfLink), MatchType.TERM);
        selfLinkClause.setOccurance(Occurance.MUST_NOT_OCCUR);

        query.addBooleanClause(nameClause);
        query.addBooleanClause(selfLinkClause);

        return query;
    }

    public static long generateRandomUnsignedInt() {
        return ThreadLocalRandom.current().nextLong(PROJECT_INDEX_ORIGIN, PROJECT_INDEX_BOUND);
    }

    public static void authorizeOperationIfProjectService(Service requestorService, Operation op) {
        if (requestorService instanceof ProjectService
                || requestorService instanceof ProjectFactoryService) {
            requestorService.setAuthorizationContext(op,
                    requestorService.getSystemAuthorizationContext());
        }
    }
}