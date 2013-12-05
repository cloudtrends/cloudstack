// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.acl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.acl.dao.AclApiPermissionDao;
import org.apache.cloudstack.acl.dao.AclGroupAccountMapDao;
import org.apache.cloudstack.acl.dao.AclGroupDao;
import org.apache.cloudstack.acl.dao.AclGroupPolicyMapDao;
import org.apache.cloudstack.acl.dao.AclPolicyDao;
import org.apache.cloudstack.acl.dao.AclPolicyPermissionDao;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.context.CallContext;

import com.cloud.domain.Domain;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

@Local(value = {AclService.class})
public class AclServiceImpl extends ManagerBase implements AclService, Manager {

    public static final Logger s_logger = Logger.getLogger(AclServiceImpl.class);
    private String _name;

    @Inject
    AccountManager _accountMgr;

    @Inject
    AccountDao _accountDao;

    @Inject
    AclPolicyDao _aclRoleDao;

    @Inject
    AclGroupDao _aclGroupDao;

    @Inject
    EntityManager _entityMgr;

    @Inject
    AclGroupPolicyMapDao _aclGroupPolicyMapDao;

    @Inject
    AclGroupAccountMapDao _aclGroupAccountMapDao;

    @Inject
    AclApiPermissionDao _apiPermissionDao;

    @Inject
    AclPolicyPermissionDao _policyPermissionDao;


    public static HashMap<String, Class> entityClassMap = new HashMap<String, Class>();

    static {
        entityClassMap.put("VirtualMachine", UserVm.class);
        entityClassMap.put("Volume", Volume.class);
        entityClassMap.put("Template", VirtualMachineTemplate.class);
        entityClassMap.put("Snapshot", Snapshot.class);
        // To be filled in later depending on the entity permission grant scope
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_CREATE, eventDescription = "Creating Acl Role", create = true)
    public AclRole createAclRole(Long domainId, final String aclRoleName, final String description, final Long parentRoleId) {
        Account caller = CallContext.current().getCallingAccount();
        if (domainId == null) {
            domainId = caller.getDomainId();
        }
        if (!_accountMgr.isRootAdmin(caller.getAccountId())) {
            // domain admin can only create role for his domain
            if (caller.getDomainId() != domainId.longValue()) {
                throw new PermissionDeniedException("Can't create acl role in domain " + domainId + ", permission denied");
            }
        }
        // check if the role is already existing
        AclRole ro = _aclRoleDao.findByName(domainId, aclRoleName);
        if (ro != null) {
            throw new InvalidParameterValueException(
                    "Unable to create acl role with name " + aclRoleName
                            + " already exisits for domain " + domainId);
        }

        final long domain_id = domainId;
        AclRole role = Transaction.execute(new TransactionCallback<AclRole>() {
            @Override
            public AclRole doInTransaction(TransactionStatus status) {
                AclRoleVO rvo = new AclRoleVO(aclRoleName, description);
                rvo.setDomainId(domain_id);
                AclRole role = _aclRoleDao.persist(rvo);
                if (parentRoleId != null) {
                    // copy parent role permissions
                    List<AclRolePermissionVO> perms = _policyPermissionDao.listByRole(parentRoleId);
                    if (perms != null) {
                        for (AclRolePermissionVO perm : perms) {
                            perm.setAclRoleId(role.getId());
                            _policyPermissionDao.persist(perm);
                        }
                    }
                }
                return role;
            }
        });
                

        return role;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_DELETE, eventDescription = "Deleting Acl Role")
    public boolean deleteAclRole(final long aclRoleId) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        final AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to delete acl role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove this role related entry in acl_group_role_map
                List<AclGroupRoleMapVO> groupRoleMap = _aclGroupPolicyMapDao.listByRoleId(role.getId());
                if (groupRoleMap != null) {
                    for (AclGroupRoleMapVO gr : groupRoleMap) {
                        _aclGroupPolicyMapDao.remove(gr.getId());
                    }
                }

