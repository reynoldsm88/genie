/*
 *
 *  Copyright 2013 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.server.services.impl;

import com.netflix.client.http.HttpRequest.Verb;
import com.netflix.genie.common.exceptions.CloudServiceException;
import com.netflix.genie.common.messages.ClusterConfigRequest;
import com.netflix.genie.common.messages.ClusterConfigResponse;
import com.netflix.genie.common.model.Cluster;
import com.netflix.genie.common.model.ClusterCriteria;
import com.netflix.genie.common.model.Command;
import com.netflix.genie.common.model.Types;
import com.netflix.genie.common.model.Types.ClusterStatus;
import com.netflix.genie.server.persistence.ClauseBuilder;
import com.netflix.genie.server.persistence.PersistenceManager;
import com.netflix.genie.server.persistence.QueryBuilder;
import com.netflix.genie.server.services.ClusterConfigService;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.persistence.EntityExistsException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.Query;
import javax.persistence.RollbackException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the PersistentClusterConfig interface.
 *
 * @author skrishnan
 * @author amsharma
 */
public class PersistentClusterConfigImpl implements ClusterConfigService {

    private static final Logger LOG = LoggerFactory
            .getLogger(PersistentClusterConfigImpl.class);

    private final PersistenceManager<Cluster> pm;

    /**
     * Default constructor - initialize all required dependencies.
     *
     * @throws CloudServiceException
     */
    public PersistentClusterConfigImpl() throws CloudServiceException {
        // instantiate PersistenceManager
        this.pm = new PersistenceManager<Cluster>();
    }

    @Override
    public ClusterConfigResponse getClusterConfig(String id) {
        LOG.info("called");

        ClusterConfigResponse ccr;
        Cluster cce;
        try {
            cce = pm.getEntity(id, Cluster.class);
        } catch (Exception e) {
            LOG.error("Failed to get cluster config: ", e);
            ccr = new ClusterConfigResponse(new CloudServiceException(
                    HttpURLConnection.HTTP_INTERNAL_ERROR, e.getMessage()));
            return ccr;
        }

        if (cce == null) {
            String msg = "Cluster config not found: " + id;
            LOG.error(msg);
            ccr = new ClusterConfigResponse(new CloudServiceException(
                    HttpURLConnection.HTTP_NOT_FOUND, msg));
            return ccr;
        } else {
            ccr = new ClusterConfigResponse();
            ccr.setClusterConfigs(new Cluster[]{cce});
            ccr.setMessage("Returning cluster config for: " + id);
            return ccr;
        }
    }

    @Override
    public ClusterConfigResponse getClusterConfig(String id, String name,
            String commandId, List<String> tags, ClusterStatus status) {

        List<String> statusList = Arrays.asList(status.name());
        return getClusterConfig(id, name, statusList, tags,
                null, null, null, null);
    }