                // remove this role related entry in acl_api_permission table
                List<AclApiPermissionVO> roleApiMap = _apiPermissionDao.listByRoleId(role.getId());
                if (roleApiMap != null) {
                    for (AclApiPermissionVO roleApi : roleApiMap) {
                        _apiPermissionDao.remove(roleApi.getId());
                    }
                }

                // remove this role from acl_role table
                _aclRoleDao.remove(aclRoleId);
            }
        });

        return true;
    }


    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_GRANT, eventDescription = "Granting permission to Acl Role")
    public AclRole grantApiPermissionToAclRole(final long aclRoleId, final List<String> apiNames) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to grant permission to role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_api_permission table
                for (String api : apiNames) {
                    AclApiPermissionVO perm = _apiPermissionDao.findByRoleAndApi(aclRoleId, api);
                    if (perm == null) {
                        // not there already
                        perm = new AclApiPermissionVO(aclRoleId, api);
                        _apiPermissionDao.persist(perm);
                    }
                }
            }
        });
            
        return role;

    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_REVOKE, eventDescription = "Revoking permission from Acl Role")
    public AclRole revokeApiPermissionFromAclRole(final long aclRoleId, final List<String> apiNames) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to revoke permission from role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove entries from acl_api_permission table
                for (String api : apiNames) {
                    AclApiPermissionVO perm = _apiPermissionDao.findByRoleAndApi(aclRoleId, api);
                    if (perm != null) {
                        // not removed yet
                        _apiPermissionDao.remove(perm.getId());
                    }
                }
            }
        });
        return role;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_GRANT, eventDescription = "Granting entity permission to Acl Group")
    public AclGroup grantEntityPermissionToAclGroup(long aclGroupId, String entityType, long entityId, AccessType accessType) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(aclGroupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + aclGroupId
                    + "; failed to grant permission to group.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, group);

        // get the entity and check permission
        Class entityClass = entityClassMap.get(entityType);
        if (entityClass == null) {
            throw new InvalidParameterValueException("Entity type " + entityType + " permission granting is not supported yet");
        }
        ControlledEntity entity = (ControlledEntity)_entityMgr.findById(entityClass, entityId);
        if (entity == null) {
            throw new InvalidParameterValueException("Unable to find entity " + entityType + " by id: " + entityId);
        }
        _accountMgr.checkAccess(caller,null, true, entity);
        
        // add entry in acl_entity_permission table
        AclEntityPermissionVO perm = _entityPermissionDao.findByGroupAndEntity(aclGroupId, entityType, entityId, accessType);
        if (perm == null) {
            // not there already
            String entityUuid = String.valueOf(entityId);
            if (entity instanceof Identity) {
                entityUuid = ((Identity)entity).getUuid();
            }
            perm = new AclEntityPermissionVO(aclGroupId, entityType, entityId, entityUuid, accessType, true);
            _entityPermissionDao.persist(perm);
        }
        return group;

    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_REVOKE, eventDescription = "Revoking entity permission from Acl Group")
    public AclGroup revokeEntityPermissionFromAclGroup(long aclGroupId, String entityType, long entityId, AccessType accessType) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(aclGroupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + aclGroupId
                    + "; failed to revoke permission from group.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, group);

        // get the entity and check permission
        Class entityClass = entityClassMap.get(entityType);
        if (entityClass == null) {
            throw new InvalidParameterValueException("Entity type " + entityType + " permission revoke is not supported yet");
        }
        ControlledEntity entity = (ControlledEntity)_entityMgr.findById(entityClass, entityId);
        if (entity == null) {
            throw new InvalidParameterValueException("Unable to find entity " + entityType + " by id: " + entityId);
        }
        _accountMgr.checkAccess(caller, null, true, entity);

        // remove entry from acl_entity_permission table
        AclEntityPermissionVO perm = _entityPermissionDao.findByGroupAndEntity(aclGroupId, entityType, entityId, accessType);
        if (perm != null) {
            // not removed yet
            _entityPermissionDao.remove(perm.getId());
        }
        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Adding roles to acl group")
    public AclGroup addAclRolesToGroup(final List<Long> roleIds, final Long groupId) {
        final Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to add roles to acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);
 
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_role_map table
                for (Long roleId : roleIds) {
                    // check role permissions
                    AclRole role = _aclRoleDao.findById(roleId);
                    if (role == null) {
                        throw new InvalidParameterValueException("Unable to find acl role: " + roleId
                                + "; failed to add roles to acl group.");
                    }
                    _accountMgr.checkAccess(caller, null, true, role);

                    AclGroupRoleMapVO grMap = _aclGroupPolicyMapDao.findByGroupAndRole(groupId, roleId);
                    if (grMap == null) {
                        // not there already
                        grMap = new AclGroupRoleMapVO(groupId, roleId);
                        _aclGroupPolicyMapDao.persist(grMap);
                    }
                }
            }
        });

        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Removing roles from acl group")
    public AclGroup removeAclRolesFromGroup(final List<Long> roleIds, final Long groupId) {
        final Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to remove roles from acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_role_map table
                for (Long roleId : roleIds) {
                    // check role permissions
                    AclRole role = _aclRoleDao.findById(roleId);
                    if (role == null) {
                        throw new InvalidParameterValueException("Unable to find acl role: " + roleId
                                + "; failed to add roles to acl group.");
                    }
                    _accountMgr.checkAccess(caller, null, true, role);

                    AclGroupRoleMapVO grMap = _aclGroupPolicyMapDao.findByGroupAndRole(groupId, roleId);
                    if (grMap != null) {
                        // not removed yet
                        _aclGroupPolicyMapDao.remove(grMap.getId());
                    }
                }
            }
        });
        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Adding accounts to acl group")
    public AclGroup addAccountsToGroup(final List<Long> acctIds, final Long groupId) {
        final Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to add accounts to acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_account_map table
                for (Long acctId : acctIds) {
                    // check account permissions
                    Account account = _accountDao.findById(acctId);
                    if (account == null) {
                        throw new InvalidParameterValueException("Unable to find account: " + acctId
                                + "; failed to add account to acl group.");
                    }
                    _accountMgr.checkAccess(caller, null, true, account);

                    AclGroupAccountMapVO grMap = _aclGroupAccountMapDao.findByGroupAndAccount(groupId, acctId);
                    if (grMap == null) {
                        // not there already
                        grMap = new AclGroupAccountMapVO(groupId, acctId);
                        _aclGroupAccountMapDao.persist(grMap);
                    }
                }
            }
        });
        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Removing accounts from acl group")
    public AclGroup removeAccountsFromGroup(final List<Long> acctIds, final Long groupId) {
        final Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to remove accounts from acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove entries from acl_group_account_map table
                for (Long acctId : acctIds) {
                    // check account permissions
                    Account account = _accountDao.findById(acctId);
                    if (account == null) {
                        throw new InvalidParameterValueException("Unable to find account: " + acctId
                                + "; failed to add account to acl group.");
                    }
                    _accountMgr.checkAccess(caller, null, true, account);

                    AclGroupAccountMapVO grMap = _aclGroupAccountMapDao.findByGroupAndAccount(groupId, acctId);
                    if (grMap != null) {
                        // not removed yet
                        _aclGroupAccountMapDao.remove(grMap.getId());
                    }
                }
            }
        });
        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_CREATE, eventDescription = "Creating Acl Group", create = true)
    public AclGroup createAclGroup(Long domainId, String aclGroupName, String description) {
        Account caller = CallContext.current().getCallingAccount();
        if (domainId == null) {
            domainId = caller.getDomainId(); // use caller's domain id
        }
        if (!_accountMgr.isRootAdmin(caller.getAccountId())) {
            // domain admin can only create role for his domain
            if (caller.getDomainId() != domainId.longValue()) {
                throw new PermissionDeniedException("Can't create acl group in domain " + domainId + ", permission denied");
            }
        }
        // check if the role is already existing
        AclGroup grp = _aclGroupDao.findByName(domainId, aclGroupName);
        if (grp != null) {
            throw new InvalidParameterValueException(
                    "Unable to create acl group with name " + aclGroupName
                            + " already exisits for domain " + domainId);
        }
        AclGroupVO rvo = new AclGroupVO(aclGroupName, description);
        rvo.setDomainId(domainId);

        return _aclGroupDao.persist(rvo);
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_DELETE, eventDescription = "Deleting Acl Group")
    public boolean deleteAclGroup(final Long aclGroupId) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        final AclGroup grp = _aclGroupDao.findById(aclGroupId);
        if (grp == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + aclGroupId
                    + "; failed to delete acl group.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, grp);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove this group related entry in acl_group_role_map
                List<AclGroupRoleMapVO> groupRoleMap = _aclGroupPolicyMapDao.listByGroupId(grp.getId());
                if (groupRoleMap != null) {
                    for (AclGroupRoleMapVO gr : groupRoleMap) {
                        _aclGroupPolicyMapDao.remove(gr.getId());
                    }
                }

                // remove this group related entry in acl_group_account table
                List<AclGroupAccountMapVO> groupAcctMap = _aclGroupAccountMapDao.listByGroupId(grp.getId());
                if (groupAcctMap != null) {
                    for (AclGroupAccountMapVO grpAcct : groupAcctMap) {
                        _aclGroupAccountMapDao.remove(grpAcct.getId());
                    }
                }

                // remove this group from acl_group table
                _aclGroupDao.remove(aclGroupId);
            }
        });

        return true;
    }

    @Override
    public List<AclRole> getAclRoles(long accountId) {

        // static roles of the account
        SearchBuilder<AclGroupAccountMapVO> groupSB = _aclGroupAccountMapDao.createSearchBuilder();
        groupSB.and("account", groupSB.entity().getAccountId(), Op.EQ);

        GenericSearchBuilder<AclGroupRoleMapVO, Long> roleSB = _aclGroupPolicyMapDao.createSearchBuilder(Long.class);
        roleSB.selectFields(roleSB.entity().getAclRoleId());
        roleSB.join("accountgroupjoin", groupSB, groupSB.entity().getAclGroupId(), roleSB.entity().getAclGroupId(),
                JoinType.INNER);
        roleSB.done();
        SearchCriteria<Long> roleSc = roleSB.create();
        roleSc.setJoinParameters("accountgroupjoin", "account", accountId);

        List<Long> roleIds = _aclGroupPolicyMapDao.customSearch(roleSc, null);

        SearchBuilder<AclRoleVO> sb = _aclRoleDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        SearchCriteria<AclRoleVO> sc = sb.create();
        sc.setParameters("ids", roleIds.toArray(new Object[roleIds.size()]));
        List<AclRoleVO> roles = _aclRoleDao.customSearch(sc, null);

        return new ArrayList<AclRole>(roles);
    }

    @Override
    public AclRolePermission getAclRolePermission(long accountId, String entityType, AccessType accessType) {
        List<AclRole> roles = getAclRoles(accountId);
        AclRolePermission curPerm = null;
        for (AclRole role : roles) {
            AclRolePermission perm = _policyPermissionDao.findByRoleEntityAndPermission(role.getId(), entityType, accessType, true);
            if (perm == null)
                continue;
            if (curPerm == null) {
                curPerm = perm;
            } else if (perm.getScope().greaterThan(curPerm.getScope())) {
                // pick the more relaxed allowed permission
                curPerm = perm;
            }
        }

        return curPerm;
    }

    @Override
    public List<AclGroup> getAclGroups(long accountId) {

        GenericSearchBuilder<AclGroupAccountMapVO, Long> groupSB = _aclGroupAccountMapDao.createSearchBuilder(Long.class);
        groupSB.selectFields(groupSB.entity().getAclGroupId());
        groupSB.and("account", groupSB.entity().getAccountId(), Op.EQ);
        SearchCriteria<Long> groupSc = groupSB.create();

        List<Long> groupIds = _aclGroupAccountMapDao.customSearch(groupSc, null);

        SearchBuilder<AclGroupVO> sb = _aclGroupDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        SearchCriteria<AclGroupVO> sc = sb.create();
        sc.setParameters("ids", groupIds.toArray(new Object[groupIds.size()]));
        List<AclGroupVO> groups = _aclGroupDao.search(sc, null);

        return new ArrayList<AclGroup>(groups);
    }

    @Override
    public Pair<List<Long>, List<Long>> getAclEntityPermission(long accountId, String entityType, AccessType accessType) {
        List<AclGroup> groups = getAclGroups(accountId);
        if (groups == null || groups.size() == 0) {
            return new Pair<List<Long>, List<Long>>(new ArrayList<Long>(), new ArrayList<Long>());
        }
        Set<Long> allowedIds = new HashSet<Long>();
        Set<Long> deniedIds = new HashSet<Long>();
        for (AclGroup grp : groups) {
            // get allowed id  and denied list for each group
            List<Long> allowedIdList = _entityPermissionDao.findEntityIdByGroupAndPermission(grp.getId(), entityType, accessType, true);
            if (allowedIdList != null) {
                allowedIds.addAll(allowedIdList);
            }
            List<Long> deniedIdList = _entityPermissionDao.findEntityIdByGroupAndPermission(grp.getId(), entityType, accessType, false);
            if (deniedIdList != null) {
                deniedIds.addAll(deniedIdList);
            }
        }
        return new Pair<List<Long>, List<Long>>(new ArrayList<Long>(allowedIds), new ArrayList<Long>(deniedIds))
        ;
    }

    @Override
    public boolean isAPIAccessibleForRoles(String apiName, List<AclRole> roles) {

        boolean accessible = false;

        List<Long> roleIds = new ArrayList<Long>();
        for (AclRole role : roles) {
            roleIds.add(role.getId());
        }

        SearchBuilder<AclApiPermissionVO> sb = _apiPermissionDao.createSearchBuilder();
        sb.and("apiName", sb.entity().getApiName(), Op.EQ);
        sb.and("roleId", sb.entity().getAclRoleId(), Op.IN);

        SearchCriteria<AclApiPermissionVO> sc = sb.create();
        sc.setParameters("roleId", roleIds.toArray(new Object[roleIds.size()]));

        List<AclApiPermissionVO> permissions = _apiPermissionDao.customSearch(sc, null);

        if (permissions != null && !permissions.isEmpty()) {
            accessible = true;
        }

        return accessible;
    }

    @Override
    public List<AclRole> getEffectiveRoles(Account caller, ControlledEntity entity) {

        // Get the static Roles of the Caller
        List<AclRole> roles = getAclRoles(caller.getId());

        // add any dynamic roles w.r.t the entity
        if (caller.getId() == entity.getAccountId()) {
            // The caller owns the entity
            AclRole owner = _aclRoleDao.findByName(Domain.ROOT_DOMAIN, "RESOURCE_OWNER");
            roles.add(owner);
        }

        return roles;
    }

    @Override
    public List<Long> getGrantedDomains(long accountId, AclEntityType entityType, String action) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<Long> getGrantedAccounts(long accountId, AclEntityType entityType, String action) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<Long> getGrantedResources(long accountId, AclEntityType entityType, String action) {
        // TODO Auto-generated method stub
        return null;
    }

}