    @Override
    public ClusterConfigResponse getClusterConfig(String id, String name,
            List<String> status, List<String> tags, Long minUpdateTime,
            Long maxUpdateTime, Integer limit, Integer page) {

        LOG.info("called");

        ClusterConfigResponse ccr;
        try {
            LOG.info("GENIE: Returning configs for specified params");

            ccr = new ClusterConfigResponse();
            Object[] results;

            // construct query
            ClauseBuilder criteria = new ClauseBuilder(ClauseBuilder.AND);
            if ((id != null) && (!id.isEmpty())) {
                criteria.append("id like '" + id + "'");
            }
            if ((name != null) && (!name.isEmpty())) {
                criteria.append("name like '" + name + "'");
            }
            if (minUpdateTime != null) {
                criteria.append("updated >= " + minUpdateTime);
            }
            if (maxUpdateTime != null) {
                criteria.append("updated <= " + maxUpdateTime);
            }

            if(tags != null) {
                for (String tag : tags) {
                    criteria.append("\"" + tag + "\" member of T.tags", false);
                }
            }
            
            if ((status != null) && !status.isEmpty()) {
                int count = 0;
                ClauseBuilder statusCriteria = new ClauseBuilder(ClauseBuilder.OR);
                Iterator<String> it = status.iterator();
                while (it.hasNext()) {
                    String next = it.next();
                    if ((next == null) || (next.isEmpty())) {
                        continue;
                    }
                    if (Types.ClusterStatus.parse(next) == null) {
                        ccr = new ClusterConfigResponse(
                                new CloudServiceException(
                                        HttpURLConnection.HTTP_BAD_REQUEST,
                                        "Cluster status: " + next
                                        + " can only be UP, OUT_OF_SERVICE or TERMINATED"));
                        LOG.error(ccr.getErrorMsg());
                        return ccr;
                    }
                    statusCriteria.append("status = '" + next.toUpperCase() + "'");
                    count++;
                }
                if (count > 0) {
                    criteria.append("(" + statusCriteria.toString() + ")", false);
                }
            }

            // Get all the results as an array
            String criteriaString = criteria.toString();
            LOG.info("Criteria: " + criteriaString);
            QueryBuilder builder = new QueryBuilder()
                    .table("Cluster").clause(criteriaString)
                    .limit(limit).page(page);
            results = pm.query(builder);

            if (results.length == 0) {
                ccr = new ClusterConfigResponse(new CloudServiceException(
                        HttpURLConnection.HTTP_NOT_FOUND,
                        "No clusterConfigs found for input parameters"));
                LOG.error(ccr.getErrorMsg());
                return ccr;
            } else {
                ccr.setMessage("Returning clusterConfigs for input parameters");
            }

            Cluster[] elements = new Cluster[results.length];
            for (int i = 0; i < elements.length; i++) {
                elements[i] = (Cluster) results[i];
            }
            ccr.setClusterConfigs(elements);
            return ccr;

        } catch (final CloudServiceException e) {
            LOG.error(e.getMessage(), e);
            ccr = new ClusterConfigResponse(new CloudServiceException(
                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                    "Received exception: " + e.getMessage()));
            return ccr;
        }
    }

    @Override
    public ClusterConfigResponse createClusterConfig(
            ClusterConfigRequest request) {
        LOG.info("called");
        return createUpdateConfig(request, Verb.POST);
    }

    @Override
    public ClusterConfigResponse updateClusterConfig(
            ClusterConfigRequest request) {
        LOG.info("called");
        return createUpdateConfig(request, Verb.PUT);
    }

    @Override
    public ClusterConfigResponse deleteClusterConfig(String id) {
        LOG.info("called");
        ClusterConfigResponse ccr;

        if (id == null) {
            // basic error checking
            ccr = new ClusterConfigResponse(new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "Missing required parameter: id"));
            LOG.error(ccr.getErrorMsg());
        } else {
            // do some filtering
            LOG.info("GENIE: Deleting clusterConfig for id: " + id);
            try {
                Cluster element = pm.deleteEntity(id,
                        Cluster.class);
                if (element == null) {
                    // element doesn't exist
                    ccr = new ClusterConfigResponse(new CloudServiceException(
                            HttpURLConnection.HTTP_NOT_FOUND,
                            "No clusterConfig exists for id: " + id));
                    LOG.error(ccr.getErrorMsg());
                } else {
                    // all good - create a response
                    ccr = new ClusterConfigResponse();
                    ccr.setMessage("Successfully deleted clusterConfig for id: "
                            + id);
                    Cluster[] elements = new Cluster[]{element};
                    ccr.setClusterConfigs(elements);
                }
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                // send the exception back
                ccr = new ClusterConfigResponse(new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        "Received exception: " + e.getMessage()));
            }
        }
        return ccr;
    }

    /*
     * Common private method called by the create and update Can use either
     * method to create/update resource.
     */
    private ClusterConfigResponse createUpdateConfig(
            ClusterConfigRequest request, Verb method) {
        LOG.debug("called");
        ClusterConfigResponse ccr;
        Cluster clusterConfig = request.getClusterConfig();
        // ensure that the element is not null
        if (clusterConfig == null) {
            ccr = new ClusterConfigResponse(new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "Missing clusterConfig object"));
            LOG.error(ccr.getErrorMsg());
            return ccr;
        }

        // generate/validate id for request
        String id = clusterConfig.getId();
        if (id == null) {
            if (method.equals(Verb.POST)) {
                // create UUID for POST, if it doesn't exist
                id = UUID.randomUUID().toString();
                clusterConfig.setId(id);
            } else {
                ccr = new ClusterConfigResponse(new CloudServiceException(
                        HttpURLConnection.HTTP_BAD_REQUEST,
                        "Missing required parameter for PUT: id"));
                LOG.error(ccr.getErrorMsg());
                return ccr;
            }
        }

        // more error checking
        if (clusterConfig.getUser() == null) {
            ccr = new ClusterConfigResponse(
                    new CloudServiceException(
                            HttpURLConnection.HTTP_BAD_REQUEST,
                            "Missing parameter 'user' for creating/updating clusterConfig"));
            LOG.error(ccr.getErrorMsg());
            return ccr;
        }

        // ensure that child command configs exist
        try {
            validateChildren(clusterConfig);
        } catch (CloudServiceException cse) {
            ccr = new ClusterConfigResponse(cse);
            LOG.error(ccr.getErrorMsg(), cse);
            return ccr;
        }

        // common error checks done - set update time before proceeding
        //Should now be done automatically by @PreUpdate but will leave just in case
//        clusterConfig.setUpdateTime(System.currentTimeMillis());
        // handle POST and PUT differently
        if (method.equals(Verb.POST)) {
            LOG.info("GENIE: creating config for id: " + id);

            // validate/initialize new element
            try {
                initAndValidateNewElement(clusterConfig);
            } catch (CloudServiceException e) {
                ccr = new ClusterConfigResponse(e);
                LOG.error(ccr.getErrorMsg(), e);
                return ccr;
            }

            // now create the new element
            try {
                pm.createEntity(clusterConfig);

                // create a response
                ccr = new ClusterConfigResponse();
                ccr.setMessage("Successfully created clusterConfig for id: "
                        + id);
                ccr.setClusterConfigs(new Cluster[]{clusterConfig});
                return ccr;
            } catch (RollbackException e) {
                LOG.error(e.getMessage(), e);
                if (e.getCause() instanceof EntityExistsException) {
                    // most likely entity already exists - return useful message
                    ccr = new ClusterConfigResponse(new CloudServiceException(
                            HttpURLConnection.HTTP_CONFLICT,
                            "Cluster already exists for id: " + id
                            + ", use PUT to update config"));
                    return ccr;
                } else {
                    // unknown exception - send it back
                    ccr = new ClusterConfigResponse(new CloudServiceException(
                            HttpURLConnection.HTTP_INTERNAL_ERROR,
                            "Received exception: " + e.getCause()));
                    LOG.error(ccr.getErrorMsg());
                    return ccr;
                }
            }
        } else {
            // method is PUT
            LOG.info("GENIE: updating config for id: " + id);

            try {
                Cluster old = pm.getEntity(id,
                        Cluster.class);
                // check if this is a create or an update
                if (old == null) {
                    try {
                        initAndValidateNewElement(clusterConfig);
                    } catch (CloudServiceException e) {
                        ccr = new ClusterConfigResponse(e);
                        LOG.error(ccr.getErrorMsg(), e);
                        return ccr;
                    }
                }
                clusterConfig = pm.updateEntity(clusterConfig);

                // all good - create a response
                ccr = new ClusterConfigResponse();
                ccr.setMessage("Successfully updated clusterConfig for id: "
                        + id);
                ccr.setClusterConfigs(new Cluster[]{clusterConfig});
                return ccr;
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                // send the exception back
                ccr = new ClusterConfigResponse(new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        "Received exception: " + e.getCause()));
                return ccr;
            }
        }
    }

    private void validateChildren(Cluster clusterConfigElement)
            throws CloudServiceException {

        ArrayList<String> cmdIds = clusterConfigElement.getCmdIds();

        if (cmdIds != null) {
            PersistenceManager<Command> pma = new PersistenceManager<Command>();
            ArrayList<Command> cmdList = new ArrayList<Command>();
            Iterator<String> it = cmdIds.iterator();
            while (it.hasNext()) {
                String cmdId = (String) it.next();
                Command cmde = (Command) pma.getEntity(cmdId, Command.class);
                if (cmde != null) {
                    cmdList.add(cmde);
                } else {
                    throw new CloudServiceException(
                            HttpURLConnection.HTTP_BAD_REQUEST,
                            "Command Does Not Exist: {" + cmdId + "}");
                }
            }
            clusterConfigElement.setCommands(cmdList);
        } else {
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "No commandId's specified for the cluster");
        }
    }

    /*
     * Throws exception if all required params are not present - else
     * initialize, and set creation time.
     */
    private void initAndValidateNewElement(
            Cluster clusterConfigElement)
            throws CloudServiceException {

        //TODO Figure out all mandatory parameters for a cluster
        if (StringUtils.isEmpty(clusterConfigElement.getName())) {
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "Missing required Hadoop parameters for creating clusterConfig: "
                    + "{name, s3MapredSiteXml, s3HdfsSiteXml, s3CoreSiteXml}");
        }
    }

    @Override
    public ClusterConfigResponse getClusterConfig(String applicationId,
            String applicationName, String commandId, String commandName,
            ArrayList<ClusterCriteria> clusterCriteriaList) {

        ClusterConfigResponse ccr;

        //TODO: Remove use of iterators explicitly
        Iterator<ClusterCriteria> criteriaIter = clusterCriteriaList.iterator();
        //String queryString = "SELECT distinct x from Cluster x, IN(x.tags) t WHERE " ;
        //String queryString = "SELECT distinct x from Cluster x, IN(x.commands) cmds where :element1 member of  x.tags AND :element2 member of x.tags AND cmds.name=\"prodhive\"";

        Cluster[] elements = null;

        while (criteriaIter.hasNext()) {
            final StringBuilder builder = new StringBuilder();
            builder.append("SELECT distinct cstr from Cluster cstr, IN(cstr.commands) cmds ");

            if ((applicationId != null) && (!applicationId.isEmpty())) {
                builder.append(", IN(cmds.applications) apps ");
            } else if ((applicationName != null) && (!applicationName.isEmpty())) {
                builder.append(", IN(cmds.applications) apps ");
            }

            builder.append(" where ");

            ClusterCriteria cc = (ClusterCriteria) criteriaIter.next();

            for (int i = 0; i < cc.getTags().size(); i++) {
                if (i != 0) {
                    builder.append(" AND ");
                }

                builder.append(":tag").append(i).append(" member of cstr.tags ");
            }

            if ((commandId != null) && (!commandId.isEmpty())) {
                builder.append(" AND cmds.id = \"").append(commandId).append("\" ");
            } else if ((commandName != null) && (!commandName.isEmpty())) {
                builder.append(" AND cmds.name = \"").append(commandName).append("\" ");
            }

            if ((applicationId != null) && (!applicationId.isEmpty())) {
                builder.append(" AND apps.id = \"").append(applicationId).append("\" ");
            } else if ((applicationName != null) && (!applicationName.isEmpty())) {
                builder.append(" AND apps.name = \"").append(applicationName).append("\" ");
            }

            Iterator<String> tagIter = cc.getTags().iterator();

            final String queryString = builder.toString();
            LOG.info("Query is " + queryString);

            Map<Object, Object> map = new HashMap<Object, Object>();
            EntityManagerFactory factory = Persistence.createEntityManagerFactory("genie", map);
            EntityManager em = factory.createEntityManager();
            Query q = em.createQuery(queryString);

            int tagNum = 0;
            while (tagIter.hasNext()) {
                String tag = (String) tagIter.next();
                q.setParameter("tag" + tagNum, tag);
                tagNum++;
            }

            List<Cluster> results = (List<Cluster>) q.getResultList();

            if (results.size() > 0) {
                elements = new Cluster[results.size()];
                Iterator<Cluster> cceIter = results.iterator();
                int j = 0;
                while (cceIter.hasNext()) {
                    Cluster cce = (Cluster) cceIter.next();
                    elements[j] = cce;
                    j++;
                }
                break;
            }
        }

        ccr = new ClusterConfigResponse();
        ccr.setClusterConfigs(elements);

        return ccr;
    }
}
