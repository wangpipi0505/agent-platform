import{b as rs}from"./input-schema-BZG-nLDC.js";import{p as wt}from"./satellite-planning.seed-tgGLGXYD.js";import{a as Mt,r as os,m as ds,b as ls}from"./mock-artifacts-DFvtKW-B.js";import"./2020-CT3TCxRe.js";import"./index-4m_1EMrH.js";const lt=`{
  "sceneVersion": "demo-20261105.1",
  "clock": "2026-11-05T09:00:00+08:00",
  "planningDate": "2026-11-06",
  "classifications": [
    {
      "id": "GENERAL",
      "code": "GENERAL",
      "name": "通用能力",
      "kind": "BIZ_CATEGORY",
      "children": []
    },
    {
      "id": "SPACE_OBSERVATION",
      "code": "SPACE_OBSERVATION",
      "name": "观测规划",
      "kind": "BIZ_CATEGORY",
      "children": []
    },
    {
      "id": "USER_GOVERNANCE",
      "code": "USER_GOVERNANCE",
      "name": "用户与权限管理",
      "kind": "BIZ_CATEGORY",
      "children": []
    },
    {
      "id": "DATA_PROCESSING",
      "code": "DATA_PROCESSING",
      "name": "数据处理",
      "kind": "BIZ_CATEGORY",
      "children": []
    }
  ],
  "businessSystem": {
    "id": "user-mgmt",
    "code": "user-mgmt",
    "name": "业务用户管理系统",
    "revision": 1
  },
  "departments": [
    {
      "id": "dept-a",
      "name": "遥感业务部"
    },
    {
      "id": "dept-b",
      "name": "数据应用部"
    },
    {
      "id": "dept-security",
      "name": "安全管理部"
    }
  ],
  "roles": [
    {
      "id": "ROLE_ANALYST",
      "code": "ROLE_ANALYST",
      "name": "业务分析员",
      "permissionCodes": [
        "um.user.read"
      ],
      "status": "ACTIVE",
      "privileged": false,
      "conflictsWith": [
        "ROLE_APPROVER"
      ]
    },
    {
      "id": "ROLE_APPROVER",
      "code": "ROLE_APPROVER",
      "name": "业务审批员",
      "permissionCodes": [
        "um.user.read",
        "um.request.approve"
      ],
      "status": "ACTIVE",
      "privileged": false,
      "conflictsWith": [
        "ROLE_ANALYST"
      ]
    },
    {
      "id": "ROLE_ADMIN",
      "code": "ROLE_ADMIN",
      "name": "业务管理员",
      "permissionCodes": [
        "um.user.read",
        "um.user.create",
        "um.user.disable",
        "um.role.assign"
      ],
      "status": "ACTIVE",
      "privileged": true,
      "conflictsWith": []
    },
    {
      "id": "ROLE_RETIRED",
      "code": "ROLE_RETIRED",
      "name": "已停用访客角色",
      "permissionCodes": [],
      "status": "DISABLED",
      "privileged": false,
      "conflictsWith": []
    }
  ],
  "permissions": [
    {
      "id": "um.user.read",
      "code": "um.user.read",
      "name": "查询账号",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.user.create",
      "code": "um.user.create",
      "name": "创建账号",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.user.disable",
      "code": "um.user.disable",
      "name": "停用账号",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.role.read",
      "code": "um.role.read",
      "name": "查询业务角色",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.role.assign",
      "code": "um.role.assign",
      "name": "授予业务角色",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.request.create",
      "code": "um.request.create",
      "name": "提交账号申请",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.request.approve",
      "code": "um.request.approve",
      "name": "部门审批",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.security.approve",
      "code": "um.security.approve",
      "name": "高权限复核",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "um.audit.read",
      "code": "um.audit.read",
      "name": "查看业务审计",
      "systemCode": "user-mgmt",
      "status": "ACTIVE"
    },
    {
      "id": "space.plan",
      "code": "space.plan",
      "name": "执行观测规划",
      "systemCode": "space-ground",
      "status": "ACTIVE"
    },
    {
      "id": "file.upload",
      "code": "file.upload",
      "name": "上传输入文件",
      "systemCode": "space-ground",
      "status": "ACTIVE"
    },
    {
      "id": "file.download",
      "code": "file.download",
      "name": "下载结果文件",
      "systemCode": "space-ground",
      "status": "ACTIVE"
    }
  ],
  "actors": [
    {
      "id": "operator-a",
      "name": "张敏 · A部门操作员",
      "departmentId": "dept-a",
      "roles": [
        "UM_OPERATOR",
        "SPACE_OPERATOR"
      ],
      "permissions": [
        "um.user.read",
        "um.user.create",
        "um.user.disable",
        "um.role.read",
        "um.role.assign",
        "um.request.create",
        "um.audit.read",
        "space.plan",
        "file.upload",
        "file.download"
      ],
      "status": "ACTIVE"
    },
    {
      "id": "manager-a",
      "name": "李主任 · A部门负责人",
      "departmentId": "dept-a",
      "roles": [
        "UM_APPROVER"
      ],
      "permissions": [
        "um.user.read",
        "um.role.read",
        "um.request.approve",
        "um.audit.read",
        "file.download"
      ],
      "status": "ACTIVE"
    },
    {
      "id": "security-a",
      "name": "王安 · 安全复核员",
      "departmentId": "dept-security",
      "roles": [
        "UM_SECURITY"
      ],
      "permissions": [
        "um.user.read",
        "um.role.read",
        "um.security.approve",
        "um.audit.read",
        "file.download"
      ],
      "status": "ACTIVE"
    },
    {
      "id": "reader-a",
      "name": "陈阅 · 只读人员",
      "departmentId": "dept-a",
      "roles": [
        "VIEWER"
      ],
      "permissions": [
        "um.user.read",
        "um.role.read",
        "file.download"
      ],
      "status": "ACTIVE"
    },
    {
      "id": "operator-b",
      "name": "赵明 · B部门操作员",
      "departmentId": "dept-b",
      "roles": [
        "UM_OPERATOR"
      ],
      "permissions": [
        "um.user.read",
        "um.user.create",
        "um.role.read",
        "um.role.assign",
        "um.request.create",
        "file.upload",
        "file.download"
      ],
      "status": "ACTIVE"
    },
    {
      "id": "disabled-a",
      "name": "停用操作账号",
      "departmentId": "dept-a",
      "roles": [
        "UM_OPERATOR"
      ],
      "permissions": [],
      "status": "DISABLED"
    }
  ],
  "accounts": [
    {
      "id": "biz-admin",
      "username": "business.admin",
      "name": "系统管理员",
      "employeeNo": "EMP-0001",
      "departmentId": "dept-a",
      "status": "ACTIVE",
      "roleIds": [
        "ROLE_ADMIN"
      ],
      "revision": 1
    },
    {
      "id": "biz-analyst-a",
      "username": "analyst.a",
      "name": "周晴",
      "employeeNo": "EMP-0101",
      "departmentId": "dept-a",
      "status": "ACTIVE",
      "roleIds": [
        "ROLE_ANALYST"
      ],
      "revision": 2
    },
    {
      "id": "biz-reviewer-a",
      "username": "reviewer.a",
      "name": "吴审",
      "employeeNo": "EMP-0102",
      "departmentId": "dept-a",
      "status": "ACTIVE",
      "roleIds": [
        "ROLE_APPROVER"
      ],
      "revision": 1
    },
    {
      "id": "biz-analyst-b",
      "username": "analyst.b",
      "name": "何远",
      "employeeNo": "EMP-0201",
      "departmentId": "dept-b",
      "status": "ACTIVE",
      "roleIds": [
        "ROLE_ANALYST"
      ],
      "revision": 1
    },
    {
      "id": "biz-disabled",
      "username": "former.user",
      "name": "历史离岗人员",
      "employeeNo": "EMP-0099",
      "departmentId": "dept-a",
      "status": "DISABLED",
      "roleIds": [],
      "revision": 3
    }
  ],
  "releaseRef": {
    "space": "sp-user-governance",
    "spaceCode": "user-governance",
    "releaseId": "rel-user-governance-v1",
    "version": "v1",
    "contentDigest": "a12d718246dd1b05e6a28eba05246c6f28944883d31b900ab2174e6b87136ac0"
  },
  "ontology": {
    "space": {
      "id": "sp-user-governance",
      "code": "user-governance",
      "name": "用户与权限治理本体"
    },
    "classes": [
      {
        "id": "um-BusinessSystem",
        "name": "BusinessSystem",
        "displayName": "业务系统",
        "description": "业务系统的业务定义"
      },
      {
        "id": "um-Account",
        "name": "Account",
        "displayName": "业务账号",
        "description": "业务账号的业务定义"
      },
      {
        "id": "um-Department",
        "name": "Department",
        "displayName": "部门",
        "description": "部门的业务定义"
      },
      {
        "id": "um-Role",
        "name": "Role",
        "displayName": "业务角色",
        "description": "业务角色的业务定义"
      },
      {
        "id": "um-Permission",
        "name": "Permission",
        "displayName": "业务权限",
        "description": "业务权限的业务定义"
      },
      {
        "id": "um-RoleAssignment",
        "name": "RoleAssignment",
        "displayName": "角色授予",
        "description": "角色授予的业务定义"
      },
      {
        "id": "um-ManagementRequest",
        "name": "ManagementRequest",
        "displayName": "账号申请",
        "description": "账号申请的业务定义"
      },
      {
        "id": "um-ApprovalRecord",
        "name": "ApprovalRecord",
        "displayName": "审批记录",
        "description": "审批记录的业务定义"
      },
      {
        "id": "um-AuthorizationSnapshot",
        "name": "AuthorizationSnapshot",
        "displayName": "授权快照",
        "description": "授权快照的业务定义"
      },
      {
        "id": "um-PolicyDecision",
        "name": "PolicyDecision",
        "displayName": "规则判定",
        "description": "规则判定的业务定义"
      }
    ],
    "properties": [
      {
        "id": "um-BusinessSystem-id",
        "classId": "um-BusinessSystem",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-BusinessSystem-name",
        "classId": "um-BusinessSystem",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-BusinessSystem-status",
        "classId": "um-BusinessSystem",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-id",
        "classId": "um-Account",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-Account-name",
        "classId": "um-Account",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-status",
        "classId": "um-Account",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Department-id",
        "classId": "um-Department",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-Department-name",
        "classId": "um-Department",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Department-status",
        "classId": "um-Department",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Role-id",
        "classId": "um-Role",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-Role-name",
        "classId": "um-Role",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Role-status",
        "classId": "um-Role",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Permission-id",
        "classId": "um-Permission",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-Permission-name",
        "classId": "um-Permission",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Permission-status",
        "classId": "um-Permission",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-id",
        "classId": "um-RoleAssignment",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-RoleAssignment-name",
        "classId": "um-RoleAssignment",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-status",
        "classId": "um-RoleAssignment",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-id",
        "classId": "um-ManagementRequest",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-ManagementRequest-name",
        "classId": "um-ManagementRequest",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-status",
        "classId": "um-ManagementRequest",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-id",
        "classId": "um-ApprovalRecord",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-ApprovalRecord-name",
        "classId": "um-ApprovalRecord",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-status",
        "classId": "um-ApprovalRecord",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-id",
        "classId": "um-AuthorizationSnapshot",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-AuthorizationSnapshot-name",
        "classId": "um-AuthorizationSnapshot",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-status",
        "classId": "um-AuthorizationSnapshot",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-id",
        "classId": "um-PolicyDecision",
        "name": "id",
        "displayName": "稳定标识",
        "rangeType": "STRING",
        "required": true
      },
      {
        "id": "um-PolicyDecision-name",
        "classId": "um-PolicyDecision",
        "name": "name",
        "displayName": "名称",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-status",
        "classId": "um-PolicyDecision",
        "name": "status",
        "displayName": "状态",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-BusinessSystem-code",
        "classId": "um-BusinessSystem",
        "name": "code",
        "displayName": "code",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-BusinessSystem-revision",
        "classId": "um-BusinessSystem",
        "name": "revision",
        "displayName": "业务修订",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-Account-username",
        "classId": "um-Account",
        "name": "username",
        "displayName": "账号名",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-employeeNo",
        "classId": "um-Account",
        "name": "employeeNo",
        "displayName": "工号",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-departmentId",
        "classId": "um-Account",
        "name": "departmentId",
        "displayName": "部门",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-roleIds",
        "classId": "um-Account",
        "name": "roleIds",
        "displayName": "角色集合",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Account-revision",
        "classId": "um-Account",
        "name": "revision",
        "displayName": "业务修订",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-Department-parentId",
        "classId": "um-Department",
        "name": "parentId",
        "displayName": "parentId",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Role-permissionCodes",
        "classId": "um-Role",
        "name": "permissionCodes",
        "displayName": "权限集合",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Role-conflictsWith",
        "classId": "um-Role",
        "name": "conflictsWith",
        "displayName": "互斥角色",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Role-privileged",
        "classId": "um-Role",
        "name": "privileged",
        "displayName": "高权限标识",
        "rangeType": "BOOLEAN",
        "required": false
      },
      {
        "id": "um-Permission-systemCode",
        "classId": "um-Permission",
        "name": "systemCode",
        "displayName": "systemCode",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Permission-code",
        "classId": "um-Permission",
        "name": "code",
        "displayName": "code",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Permission-resourceType",
        "classId": "um-Permission",
        "name": "resourceType",
        "displayName": "resourceType",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-Permission-action",
        "classId": "um-Permission",
        "name": "action",
        "displayName": "action",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-accountId",
        "classId": "um-RoleAssignment",
        "name": "accountId",
        "displayName": "accountId",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-roleId",
        "classId": "um-RoleAssignment",
        "name": "roleId",
        "displayName": "roleId",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-validFrom",
        "classId": "um-RoleAssignment",
        "name": "validFrom",
        "displayName": "有效开始",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-validUntil",
        "classId": "um-RoleAssignment",
        "name": "validUntil",
        "displayName": "有效截止",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-RoleAssignment-revision",
        "classId": "um-RoleAssignment",
        "name": "revision",
        "displayName": "业务修订",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-ManagementRequest-actorId",
        "classId": "um-ManagementRequest",
        "name": "actorId",
        "displayName": "业务操作者",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-username",
        "classId": "um-ManagementRequest",
        "name": "username",
        "displayName": "账号名",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-departmentId",
        "classId": "um-ManagementRequest",
        "name": "departmentId",
        "displayName": "部门",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-roleIds",
        "classId": "um-ManagementRequest",
        "name": "roleIds",
        "displayName": "角色集合",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ManagementRequest-validityDays",
        "classId": "um-ManagementRequest",
        "name": "validityDays",
        "displayName": "有效天数",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-ManagementRequest-expectedRevision",
        "classId": "um-ManagementRequest",
        "name": "expectedRevision",
        "displayName": "预期修订",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-requestId",
        "classId": "um-ApprovalRecord",
        "name": "requestId",
        "displayName": "申请编号",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-actorId",
        "classId": "um-ApprovalRecord",
        "name": "actorId",
        "displayName": "业务操作者",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-stage",
        "classId": "um-ApprovalRecord",
        "name": "stage",
        "displayName": "stage",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-ApprovalRecord-decidedAt",
        "classId": "um-ApprovalRecord",
        "name": "decidedAt",
        "displayName": "decidedAt",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-subjectId",
        "classId": "um-AuthorizationSnapshot",
        "name": "subjectId",
        "displayName": "业务主体",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-sourceSystem",
        "classId": "um-AuthorizationSnapshot",
        "name": "sourceSystem",
        "displayName": "来源系统",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-roles",
        "classId": "um-AuthorizationSnapshot",
        "name": "roles",
        "displayName": "roles",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-permissions",
        "classId": "um-AuthorizationSnapshot",
        "name": "permissions",
        "displayName": "permissions",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-revision",
        "classId": "um-AuthorizationSnapshot",
        "name": "revision",
        "displayName": "业务修订",
        "rangeType": "INTEGER",
        "required": false
      },
      {
        "id": "um-AuthorizationSnapshot-observedAt",
        "classId": "um-AuthorizationSnapshot",
        "name": "observedAt",
        "displayName": "获取时点",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-requestId",
        "classId": "um-PolicyDecision",
        "name": "requestId",
        "displayName": "申请编号",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-ruleRef",
        "classId": "um-PolicyDecision",
        "name": "ruleRef",
        "displayName": "规则引用",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-ruleDigest",
        "classId": "um-PolicyDecision",
        "name": "ruleDigest",
        "displayName": "规则摘要",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-conclusion",
        "classId": "um-PolicyDecision",
        "name": "conclusion",
        "displayName": "规则结论",
        "rangeType": "STRING",
        "required": false
      },
      {
        "id": "um-PolicyDecision-evaluatedAt",
        "classId": "um-PolicyDecision",
        "name": "evaluatedAt",
        "displayName": "evaluatedAt",
        "rangeType": "STRING",
        "required": false
      }
    ],
    "relations": [
      {
        "id": "um-rel-belongsTo",
        "name": "belongsTo",
        "displayName": "账号所属部门",
        "domainClassId": "um-Account",
        "rangeClassId": "um-Department"
      },
      {
        "id": "um-rel-assignedRole",
        "name": "assignedRole",
        "displayName": "账号角色",
        "domainClassId": "um-Account",
        "rangeClassId": "um-Role"
      },
      {
        "id": "um-rel-permits",
        "name": "permits",
        "displayName": "角色包含权限",
        "domainClassId": "um-Role",
        "rangeClassId": "um-Permission"
      },
      {
        "id": "um-rel-managedBy",
        "name": "managedBy",
        "displayName": "账号来源系统",
        "domainClassId": "um-Account",
        "rangeClassId": "um-BusinessSystem"
      },
      {
        "id": "um-rel-requestedBy",
        "name": "requestedBy",
        "displayName": "申请人",
        "domainClassId": "um-ManagementRequest",
        "rangeClassId": "um-Account"
      },
      {
        "id": "um-rel-targets",
        "name": "targets",
        "displayName": "目标账号",
        "domainClassId": "um-ManagementRequest",
        "rangeClassId": "um-Account"
      },
      {
        "id": "um-rel-reviewedBy",
        "name": "reviewedBy",
        "displayName": "审批人",
        "domainClassId": "um-ApprovalRecord",
        "rangeClassId": "um-Account"
      },
      {
        "id": "um-rel-approves",
        "name": "approves",
        "displayName": "审批申请",
        "domainClassId": "um-ApprovalRecord",
        "rangeClassId": "um-ManagementRequest"
      },
      {
        "id": "um-rel-snapshotOf",
        "name": "snapshotOf",
        "displayName": "主体权限快照",
        "domainClassId": "um-AuthorizationSnapshot",
        "rangeClassId": "um-Account"
      },
      {
        "id": "um-rel-evaluates",
        "name": "evaluates",
        "displayName": "规则判定对象",
        "domainClassId": "um-PolicyDecision",
        "rangeClassId": "um-ManagementRequest"
      }
    ],
    "rules": [
      {
        "id": "um-rule-active",
        "name": "当前主体有效",
        "description": "主体必须为ACTIVE，权限事实存在且有效",
        "condition": "主体必须为ACTIVE，权限事实存在且有效",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-scope",
        "name": "部门数据范围",
        "description": "申请与目标必须位于被授权部门",
        "condition": "申请与目标必须位于被授权部门",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-permission",
        "name": "业务操作权限",
        "description": "执行账号创建/角色授予前重新获取业务源权限",
        "condition": "执行账号创建/角色授予前重新获取业务源权限",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-role-active",
        "name": "角色状态有效",
        "description": "只允许授予ACTIVE角色",
        "condition": "只允许授予ACTIVE角色",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-conflict",
        "name": "角色互斥",
        "description": "ROLE_ANALYST与ROLE_APPROVER不能同时授予",
        "condition": "ROLE_ANALYST与ROLE_APPROVER不能同时授予",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-no-self-approval",
        "name": "申请审批职责分离",
        "description": "申请者不能审批自己的请求",
        "condition": "申请者不能审批自己的请求",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-security",
        "name": "高权限追加复核",
        "description": "ROLE_ADMIN必须经过独立安全岗位复核",
        "condition": "ROLE_ADMIN必须经过独立安全岗位复核",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-last-admin",
        "name": "最后管理员保护",
        "description": "不得停用最后一个ACTIVE管理员",
        "condition": "不得停用最后一个ACTIVE管理员",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-revision",
        "name": "目标修订一致",
        "description": "审批后的目标与权限变化必须重新核验",
        "condition": "审批后的目标与权限变化必须重新核验",
        "status": "ACTIVE"
      },
      {
        "id": "um-rule-readback",
        "name": "业务结果读回",
        "description": "账号状态与授权角色必须和已批准计划一致",
        "condition": "账号状态与授权角色必须和已批准计划一致",
        "status": "ACTIVE"
      }
    ],
    "operations": [
      {
        "code": "user-governance-facts",
        "name": "账号与权限事实查询",
        "kind": "OBJECT_QUERY"
      },
      {
        "code": "user-governance-check",
        "name": "账号变更规则判定",
        "kind": "RULE_CHECK"
      },
      {
        "code": "user-governance-readback",
        "name": "账号授权读回",
        "kind": "OBJECT_QUERY"
      },
      {
        "code": "user-governance-relations",
        "name": "账号角色部门关系",
        "kind": "RELATION_QUERY"
      }
    ],
    "instances": [
      {
        "className": "BusinessSystem",
        "stableId": "user-mgmt",
        "values": {
          "id": "user-mgmt",
          "name": "业务用户管理系统",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Department",
        "stableId": "dept-a",
        "values": {
          "id": "dept-a",
          "name": "遥感业务部"
        }
      },
      {
        "className": "Department",
        "stableId": "dept-b",
        "values": {
          "id": "dept-b",
          "name": "数据应用部"
        }
      },
      {
        "className": "Department",
        "stableId": "dept-security",
        "values": {
          "id": "dept-security",
          "name": "安全管理部"
        }
      },
      {
        "className": "Account",
        "stableId": "biz-admin",
        "values": {
          "id": "biz-admin",
          "username": "business.admin",
          "name": "系统管理员",
          "employeeNo": "EMP-0001",
          "departmentId": "dept-a",
          "status": "ACTIVE",
          "roleIds": [
            "ROLE_ADMIN"
          ],
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "biz-analyst-a",
        "values": {
          "id": "biz-analyst-a",
          "username": "analyst.a",
          "name": "周晴",
          "employeeNo": "EMP-0101",
          "departmentId": "dept-a",
          "status": "ACTIVE",
          "roleIds": [
            "ROLE_ANALYST"
          ],
          "revision": 2
        }
      },
      {
        "className": "Account",
        "stableId": "biz-reviewer-a",
        "values": {
          "id": "biz-reviewer-a",
          "username": "reviewer.a",
          "name": "吴审",
          "employeeNo": "EMP-0102",
          "departmentId": "dept-a",
          "status": "ACTIVE",
          "roleIds": [
            "ROLE_APPROVER"
          ],
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "biz-analyst-b",
        "values": {
          "id": "biz-analyst-b",
          "username": "analyst.b",
          "name": "何远",
          "employeeNo": "EMP-0201",
          "departmentId": "dept-b",
          "status": "ACTIVE",
          "roleIds": [
            "ROLE_ANALYST"
          ],
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "biz-disabled",
        "values": {
          "id": "biz-disabled",
          "username": "former.user",
          "name": "历史离岗人员",
          "employeeNo": "EMP-0099",
          "departmentId": "dept-a",
          "status": "DISABLED",
          "roleIds": [],
          "revision": 3
        }
      },
      {
        "className": "Account",
        "stableId": "operator-a",
        "values": {
          "id": "operator-a",
          "name": "张敏 · A部门操作员",
          "departmentId": "dept-a",
          "roles": [
            "UM_OPERATOR",
            "SPACE_OPERATOR"
          ],
          "permissions": [
            "um.user.read",
            "um.user.create",
            "um.user.disable",
            "um.role.read",
            "um.role.assign",
            "um.request.create",
            "um.audit.read",
            "space.plan",
            "file.upload",
            "file.download"
          ],
          "status": "ACTIVE",
          "username": "operator-a",
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "manager-a",
        "values": {
          "id": "manager-a",
          "name": "李主任 · A部门负责人",
          "departmentId": "dept-a",
          "roles": [
            "UM_APPROVER"
          ],
          "permissions": [
            "um.user.read",
            "um.role.read",
            "um.request.approve",
            "um.audit.read",
            "file.download"
          ],
          "status": "ACTIVE",
          "username": "manager-a",
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "security-a",
        "values": {
          "id": "security-a",
          "name": "王安 · 安全复核员",
          "departmentId": "dept-security",
          "roles": [
            "UM_SECURITY"
          ],
          "permissions": [
            "um.user.read",
            "um.role.read",
            "um.security.approve",
            "um.audit.read",
            "file.download"
          ],
          "status": "ACTIVE",
          "username": "security-a",
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "reader-a",
        "values": {
          "id": "reader-a",
          "name": "陈阅 · 只读人员",
          "departmentId": "dept-a",
          "roles": [
            "VIEWER"
          ],
          "permissions": [
            "um.user.read",
            "um.role.read",
            "file.download"
          ],
          "status": "ACTIVE",
          "username": "reader-a",
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "operator-b",
        "values": {
          "id": "operator-b",
          "name": "赵明 · B部门操作员",
          "departmentId": "dept-b",
          "roles": [
            "UM_OPERATOR"
          ],
          "permissions": [
            "um.user.read",
            "um.user.create",
            "um.role.read",
            "um.role.assign",
            "um.request.create",
            "file.upload",
            "file.download"
          ],
          "status": "ACTIVE",
          "username": "operator-b",
          "revision": 1
        }
      },
      {
        "className": "Account",
        "stableId": "disabled-a",
        "values": {
          "id": "disabled-a",
          "name": "停用操作账号",
          "departmentId": "dept-a",
          "roles": [
            "UM_OPERATOR"
          ],
          "permissions": [],
          "status": "DISABLED",
          "username": "disabled-a",
          "revision": 1
        }
      },
      {
        "className": "Role",
        "stableId": "ROLE_ANALYST",
        "values": {
          "id": "ROLE_ANALYST",
          "code": "ROLE_ANALYST",
          "name": "业务分析员",
          "permissionCodes": [
            "um.user.read"
          ],
          "status": "ACTIVE",
          "privileged": false,
          "conflictsWith": [
            "ROLE_APPROVER"
          ]
        }
      },
      {
        "className": "Role",
        "stableId": "ROLE_APPROVER",
        "values": {
          "id": "ROLE_APPROVER",
          "code": "ROLE_APPROVER",
          "name": "业务审批员",
          "permissionCodes": [
            "um.user.read",
            "um.request.approve"
          ],
          "status": "ACTIVE",
          "privileged": false,
          "conflictsWith": [
            "ROLE_ANALYST"
          ]
        }
      },
      {
        "className": "Role",
        "stableId": "ROLE_ADMIN",
        "values": {
          "id": "ROLE_ADMIN",
          "code": "ROLE_ADMIN",
          "name": "业务管理员",
          "permissionCodes": [
            "um.user.read",
            "um.user.create",
            "um.user.disable",
            "um.role.assign"
          ],
          "status": "ACTIVE",
          "privileged": true,
          "conflictsWith": []
        }
      },
      {
        "className": "Role",
        "stableId": "ROLE_RETIRED",
        "values": {
          "id": "ROLE_RETIRED",
          "code": "ROLE_RETIRED",
          "name": "已停用访客角色",
          "permissionCodes": [],
          "status": "DISABLED",
          "privileged": false,
          "conflictsWith": []
        }
      },
      {
        "className": "Permission",
        "stableId": "um.user.read",
        "values": {
          "id": "um.user.read",
          "code": "um.user.read",
          "name": "查询账号",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.user.create",
        "values": {
          "id": "um.user.create",
          "code": "um.user.create",
          "name": "创建账号",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.user.disable",
        "values": {
          "id": "um.user.disable",
          "code": "um.user.disable",
          "name": "停用账号",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.role.read",
        "values": {
          "id": "um.role.read",
          "code": "um.role.read",
          "name": "查询业务角色",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.role.assign",
        "values": {
          "id": "um.role.assign",
          "code": "um.role.assign",
          "name": "授予业务角色",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.request.create",
        "values": {
          "id": "um.request.create",
          "code": "um.request.create",
          "name": "提交账号申请",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.request.approve",
        "values": {
          "id": "um.request.approve",
          "code": "um.request.approve",
          "name": "部门审批",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.security.approve",
        "values": {
          "id": "um.security.approve",
          "code": "um.security.approve",
          "name": "高权限复核",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "Permission",
        "stableId": "um.audit.read",
        "values": {
          "id": "um.audit.read",
          "code": "um.audit.read",
          "name": "查看业务审计",
          "systemCode": "user-mgmt",
          "status": "ACTIVE"
        }
      },
      {
        "className": "RoleAssignment",
        "stableId": "biz-admin-ROLE_ADMIN",
        "values": {
          "id": "biz-admin-ROLE_ADMIN",
          "accountId": "biz-admin",
          "roleId": "ROLE_ADMIN",
          "status": "ACTIVE",
          "revision": 1
        }
      },
      {
        "className": "RoleAssignment",
        "stableId": "biz-analyst-a-ROLE_ANALYST",
        "values": {
          "id": "biz-analyst-a-ROLE_ANALYST",
          "accountId": "biz-analyst-a",
          "roleId": "ROLE_ANALYST",
          "status": "ACTIVE",
          "revision": 1
        }
      },
      {
        "className": "RoleAssignment",
        "stableId": "biz-reviewer-a-ROLE_APPROVER",
        "values": {
          "id": "biz-reviewer-a-ROLE_APPROVER",
          "accountId": "biz-reviewer-a",
          "roleId": "ROLE_APPROVER",
          "status": "ACTIVE",
          "revision": 1
        }
      },
      {
        "className": "RoleAssignment",
        "stableId": "biz-analyst-b-ROLE_ANALYST",
        "values": {
          "id": "biz-analyst-b-ROLE_ANALYST",
          "accountId": "biz-analyst-b",
          "roleId": "ROLE_ANALYST",
          "status": "ACTIVE",
          "revision": 1
        }
      },
      {
        "className": "ManagementRequest",
        "stableId": "UM-HISTORY-0001",
        "values": {
          "id": "UM-HISTORY-0001",
          "name": "周晴业务分析角色授予",
          "actorId": "operator-a",
          "username": "analyst.a",
          "departmentId": "dept-a",
          "roleIds": [
            "ROLE_ANALYST"
          ],
          "status": "SUCCEEDED",
          "validityDays": 30,
          "expectedRevision": 1
        }
      },
      {
        "className": "ApprovalRecord",
        "stableId": "um-history-approval-1",
        "values": {
          "id": "um-history-approval-1",
          "name": "部门批准",
          "requestId": "UM-HISTORY-0001",
          "actorId": "manager-a",
          "stage": "department",
          "status": "APPROVED",
          "decidedAt": "2026-11-04T10:00:00+08:00"
        }
      },
      {
        "className": "AuthorizationSnapshot",
        "stableId": "um-history-auth-1",
        "values": {
          "id": "um-history-auth-1",
          "name": "A部门操作员授权快照",
          "subjectId": "operator-a",
          "sourceSystem": "user-mgmt",
          "roles": [
            "UM_OPERATOR",
            "SPACE_OPERATOR"
          ],
          "permissions": [
            "um.user.read",
            "um.user.create",
            "um.user.disable",
            "um.role.read",
            "um.role.assign",
            "um.request.create",
            "um.audit.read",
            "space.plan",
            "file.upload",
            "file.download"
          ],
          "revision": 1,
          "observedAt": "2026-11-04T09:59:00+08:00",
          "status": "ACTIVE"
        }
      },
      {
        "className": "PolicyDecision",
        "stableId": "um-history-decision-1",
        "values": {
          "id": "um-history-decision-1",
          "name": "历史部门范围判定",
          "requestId": "UM-HISTORY-0001",
          "ruleRef": "um-rule-scope",
          "conclusion": "MATCHED",
          "evaluatedAt": "2026-11-04T09:59:30+08:00",
          "status": "COMPLETED"
        }
      }
    ],
    "knowledge": [
      {
        "id": "um-knowledge-1",
        "name": "账号开通规范",
        "content": "普通账号开通须经部门审批；高权限增加安全复核。"
      },
      {
        "id": "um-knowledge-2",
        "name": "角色职责分离",
        "content": "申请人与审批人分离，业务分析与业务审批角色互斥。"
      }
    ]
  }
}
`,Le=n=>n===void 0?n:JSON.parse(JSON.stringify(n)),pe=()=>new Date().toISOString(),mn=n=>Number.isSafeInteger(Number(n))&&Number(n)>0,us=new Set(["satellite-planning-assistant","user-governance-assistant","satellite-assistant","payload-advisor","anomaly-triage","space-visibility-agent"]),Pe=(n,t,e)=>{var s;return(s=n==null?void 0:n.revisions)==null?void 0:s.find(a=>Number(a[t])===Number(e))},ut=(n,t,e,s)=>{var a;return(a=t.versions[e])==null?void 0:a.find(r=>Number(r.versionNo)===Number(s))},yn=n=>({createdBy:n.createdBy??"u-demo",createdAt:n.createdAt??pe()}),Bt=n=>({releaseId:n.releaseId,appCode:n.appCode,appVersion:n.appVersion,candidateSha256:n.candidateSha256,experienceRev:n.experienceRev,integrationProfileId:n.integrationProfileId,integrationProfileRev:n.integrationProfileRev,connectionProfileRefs:n.connectionProfileRefs,activationScope:n.activationScope,skillRefs:n.skillRefs,semanticBindings:n.semanticBindings});function Xe(n,t,e,s){var o,u,c,I,y;const a=t==="integration-profiles"?"profileRev":"currentRev",r=t==="environment-bindings"?"bindingRev":"profileRev";n[a]??(n[a]=Number(n.revision??n.profileVersion??((u=(o=n.revisions)==null?void 0:o.at(-1))==null?void 0:u[r])??1)),n.status=n.status==="ACTIVE"?"APPROVED":n.status??"DRAFT",Object.assign(n,yn(n)),n.updatedAt??(n.updatedAt=n.createdAt);const i=e.demoConnections.find(S=>S.profileId===n.connectionProfileId),d=t==="connection-profiles"?{protocol:n.protocol??"HTTP_JSON",endpointPolicy:n.endpointPolicy??{baseUrl:n.baseUrl??"http://127.0.0.1:19180"},authScheme:n.authScheme??{kind:n.authType??"NONE"},identityPolicy:n.identityPolicy??{},allowedUses:n.allowedUses??["CONFIG_RELEASE"],operationAllowlist:n.operationAllowlist??[],inputContractRefs:n.inputContractRefs??[],outputContractRefs:n.outputContractRefs??[]}:t==="environment-bindings"?{environment:n.environment??"production",connectionProfileId:n.connectionProfileId,connectionProfileRev:n.connectionProfileRev??(i==null?void 0:i.currentRev)??1,baseEndpoint:n.baseEndpoint??((y=(I=(c=i==null?void 0:i.revisions)==null?void 0:c.at(-1))==null?void 0:I.endpointPolicy)==null?void 0:y.baseUrl)??"http://127.0.0.1:19180",secretRef:n.secretRef??"",runtimeRef:n.runtimeRef??{},originPolicy:n.originPolicy??[],healthPolicy:n.healthPolicy??{}}:{bizSystemCode:n.bizSystemCode??(n.appCode==="user-governance-assistant"?"USER_CENTER":"SPACE"),environment:n.environment??"production",mode:n.mode??"L2",allowedOrigins:n.allowedOrigins??["http://127.0.0.1:25175","http://localhost:25175"],contextSchema:n.contextSchema??{},credentialPolicyRef:n.credentialPolicyRef??"policy:frontend-demo",experienceRef:n.experienceRef??{appCode:n.appCode,experienceRev:1}};(!Array.isArray(n.revisions)||!n.revisions.length)&&(n.revisions=[{...d,[r]:n[a],...yn(n)}]);for(const S of n.revisions){for(const[A,T]of Object.entries(d))S[A]??(S[A]=Le(T));S[r]??(S[r]=n[a]),Object.assign(S,yn(S)),S.declarationSha256??(S.declarationSha256=s(S)),t==="environment-bindings"&&(S.hasSecret=!!S.secretRef)}}function cs(n,t){var s,a,r,i;const e={experienceRev:1,title:n.name,welcomeText:((s=n.config)==null?void 0:s.welcome)??n.description??"",capabilityDescriptions:((r=(a=n.config)==null?void 0:a.skillRefs)==null?void 0:r.map(d=>({code:d.code,version:d.version})))??[],promptRefs:Le(((i=n.config)==null?void 0:i.promptRefs)??[]),helpRefs:[],inputPresentation:{type:"CHAT"},resultPresentation:{format:"MARKDOWN"},createdBy:"u-demo",createdAt:pe()};return{appCode:n.code,experienceRev:1,status:"APPROVED",createdBy:"u-demo",createdAt:e.createdAt,updatedAt:e.createdAt,revisions:[{...e,declarationSha256:t(e)}]}}function ps(n,t,e){var r,i,d,o,u,c,I,y,S,A,T,v;if(Number(t.configurationSchemaVersion??0)>=1)return;const s=n.demoReleases,a=n.demoIntegrationProfiles;for(const l of n.demoConnections)Xe(l,"connection-profiles",n,e);for(const l of a)l.appCode??(l.appCode=((r=s.find(g=>g.integrationProfileId===l.profileId))==null?void 0:r.appCode)??(l.profileId==="ip-sat-1"?"satellite-assistant":void 0)),Xe(l,"integration-profiles",n,e);for(const l of n.demoBindings)Xe(l,"environment-bindings",n,e);for(const l of n.apps.filter(g=>mn(g.currentVersionNo))){const g=(i=t.experiences)[d=l.code]??(i[d]=cs(l,e));if(g.status==="ACTIVE"&&(g.status="APPROVED"),!us.has(l.code))continue;const f=s.filter(N=>N.appCode===l.code);let p=n.demoConnections.find(N=>{var D,$,U;return N.profileId===((U=($=(D=f[0])==null?void 0:D.connectionProfileRefs)==null?void 0:$[0])==null?void 0:U.profileId)});p||(p=n.demoConnections.find(N=>N.profileId===`cp-${l.code}`)),p||(p={profileId:`cp-${l.code}`,currentRev:1,status:"APPROVED",operationAllowlist:((u=(o=l.config)==null?void 0:o.skillRefs)==null?void 0:u.map(N=>N.code))??[]},Xe(p,"connection-profiles",n,e),n.demoConnections.push(p));const m=Pe(p,"profileRev",p.currentRev);p.profileId==="cp-sat"&&!m.operationAllowlist.length&&(m.operationAllowlist=((I=(c=l.config)==null?void 0:c.skillRefs)==null?void 0:I.map(N=>N.code))??[],m.declarationSha256=e({...m,declarationSha256:void 0}));let E=n.demoBindings.find(N=>{var D;return(D=N.revisions)==null?void 0:D.some($=>$.connectionProfileId===p.profileId&&$.connectionProfileRev===p.currentRev&&$.environment==="production")});E||(E={bindingId:`eb-${l.code}`,currentRev:1,status:"APPROVED",connectionProfileId:p.profileId,connectionProfileRev:p.currentRev,environment:"production"},Xe(E,"environment-bindings",n,e),n.demoBindings.push(E));let R=a.find(N=>{var D;return N.appCode===l.code&&((D=N.revisions)==null?void 0:D.some($=>$.profileRev===N.profileRev&&$.environment==="production"))});R||(R={profileId:`ip-${l.code}`,appCode:l.code,profileRev:1,status:"APPROVED",experienceRef:{appCode:l.code,experienceRev:g.experienceRev}},Xe(R,"integration-profiles",n,e),a.push(R));const h=[{profileId:p.profileId,profileRev:p.currentRev,bindingId:E.bindingId,bindingRev:E.currentRev,environment:"production",operation:m.operationAllowlist[0]??""}],L={appCode:l.code,appVersion:Number(l.currentVersionNo),experienceRev:g.experienceRev,integrationProfileId:R.profileId,integrationProfileRev:R.profileRev,connectionProfileRefs:h,activationScope:"production"};for(const N of["ACTIVE","APPROVED","DRAFT"]){if(f.some($=>$.status===N)||N==="ACTIVE"&&f.length)continue;const D={...Le(L),releaseId:`rel-demo-${l.code}-${N.toLowerCase()}`,status:N,createdBy:"u-demo",createdAt:pe(),updatedAt:pe()};s.some($=>$.releaseId===D.releaseId)||s.push(D)}}for(const l of s){const g=n.apps.find(p=>p.code===l.appCode),f=ut(n,t,l.appCode,l.appVersion);l.experienceRev??(l.experienceRev=1),l.activationScope??(l.activationScope=l.environment??"production"),l.connectionProfileRefs??(l.connectionProfileRefs=[]),l.semanticBindings??(l.semanticBindings=[]),l.skillRefs??(l.skillRefs=Le(((S=(y=f==null?void 0:f.snapshot)==null?void 0:y.config)==null?void 0:S.skillRefs)??((A=g==null?void 0:g.config)==null?void 0:A.skillRefs)??[]));for(const p of l.connectionProfileRefs){const m=n.demoBindings.find(R=>{var h;return(h=R.revisions)==null?void 0:h.some(L=>L.connectionProfileId===p.profileId&&L.connectionProfileRev===p.profileRev&&L.environment===l.activationScope)});m&&!p.bindingId&&Object.assign(p,{bindingId:m.bindingId,bindingRev:m.currentRev,environment:l.activationScope});const E=n.demoConnections.find(R=>R.profileId===p.profileId);p.operation??(p.operation=((v=(T=Pe(E,"profileRev",p.profileRev))==null?void 0:T.operationAllowlist)==null?void 0:v[0])??""),p.resolutionDigest??(p.resolutionDigest=e(p))}/^[0-9a-f]{64}$/.test(l.candidateSha256??"")||(l.candidateSha256=(f==null?void 0:f.snapshotDigest)??e((g==null?void 0:g.config)??{})),/^[0-9a-f]{64}$/.test(l.digest??"")||(l.digest=e(Bt(l))),Object.assign(l,yn(l)),l.updatedAt??(l.updatedAt=l.createdAt),["APPROVED","ACTIVE","SUPERSEDED"].includes(l.status)&&(l.validation??(l.validation={passed:!0,errors:[],checks:[],candidateSha256:l.digest}))}t.configurationSchemaVersion=1,n.persist()}function ms(n,t,e){var d,o,u;const s=[];ut(t,e,n.appCode,n.appVersion)||s.push("应用发布版本不存在");const a=e.experiences[n.appCode];(!a||a.status!=="APPROVED"||!Pe(a,"experienceRev",n.experienceRev))&&s.push("体验修订不存在或未批准");const r=t.demoIntegrationProfiles.find(c=>c.profileId===n.integrationProfileId),i=Pe(r,"profileRev",n.integrationProfileRev);!r||r.appCode!==n.appCode||r.status!=="APPROVED"||!i?s.push("接入档案修订不存在或未批准"):(i.environment!==n.activationScope&&s.push("接入档案环境与激活环境不一致"),(((d=i.experienceRef)==null?void 0:d.appCode)!==n.appCode||((o=i.experienceRef)==null?void 0:o.experienceRev)!==n.experienceRev)&&s.push("接入档案的体验引用不一致"));for(const c of n.connectionProfileRefs??[]){const I=t.demoConnections.find(T=>T.profileId===c.profileId),y=Pe(I,"profileRev",c.profileRev);if(!I||I.status!=="APPROVED"||!y){s.push(`连接档案 ${c.profileId} 不存在或未批准`);continue}const S=t.demoBindings.find(T=>T.bindingId===c.bindingId),A=Pe(S,"bindingRev",c.bindingRev);(!S||S.status!=="APPROVED"||!A||A.environment!==n.activationScope||A.connectionProfileId!==c.profileId||A.connectionProfileRev!==c.profileRev)&&s.push(`连接 ${c.profileId} 的环境绑定不存在、不匹配或未批准`),(u=y.operationAllowlist)!=null&&u.length&&!y.operationAllowlist.includes(c.operation)&&s.push(`连接 ${c.profileId} 的操作不在批准白名单中`)}return s}function fs(n,t,e,s,a,r,i){var I,y,S;const d=A=>new Response(JSON.stringify({code:"OK",message:"",data:A}),{headers:{"Content-Type":"application/json"}}),o=(A,T)=>new Response(JSON.stringify({code:A===404?"CONFIG_REF_NOT_FOUND":A===405?"METHOD_NOT_ALLOWED":A===409?"CONFLICT":"INVALID_ARGUMENT",message:T,data:null}),{status:A,headers:{"Content-Type":"application/json"}}),u=(A,T,v)=>(a.audit(T,"CONFIGURATION",v,T),a.persist(),d(A));let c;if(c=n.match(/^\/admin\/(connection-profiles|environment-bindings|integration-profiles)(?:\/([^/]+))?(?:\/(versions|approve|disable)(?:\/(\d+)\/grants)?)?$/)){const A=c[1],T=A==="connection-profiles"?a.demoConnections:A==="environment-bindings"?a.demoBindings:a.demoIntegrationProfiles,v=A==="environment-bindings"?"bindingId":"profileId",l=A==="integration-profiles"?"profileRev":"currentRev",g=A==="environment-bindings"?"bindingRev":"profileRev",f=c[2]?T.find(E=>E[v]===c[2]):void 0;if(c[2]&&!f)return o(404,"档案不存在");if(t==="GET")return d(c[4]?{grants:[],items:[],revision:Number(c[4])}:f??T.filter(E=>!s.get("appCode")||E.appCode===s.get("appCode")));if(t!=="POST")return o(405,"仅支持GET/POST");if(c[4])return o(405,"授权目录只读");if(f&&(e==null?void 0:e.expectedRev)!==void 0&&Number(e.expectedRev)!==Number(f[l]))return o(409,"档案修订已变化，请刷新");if(c[3]==="approve"||c[3]==="disable"){if(c[3]==="approve"&&A==="integration-profiles"){const E=(I=Pe(f,g,f[l]))==null?void 0:I.experienceRef,R=r.experiences[f.appCode];if(!E||E.appCode!==f.appCode||(R==null?void 0:R.status)!=="APPROVED"||!Pe(R,"experienceRev",E.experienceRev))return o(409,"请先批准引用的应用体验")}if(c[3]==="approve"&&A==="environment-bindings"){const E=Pe(f,g,f[l]),R=a.demoConnections.find(h=>h.profileId===(E==null?void 0:E.connectionProfileId));if((R==null?void 0:R.status)!=="APPROVED"||!Pe(R,"profileRev",E==null?void 0:E.connectionProfileRev))return o(409,"请先批准引用的连接档案")}return f.status=c[3]==="approve"?"APPROVED":"DISABLED",f.updatedAt=pe(),u(f,`CONFIG_PROFILE_${c[3].toUpperCase()}`,f[v])}if(!f&&(!(e!=null&&e[v])||T.some(E=>E[v]===e[v])))return o(409,"档案编码为空或已存在");if(A==="integration-profiles"&&!f&&!a.apps.some(E=>E.code===(e==null?void 0:e.appCode)))return o(400,"应用不存在");if(A==="connection-profiles"&&!(e!=null&&e.protocol))return o(400,"连接协议必填");if(A==="environment-bindings"&&(!(e!=null&&e.connectionProfileId)||!mn(e==null?void 0:e.connectionProfileRev)||!(e!=null&&e.baseEndpoint)))return o(400,"连接档案、修订和物理端点必填");const p=f??{[v]:e[v],[l]:0,...A==="integration-profiles"?{appCode:e.appCode}:{},revisions:[],createdBy:"u-demo",createdAt:pe()},m=Le(e??{});for(const E of[v,"expectedRev","requestId","appCode"])delete m[E];return p[l]++,p.status="DRAFT",p.updatedAt=pe(),p.revisions.push({...m,[g]:p[l],declarationSha256:i(m),createdBy:"u-demo",createdAt:pe(),...A==="environment-bindings"?{hasSecret:!!m.secretRef}:{}}),f||T.unshift(p),u(p,"CONFIG_PROFILE_REVISION",p[v])}if(c=n.match(/^\/admin\/apps\/([^/]+)\/experiences(?:\/(versions|approve|disable))?$/)){const A=a.apps.find(v=>v.code===c[1]);if(!A)return o(404,"应用不存在");const T=r.experiences[c[1]];if(t==="GET")return T?d(T):o(404,"应用体验尚未创建");if(t!=="POST")return o(405,"仅支持GET/POST");if(!T&&c[2])return o(404,"应用体验尚未创建");if(T&&!c[2])return o(409,"体验已存在，请创建新修订");if((e==null?void 0:e.expectedRev)!==void 0&&Number(e.expectedRev)!==Number(T==null?void 0:T.experienceRev))return o(409,"体验修订已变化");if(c[2]==="approve"||c[2]==="disable")T.status=c[2]==="approve"?"APPROVED":"DISABLED";else{if(typeof(e==null?void 0:e.title)!="string"||!e.title.trim())return o(400,"体验标题必填");const v=T??{appCode:A.code,experienceRev:0,createdBy:"u-demo",createdAt:pe(),revisions:[]},l=Le(e??{});delete l.expectedRev,delete l.requestId,v.experienceRev++,v.status="DRAFT",v.revisions.push({...l,experienceRev:v.experienceRev,declarationSha256:i(l),createdBy:"u-demo",createdAt:pe()}),r.experiences[A.code]=v}return r.experiences[A.code].updatedAt=pe(),u(r.experiences[A.code],"APP_EXPERIENCE_UPDATE",A.code)}if(c=n.match(/^\/admin\/config-releases(?:\/([^/]+))?(?:\/(validate|approve|activate|disable))?$/)){const A=a.demoReleases,T=c[1]?A.find(l=>l.releaseId===c[1]):void 0;if(c[1]&&!T)return o(404,"配置发布不存在");if(t==="GET")return d(T??A.filter(l=>!s.get("appCode")||l.appCode===s.get("appCode")));if(t!=="POST")return o(405,"仅支持GET/POST");if(!c[1]){const l=a.apps.find(p=>p.code===(e==null?void 0:e.appCode)),g=ut(a,r,e==null?void 0:e.appCode,e==null?void 0:e.appVersion);if(!l||!g)return o(409,"请先发布引用的应用版本");if(!mn(e==null?void 0:e.experienceRev)||!(e!=null&&e.integrationProfileId)||!mn(e==null?void 0:e.integrationProfileRev)||!/^[a-z][a-z0-9-]{0,31}$/.test((e==null?void 0:e.activationScope)??"")||!Array.isArray(e==null?void 0:e.connectionProfileRefs)||e.connectionProfileRefs.some(p=>!p||typeof p.profileId!="string"||!p.profileId||!mn(p.profileRev)))return o(400,"配置发布引用或环境格式不正确");const f={...Le(e),releaseId:(e==null?void 0:e.releaseId)??a.nextId("release"),status:"DRAFT",candidateSha256:g.snapshotDigest,createdBy:"u-demo",createdAt:pe(),updatedAt:pe(),skillRefs:Le(((y=g.snapshot.config)==null?void 0:y.skillRefs)??[]),semanticBindings:Le((e==null?void 0:e.semanticBindings)??[])};delete f.validation,delete f.requestId;for(const p of f.connectionProfileRefs)p.resolutionDigest=i(p);return f.digest=i(Bt(f)),A.some(p=>p.releaseId===f.releaseId)?o(409,"发布编码已存在"):(A.unshift(f),u(f,"CONFIG_RELEASE_CREATE",f.releaseId))}if(!c[2])return o(405,"请选择发布操作");if(T.status==="DISABLED"&&c[2]!=="disable")return o(409,"已停用的配置发布不能重新启用，请复制为新草稿");const v=ms(T,a,r);if(c[2]==="validate")return T.validation={passed:!v.length,errors:v,candidateSha256:T.digest,checks:v.length?v.map(l=>({result:"ERROR",detail:l})):[{result:"PASS",detail:"应用版本、体验、接入档案和环境连接引用一致"}]},!v.length&&!["ACTIVE","APPROVED"].includes(T.status)&&(T.status="VALIDATED"),T.updatedAt=pe(),u({...T,...T.validation},"CONFIG_RELEASE_VALIDATE",T.releaseId);if(c[2]==="approve"){if(v.length||!((S=T.validation)!=null&&S.passed)||T.validation.candidateSha256!==T.digest||!["VALIDATED","APPROVED","ACTIVE"].includes(T.status))return o(409,v.join("；")||"请先通过当前配置发布校验");T.status!=="ACTIVE"&&(T.status="APPROVED"),T.approvedBy="u-demo",T.approvedAt=pe()}if(c[2]==="activate"){if(v.length||!["APPROVED","ACTIVE"].includes(T.status))return o(409,v.join("；")||"配置发布尚未批准");const l=A.find(g=>g.appCode===T.appCode&&g.activationScope===T.activationScope&&g.status==="ACTIVE");if((e==null?void 0:e.expectedActiveReleaseId)!==void 0&&e.expectedActiveReleaseId!==((l==null?void 0:l.releaseId)??null))return o(409,"当前激活版本已变化，请刷新");for(const g of A)g!==T&&g.appCode===T.appCode&&g.activationScope===T.activationScope&&g.status==="ACTIVE"&&(g.status="SUPERSEDED",g.updatedAt=pe());T.status="ACTIVE",T.activatedAt=pe()}return c[2]==="disable"&&(T.status="DISABLED"),T.updatedAt=pe(),u(T,`CONFIG_RELEASE_${c[2].toUpperCase()}`,T.releaseId)}}const oe=n=>JSON.parse(JSON.stringify(n)),Ae=()=>new Date().toISOString(),H={versions:{},skillVersions:{},experiences:{},sessions:[],alerts:[],prechecks:{},datasets:{},snapshots:{}};function gs(n){n&&typeof n=="object"&&Object.assign(H,n)}function Te(n){const t=JSON.stringify(n)??"null";let e=2166136261;for(let s=0;s<t.length;s++)e=Math.imul(e^t.charCodeAt(s),16777619);return(e>>>0).toString(16).padStart(8,"0").repeat(8)}const qt=JSON.parse(lt).classifications;function et(n){return qt.some(t=>t.code===n)?n:/用户|治理/.test(n)?"USER_GOVERNANCE":/数据|处理/.test(n)?"DATA_PROCESSING":n?"SPACE_OBSERVATION":"GENERAL"}function Un(n){var t;for(const e of n.apps)e.bizCategory=et(e.bizCategory),e.code==="user-governance-assistant"&&((t=e.config).responseStyle??(t.responseStyle="detailed")),!H.versions[e.code]&&e.currentVersionNo&&(H.versions[e.code]=Array.from({length:Number(e.currentVersionNo)},(s,a)=>{const r=Number(e.currentVersionNo)-a,i=oe(e);return i.config={...i.config,responseStyle:a?"compact":"detailed"},a&&(i.description=`${i.description}（演示历史 v${r}）`),{versionNo:r,status:a?"SUPERSEDED":"PUBLISHED",snapshot:i,snapshotDigest:Te(i),publishedAt:e.updatedAt,publishedBy:"u-demo",note:a?"演示历史版本：简洁回答":"当前演示版本：详细回答"}}));for(const e of n.skills)e.bizCategories??(e.bizCategories=[e.code.startsWith("user-")?"USER_GOVERNANCE":"SPACE_OBSERVATION"]),e.bizCategories=e.bizCategories.map(et),e.businessSystemCode??(e.businessSystemCode=e.bizCategories.includes("USER_GOVERNANCE")?"USER_CENTER":"SPACE"),e.accessPolicy??(e.accessPolicy={visibility:"PUBLIC",viewRoles:["ADMIN","EDITOR","OPERATOR","APPROVER"],configureRoles:["SPACE_OPERATOR","UM_OPERATOR"],invokeRoles:["SPACE_OPERATOR","UM_OPERATOR"]});ps(n,H,Te)}function Rt(n,t,e,s,a){var I,y,S,A,T,v,l,g,f,p;const r=H,i=m=>new Response(JSON.stringify({code:"OK",message:"",data:m}),{headers:{"Content-Type":"application/json"}}),d=(m,E)=>new Response(JSON.stringify({code:m===404?"NOT_FOUND":m===405?"METHOD_NOT_ALLOWED":m===409?"CONFLICT":"INVALID_ARGUMENT",message:E,data:null}),{status:m,headers:{"Content-Type":"application/json"}}),o=(m,E="DEMO_UPDATE",R=n)=>(a.audit(E,"RESOURCE",R,E),a.persist(),i(m)),u=(m,E)=>m.find(R=>String(R.id??R.code??R.profileId??R.bindingId??R.releaseId??R.sessionId)===E);let c;if(Un(a),n==="/admin/classifications")return t==="GET"?i(qt):d(405,"分类目录只读");if(c=n.match(/^\/admin\/apps\/([^/]+)\/versions\/(\d+)(?:\/(restore|activate))?$/)){const m=u(a.apps,c[1]);if(!m)return d(404,"应用不存在");const E=(r.versions[m.code]??[]).find(L=>L.versionNo===Number(c[2]));if(!E)return d(404,"发布版本不存在");if(!c[3])return t==="GET"?i({...E,appCode:m.code,config:oe(E.snapshot.config),definition:oe(E.snapshot.definition??null)}):d(405,"发布快照只读");if(t!=="POST")return d(405,"版本切换仅接受POST");if((e==null?void 0:e.draftRev)!==void 0&&e.draftRev!==m.draftRev)return d(409,"应用草稿修订已变化，请刷新");if(c[3]==="activate")return m.currentVersionNo=E.versionNo,m.status="PUBLISHED",m.updatedAt=Ae(),r.versions[m.code].forEach(L=>L.status=L.versionNo===E.versionNo?"PUBLISHED":"SUPERSEDED"),o({...m,activatedVersionNo:E.versionNo},"APP_VERSION_ACTIVATE",m.code);const R=m.currentVersionNo,h=Number(m.draftRev)+1;return Object.assign(m,oe(E.snapshot),{code:m.code,currentVersionNo:R,draftRev:h,draftId:`draft-${m.code}-r${h}`,status:"DRAFT",updatedAt:Ae()}),m.type==="FLOW"&&(a.flowStore[m.code]={definition:oe(m.definition),layout:oe(m.layout??{}),draftRev:h}),delete r.prechecks[m.code],o({...m,restoredFromVersion:E.versionNo},"APP_VERSION_RESTORE",m.code)}if(n==="/admin/apps"&&t==="GET")return i(a.apps.filter(m=>{var E;return(!s.get("type")||m.type===s.get("type"))&&(!s.get("status")||m.status===s.get("status"))&&(!s.get("bizCategory")||m.bizCategory===s.get("bizCategory"))&&(!s.get("q")&&!s.get("keyword")||`${m.name} ${m.code} ${m.description}`.toLowerCase().includes((s.get("q")??s.get("keyword")??"").toLowerCase()))&&(!s.get("code")||m.code.includes(s.get("code")))&&(!s.get("domain")||m.domain===s.get("domain"))&&(!s.get("tag")||((E=m.tags)==null?void 0:E.includes(s.get("tag"))))}));if(n==="/admin/apps"&&t==="POST"){if(!(e!=null&&e.code)||!(e!=null&&e.name))return d(400,"应用编码和名称必填");if(u(a.apps,e.code))return d(409,"应用编码已存在");const m={...oe(e),displayName:e.name,status:"DRAFT",type:e.type??"CHAT",currentVersionNo:null,draftRev:1,draftId:`draft-${e.code}-r1`,bizCategory:et(e.bizCategory??""),createdAt:Ae(),updatedAt:Ae(),config:{modelBinding:{modelEntryId:(I=a.modelEntries[0])==null?void 0:I.id},skillRefs:[],...e.config}};return m.type==="FLOW"&&Object.assign(m,{definition:{definitionVersion:"1.0",trigger:"TASK",entry:"start",inputs:[],outputs:[],joins:[],nodes:[{id:"start",type:"START",name:"开始"},{id:"end",type:"END",name:"结束"}],edges:[{from:"start",to:"end"}],executionRules:{parallel:!1,maxParallel:1,cyclesForbidden:!0}},layout:{}}),a.apps.unshift(m),o(m,"APP_CREATE",m.code)}if(c=n.match(/^\/admin\/apps\/([^/]+)\/(lifecycle-logs|versions(?:\/diff)?)$/)){const m=u(a.apps,c[1]);if(!m)return d(404,"应用不存在");if(t!=="GET")return d(405,"版本目录只读");const E=(y=r.versions)[S=m.code]??(y[S]=Array.from({length:Number(m.currentVersionNo??0)},(R,h)=>({versionNo:Number(m.currentVersionNo)-h,status:h?"SUPERSEDED":"PUBLISHED",snapshot:oe(m),snapshotDigest:Te([m.code,h,m.config]),publishedAt:m.updatedAt,publishedBy:"u-demo"})));if(c[2]==="lifecycle-logs")return i(a.auditLogs.filter(R=>R.targetId===m.code).map(R=>({...R,action:R.action,operator:R.actor,reason:R.detail})));if(c[2]==="versions/diff"){const R=E.find(N=>N.versionNo===Number(s.get("fromVersion"))),h=E.find(N=>N.versionNo===Number(s.get("toVersion")));if(!R||!h)return d(404,"发布版本不存在");const L=Object.keys({...R.snapshot,...h.snapshot}).filter(N=>JSON.stringify(R.snapshot[N])!==JSON.stringify(h.snapshot[N])).map(N=>({operation:R.snapshot[N]===void 0?"ADD":h.snapshot[N]===void 0?"REMOVE":"REPLACE",path:N,field:N,before:R.snapshot[N],after:h.snapshot[N]}));return i({fromVersion:R.versionNo,toVersion:h.versionNo,fromSha256:R.snapshotDigest,toSha256:h.snapshotDigest,changes:L,differences:L,from:R.snapshot,to:h.snapshot})}return i(E)}if(c=n.match(/^\/admin\/apps\/([^/]+)\/publish\/(precheck|commit)$/)){if(t!=="POST")return d(405,"发布操作仅接受 POST");const m=u(a.apps,c[1]);if(!m)return d(404,"应用不存在");const E=Te({config:m.config,definition:m.definition,draftRev:m.draftRev});if(c[2]==="precheck"){const h=a.evaluations.find(D=>D.appCode===m.code&&D.draftRev===m.draftRev&&D.candidateSha256===E&&D.status==="ACCEPTED"),L=[{group:"候选配置",result:(T=(A=m.config)==null?void 0:A.modelBinding)!=null&&T.modelEntryId?"PASS":"ERROR",detail:(l=(v=m.config)==null?void 0:v.modelBinding)!=null&&l.modelEntryId?"模型已绑定":"请先选择模型"},{group:"评测门禁",result:h?"PASS":"ERROR",detail:h?"评测报告与当前候选匹配":"请运行当前候选评测后重新预检"}],N={precheckId:a.nextId("precheck"),appCode:m.code,draftRev:m.draftRev,candidateSha256:E,passed:L.every(D=>D.result==="PASS"),gateMode:"BLOCKING",publicationTarget:"NON_PRODUCTION",evaluationEnvironment:"DEMO",environmentConfirmationRequired:!1,report:{checks:L,warnings:[]},...h?{evalReportId:h.id,evaluationBinding:{evalReportId:h.id,candidateSha256:E,evaluatedCandidateSha256:E,draftRev:m.draftRev,matchedBy:"SHA256"}}:{}};return r.prechecks[m.code]=N,o(N,"APP_PRECHECK",m.code)}const R=r.prechecks[m.code];return!(R!=null&&R.passed)||R.draftRev!==m.draftRev||R.candidateSha256!==E||(e==null?void 0:e.precheckId)!==R.precheckId||(e==null?void 0:e.candidateSha256)!==E||(e==null?void 0:e.draftRev)!==m.draftRev?d(409,"当前候选未通过评测并绑定预检，请重新预检"):((g=r.versions)[f=m.code]??(g[f]=Array.from({length:Number(m.currentVersionNo??0)},(h,L)=>({versionNo:Number(m.currentVersionNo)-L,status:"SUPERSEDED",snapshot:oe(m),snapshotDigest:Te([m.code,L]),publishedAt:m.updatedAt,publishedBy:"u-demo"}))),r.versions[m.code].forEach(h=>h.status="SUPERSEDED"),m.currentVersionNo=Math.max(0,...r.versions[m.code].map(h=>Number(h.versionNo)))+1,m.status="PUBLISHED",m.updatedAt=Ae(),r.versions[m.code].unshift({versionNo:m.currentVersionNo,status:"PUBLISHED",snapshot:oe(m),config:oe(m.config),definition:oe(m.definition??null),snapshotDigest:E,candidateSha256:E,publishedAt:Ae(),publishedBy:"u-demo"}),delete r.prechecks[m.code],o(m,"APP_PUBLISH",m.code))}if(c=n.match(/^\/admin\/(model-providers|model-entries)(?:\/([^/]+))?(?:\/(credentials\/rotate|disable|enable|quota|test|metering))?$/)){const m=c[1]==="model-providers"?a.modelProviders:a.modelEntries,E=c[2]?u(m,c[2]):void 0;if(c[2]&&!E)return d(404,"模型资源不存在");if(!c[2]){if(t==="GET")return i(m);if(t!=="POST")return d(405,"不支持该方法");if(!(e!=null&&e[c[1]==="model-providers"?"code":"modelName"])&&!(e!=null&&e.modelId))return d(400,"模型编码必填");if(m.some(h=>c[1]==="model-providers"?h.code===(e==null?void 0:e.code):h.providerId===(e==null?void 0:e.providerId)&&(h.modelId??h.modelName)===((e==null?void 0:e.modelId)??(e==null?void 0:e.modelName))))return d(409,"模型资源已存在");const R={...oe(e),id:a.nextId(c[1]==="model-providers"?"mp":"me"),modelId:(e==null?void 0:e.modelId)??(e==null?void 0:e.modelName),status:"ACTIVE",credentialStatus:e!=null&&e.secretRef?"SET":"UNSET",updatedAt:Ae()};return m.unshift(R),o(R)}if(c[3]==="test")return t==="POST"?i({id:E.id,status:"HEALTHY",ok:!0,model:E.modelId??E.name,checks:[{key:"demo-contract",status:"PASS",message:"模型契约检查通过"}],success:!0,reachable:!0,latencyMs:42,message:"连接检查通过",checkedAt:Ae()}):d(405,"仅接受POST");if(c[3]==="metering"&&t==="GET")return i(E.metering??{});if(c[3]&&["POST","PUT"].includes(t)){if(c[3]==="disable"||c[3]==="enable")E.status=c[3]==="disable"?"DISABLED":"ACTIVE";else if(c[3]==="credentials/rotate"){if(!(e!=null&&e.secretRef))return d(400,"secretRef必填");E.secretRef=e.secretRef,E.credentialStatus="SET"}else E[c[3]]=oe(e??{});return E.updatedAt=Ae(),o(E)}return!c[3]&&t==="GET"?i(E):!c[3]&&t==="PUT"?(Object.assign(E,oe(e??{}),{id:E.id,updatedAt:Ae()}),o(E)):!c[3]&&t==="DELETE"?(m.splice(m.indexOf(E),1),o(null)):d(405,"不支持该方法")}if((c=n.match(/^\/admin\/(skills|prompt-templates)(?:\/([^/]+))?(?:\/(versions|drafts)(?:\/(\d+))?(?:\/(publish|render|acceptance|connection-test|service-probe))?|\/(referenced-by|disable|draft))?$/))&&!["semantic-catalog","import-openapi"].includes(c[2])){const m=c[1]==="skills",E=m?a.skills:a.promptTemplates,R=c[2]?u(E,c[2]):void 0,h=D=>{var $,U,V,j;if(!m){const W=($=a.templateVersionSets)[U=D.code]??($[U]=[{...oe(D),version:1,versionNo:1}]);for(const G of W)G.versionNo??(G.versionNo=G.version);return W}return(V=r.skillVersions)[j=D.code]??(V[j]=[{...oe(D),version:D.currentVersion??1,versionNo:D.currentVersion??1,sha256:Te(D),definition:{...oe(D),execution:D.execution??{readonly:D.readonly!==!1,sideEffect:D.sideEffect===!0,idempotent:!0},inputSchema:D.inputSchema??{type:"object",properties:{target:{type:"string"}}},outputSchema:D.outputSchema??{type:"object"}}}])};if(!c[2]){if(t==="GET"){const $=((p=a.actorRoles)==null?void 0:p.call(a))??["ADMIN","EDITOR","APPROVER"];return i(E.filter(U=>{var z,se,B,X,k;if(!m)return!0;const V=U.accessPolicy??{},j=s.get("purpose")==="configure"?"configureRoles":"viewRoles",W=s.get("appCode")?u(a.apps,s.get("appCode")):void 0,G=(z=a.businessContext)==null?void 0:z.call(a);return(!s.get("bizCategory")||((se=U.bizCategories)==null?void 0:se.includes(s.get("bizCategory"))))&&(V.visibility==="PUBLIC"&&j==="viewRoles"||(V[j]??[]).some(_=>$.includes(_)))&&(!W||((B=U.bizCategories)==null?void 0:B.includes("GENERAL"))||((X=U.bizCategories)==null?void 0:X.includes(W.bizCategory)))&&(!(G!=null&&G.businessSystemCode)||!U.businessSystemCode||U.businessSystemCode===G.businessSystemCode||((k=U.bizCategories)==null?void 0:k.includes("GENERAL"))||$.includes("ADMIN"))}))}if(t!=="POST")return d(405,"不支持该方法");if(!(e!=null&&e.code)||!(e!=null&&e.name))return d(400,"编码和名称必填");if(u(E,e.code))return d(409,"资源编码已存在");const D={...oe(e),status:"DRAFT",currentVersion:1,kind:e.kind??e.type??"HTTP",updatedAt:Ae()};return E.unshift(D),Un(a),h(D),o(D)}if(!R)return d(404,"资源不存在");const L=h(R),N=c[4]?L.find(D=>Number(D.versionNo??D.version)===Number(c[4])):void 0;if(c[4]&&!N)return d(404,"资源版本不存在");if(c[6]==="referenced-by")return t==="GET"?i(a.apps.filter(D=>{var $,U;return(U=($=D.config)==null?void 0:$.skillRefs)==null?void 0:U.some(V=>V.code===R.code)}).map(D=>{var $;return{appCode:D.code,appName:D.name,code:D.code,name:D.name,draftRev:D.draftRev,version:($=D.config.skillRefs.find(U=>U.code===R.code))==null?void 0:$.version}})):d(405,"只读接口");if(c[5]==="render"){if(t!=="POST")return d(405,"仅接受POST");const D=String(N.content??R.content??"").replace(/\{\{\s*([^{}]+?)\s*\}\}/g,($,U)=>{var V;return String(((V=e==null?void 0:e.variables)==null?void 0:V[U])??`{{${U}}}`)});return i({rendered:D,content:D})}if(c[5])return t!=="POST"?d(405,"仅接受POST"):c[5]==="publish"?(N.status="PUBLISHED",N.publishedAt=Ae(),R.currentVersion=Number(c[4]),R.status="PUBLISHED",o({...N,code:R.code},"RESOURCE_PUBLISH")):c[5]==="service-probe"?i({status:"PASS",reachable:!0,success:!0,latencyMs:42,message:"演示服务检查通过",checkedAt:Ae()}):i({code:R.code,version:Number(c[4]),status:"ACCEPTED",success:!0,passed:!0,controlledCallId:9001,results:((e==null?void 0:e.cases)??[]).map((D,$)=>({...D,index:$,passed:!0,status:"SUCCEEDED"})),message:"演示契约检查通过"});if(c[3])return t==="GET"?i(c[4]?N:L):d(405,"版本目录只读");if(c[6]){if(t!=="POST")return d(405,"仅接受POST");if(c[6]==="draft"){const D=Math.max(...L.map($=>Number($.versionNo??$.version)))+1;L.unshift({...oe(L[0]),version:D,versionNo:D,status:"DRAFT"}),R.currentVersion=D,R.status="DRAFT"}else R.status="DISABLED";return o(R)}if(t==="GET")return i({...R,versions:L});if(t==="PUT"){let D=L.find(V=>V.status==="DRAFT");if(!D){const V=Math.max(...L.map(j=>Number(j.versionNo??j.version)))+1;D={...oe(L[0]),version:V,versionNo:V,status:"DRAFT"},L.unshift(D)}const $=R.code,U=D.versionNo??D.version;return Object.assign(R,oe(e??{}),{code:$,updatedAt:Ae(),status:"DRAFT",currentVersion:U}),Object.assign(D,oe(e??{}),{code:$,versionNo:U,version:U,status:"DRAFT",content:(e==null?void 0:e.content)??D.content}),m&&(D.definition={...D.definition,...oe(e??{})}),o({...R,versions:L})}return t==="DELETE"?m&&a.apps.some(D=>{var $,U;return(U=($=D.config)==null?void 0:$.skillRefs)==null?void 0:U.some(V=>V.code===R.code)})?d(409,"技能仍被应用引用，请先解除引用"):(E.splice(E.indexOf(R),1),o(null)):d(405,"不支持该方法")}return fs(n,t,e,s,a,r,Te)}const Oe=n=>n&&typeof n=="object"&&!Array.isArray(n)?n:{},fn=n=>JSON.parse(JSON.stringify(n)),ke=JSON.parse(wt),jt=JSON.parse(lt),ee=(n,t=n,e={})=>({type:"string",title:n,description:t,...e}),Qe=(n,t=1,e={})=>({type:"integer",title:n,description:n,minimum:t,...e}),ze=(n,t,e={})=>({type:"array",title:n,description:n,items:t,uniqueItems:!0,...e}),In=(n,t,e=[],s={})=>({type:"object",title:n,description:n,properties:t,required:e,...s}),nt=(n,t,e)=>ze(n,{type:"integer",enum:t},{minItems:1,...e?{default:e}:{}}),_n=ke.assets.satellites.map(n=>n.id),Tt=ke.assets.satellites.filter(n=>n.supportedPayloads.includes("SPACE")).map(n=>n.id),en=ee("搜索关键词","按名称或编码筛选；留空返回全部"),Nn={pageNumber:Qe("页码",1,{default:1}),pageSize:Qe("每页条数",1,{maximum:200,default:20})},Dn=Qe("观测卫星",1,{enum:_n}),Jn=ee("载荷类型","SPACE为空间观测，GROUND为对地观测",{enum:["SPACE","GROUND"]}),St=ee("地面站","留空使用全部可用地面站",{enum:ke.assets.stations.map(n=>n.id)}),tt=ee("开始时间","带时区的规划起始时间，必须早于结束时间",{format:"date-time",default:`${ke.planningDate}T08:00:00+08:00`}),st=ee("结束时间","带时区的规划结束时间，必须晚于开始时间",{format:"date-time",default:`${ke.planningDate}T12:00:00+08:00`}),hn=ee("规划标识","请先生成规划；自动带入当前会话规划标识",{minLength:1}),Pn=ee("任务标识","填写已受理任务标识；可查询共享演示任务",{minLength:1,default:"DEMO-RUNNING-0001"}),He=ee("申请标识","请先生成账号变更申请；自动带入当前会话申请标识",{minLength:1}),Ct=ee("业务账号标识","从用户查询或创建账号结果中选择账号标识",{minLength:1}),Wn=ee("文件制品标识","上传文件或生成导出制品后填入真实 artifactId",{minLength:1}),bt=ze("申请授予的角色",ee("目标角色","仅表示申请目标角色；操作人的权限仍由业务源读取",{enum:jt.roles.filter(n=>n.status==="ACTIVE").map(n=>n.id)}),{minItems:1,default:["ROLE_ANALYST"]}),at=ee("目标部门","账号所属部门；授权范围以业务源当前身份为准",{enum:jt.departments.map(n=>n.id),default:"dept-a"}),zn=In("所选本体正式版本",{space:ee("本体空间标识"),spaceCode:ee("本体空间编码",void 0,{minLength:1}),releaseId:ee("正式发布标识",void 0,{minLength:1}),version:ee("本体版本",void 0,{minLength:1}),contentDigest:ee("版本内容摘要",void 0,{minLength:1})},["spaceCode","releaseId","version","contentDigest"],{description:"从当前配置的本体正式版本带入，未选择本体时请先配置",default:{}}),Is=In("已冻结的规划",{id:ee("规划标识",void 0,{minLength:1}),kind:ee("观测类型",void 0,{enum:["SPACE","GROUND"]}),targetId:{title:"目标标识",description:"空间目标数字ID或地面目标GT编码",type:["string","integer"]},windows:ze("已选择观测窗口",In("观测窗口",{id:ee("窗口标识",void 0,{minLength:1})},["id"]),{minItems:1}),downlinks:ze("下传窗口",In("下传窗口",{id:ee("下传窗口标识",void 0,{minLength:1})},["id"])),revision:Qe("规划修订")},["id","kind","targetId","windows","revision"],{description:"请先生成并冻结规划；提交前检查下传并等待人工确认",default:{}}),Es=n=>n?ee("地面目标","从地面目标目录选择目标编码",{enum:ke.assets.groundTargets.map(t=>t.id),default:"GT-QH-01"}):Qe("空间目标",1,{enum:ke.assets.spaceTargets.map(t=>t.id),default:8890}),Nt=n=>({targetId:Es(n),observerSatelliteIds:nt("观测卫星集合",n?_n.filter(t=>ke.assets.payloads.some(e=>e.satelliteId===t&&e.payloadType==="GROUND")):Tt,n?[3150]:Tt),startTime:tt,endTime:st,...n?{cloudLimit:{type:"number",title:"最大云量（%）",description:"仅保留云量不超过该阈值的对地窗口",minimum:0,maximum:100,default:30}}:{}}),On={username:ee("账号名","创建或变更的业务账号名",{minLength:1,maxLength:64,pattern:"^[A-Za-z0-9][A-Za-z0-9._-]*$",default:"chen.xin"}),name:ee("员工姓名",void 0,{minLength:1,maxLength:64,default:"陈新"}),employeeNo:ee("员工编号",void 0,{minLength:1,maxLength:64,default:"EMP-0103"}),departmentId:at,roleIds:bt,validityDays:Qe("授权有效期（天）",1,{maximum:30,default:7,description:"最多30天；关联本体v2时业务规则进一步限制为7天"})},Z=(n,t,e=[])=>In(n,t,e,{additionalProperties:!1}),$n={orbit_query:Z("轨道要素查询",{satelliteId:{...Dn,default:2068}},["satelliteId"]),telemetry_fetch:Z("遥测片段提取",{satelliteId:{...Dn,default:2068},startTime:tt,endTime:st,parameterCodes:ze("遥测参数编码",ee("参数编码",void 0,{minLength:1}),{minItems:1,default:["BUS_VOLTAGE","BATTERY_TEMPERATURE"]})},["satelliteId","startTime","endTime","parameterCodes"]),payload_compat:Z("载荷兼容检查",{satelliteId:{...Dn,default:2068},payloadType:{...Jn,default:"SPACE"}},["satelliteId","payloadType"]),"planning-satellite-query":Z("卫星目录查询",{keyword:en,satelliteId:Dn,payloadType:Jn,...Nn}),"planning-payload-query":Z("独立载荷配置查询",{satelliteIds:nt("卫星集合",_n,[3150]),payloadType:Jn},["satelliteIds"]),"planning-space-target-query":Z("空间目标筛选",{targetId:Qe("空间目标标识",1,{enum:ke.assets.spaceTargets.map(n=>n.id)}),keyword:en,...Nn}),"planning-ground-target-query":Z("地面目标区域查询",{targetId:ee("地面目标编码",void 0,{enum:ke.assets.groundTargets.map(n=>n.id)}),keyword:en,...Nn}),"planning-station-query":Z("地面站目录查询",{stationId:St,keyword:en,...Nn}),"planning-defaults-read":Z("规划默认约束",{}),"planning-space-plan":Z("对空观测窗口规划",Nt(!1),["targetId","observerSatelliteIds","startTime","endTime"]),"planning-ground-plan":Z("对地观测窗口规划",Nt(!0),["targetId","observerSatelliteIds","startTime","endTime"]),"planning-downlink-plan":Z("站星下传窗口规划",{satelliteIds:nt("需要下传的卫星集合",_n,[3150]),stationId:St,startTime:tt,endTime:st},["satelliteIds","startTime","endTime"]),"planning-downlink-validate":Z("完整时段与容量校验",{planId:hn},["planId"]),"planning-ontology-facts":Z("本体对象事实与关联",{releaseRef:zn,objectType:ee("对象类型","可按对象类型过滤；留空读取所选版本全部事实")},["releaseRef"]),"planning-ontology-rules":Z("本体规则与评分",{releaseRef:zn,planId:hn,ruleIds:ze("规则编码集合",ee("规则编码",void 0,{minLength:1}),{description:"留空显示全部规则；提供规划标识后评估具体规划"})},["releaseRef"]),"planning-task-submit":Z("观测任务提交",{plan:Is},["plan"]),"planning-task-status":Z("任务进度查询",{taskId:Pn},["taskId"]),"planning-task-result":Z("任务结果查询",{taskId:Pn},["taskId"]),"planning-task-cancel":Z("受控取消任务",{taskId:{...Pn,description:"填写本会话可取消任务标识；执行前必须人工确认"}},["taskId"]),"planning-selection-save":Z("确认规划窗口选择",{taskId:Pn,windowIds:ze("选中的窗口标识",ee("窗口标识",void 0,{minLength:1}),{minItems:1,description:"从该任务规划结果中选择至少一个实际窗口标识"})},["taskId","windowIds"]),"planning-artifact-export":Z("导出规划报告与窗口CSV",{planId:hn},["planId"]),"planning-file-upload":Z("上传规划文件",{artifactId:{...Wn,format:"binary",description:"上传单目标CSV、约束JSON或星历TXT后自动填入真实 artifactId"}},["artifactId"]),"planning-file-parse":Z("解析目标与规划约束",{artifactId:Wn},["artifactId"]),"planning-file-export":Z("导出规划文件",{planId:hn},["planId"]),"planning-file-download":Z("下载规划制品",{artifactId:Wn},["artifactId"]),"um-identity-resolve":Z("识别当前业务账号",{}),"um-permissions-pull":Z("拉取当前业务角色与权限",{}),"um-user-query":Z("查询用户与部门",{departmentId:at,keyword:en}),"um-role-query":Z("查询角色及互斥关系",{keyword:en}),"um-ontology-check":Z("本体规则校验",{requestId:He,releaseRef:zn},["requestId","releaseRef"]),"um-request-prepare":Z("生成账号变更计划",{action:ee("变更操作","创建账号、变更角色或停用账号；不会跳过业务规则和独立审批",{enum:["CREATE_ACCOUNT","ASSIGN_ROLES","DISABLE_ACCOUNT"],default:"CREATE_ACCOUNT"}),...On},["action","username","name","employeeNo","departmentId","roleIds","validityDays"]),"um-request-submit":Z("提交账号申请",{requestId:He},["requestId"]),"um-approval-decide":Z("审批账号申请",{requestId:He,approved:{type:"boolean",title:"是否批准",description:"当前审批人的身份、权限和职责分离从业务源校验",default:!1},reason:ee("审批意见","拒绝时请填写原因；实际审批人由当前业务身份确定",{maxLength:500,default:""})},["requestId","approved"]),"um-account-create":Z("创建业务账号",{requestId:He,username:{...Oe(On.username),default:void 0},name:{...Oe(On.name),default:void 0},employeeNo:{...Oe(On.employeeNo),default:void 0},departmentId:{...at,default:void 0}},["requestId"]),"um-role-assign":Z("授予业务角色",{requestId:He,accountId:Ct,roleIds:bt},["requestId","accountId","roleIds"]),"um-request-readback":Z("读回账号与授权",{requestId:He,accountId:Ct},["requestId"]),"um-request-receipt":Z("导出授权回执",{requestId:He},["requestId"])};for(const[n,t]of Object.entries({"space-target-query":"planning-space-target-query","space-visibility-plan":"planning-space-plan","space-visibility-submit":"planning-task-submit","space-visibility-status":"planning-task-status","space-visibility-result":"planning-task-result"}))$n[n]=$n[t];function Ht(n,t=!1){if(n.default!==void 0)return fn(n.default);if(Array.isArray(n.examples)&&n.examples.length)return fn(n.examples[0]);if(n.const!==void 0)return fn(n.const);if(n.type==="object"||n.properties){const s=Array.isArray(n.required)?n.required:[],a=Object.entries(Oe(n.properties)).map(([r,i])=>[r,Ht(Oe(i),s.includes(r))]).filter(([,r])=>r!==void 0);return t||a.length?Object.fromEntries(a):void 0}if(!t)return;if(Array.isArray(n.enum)&&n.enum.length)return fn(n.enum[0]);const e=Array.isArray(n.type)?n.type[0]:n.type;return e==="array"?[]:e==="boolean"?!1:e==="null"?null:e==="number"||e==="integer"?typeof n.minimum=="number"?n.minimum:0:""}function xt(n){return Oe(Ht(n,!0))}function As(n,t,e){const s=fn($n[n]??t),a=Oe(e==null?void 0:e.ontology),r=Oe(a.releaseRef??a),i=Oe(s.properties);if($n[n]&&i.releaseRef&&["spaceCode","releaseId","version","contentDigest"].every(d=>typeof r[d]=="string"&&r[d])){const d=Object.fromEntries(["space","spaceCode","releaseId","version","contentDigest"].filter(o=>r[o]!==void 0).map(o=>[o,r[o]]));i.releaseRef={...Oe(i.releaseRef),default:d}}return{inputSchema:s,parameterTemplate:xt(s)}}const P=JSON.parse(wt),Ke=new Set(["satellite-planning-assistant","satellite-assistant","space-visibility-agent"]),vs={space:P.ontology.space.id,...P.releaseRef},F={conversations:{},tasks:{},pending:{},results:{},sequence:0},J=n=>JSON.parse(JSON.stringify(n)),ct=n=>n&&typeof n=="object"&&!Array.isArray(n)?n:{};F.tasks["DEMO-RUNNING-0001"]={taskId:"DEMO-RUNNING-0001",status:"RUNNING",sourceRunId:"seed-running-task",stages:["SUBMITTED","RUNNING"],artifacts:[],shared:!0,plan:{id:"PLAN-RUNNING-01",kind:"SPACE",targetId:8890,targetName:"STARLINK-31091",planningDate:P.planningDate,windows:[J(P.windows[0])],excludedWindows:[],downlinks:[J(P.downlinks[0])],recommendedWindowId:"SP-W01",revision:1,constraints:{maxCloudPercent:30,epochAgeDays:2,night:!1}}};function Rs(n){const t=ct(n);for(const e of["conversations","tasks","pending","results"])t[e]&&typeof t[e]=="object"&&!Array.isArray(t[e])&&Object.assign(F[e],t[e]);typeof t.sequence=="number"&&(F.sequence=t.sequence)}const jn=n=>P.tools.find(t=>t.code===n);function de(n,t,e,s,a="SUCCEEDED"){var r;n.toolCalls.push({id:`${n.runId}-call-${n.toolCalls.length+1}`,skillCode:t,name:((r=jn(t))==null?void 0:r.name)??t,input:J(e),output:J(s),status:a})}function Ft(n){const t=ct(n.ontology);if(!t.releaseId)return null;const e=t.releaseId===P.releaseRef.releaseId;return{releaseRef:{spaceCode:String(t.spaceCode??t.space??""),releaseId:String(t.releaseId),version:String(t.version??""),contentDigest:String(t.contentDigest??"")},name:e?P.ontology.space.name:"所选本体版本",facts:e?P.ontology.instances.map(s=>({objectType:s.className,stableId:s.stableId,values:J(s.values)})):[],decisions:[],relations:e?["Satellite → carries → Payload","ObservationWindow → observesSpace → SpaceTarget","ObservationWindow → observesGround → GroundTarget","ObservationWindow → followedBy → DownlinkWindow → receivedBy → GroundStation"]:[]}}function sn(n,t){var d;if(!n.ontology||n.ontology.releaseRef.releaseId!==P.releaseRef.releaseId)return;const e=(o,u,c,I)=>{var y;return{ruleId:o,name:((y=P.ontology.rules.find(S=>S.id===o))==null?void 0:y.name)??o,result:u?"HOLDS":"NOT_HOLDS",reason:c,...I?{windowId:I}:{}}},s=t.constraints??{maxCloudPercent:30,epochAgeDays:2,night:!1},a=[...t.windows,...t.excludedWindows],r=new Set(a.map(o=>o.satelliteId));s.onlySatelliteId&&r.add(s.onlySatelliteId),n.ontology.facts=P.ontology.instances.filter(o=>o.className==="Satellite"?r.has(Number(o.values.id)):o.className==="Payload"?r.has(Number(o.values.satelliteId))&&o.values.payloadType===t.kind:["SpaceTarget","GroundTarget"].includes(o.className)?o.values.id===t.targetId:o.className==="ObservationWindow"?a.some(u=>u.id===o.stableId):o.className==="DownlinkWindow"?t.downlinks.some(u=>u.id===o.stableId):o.className==="GroundStation"?t.downlinks.some(u=>u.stationId===o.values.id):!1).map(o=>({objectType:o.className,stableId:o.stableId,values:J(o.values)}));const i=!s.onlySatelliteId||!!((d=P.assets.satellites.find(o=>o.id===s.onlySatelliteId))!=null&&d.supportedPayloads.includes(t.kind));n.ontology.decisions=[e("rule-payload",i,`按${t.kind}载荷及目标类型匹配观测卫星`),e("rule-epoch",s.epochAgeDays<=14,`星历年龄${s.epochAgeDays}天 / 阈值14天`),e("rule-sunlight",!s.night,s.night?"夜间光学对地观测不满足光照条件":"所选窗口满足本场景光照条件"),...[...t.windows,...t.excludedWindows].filter(o=>o.kind==="GROUND").map(o=>e("rule-cloud",Number(o.cloudPercent)<=s.maxCloudPercent,`云量${o.cloudPercent}% / 上限${s.maxCloudPercent}%`,o.id)),...t.windows.filter(o=>o.kind==="GROUND").map(o=>e("rule-resolution",Number(o.resolutionM)<=1,`分辨率${o.resolutionM}m / 要求1m`,o.id))],de(n,"planning-ontology-facts",{releaseRef:n.ontology.releaseRef,targetId:t.targetId},{facts:n.ontology.facts,relations:n.ontology.relations}),de(n,"planning-ontology-rules",{releaseRef:n.ontology.releaseRef,planId:t.id},{decisions:n.ontology.decisions})}function En(n){const t=s=>{var a;return((a=P.assets.satellites.find(r=>r.id===s))==null?void 0:a.name)??String(s)},e=n.windows.map(s=>`| ${s.id} | ${t(s.satelliteId)}（${s.satelliteId}） | ${s.startTime.slice(11,19)}—${s.endTime.slice(11,19)} | ${s.durationSec}秒 | ${s.score} | ${s.cloudPercent===void 0?"—":`云量${s.cloudPercent}% / ${s.resolutionM}m`} |`);return`## ${n.kind==="SPACE"?"对空":"对地"}观测：${n.targetName}

- 目标：\`${n.targetId}\`
- 日期：${n.planningDate}，北京时间（UTC+08:00）

${e.length?`| 窗口 | 卫星 | 时间 | 时长 | 评分 | 成像条件 |
|---|---|---|---|---|---|
`+e.join(`
`):"没有满足条件的观测窗口。"}${n.excludedWindows.length?`

### 排除项

${n.excludedWindows.map(s=>`- **${s.id}**：${s.exclusionReason??"不满足任务约束"}`).join(`
`)}`:""}

**推荐窗口：${n.recommendedWindowId??"无"}**`}function pt(n,t,e){const s=["windowId,kind,satelliteId,targetId,startTime,endTime,durationSec,score",...n.windows.map(r=>[r.id,r.kind,r.satelliteId,r.targetId,r.startTime,r.endTime,r.durationSec,r.score].join(","))].join(`\r
`),a=JSON.stringify({mode:"MOCK",source:P.dataOrigin,runId:t,plan:n,ontology:(e==null?void 0:e.releaseRef)??null},null,2);return[{name:"观测窗口.csv",mediaType:"text/csv",content:"\uFEFF"+s},{name:"规划报告.json",mediaType:"application/json",content:a}].map((r,i)=>({...r,id:`${t}-artifact-${i+1}`,size:new TextEncoder().encode(r.content).length,downloadUrl:`data:${r.mediaType};charset=utf-8,${encodeURIComponent(r.content)}`}))}function fe(n){return F.results[n.runId]=J(n),n}function Ln(n,t,e,s,a){var N,D,$,U,V,j,W,G,z,se,B,X;const r=`${n}:${s}`,i=(N=F.conversations)[r]??(N[r]={}),d={runId:a,conversationId:s,reply:"",status:"SUCCEEDED",toolCalls:[],ontology:Ft(t),artifacts:[],promptSnapshot:String(t.systemPrompt??P.systemPrompt)},o=e.trim(),u=k=>(d.status="NEEDS_INPUT",d.reply=k,fe(d));if((o.match(/\d{4}-\d{2}-\d{2}/g)??[]).some(k=>k!==P.planningDate))return u(`当前演示场景准备的是${P.planningDate} 08:00—12:00（北京时间）。请使用该日期或示例问法；其他日期没有对应Mock结果。`);const I=(D=o.match(/\bGT-[A-Z0-9]+-\d+\b/i))==null?void 0:D[0],y=($=o.match(/(?:空间目标|目标ID|目标编号)\s*[:：]?\s*(\d+)/))==null?void 0:$[1];if(I&&!P.assets.groundTargets.some(k=>k.id===I.toUpperCase()))return u(`目标目录未找到${I}，请选择GT-QH-01（青海湖）或GT-PY-02（鄱阳湖）。`);if(y&&!P.assets.spaceTargets.some(k=>String(k.id)===y))return u(`空间目标目录未找到${y}，请先查询目标目录并选择具体目标。`);if(/UNKNOWN|未知目标|99999/i.test(o))return u("没有找到该目标，请先查询目标目录并选择具体空间目标或地面目标。");if(!o)return u("请先输入目标与规划要求，或选择一条示例问法。");if(/取消.*任务/.test(o)){const k=(U=o.match(/DEMO-[A-Z0-9-]+/))==null?void 0:U[0],_=F.tasks[k??i.lastTaskId??""];return _?!_.shared&&_.ownerConversationKey!==r?u("该任务不属于当前会话，请使用本会话任务或共享演示任务。"):["SUCCEEDED","CANCELLED"].includes(_.status)?(d.status="BLOCKED",d.task=J(_),d.reply=`任务${_.taskId}已${_.status==="SUCCEEDED"?"完成":"取消"}，不能再次取消。`,fe(d)):(d.plan=J(_.plan),d.approval={runId:a,nodeId:"planning-confirm",status:"PENDING",summary:`取消任务${_.taskId}`,action:"CANCEL",taskId:_.taskId},d.status="WAITING_APPROVAL",d.reply=`取消${_.taskId}需要人工确认；确认前任务状态保持${_.status}。`,F.pending[a]={conversationKey:r,response:J(d)},fe(d)):u("请先提交任务，或给出本会话中存在的任务ID。")}if(/提交|执行这套|执行该方案/.test(o))return(V=i.plan)!=null&&V.windows.length?i.pendingRunId&&F.pending[i.pendingRunId]?J(F.pending[i.pendingRunId].response):(d.plan=J(i.plan),d.plan.downlinkChecked||(d.plan.downlinks=J(P.downlinks.filter(k=>d.plan.windows.some(_=>_.satelliteId===k.satelliteId&&Date.parse(k.startTime)>Date.parse(_.endTime)&&k.capacityGb>=_.dataGb))),d.plan.downlinkChecked=!0,de(d,"planning-downlink-validate",{planId:d.plan.id},{covered:d.plan.downlinks.length>0,windows:d.plan.downlinks})),d.plan.downlinks.length?(d.plan.windows=d.plan.windows.filter(k=>d.plan.downlinks.some(_=>_.satelliteId===k.satelliteId)),sn(d,d.plan),d.approval={runId:a,nodeId:"planning-confirm",status:"PENDING",summary:`提交${d.plan.targetName}的${d.plan.windows.length}个观测窗口`,action:"SUBMIT"},d.status="WAITING_APPROVAL",d.reply=`已冻结规划${d.plan.id}，等待人工确认。
${En(d.plan)}
确认后才会受理任务；当前尚未生成任务或产生调度效果。`,de(d,"planning-task-submit",{plan:d.plan},{accepted:!1,reason:"等待人工确认"},"WAITING_APPROVAL"),i.pendingRunId=a,F.pending[a]={conversationKey:r,response:J(d)},fe(d)):u("所选方案没有满足时序与容量的下传窗口，请先换用站点或观测方案。")):u("请先生成一套有可用窗口的规划，再提交任务。");if(/任务.*(结果|状态|进度)|查询刚才|查看刚才/.test(o)){const k=(j=o.match(/DEMO-[A-Z0-9-]+/))==null?void 0:j[0],_=F.tasks[k??i.lastTaskId??""];return _?!_.shared&&_.ownerConversationKey!==r?u("该任务不属于当前会话，请使用本会话任务或共享演示任务。"):(d.task=J(_),d.plan=J(_.plan),d.artifacts=J(_.artifacts),de(d,"planning-task-status",{taskId:_.taskId},{status:_.status,progress:_.status==="SUCCEEDED"?100:50}),de(d,"planning-task-result",{taskId:_.taskId},{plan:_.plan,artifacts:_.artifacts}),_.ontology!==void 0&&(d.ontology=J(_.ontology)),d.reply=`任务${_.taskId}${_.status==="SUCCEEDED"?"已完成":`状态为${_.status}`}。
${En(_.plan)}${_.artifacts.length?`
可下载观测窗口CSV与规划报告。`:`
当前没有已完成的结果制品。`}`,fe(d)):u("当前会话还没有已受理任务，请先规划、提交并完成确认。")}if(/导出|下载.*(报告|CSV|制品)/i.test(o))return i.plan?(d.plan=J(i.plan),d.artifacts=pt(d.plan,a,d.ontology),de(d,"planning-artifact-export",{planId:d.plan.id},{files:d.artifacts.map(k=>({name:k.name,size:k.size}))}),d.reply=`已生成${d.plan.targetName}的观测窗口CSV与规划报告，包含窗口、约束、下传及本体引用。`,fe(d)):u("请先生成观测规划，再导出窗口与报告。");if(/下传|数传|最优方案/.test(o)){if(!((W=i.plan)!=null&&W.windows.length))return u("请先生成观测规划，再校验下传时序和容量。");const k=J(i.plan),_=/喀什/.test(o)?"ST-KS-01":/密云/.test(o)?"ST-MY-01":void 0;if(k.downlinks=P.downlinks.filter(Y=>(!_||Y.stationId===_)&&k.windows.some(re=>re.satelliteId===Y.satelliteId&&Date.parse(Y.startTime)>Date.parse(re.endTime)&&Y.capacityGb>=re.dataGb)),k.downlinkChecked=!0,d.plan=k,de(d,"planning-downlink-plan",{satelliteIds:k.windows.map(Y=>Y.satelliteId),stationId:_??"ALL"},{windows:k.downlinks}),de(d,"planning-downlink-validate",{planId:k.id,dataGb:k.windows.map(Y=>Y.dataGb)},{covered:k.downlinks.length>0,windows:k.downlinks}),sn(d,k),((G=d.ontology)==null?void 0:G.releaseRef.releaseId)===P.releaseRef.releaseId){d.ontology.decisions.push({ruleId:"rule-downlink",name:"观测下传时序与容量",result:k.downlinks.length?"HOLDS":"NOT_HOLDS",reason:k.downlinks.length?"下传开始晚于观测结束，容量足够":"所选地面站没有覆盖本方案的后续下传窗口"});const Y=d.toolCalls.find(re=>re.skillCode==="planning-ontology-rules");Y&&(Y.output={decisions:J(d.ontology.decisions)})}const x=new Set(k.downlinks.map(Y=>Y.satelliteId)),Se=k.windows.filter(Y=>x.has(Y.satelliteId));return k.recommendedWindowId=(z=Se.sort((Y,re)=>re.score-Y.score)[0])==null?void 0:z.id,i.plan=J(k),d.reply=`${k.downlinks.length?"下传校验通过":"下传校验未通过"}。
${k.downlinks.map(Y=>{var re;return`${(re=P.assets.stations.find(ne=>ne.id===Y.stationId))==null?void 0:re.name}｜卫星${Y.satelliteId}｜${Y.startTime.slice(11,19)}—${Y.endTime.slice(11,19)}｜容量${Y.capacityGb}GB`}).join(`
`)}
综合推荐${k.recommendedWindowId??"无可执行窗口"}：观测顺序、站点可达与容量共同满足才可提交。`,fe(d)}if(/本体|规则|依据|语义|关系/.test(o)&&!/规划.*(青海湖|STARLINK|鄱阳湖)/i.test(o))return d.ontology?d.ontology.releaseRef.releaseId!==P.releaseRef.releaseId?(d.reply=`当前选择${d.ontology.releaseRef.spaceCode} ${d.ontology.releaseRef.version}（${d.ontology.releaseRef.releaseId}）。该版本未声明本观测规划场景的事实与规则，请选择“卫星与空间地面目标观测规划本体 v1”查看完整响应。`,fe(d)):(i.plan?(d.plan=J(i.plan),sn(d,d.plan)):(de(d,"planning-ontology-facts",{releaseRef:d.ontology.releaseRef},{facts:d.ontology.facts,relations:d.ontology.relations}),d.ontology.decisions=P.ontology.rules.map(k=>({ruleId:k.id,name:k.name,result:"UNKNOWN",reason:"尚未选择具体规划，展示规则定义，未执行判定"})),de(d,"planning-ontology-rules",{mode:"DESCRIBE"},{rules:P.ontology.rules})),d.reply=`当前本体：${d.ontology.name} ${d.ontology.releaseRef.version}（${d.ontology.releaseRef.releaseId}）。
${d.ontology.relations.join(`
`)}
${P.ontology.rules.map(k=>`${k.id}｜${k.name}：${k.description}`).join(`
`)}
事实、规则与引用随所选本体保存；模型负责解释，判定结果在右侧逐条显示。`,fe(d)):(d.reply="当前应用未选择本体。请在编排→对话配置中选择本体及正式版本后保存；当前回答不会附加本体事实或规则判定。",fe(d));if(/列出|目录|可用.*卫星|有什么|有哪些/.test(o)){for(const[k,_]of[["planning-satellite-query",P.assets.satellites],["planning-payload-query",P.assets.payloads],["planning-space-target-query",P.assets.spaceTargets],["planning-ground-target-query",P.assets.groundTargets],["planning-station-query",P.assets.stations],["planning-defaults-read",{maxApproximationDays:14,maxCloudPercent:30,requiredResolutionM:1}]])de(d,k,{scope:"SCENARIO",planningDate:P.planningDate,...k==="planning-payload-query"?{satelliteIds:P.assets.satellites.map(x=>x.id)}:{}},{items:_});return d.ontology&&de(d,"planning-ontology-facts",{releaseRef:d.ontology.releaseRef},{facts:d.ontology.facts}),d.reply=`本场景业务资源（${P.planningDate}，北京时间）：
卫星：${P.assets.satellites.map(k=>`${k.name}(${k.id}，${k.supportedPayloads.join("/")})`).join("；")}。
空间目标：${P.assets.spaceTargets.map(k=>`${k.name}(${k.id})`).join("；")}。
地面目标：${P.assets.groundTargets.map(k=>`${k.name}(${k.id})`).join("；")}。
地面站：${P.assets.stations.map(k=>`${k.name}(${k.id})`).join("；")}。
可继续选择对空规划或对地规划，再校验下传并提交。`,fe(d)}const S=/青海湖|鄱阳湖|GT-QH-01|GT-PY-02|对地|地面目标/.test(o),A=/STARLINK|空间目标|对空|8890|9002|遥感目标02/i.test(o);if(!S&&!A)return u("请先说明空间目标或地面目标。可以使用“规划明天上午STARLINK-31091观测”或“规划青海湖对地观测”，再继续校验下传、提交和查询结果。");if(S&&!/青海湖|鄱阳湖|GT-QH-01|GT-PY-02/i.test(o))return u("请先选择具体地面目标，例如青海湖GT-QH-01或鄱阳湖GT-PY-02。");if(!S&&!/STARLINK|8890|9002|遥感目标02/i.test(o))return u("请先选择具体空间目标，例如STARLINK-31091（8890）或演示遥感目标02（9002）。");const T=S?"GROUND":"SPACE",v=S?P.assets.groundTargets[/鄱阳湖|GT-PY-02/.test(o)?1:0]:P.assets.spaceTargets[/9002|遥感目标02/.test(o)?1:0],l=(se=o.match(/(?:只用|仅用|卫星ID[：:]?)[^\d]{0,6}(\d+)/))==null?void 0:se[1],g=P.windows.filter(k=>k.kind===T&&k.targetId===v.id&&(!l||k.satelliteId===Number(l))),f=/夜间|夜晚/.test(o)&&S,p=/过期|超期/.test(o),m=Number(((B=o.match(/云量[^\d]{0,5}(\d+(?:\.\d+)?)\s*%?/))==null?void 0:B[1])??30),E=g.filter(k=>!f&&!p&&(k.cloudPercent===void 0||k.cloudPercent<=Math.min(30,m))),R=Math.min(30,m),h=g.filter(k=>!E.includes(k)).map(k=>({...k,exclusionReason:f?"夜间光学观测不满足光照条件":p?"星历年龄20天超过14天":`云量${k.cloudPercent}%超过${R}%`})),L={id:`PLAN-${++F.sequence}`,kind:T,targetId:v.id,targetName:v.name,planningDate:P.planningDate,windows:J(E),excludedWindows:J(h),downlinks:[],recommendedWindowId:(X=[...E].sort((k,_)=>_.score-k.score)[0])==null?void 0:X.id,revision:1,constraints:{maxCloudPercent:R,epochAgeDays:p?20:2,night:f,...l?{onlySatelliteId:Number(l)}:{}}};return d.plan=L,de(d,T==="SPACE"?"planning-space-target-query":"planning-ground-target-query",{targetId:v.id},{target:v}),de(d,"planning-payload-query",{satelliteIds:g.map(k=>k.satelliteId),payloadType:T},{profiles:P.assets.payloads.filter(k=>k.payloadType===T&&(!l||k.satelliteId===Number(l)))}),de(d,T==="SPACE"?"planning-space-plan":"planning-ground-plan",{targetId:v.id,observerSatelliteIds:l?[Number(l)]:g.map(k=>k.satelliteId),startTime:`${P.planningDate}T08:00:00+08:00`,endTime:`${P.planningDate}T12:00:00+08:00`,cloudLimit:m},{windows:L.windows,excludedWindows:L.excludedWindows}),sn(d,L),d.status=f?"NO_WINDOW":p||!g.length?"BLOCKED":E.length?"SUCCEEDED":"NO_WINDOW",d.reply=f?`没有满足条件的观测窗口：${v.name}夜间光学观测不满足光照约束。建议改到白天，或选择具备相应能力的其他载荷。`:p?"本次规划被星历时效规则阻断：演示星历年龄20天，超过14天阈值；请先更新星历再规划。":g.length?En(L):`卫星${l??"所选卫星"}没有匹配${v.name}的${T}载荷或场景窗口，请换用具备对应载荷的卫星。`,d.ontology||(d.reply+=`
当前未选择本体，未加载本体事实与规则解释。`),i.plan=J(L),fe(d)}function Dt(n,t,e,s,a,r){var T,v,l;const i=`${n}:${a}`,d=(T=F.conversations)[i]??(T[i]={}),o={runId:r,conversationId:a,reply:"",status:"SUCCEEDED",toolCalls:[],ontology:Ft(t),artifacts:[],promptSnapshot:String(t.systemPrompt??P.systemPrompt)},u=((v=jn(e))==null?void 0:v.name)??e,c=(g,f)=>(de(o,e,s,g),o.reply=f??`### ${u}

\`\`\`json
${JSON.stringify(g,null,2)}
\`\`\``,fe(o)),I=g=>Array.isArray(s[g])?s[g].map(String):[],y=g=>{const f=Number(s.pageNumber??1),p=Number(s.pageSize??20);return{items:g.slice((f-1)*p,f*p),total:g.length,pageNumber:f,pageSize:p}},S=g=>(!s.targetId||String(g.id)===String(s.targetId))&&(!s.keyword||`${g.id} ${g.name}`.toLowerCase().includes(String(s.keyword).toLowerCase())),A=()=>{const g=d.plan;if(!g||s.planId&&s.planId!==g.id)throw Error("请先在当前会话生成规划，并选择该规划编号。");return g};if(e==="planning-satellite-query")return c(y(P.assets.satellites.filter(g=>(!s.satelliteId||g.id===s.satelliteId)&&(!I("satelliteIds").length||I("satelliteIds").includes(String(g.id)))&&(!s.payloadType||g.supportedPayloads.includes(String(s.payloadType)))&&(!s.keyword||g.name.includes(String(s.keyword))))));if(e==="planning-payload-query")return c({profiles:P.assets.payloads.filter(g=>(!I("satelliteIds").length||I("satelliteIds").includes(String(g.satelliteId)))&&(!s.satelliteId||g.satelliteId===s.satelliteId)&&(!s.payloadType||g.payloadType===s.payloadType))});if(e==="planning-space-target-query"||e==="planning-ground-target-query")return c(y((e==="planning-space-target-query"?P.assets.spaceTargets:P.assets.groundTargets).filter(S)));if(e==="planning-station-query")return c(y(P.assets.stations.filter(g=>(!s.stationId||g.id===s.stationId)&&(!s.keyword||g.name.includes(String(s.keyword))))));if(e==="planning-defaults-read")return c({planningDate:P.planningDate,maxApproximationDays:14,maxCloudPercent:30,requiredResolutionM:1,timeZone:P.timeZone});if(e==="planning-ground-plan"||e==="planning-space-plan"){const g=e==="planning-ground-plan",f=(g?P.assets.groundTargets:P.assets.spaceTargets).find(D=>String(D.id)===String(s.targetId));if(!f)throw Error("所选目标不在当前工具对应的目标目录中，请先查询目标。");const p=I("observerSatelliteIds");if(!p.length||p.some(D=>!P.assets.satellites.some($=>String($.id)===D)))throw Error("请选择目录中存在的观测卫星。");const m=Date.parse(String(s.startTime)),E=Date.parse(String(s.endTime));if(!Number.isFinite(m)||!Number.isFinite(E)||E<=m)throw Error("规划开始、结束时间必须有效，且结束时间晚于开始时间。");if(!String(s.startTime).startsWith(P.planningDate)||!String(s.endTime).startsWith(P.planningDate))throw Error(`当前场景提供${P.planningDate}的业务窗口，请选择该日期。`);const R=Ln(n,t,`规划${g?"地面目标":"空间目标"}${f.id}（${f.name}），云量不超过${Number(s.cloudLimit??30)}%`,a,r);if(!R.plan)return R;const h=R.plan,L=h.windows.filter(D=>!p.includes(String(D.satelliteId))||Date.parse(D.startTime)<m||Date.parse(D.endTime)>E);h.excludedWindows.push(...L.map(D=>({...D,exclusionReason:p.includes(String(D.satelliteId))?"窗口超出输入的完整时间范围":"未选择该观测卫星"}))),h.windows=h.windows.filter(D=>!L.includes(D)),h.excludedWindows=h.excludedWindows.filter(D=>p.includes(String(D.satelliteId))),h.recommendedWindowId=(l=[...h.windows].sort((D,$)=>$.score-D.score)[0])==null?void 0:l.id,d.plan=J(h),R.toolCalls=R.toolCalls.filter(D=>D.skillCode!=="planning-ontology-rules");const N=R.toolCalls.find(D=>D.skillCode===e);return N&&(N.input=J(s),N.output={windows:h.windows,excludedWindows:h.excludedWindows}),sn(R,h),R.status=h.windows.length?"SUCCEEDED":"NO_WINDOW",R.reply=`### ${u}

${h.windows.length?En(h):"输入的卫星、时间和云量条件下没有合规窗口，请调整参数重试。"}`,fe(R)}if(e==="planning-downlink-plan"){const g=I("satelliteIds"),f=J(P.downlinks.filter(p=>(!g.length||g.includes(String(p.satelliteId)))&&(!s.stationId||p.stationId===s.stationId)&&(!s.startTime||Date.parse(p.startTime)>=Date.parse(String(s.startTime)))&&(!s.endTime||Date.parse(p.endTime)<=Date.parse(String(s.endTime)))));return d.plan&&(d.plan.downlinks=f.filter(p=>d.plan.windows.some(m=>m.satelliteId===p.satelliteId&&Date.parse(p.startTime)>Date.parse(m.endTime)&&p.capacityGb>=m.dataGb)),d.plan.downlinkChecked=!0,o.plan=J(d.plan)),c({windows:f,total:f.length})}if(e==="planning-downlink-validate"){const g=A(),f=g.downlinkChecked?g.downlinks:P.downlinks,p=g.windows.map(E=>({windowId:E.id,covered:f.some(R=>R.satelliteId===E.satelliteId&&Date.parse(R.startTime)>Date.parse(E.endTime)&&R.capacityGb>=E.dataGb)}));o.plan=J(g);const m=p.length>0&&p.every(E=>E.covered);return m||(o.status="BLOCKED"),c({covered:m,checks:p})}if(e==="planning-ontology-facts"||e==="planning-ontology-rules"){if(!o.ontology||o.ontology.releaseRef.releaseId!==P.releaseRef.releaseId)throw Error("请先为当前智能体选择观测规划本体的对应正式版本。");if(s.releaseRef&&ct(s.releaseRef).releaseId!==o.ontology.releaseRef.releaseId)throw Error("参数中的本体版本与当前智能体绑定不一致。");return e==="planning-ontology-facts"?c({releaseRef:o.ontology.releaseRef,facts:o.ontology.facts.filter(g=>(!s.objectType||g.objectType===s.objectType)&&(!s.stableId||g.stableId===s.stableId)),relations:o.ontology.relations}):(s.planId||d.plan?(o.plan=J(A()),sn(o,o.plan)):o.ontology.decisions=P.ontology.rules.map(g=>({ruleId:g.id,name:g.name,result:"UNKNOWN",reason:"尚未输入规划，仅展示规则定义"})),o.toolCalls=[],c({rules:P.ontology.rules.filter(g=>!I("ruleIds").length||I("ruleIds").includes(g.id)),decisions:o.ontology.decisions}))}if(e==="planning-task-submit"){const g=A();if(JSON.stringify(s.plan)!==JSON.stringify(g))throw Error("提交参数必须与当前已生成的完整规划一致；请重新载入参数模板。");return Ln(n,t,"提交当前规划",a,r)}if(e.startsWith("planning-task-")||e==="planning-selection-save"){const g=F.tasks[String(s.taskId)];if(!g||!g.shared&&g.ownerConversationKey!==i)throw Error("任务不存在或不属于当前会话。");if(e==="planning-task-cancel"){const f=Ln(n,t,`取消任务${g.taskId}`,a,r);return de(f,e,s,{taskId:g.taskId,accepted:!1,reason:f.reply},f.status),fe(f)}if(o.task=J(g),o.plan=J(g.plan),e==="planning-selection-save"){if(g.status!=="SUCCEEDED")throw Error("任务完成后才可确认结果窗口选择。");const f=I("windowIds");if(!f.length||f.some(p=>!g.plan.windows.some(m=>m.id===p)))throw Error("请选择当前任务结果中存在的窗口。");return g.plan.recommendedWindowId=f[0],g.plan.revision++,o.plan=J(g.plan),o.task=J(g),c({taskId:g.taskId,selectedWindowIds:f,revision:g.plan.revision})}return e==="planning-task-result"&&(o.artifacts=J(g.artifacts)),c(e==="planning-task-status"?{taskId:g.taskId,status:g.status,progress:g.status==="SUCCEEDED"?100:50}:{taskId:g.taskId,status:g.status,plan:g.plan,artifacts:g.artifacts})}if(e==="planning-artifact-export")return o.plan=J(A()),o.artifacts=pt(o.plan,r,o.ontology),c({files:o.artifacts.map(g=>({name:g.name,size:g.size}))},`### 规划结果已导出

${o.plan.targetName}的窗口CSV与规划报告已生成，可在下方下载。`);throw Error("当前工具尚未配置可执行的业务行为。")}function Ts(n,t){var i,d;const e=F.pending[n];if(!e)return(i=F.results[n])!=null&&i.approval?{conflict:"该确认已处理，不能覆盖终态决定。"}:void 0;const s=J(e.response),a=s.approval,r=F.conversations[e.conversationKey];if(a.status=t?"APPROVED":"REJECTED",s.status=t?"SUCCEEDED":"REJECTED",t&&a.action==="SUBMIT"&&s.plan){const o=`DEMO-OBS-${String(++F.sequence).padStart(4,"0")}`,u=pt(s.plan,n,s.ontology),c={taskId:o,status:"SUCCEEDED",plan:J(s.plan),sourceRunId:n,stages:["SUBMITTED","RUNNING","SUCCEEDED"],artifacts:u,ownerConversationKey:e.conversationKey,ontology:J(s.ontology)};F.tasks[o]=c,r.lastTaskId=o,s.task=J(c),s.artifacts=u,s.toolCalls=s.toolCalls.filter(I=>I.status!=="WAITING_APPROVAL"),de(s,"planning-task-submit",{plan:s.plan,approved:!0},{taskId:o}),de(s,"planning-task-status",{taskId:o},{status:"SUCCEEDED",stages:c.stages}),de(s,"planning-task-result",{taskId:o},{plan:c.plan}),de(s,"planning-selection-save",{taskId:o,windowIds:s.plan.windows.map(I=>I.id)},{saved:!0}),de(s,"planning-artifact-export",{taskId:o},{artifacts:u.map(I=>({name:I.name,size:I.size}))}),((d=s.ontology)==null?void 0:d.releaseRef.releaseId)===P.releaseRef.releaseId&&s.ontology.decisions.push({ruleId:"rule-approval",name:"任务提交人工确认",result:"HOLDS",reason:"冻结计划已获人工批准，演示任务受理并完成"}),c.ontology=J(s.ontology),s.task=J(c),s.reply=`规划任务${o}已完成。
${En(c.plan)}
已保存窗口选择并生成观测窗口CSV与规划报告。`}else if(t&&a.action==="CANCEL"&&a.taskId){const o=F.tasks[a.taskId];o&&(o.status="CANCELLED",o.stages.push("CANCELLED"),s.task=J(o)),de(s,"planning-task-cancel",{taskId:a.taskId},{status:"CANCELLED"}),s.reply=`任务${a.taskId}已取消。`}else s.reply="已拒绝该操作；没有受理任务，也没有产生业务调度效果。";return delete F.pending[n],delete r.pendingRunId,fe(s),{response:s}}function Gn(n){return[{id:"planning-input",nodeDefId:"planning-input",label:"目标与需求解析",status:"SUCCEEDED",duration:30},...n.toolCalls.map(t=>({id:t.id,nodeDefId:t.id,label:t.name,status:t.status,duration:120,input:t.input,output:t.output})),...n.approval?[{id:n.approval.nodeId,nodeDefId:n.approval.nodeId,label:"人工确认冻结计划",status:n.approval.status==="PENDING"?"WAITING_APPROVAL":n.approval.status==="APPROVED"?"SUCCEEDED":"REJECTED",duration:0}]:[],{id:"planning-output",nodeDefId:"planning-output",label:"规划解释与结果",status:n.status==="WAITING_APPROVAL"?"PENDING":["REJECTED","CANCELLED"].includes(n.status)?n.status:"SUCCEEDED",duration:80}]}function it(n){return[...Gn(n).map((t,e)=>({seq:String(e+1),type:"NODE_FINISHED",data:{nodeId:t.id,nodeDefId:t.nodeDefId,status:t.status}})),{seq:String(Gn(n).length+1),type:n.status==="WAITING_APPROVAL"?"RUN_WAITING_APPROVAL":"RUN_FINISHED",data:{status:["WAITING_APPROVAL","REJECTED","CANCELLED"].includes(n.status)?n.status:"SUCCEEDED",output:{text:n.reply}}}]}function Ss(n){const t=F.results[n];if(!t||t.status!=="WAITING_APPROVAL")return;t.status="CANCELLED",t.reply="运行已取消，冻结计划未被提交。",t.approval&&(t.approval.status="REJECTED");const e=F.pending[n];e&&delete F.conversations[e.conversationKey].pendingRunId,delete F.pending[n]}const te=JSON.parse(lt),le=n=>JSON.parse(JSON.stringify(n)),O={actorId:"operator-a",actors:le(te.actors),accounts:le(te.accounts),requests:{},conversations:{},responses:{},pending:{},audits:[],sequence:0,permissionRevision:1},Jt="ap-business-governance-v1";function Wt(n){n&&typeof n=="object"&&Object.assign(O,n)}try{if(typeof sessionStorage<"u"){const n=sessionStorage.getItem(Jt);n&&Wt(JSON.parse(n))}}catch{}function wn(){try{typeof sessionStorage<"u"&&sessionStorage.setItem(Jt,JSON.stringify(O))}catch{}}const bn=()=>O.actors.find(n=>n.id===O.actorId)??O.actors[0],dn=()=>new Date().toISOString();function Be(n,t,e){O.audits.unshift({id:"um-audit-"+ ++O.sequence,action:n,subjectId:t,actorId:bn().id,createdAt:dn(),detail:e}),wn()}const Cs=[["um-identity-resolve","识别当前业务账号",!0],["um-permissions-pull","拉取业务角色与权限",!0],["um-user-query","查询用户与部门",!0],["um-role-query","查询角色及互斥关系",!0],["um-ontology-check","本体规则校验",!0],["um-request-prepare","生成账号变更计划",!0],["um-request-submit","提交账号申请",!1],["um-approval-decide","审批账号申请",!1],["um-account-create","创建业务账号",!1],["um-role-assign","授予业务角色",!1],["um-request-readback","读回账号与授权",!0],["um-request-receipt","导出授权回执",!0]],cn=Cs.map(([n,t,e])=>({code:n,name:t,description:t,kind:"HTTP",type:"HTTP",status:"PUBLISHED",currentVersion:1,version:1,bizCategories:["USER_GOVERNANCE"],businessSystemCode:"user-mgmt",accessPolicy:{visibility:"BUSINESS",viewRoles:["UM_OPERATOR","UM_APPROVER","UM_SECURITY","VIEWER"],configureRoles:["UM_OPERATOR","UM_APPROVER","UM_SECURITY"],invokeRoles:n==="um-approval-decide"?["UM_APPROVER","UM_SECURITY"]:e?["UM_OPERATOR","UM_APPROVER","UM_SECURITY","VIEWER"]:["UM_OPERATOR"]},guard:{roles:[]},execution:{readonly:e,sideEffect:!e,idempotent:!0,cancellable:!1},readonly:e,sideEffect:!e,inputSchema:{type:"object",properties:{requestId:{type:"string"},accountId:{type:"string"},departmentId:{type:"string"}}},outputSchema:{type:"object"},endpoint:`mock://user-mgmt/${n}`,method:e?"GET":"POST"})),ht={planningDate:te.planningDate,clock:te.clock,tools:cn,examples:[{id:"um-catalog",title:"业务账号与权限",message:"展示用户、部门、角色、权限及当前业务身份"},{id:"um-create",title:"员工账号开通",message:"为A部门新员工陈新开通账号 chen.xin，授予业务分析员角色，有效期30天"},{id:"um-rules",title:"本体规则校验",message:"检查当前申请的本体规则和权限"},{id:"um-submit",title:"提交账号申请",message:"提交当前账号开通申请"},{id:"um-result",title:"结果与审计",message:"查询刚才申请的执行结果和审计"},{id:"um-export",title:"下载授权回执",message:"导出当前申请的授权回执"},{id:"um-conflict",title:"互斥角色阻断",message:"为A部门陈新开通账号 conflict.user，同时授予业务分析员和业务审批员角色"},{id:"um-scope",title:"跨部门阻断",message:"为B部门新员工开通账号 cross.user，授予业务分析员角色"},{id:"um-admin",title:"高权限双重审批",message:"为A部门陈新开通账号 privileged.user，授予业务管理员角色，有效期7天"},{id:"um-self",title:"申请人不能自审批",message:"检查由申请人审批自己的申请是否允许"},{id:"um-revoke",title:"审批后权限变化",message:"撤销申请人的角色授予权限，再继续审批"},{id:"um-last-admin",title:"最后管理员保护",message:"停用业务账号 business.admin"}]},Pt=[{id:"start",type:"START",name:"接收账号申请",next:"identity"},{id:"identity",type:"SKILL",name:"识别业务身份并拉取权限",skill:"um-permissions-pull",next:"policy"},{id:"policy",type:"SKILL",name:"本体前置规则校验",skill:"um-ontology-check",next:"prepare"},{id:"prepare",type:"SKILL",name:"生成变更计划",skill:"um-request-prepare",next:"department"},{id:"department",type:"APPROVAL",name:"部门负责人审批",next:"security",businessPolicy:{businessSystemCode:"user-mgmt",requiredPermissions:["um.request.approve"],roles:["UM_APPROVER"],checkTiming:"BEFORE_EXECUTION"}},{id:"security",type:"CONDITION",name:"高权限安全复核",expression:"request.privileged == true",nextTrue:"security-approval",nextFalse:"execute"},{id:"security-approval",type:"APPROVAL",name:"安全岗位复核",next:"execute",businessPolicy:{businessSystemCode:"user-mgmt",requiredPermissions:["um.security.approve"],roles:["UM_SECURITY"],checkTiming:"BEFORE_EXECUTION"}},{id:"execute",type:"SKILL",name:"重查权限并执行业务变更",skill:"um-account-create",next:"readback",businessPolicy:{businessSystemCode:"user-mgmt",requiredPermissions:["um.user.create","um.role.assign"],roles:["UM_OPERATOR"],semanticConditions:["um-rule-active","um-rule-permission","um-rule-revision"],checkTiming:"BEFORE_COMMIT"}},{id:"readback",type:"SKILL",name:"读回账号和角色",skill:"um-request-readback",next:"end"},{id:"end",type:"END",name:"输出授权回执"}],Ie={code:"user-governance-assistant",name:"账号开通与角色授权助手",displayName:"账号开通与角色授权助手",type:"FLOW",status:"PUBLISHED",domain:"用户管理",bizCategory:"USER_GOVERNANCE",tags:["账号开通","权限治理","流程"],description:"从业务权限拉取、本体规则到审批执行及结果读回的账号管理流程。",currentVersionNo:2,draftRev:3,draftId:"draft-user-governance-assistant-r3",createdAt:te.clock,updatedAt:te.clock,config:{modelBinding:{modelEntryId:"me-001"},promptSource:"DIRECT",systemPrompt:"根据业务系统的当前身份、角色、权限和部门范围处理账号开通。先拉取事实，再调用本体规则。生成变更计划后等待独立审批。审批恢复后重查发起人权限与目标修订，成功执行后读回业务账号和角色。缺事实或约束不满足时停止并说明原因。以Markdown输出流程、规则和结果，不以自然语言自述代替业务读回。",promptRefs:[],skillRefs:cn.map(n=>({code:n.code,version:1})),ontology:te.releaseRef,guard:{roles:[]},requiredLabels:["PUBLIC"],flowRef:"flow-user-governance"},definition:{definitionVersion:"1.0",trigger:"CHAT",entry:"start",inputs:[{key:"message",type:"STRING",required:!0,description:"账号开通与授权需求"}],outputs:[{key:"receipt",type:"JSON",description:"业务读回与审批回执"}],joins:[],executionRules:{parallel:!1,maxParallel:1,cyclesForbidden:!0},nodes:Pt,edges:Pt.flatMap(n=>{const t=n;return[t.next,t.nextTrue,t.nextFalse].filter(e=>!!e).map(e=>({from:n.id,to:e}))})}};function zt(n,t,e){const s=e.ontology??{},a=String(s.spaceCode??"")==="user-governance"||String(s.releaseId??"").startsWith("rel-user-governance-"),r=s.releaseId?{name:a?te.ontology.space.name:"所选本体",releaseRef:{spaceCode:String(s.spaceCode??""),releaseId:String(s.releaseId),version:String(s.version??""),contentDigest:String(s.contentDigest??"")},facts:a?te.ontology.instances.map(i=>({objectType:i.className,stableId:i.stableId,values:le(i.values)})):[],relations:a?["Account → belongsTo → Department","Account → assignedRole → Role → permits → Permission","ManagementRequest → ApprovalRecord → PolicyDecision"]:[],decisions:[]}:null;return{conversationId:n,runId:t,reply:"",status:"SUCCEEDED",toolCalls:[],ontology:r,artifacts:[],promptSnapshot:String(e.systemPrompt??Ie.config.systemPrompt),responseStyle:String(e.responseStyle??"detailed"),workflow:[]}}function ge(n,t,e,s,a="SUCCEEDED"){var r;n.toolCalls.push({id:n.runId+"-call-"+(n.toolCalls.length+1),skillCode:t,name:((r=cn.find(i=>i.code===t))==null?void 0:r.name)??t,input:le(e),output:le(s),status:a})}function Mn(n){return{subjectId:n.id,name:n.name,departmentId:n.departmentId,roles:n.roles,permissions:n.permissions,status:n.status,sourceSystem:"user-mgmt",revision:O.permissionRevision,observedAt:dn()}}function Kt(n,t){ge(n,"um-identity-resolve",{sourceSystem:"user-mgmt"},{subjectId:t.id,status:t.status}),ge(n,"um-permissions-pull",{subjectId:t.id},Mn(t))}function Ns(n,t){var s;const e=n.status==="WAITING_SECURITY_APPROVAL"?"security":"department";return[{id:"identity",label:"识别业务身份",status:"SUCCEEDED",result:(s=O.actors.find(a=>a.id===n.actorId))==null?void 0:s.name},{id:"permissions",label:"拉取业务角色与权限",status:"SUCCEEDED",result:"业务源 user-mgmt · 权限修订 "+O.permissionRevision},{id:"policy",label:"本体规则校验",status:t==="BLOCKED"?"BLOCKED":"SUCCEEDED"},{id:"prepare",label:"生成变更计划",status:t==="BLOCKED"?"PENDING":"SUCCEEDED"},{id:"department",label:"部门负责人审批",status:n.approvals.some(a=>a.stage==="department")?"SUCCEEDED":t==="WAITING_APPROVAL"&&e==="department"?"WAITING_APPROVAL":"PENDING",permission:"um.request.approve"},...n.roleIds.includes("ROLE_ADMIN")?[{id:"security",label:"安全岗位复核",status:n.approvals.some(a=>a.stage==="security")?"SUCCEEDED":t==="WAITING_APPROVAL"&&e==="security"?"WAITING_APPROVAL":"PENDING",permission:"um.security.approve"}]:[],{id:"execute",label:"重查权限并执行业务变更",status:t==="SUCCEEDED"&&n.status==="SUCCEEDED"?"SUCCEEDED":t==="BLOCKED"?"BLOCKED":"PENDING"},{id:"readback",label:"读回账号与角色",status:n.status==="SUCCEEDED"?"SUCCEEDED":"PENDING"}]}function An(n,t,e){var I,y,S;const s=O.actors.find(A=>A.id===t.actorId),a=O.accounts.find(A=>A.username===t.username),r=[],i=(A,T,v)=>r.push({ruleId:A,good:T,reason:v}),d=!!n.ontology&&n.ontology.releaseRef.spaceCode==="user-governance";i("um-rule-active",!!s&&s.status==="ACTIVE","业务主体 "+((s==null?void 0:s.name)??t.actorId)+" / "+((s==null?void 0:s.status)??"未登记")),i("um-rule-scope",!!s&&s.departmentId===t.departmentId,"操作人部门 "+((s==null?void 0:s.departmentId)??"未知")+" / 目标部门 "+t.departmentId);const o=t.action==="DISABLE_ACCOUNT"?["um.user.disable"]:t.action==="ASSIGN_ROLES"?["um.role.assign"]:["um.user.create","um.role.assign"];i("um-rule-permission",!!s&&o.every(A=>s.permissions.includes(A)),"实际写入前读取修订 "+O.permissionRevision+"，所需权限："+o.join("、")),i("um-rule-role-active",t.roleIds.every(A=>te.roles.some(T=>T.id===A&&T.status==="ACTIVE")),"请求角色："+t.roleIds.join("、"));const u=[...new Set([...(a==null?void 0:a.roleIds)??[],...t.roleIds])];if(i("um-rule-conflict",!u.some(A=>{var T;return(T=te.roles.find(v=>v.id===A))==null?void 0:T.conflictsWith.some(v=>u.includes(v))}),"分析员与审批员职责互斥；当前角色组合 "+u.join("、")),t.action==="DISABLE_ACCOUNT"){const A=O.accounts.filter(T=>T.status==="ACTIVE"&&T.roleIds.includes("ROLE_ADMIN"));i("um-rule-last-admin",!(a!=null&&a.roleIds.includes("ROLE_ADMIN")&&A.length<=1),"当前有效业务管理员 "+A.length+" 人")}i("um-rule-revision",t.action==="CREATE_ACCOUNT"?!a||a.id===t.resultAccountId:!!a&&a.revision===t.expectedRevision,t.action==="CREATE_ACCOUNT"?a?"账号已存在，需核对业务意图":"账号名尚未占用":"目标修订 "+(a==null?void 0:a.revision)+" / 计划修订 "+t.expectedRevision);const c=((I=n.ontology)==null?void 0:I.releaseRef.version)==="v2"?7:30;return i("um-rule-revision",t.validityDays>0&&t.validityDays<=c,"授权期限 "+t.validityDays+"天 / 本体 "+(((y=n.ontology)==null?void 0:y.releaseRef.version)??"未选择")+" 上限 "+c+"天"),n.ontology&&d&&(n.ontology.decisions=r.map(A=>{var T;return{ruleId:A.ruleId,name:((T=te.ontology.rules.find(v=>v.id===A.ruleId))==null?void 0:T.name)??A.ruleId,result:A.good?"HOLDS":"NOT_HOLDS",reason:A.reason}})),ge(n,"um-ontology-check",{requestId:t.id,stage:e,releaseRef:((S=n.ontology)==null?void 0:S.releaseRef)??null},{result:d&&r.every(A=>A.good)?"HOLDS":"NOT_HOLDS",checks:r,sourceRef:{sourceSystem:"user-mgmt",permissionRevision:O.permissionRevision},evaluatedAt:dn()}),[...d?[]:["本流程需要关联用户与权限治理本体"],...r.filter(A=>!A.good).map(A=>A.reason)]}function _e(n,t){var e;n.workflow=Ns(t,n.status),n.businessChange={...n.businessChange,requestId:t.id,action:t.action,target:t.username,department:((e=te.departments.find(s=>s.id===t.departmentId))==null?void 0:e.name)??t.departmentId,roles:t.roleIds.map(s=>{var a;return((a=te.roles.find(r=>r.id===s))==null?void 0:a.name)??s}),requester:t.actorId,validityDays:t.validityDays,status:t.status}}function Zt(n,t){var a;const e=O.accounts.find(r=>r.id===n.resultAccountId),s={requestId:n.id,status:n.status,account:e??null,approvals:n.approvals,ontology:(a=t.ontology)==null?void 0:a.releaseRef,permissionRevision:O.permissionRevision,workflow:t.workflow};return[{name:"账号授权回执.json",mediaType:"application/json",content:JSON.stringify(s,null,2)},{name:"账号授权清单.csv",mediaType:"text/csv",content:`\uFEFFrequestId,username,department,status,roles\r
`+[n.id,n.username,n.departmentId,n.status,n.roleIds.join("|")].join(",")}].map((r,i)=>({...r,id:t.runId+"-um-file-"+i,size:new TextEncoder().encode(r.content).length,downloadUrl:"data:"+r.mediaType+";charset=utf-8,"+encodeURIComponent(r.content)}))}function ye(n){if(n.responseStyle==="compact"&&n.status==="SUCCEEDED"&&n.businessChange){const t=n.businessChange;n.reply=`### 账号申请摘要

- 申请：\``+t.requestId+"`\n- 账号：`"+t.target+"`\n- 部门："+t.department+`
- 角色：`+t.roles.join("、")+`
- 状态：**`+t.status+`**

`+(t.status==="DRAFT"?"请选择“提交账号申请”，再由独立业务审批人确认。":"流程和规则依据见结构化结果。")}return O.responses[n.runId]=le(n),wn(),n}function rt(n){var y,S,A,T;const{appCode:t,conversationId:e,message:s,runId:a,config:r}=n,i=t+":"+e,d=(y=O.conversations)[i]??(y[i]={}),o=zt(e,a,r),u=bn();if(Kt(o,u),u.status!=="ACTIVE")return o.status="BLOCKED",o.reply="当前业务账号已停用，不能查询或执行业务操作。请切换到有效业务身份。",ye(o);const c=n.parameters;let I=O.requests[String((c==null?void 0:c.requestId)??d.requestId??"")];if(/撤销.*权限|收回.*权限/.test(s)){const v=O.actors.find(l=>l.id===((I==null?void 0:I.actorId)??u.id));v.permissions=v.permissions.filter(l=>l!=="um.role.assign"),O.permissionRevision++,Be("BUSINESS_PERMISSION_REVOKE",v.id,{permissionCode:"um.role.assign"}),o.reply=`### 业务权限已更新

已撤销 **`+v.name+"** 的 `um.role.assign`。\n\n审批恢复时会重新拉取业务权限并执行本体检查；已有批准不会绕过这次变化。"}else if(/自.*审批|申请人审批|申请人.*批准/.test(s))o.status="BLOCKED",o.reply=`### 审批职责检查

**申请人不能审批自己的申请。**

请切换到部门负责人李主任；涉及业务管理员角色时，还需安全复核员王安独立批准。`,o.ontology&&(o.ontology.decisions=[{ruleId:"um-rule-no-self-approval",name:"申请审批职责分离",result:"NOT_HOLDS",reason:"申请人与审批人必须是不同业务主体"}]);else if(/展示|目录|列出|哪些|有哪些/.test(s)&&!/开通|创建|授予|停用/.test(s))ge(o,"um-user-query",{departmentId:u.departmentId},{accounts:O.accounts.filter(v=>v.departmentId===u.departmentId),departments:te.departments}),ge(o,"um-role-query",{},{roles:te.roles}),o.reply=`## 业务账号与权限目录

当前业务身份：**`+u.name+"**。来源：业务用户管理系统；权限修订 **"+O.permissionRevision+`**。

| 账号 | 姓名 | 部门 | 状态 | 角色 |
|---|---|---|---|---|
`+O.accounts.filter(v=>v.departmentId===u.departmentId).map(v=>"| "+[v.username,v.name,v.departmentId,v.status,v.roleIds.join("、")||"无"].join(" | ")+" |").join(`
`)+`

### 角色

`+te.roles.map(v=>"- **"+v.name+"**（`"+v.id+"`）："+v.status+(v.privileged?"，需要安全复核":"")).join(`
`)+`

### 当前权限

`+u.permissions.map(v=>"`"+v+"`").join("、")+`

可从“员工账号开通”开始生成计划。`;else if(/结果|审计|回执|导出|下载/.test(s))if(!I)o.status="NEEDS_INPUT",o.reply="请先生成并提交一份账号申请。";else{_e(o,I);const v=O.accounts.find(l=>l.id===(I==null?void 0:I.resultAccountId));ge(o,"um-request-readback",{requestId:I.id},{request:le(I),account:v??null,audits:O.audits.filter(l=>l.subjectId===I.id)}),o.reply=`## 账号申请结果

- 申请：\``+I.id+"`\n- 状态：**"+I.status+"**\n- 目标账号：`"+I.username+"`\n- 审批记录："+I.approvals.length+` 条

`+(v?"业务读回：**"+v.name+" / "+v.status+"**，角色 `"+v.roleIds.join("、")+"`，修订 "+v.revision+"。":"尚未产生账号变更，等待必要审批或条件满足。"),I.status==="SUCCEEDED"&&(o.artifacts=Zt(I,o),o.task={taskId:I.id,status:"SUCCEEDED",stages:["PREPARED","APPROVED","EXECUTED","READBACK_VERIFIED"],artifacts:o.artifacts},ge(o,"um-request-receipt",{requestId:I.id},{files:o.artifacts.map(l=>({id:l.id,name:l.name,bytes:l.size}))}))}else if(/规则|本体|检查|校验/.test(s)&&!/开通|创建|停用/.test(s))if(!I)o.reply=`### 用户与权限治理规则

`+te.ontology.rules.map(v=>"- **"+v.name+"**："+v.description).join(`
`)+`

生成账号计划后，会结合当前业务权限和目标账号逐项判定。`;else{const v=An(o,I,"BEFORE_EXECUTION");o.status=v.length?"BLOCKED":"SUCCEEDED",_e(o,I),o.reply=`### 本体规则检查

`+(v.length?v.map(l=>"- "+l).join(`
`):"当前账号、部门、角色、权限及目标状态满足申请条件。")}else if(/提交|执行当前|确认这/.test(s))if(!I)o.status="NEEDS_INPUT",o.reply="请先说明目标员工、部门及角色，生成账号变更计划。";else if(I.status==="SUCCEEDED")o.reply="申请 `"+I.id+"` 已执行完成；相同业务意图不会重复创建账号。",_e(o,I);else{const v=Object.values(O.pending).find(g=>g.requestId===I.id);if(v)return le(v.response);const l=An(o,I,"BEFORE_APPROVAL");l.length?(I.status="BLOCKED",o.status="BLOCKED",o.reply=`### 申请被规则阻断

`+l.map(g=>"- "+g).join(`
`)):(I.status="WAITING_DEPARTMENT_APPROVAL",o.status="WAITING_APPROVAL",o.approval={runId:a,nodeId:"um-department-approval",status:"PENDING",summary:"为 "+I.name+"（"+I.username+"）"+(I.action==="DISABLE_ACCOUNT"?"停用账号":"开通/授予 "+I.roleIds.join("、")),action:"USER_GOVERNANCE"},o.reply=`## 申请已提交，等待部门审批

申请 \``+I.id+`\` 尚未产生业务写入。

请在“业务身份”切换为 **李主任 · A部门负责人** 后批准。`+(I.roleIds.includes("ROLE_ADMIN")?`

业务管理员角色还需要安全复核员王安独立复核。`:"")+`

批准后会重新读取申请人当前权限和目标状态，再执行业务变更。`,O.pending[a]={requestId:I.id,stage:"department",response:le(o)},ge(o,"um-request-submit",{requestId:I.id},{status:I.status,approvalNodeId:o.approval.nodeId}),Be("REQUEST_SUBMIT",I.id,{runId:a})),_e(o,I),O.pending[a]&&(O.pending[a].response=le(o))}else if(/开通|创建|授予|停用/.test(s)){const v=c?String(c.departmentId??u.departmentId):/B部门|数据应用部|dept-b/i.test(s)?"dept-b":"dept-a",l=c&&Array.isArray(c.roleIds)?c.roleIds.map(String):[];c||(/业务分析员|ROLE_ANALYST/i.test(s)&&l.push("ROLE_ANALYST"),/业务审批员|ROLE_APPROVER/i.test(s)&&l.push("ROLE_APPROVER"),/业务管理员|ROLE_ADMIN/i.test(s)&&l.push("ROLE_ADMIN"),!l.length&&!/停用/.test(s)&&l.push("ROLE_ANALYST"));const g=c?String(c.username??""):((S=s.match(/(?:账号|用户名)\s*([a-z][a-z0-9._-]+)/i))==null?void 0:S[1])??(/停用/.test(s)?"business.admin":"chen.xin"),f=O.accounts.find(E=>E.username===g),p=c?c.action:/停用/.test(s)?"DISABLE_ACCOUNT":/授予/.test(s)&&!/开通|创建/.test(s)&&f?"ASSIGN_ROLES":"CREATE_ACCOUNT";I={id:"UM-REQ-"+String(++O.sequence).padStart(4,"0"),actorId:u.id,action:p,username:g,name:c?String(p==="CREATE_ACCOUNT"?c.name??"":(f==null?void 0:f.name)??""):(f==null?void 0:f.name)??(/陈新/.test(s)?"陈新":"新业务员工"),employeeNo:c?String(p==="CREATE_ACCOUNT"?c.employeeNo??"":(f==null?void 0:f.employeeNo)??""):(f==null?void 0:f.employeeNo)??"EMP-"+String(300+O.sequence),departmentId:v,roleIds:c&&p==="DISABLE_ACCOUNT"?(f==null?void 0:f.roleIds)??[]:l.length?l:(f==null?void 0:f.roleIds)??[],validityDays:Number(c?c.validityDays??7:((A=s.match(/(?:有效期|期限)\s*(\d+)\s*天/))==null?void 0:A[1])??30),expectedRevision:(f==null?void 0:f.revision)??0,status:"DRAFT",sourceRunId:a,conversationKey:i,createdAt:dn(),approvals:[]},O.requests[I.id]=I,d.requestId=I.id;const m=An(o,I,"BEFORE_EXECUTION");m.length&&(I.status="BLOCKED",o.status="BLOCKED"),ge(o,"um-request-prepare",{requestId:I.id},{action:I.action,target:I.username,departmentId:I.departmentId,roleIds:I.roleIds,status:I.status}),_e(o,I),o.reply=`## 账号变更计划

| 项目 | 内容 |
|---|---|
| 申请编号 | \``+I.id+"` |\n| 目标账号 | `"+I.username+"` |\n| 姓名 | "+I.name+` |
| 部门 | `+(((T=te.departments.find(E=>E.id===v))==null?void 0:T.name)??v)+` |
| 角色 | `+I.roleIds.map(E=>{var R;return((R=te.roles.find(h=>h.id===E))==null?void 0:R.name)??E}).join("、")+` |
| 有效期 | `+I.validityDays+`天 |

`+(m.length?`### 规则阻断

`+m.map(E=>"- "+E).join(`
`):"规则检查通过。请选择 **提交账号申请**，再由独立业务审批人确认。"),Be("REQUEST_PREPARE",I.id,{status:I.status,target:I.username})}else o.status="NEEDS_INPUT",o.reply="可以查询业务账号和角色、为员工生成账号开通计划、提交审批、检查本体规则，或查询并下载授权回执。请选择上方业务示例。";return d.lastRunId=a,ye(o)}function Ds(n,t,e){var y;if(t==="um-approval-decide")throw Error("请通过当前申请的既有审批接口提交决定；不能直接调用技能绕过审批节点。");if(!cn.some(S=>S.code===t))throw Error("未找到用户治理技能 "+t);const s=bn(),a=zt(n.conversationId,n.runId,n.config),r=(S,A="BLOCKED")=>(a.status=A,a.reply=S,ye(a));if(s.status!=="ACTIVE")return r("当前业务账号已停用，请切换到有效业务身份。");if(e.releaseRef){const S=e.releaseRef,A=n.config.ontology;if(!A||["spaceCode","releaseId","version","contentDigest"].some(T=>S[T]!==A[T]))return r("releaseRef与当前应用所选本体正式版本不一致，请重新载入参数模板。")}if(t==="um-identity-resolve")return ge(a,t,{},Mn(s)),a.reply="当前业务账号："+s.name+"（"+s.id+"），部门 "+s.departmentId+"。",ye(a);if(Kt(a,s),t==="um-permissions-pull")return a.reply="业务源 user-mgmt 当前权限修订 "+O.permissionRevision+`：

角色：`+s.roles.join("、")+`
权限：`+s.permissions.join("、"),ye(a);const i=t==="um-role-query"?"um.role.read":t==="um-request-prepare"||t==="um-request-submit"||t==="um-account-create"?"um.request.create":t==="um-role-assign"?"um.role.assign":t==="um-request-receipt"?"file.download":"um.user.read";if(!s.permissions.includes(i))return r("当前业务身份缺少 "+i+"，请在业务源确认权限。");if(t==="um-user-query"||t==="um-role-query"){const S=String(e.departmentId??s.departmentId);if(t==="um-user-query"&&S!==s.departmentId)return r("当前业务身份不能查询其他部门的账号。");const A=String(e.keyword??"").trim().toLowerCase(),T=t==="um-user-query"?O.accounts.filter(v=>v.departmentId===S&&(!A||[v.id,v.username,v.name,v.employeeNo].some(l=>l.toLowerCase().includes(A)))):te.roles.filter(v=>!A||[v.id,v.name].some(l=>l.toLowerCase().includes(A)));return ge(a,t,e,t==="um-user-query"?{accounts:le(T),departments:te.departments.filter(v=>v.id===S)}:{roles:le(T)}),a.reply=(t==="um-user-query"?"业务账号":"业务角色")+"查询完成，共 "+T.length+" 条。\n\n```json\n"+JSON.stringify(T,null,2)+"\n```",ye(a)}if(t==="um-request-prepare"){const S=String(e.action??"CREATE_ACCOUNT");if(!["CREATE_ACCOUNT","ASSIGN_ROLES","DISABLE_ACCOUNT"].includes(S))return r("请选择有效的账号变更操作。","NEEDS_INPUT");if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(String(e.username??"")))return r("请填写有效的业务账号名。","NEEDS_INPUT");if(e.departmentId!==void 0&&e.departmentId!==s.departmentId)return r("当前业务身份不能为其他部门生成账号申请。");const A=O.accounts.find(T=>T.username===e.username);return S!=="CREATE_ACCOUNT"&&(!A||A.departmentId!==s.departmentId)?r("请从当前业务部门选择要变更的账号。","NEEDS_INPUT"):S==="CREATE_ACCOUNT"&&(!String(e.name??"").trim()||!String(e.employeeNo??"").trim())?r("创建账号需要填写员工姓名和员工编号。","NEEDS_INPUT"):S!=="DISABLE_ACCOUNT"&&(!Array.isArray(e.roleIds)||!e.roleIds.length||e.roleIds.some(T=>!te.roles.some(v=>v.id===T&&v.status==="ACTIVE")))?r("请从业务角色目录选择至少一个有效角色。","NEEDS_INPUT"):!Number.isInteger(Number(e.validityDays??7))||Number(e.validityDays??7)<1||Number(e.validityDays??7)>30?r("授权有效期必须为1至30天的整数。","NEEDS_INPUT"):rt({...n,message:"创建账号变更计划",parameters:{...e,action:S}})}const d=n.appCode+":"+n.conversationId,o=String(e.requestId??((y=O.conversations[d])==null?void 0:y.requestId)??""),u=O.requests[o];if(!u)return r("请先生成账号变更申请，并提供有效的 requestId。","NEEDS_INPUT");if(u.departmentId!==s.departmentId)return r("该申请不属于当前业务身份的部门。");if(t==="um-request-receipt"&&u.status!=="SUCCEEDED")return r("申请尚未执行完成，当前没有可导出的授权回执；请先完成必要审批。","NEEDS_INPUT");if(t==="um-request-submit"||t==="um-account-create"||t==="um-role-assign"){if(s.id!==u.actorId)return r("请由申请发起人的业务身份提交或执行当前申请。");if(u.status==="REJECTED")return r("该申请已拒绝；请重新生成变更计划。")}const c=O.accounts.find(S=>S.username===u.username);if(e.accountId&&e.accountId!==(c==null?void 0:c.id))return r("accountId与当前申请目标账号不一致。");if(t==="um-account-create"||t==="um-role-assign"){if(t==="um-account-create"&&u.action!=="CREATE_ACCOUNT")return r("当前申请不是创建账号操作。");if(t==="um-role-assign"&&u.action==="DISABLE_ACCOUNT")return r("停用申请不能授予角色。");for(const S of["username","name","employeeNo","departmentId"])if(e[S]!==void 0&&e[S]!==u[S])return r(S+"与已冻结申请不一致，请重新生成计划。");if(e.roleIds&&(!Array.isArray(e.roleIds)||JSON.stringify([...e.roleIds].sort())!==JSON.stringify([...u.roleIds].sort())))return r("roleIds与已冻结申请不一致，请重新生成计划。")}if(t==="um-ontology-check"){const S=An(a,u,"BEFORE_EXECUTION");return a.status=S.length?"BLOCKED":"SUCCEEDED",_e(a,u),a.reply=S.length?`规则校验未通过：
`+S.join(`
`):"当前申请的业务权限、部门范围、角色与目标状态满足规则。",ye(a)}const I=rt({...n,message:t==="um-request-submit"||t==="um-account-create"||t==="um-role-assign"?"提交当前账号申请":"查询当前申请结果和回执",parameters:{requestId:u.id}});return(t==="um-account-create"||t==="um-role-assign")&&!I.toolCalls.some(S=>S.skillCode===t)?(ge(I,t,e,{requestId:u.id,status:u.status,executed:!1,reason:u.status==="SUCCEEDED"?"批准计划已执行；本次仅返回既有结果":"须通过当前申请的必要审批；没有直接写入账号或角色"},I.status==="WAITING_APPROVAL"?"WAITING_APPROVAL":I.status),ye(I)):I}function hs(n,t){var d;const e=O.pending[n];if(!e)return(d=O.responses[n])!=null&&d.approval?{conflict:"该审批已处理，不能覆盖原决定。"}:void 0;const s=O.requests[e.requestId],a=le(e.response),r=bn(),i=e.stage==="department"?"um.request.approve":"um.security.approve";if(r.status!=="ACTIVE"||!r.permissions.includes(i))return{conflict:"当前业务身份没有"+i+"。请切换到对应审批岗位。"};if(r.id===s.actorId)return{conflict:"申请人不得审批自己的请求。"};if(e.stage==="department"&&r.departmentId!==s.departmentId)return{conflict:"审批人不属于申请部门。"};if(e.stage==="security"&&s.approvals.some(o=>o.actorId===r.id))return{conflict:"高权限申请需要另一位独立安全复核人。"};if(ge(a,"um-approval-decide",{requestId:s.id,stage:e.stage,approved:t},{actorId:r.id,permission:i,decidedAt:dn()}),!t)s.status="REJECTED",a.status="REJECTED",a.approval.status="REJECTED",a.reply=`### 申请已拒绝

\``+s.id+"` 未执行业务变更。",delete O.pending[n],Be("REQUEST_REJECT",s.id,{actorId:r.id});else{s.approvals.push({actorId:r.id,stage:e.stage,at:dn()}),Be("REQUEST_APPROVE",s.id,{stage:e.stage,reviewer:r.id});const o=An(a,s,"BEFORE_COMMIT");if(o.length)s.status="BLOCKED",a.status="BLOCKED",a.approval.status="APPROVED",a.reply=`### 审批后复核阻断

审批意见已保存，但未进行业务写入：

`+o.map(u=>"- "+u).join(`
`)+`

请恢复有效业务权限或重新生成计划。`,delete O.pending[n],Be("EXECUTION_BLOCKED",s.id,{failures:o});else if(e.stage==="department"&&s.roleIds.includes("ROLE_ADMIN"))s.status="WAITING_SECURITY_APPROVAL",a.status="WAITING_APPROVAL",a.approval={runId:n,nodeId:"um-security-approval",status:"PENDING",summary:"独立安全复核："+s.username+" 的业务管理员授权",action:"USER_GOVERNANCE"},a.reply=`### 部门审批已通过

申请包含业务管理员角色，尚未写入。请切换到 **王安 · 安全复核员** 后完成第二次独立批准。`,O.pending[n]={requestId:s.id,stage:"security",response:le(a)};else{let u=O.accounts.find(I=>I.username===s.username);const c=u?le(u):null;s.action==="CREATE_ACCOUNT"?(u={id:"biz-created-"+ ++O.sequence,username:s.username,name:s.name,employeeNo:s.employeeNo,departmentId:s.departmentId,status:"ACTIVE",roleIds:[...s.roleIds],revision:1},O.accounts.push(u),ge(a,"um-account-create",{requestId:s.id,username:s.username,departmentId:s.departmentId},{accountId:u.id,revision:u.revision})):u&&(u.revision++,s.action==="DISABLE_ACCOUNT"?u.status="DISABLED":u.roleIds=[...new Set([...u.roleIds,...s.roleIds])]),u&&s.action!=="DISABLE_ACCOUNT"&&ge(a,"um-role-assign",{requestId:s.id,accountId:u.id,roleIds:s.roleIds},{roleIds:u.roleIds,revision:u.revision}),s.resultAccountId=u==null?void 0:u.id,s.status="SUCCEEDED",a.status="SUCCEEDED",a.approval.status="APPROVED",delete O.pending[n],ge(a,"um-request-readback",{accountId:u==null?void 0:u.id},{account:le(u),matchesPlan:!!u&&u.username===s.username}),a.ontology&&a.ontology.decisions.push({ruleId:"um-rule-readback",name:"业务结果读回",result:"HOLDS",reason:"从业务账号集合重新查询，账号状态和授权角色与批准计划一致"}),_e(a,s),a.businessChange={...a.businessChange,before:c,after:le(u)},a.artifacts=Zt(s,a),a.task={taskId:s.id,status:"SUCCEEDED",stages:["PREPARED","APPROVED","EXECUTED","READBACK_VERIFIED"],artifacts:a.artifacts},a.reply=`## 账号变更已完成

- 申请：\``+s.id+"`\n- 账号：**"+(u==null?void 0:u.username)+" / "+(u==null?void 0:u.name)+`**
- 部门：`+(u==null?void 0:u.departmentId)+`
- 状态：**`+(u==null?void 0:u.status)+`**
- 角色：`+(u==null?void 0:u.roleIds.join("、"))+`
- 业务修订：`+(u==null?void 0:u.revision)+`

部门审批`+(s.roleIds.includes("ROLE_ADMIN")?"及独立安全复核":"")+`已记录；执行前重新拉取权限，本体复核通过，业务结果已读回。

可以下载授权回执或继续查询业务目录。`,Be("BUSINESS_CHANGE_READBACK",s.id,{accountId:u==null?void 0:u.id,before:c,after:u})}}return _e(a,s),O.pending[n]&&(O.pending[n].response=le(a)),ye(a),{response:a}}function mt(n){const t=O.responses[n];return((t==null?void 0:t.workflow)??[]).map(e=>({id:e.id,nodeDefId:e.id,label:e.label,status:e.status,duration:e.status==="SUCCEEDED"?100:0,output:e.result??e.reason??"",errorMessage:e.status==="BLOCKED"?t==null?void 0:t.reply:void 0}))}function Ot(n){const t=O.responses[n];return t?[{type:"run.started",runId:n},...mt(n).map(e=>({type:"node.status",runId:n,nodeId:e.id,status:e.status})),{type:t.status==="WAITING_APPROVAL"?"run.waiting":t.status==="SUCCEEDED"?"run.completed":"run.failed",runId:n,status:t.status}]:[]}function kn(n,t,e){const s=e??{};if(n==="/admin/business/context"&&t==="GET")return{data:{sceneVersion:te.sceneVersion,currentActorId:O.actorId,actors:le(O.actors),users:le(O.accounts),departments:te.departments,roles:te.roles,permissions:te.permissions,requests:Object.values(O.requests),audits:O.audits,permissionRevision:O.permissionRevision,businessSystem:te.businessSystem}};if(n==="/admin/business/actor"&&t==="POST"){const a=O.actors.find(r=>r.id===s.actorId);return a?(O.actorId=a.id,wn(),{data:Mn(a)}):{status:404,error:"业务身份不存在"}}if(n==="/admin/business/permissions"&&t==="POST"){const a=O.actors.find(i=>i.id===s.actorId),r=String(s.permissionCode??"");return!a||!te.permissions.some(i=>i.code===r)?{status:400,error:"请选择有效业务主体和权限"}:(a.permissions=s.enabled===!0?[...new Set([...a.permissions,r])]:a.permissions.filter(i=>i!==r),O.permissionRevision++,Be("BUSINESS_PERMISSION_CHANGE",a.id,{permission:r,enabled:s.enabled}),{data:Mn(a)})}if(n==="/admin/business/reset"&&t==="POST")return Object.assign(O,{actorId:"operator-a",actors:le(te.actors),accounts:le(te.accounts),requests:{},conversations:{},responses:{},pending:{},audits:[],sequence:0,permissionRevision:1}),wn(),{data:{restored:!0}}}const Ps=["targetId","targetType","planningDate","maxCloudPercent","onlySatelliteId","epochAgeDays"],Yt=n=>{if(!n||typeof n!="object"||Array.isArray(n))throw Error("运行参数必须是JSON对象");return n},Os=[["planning-file-upload","上传规划文件",!1],["planning-file-parse","解析目标与规划约束",!0],["planning-file-export","导出规划文件",!0],["planning-file-download","下载规划制品",!0]],ln=Os.map(([n,t,e])=>({code:n,name:t,description:`${t}；目标CSV当前仅支持一条目标，多目标请分批上传。`,type:"HTTP",kind:"HTTP",status:"PUBLISHED",currentVersion:1,version:1,bizCategories:["GENERAL","SPACE_OBSERVATION"],businessSystemCode:"SPACE",accessPolicy:{visibility:"PUBLIC",viewRoles:["ADMIN","EDITOR","SPACE_OPERATOR","VIEWER"],configureRoles:["ADMIN","EDITOR"],invokeRoles:["ADMIN","EDITOR","SPACE_OPERATOR"]},endpoint:`mock://planning-files/${n}`,method:e?"GET":"POST",timeoutMs:3e4,execution:{readonly:e,sideEffect:!e,idempotent:!0,cancellable:!0},readonly:e,sideEffect:!e,guard:{roles:[],attributes:{}},inputSchema:{type:"object",properties:{artifactId:{type:"string"},parameters:{type:"object"}}},outputSchema:{type:"object",properties:{artifactId:{type:"string"},parameters:{type:"object"},bytes:{type:"integer"}}}}));function Qt(n){return[...P.assets.groundTargets,...P.assets.spaceTargets].find(t=>String(t.id)===String(n))}function Kn(n,t,e=1/0){if(typeof n!="number"&&typeof n!="string"||String(n).trim()==="")throw Error(`${t}必须是有效数值`);const s=Number(n);if(!Number.isFinite(s)||s<0||s>e)throw Error(`${t}必须在0至${e===1/0?"有限正数":e}之间`);return s}function ft(n,t){const e={};for(const s of Ps)n[s]!==void 0&&n[s]!==""&&(e[s]=n[s]);if(n.satelliteId!==void 0&&n.satelliteId!==""){if(e.onlySatelliteId!==void 0&&Number(e.onlySatelliteId)!==Number(n.satelliteId))throw Error(`${t}的satelliteId与onlySatelliteId冲突`);e.onlySatelliteId=n.satelliteId}if(e.targetId!==void 0){const s=Qt(e.targetId);if(!s)throw Error(`${t}的目标ID ${String(e.targetId)}不存在，请使用目标目录中的ID`);if(e.targetId=s.id,e.targetType!==void 0&&String(e.targetType).toUpperCase()!==s.type)throw Error(`${t}的targetType与目标ID不匹配`);e.targetType=s.type}else if(e.targetType!==void 0&&(e.targetType=String(e.targetType).toUpperCase(),!["GROUND","SPACE"].includes(String(e.targetType))))throw Error(`${t}的targetType仅支持GROUND或SPACE`);if(e.planningDate!==void 0){const s=String(e.planningDate);if(!/^\d{4}-\d{2}-\d{2}$/.test(s)||!Number.isFinite(Date.parse(s))||new Date(s).toISOString().slice(0,10)!==s)throw Error(`${t}的planningDate必须为有效的YYYY-MM-DD日期`);if(s!==P.planningDate)throw Error(`当前场景仅提供${P.planningDate}的规划窗口，请调整日期后重试`);e.planningDate=s}if(e.maxCloudPercent!==void 0&&(e.maxCloudPercent=Kn(e.maxCloudPercent,`${t}的最大云量`,100)),e.epochAgeDays!==void 0&&(e.epochAgeDays=Kn(e.epochAgeDays,`${t}的星历年龄天数`)),e.onlySatelliteId!==void 0){const s=Kn(e.onlySatelliteId,`${t}的卫星ID`);if(!Number.isInteger(s)||!P.assets.satellites.some(a=>a.id===s))throw Error(`${t}的卫星ID ${s}不存在`);e.onlySatelliteId=s}return e}function Zn(n,t,e){for(const[s,a]of Object.entries(t)){if(n[s]!==void 0&&String(n[s])!==String(a))throw Error(`${e}与已有输入的${s}冲突（${String(n[s])} / ${String(a)}），请统一后重试`);n[s]=a}}function ks(n,t){const e=[];let s=[],a="",r=!1;for(let o=0;o<n.length;o++){const u=n[o];u==='"'?r&&n[o+1]==='"'?(a+='"',o++):r=!r:!r&&(u===","||u===`
`||u==="\r")?(s.push(a.trim()),a="",u!==","&&(s.some(Boolean)&&e.push(s),s=[],u==="\r"&&n[o+1]===`
`&&o++)):a+=u}if(r)throw Error(`${t}的CSV引号没有闭合`);if(s.push(a.trim()),s.some(Boolean)&&e.push(s),e.length<2)throw Error(`${t}至少需要表头和一条目标记录`);if(e.length>2)throw Error(`${t}包含多个目标；当前仅支持单目标规划，请每次保留一行目标并分批上传`);const i=e[0];if(new Set(i).size!==i.length||!["targetId","targetType","planningDate"].every(o=>i.includes(o)))throw Error(`${t}表头必须包含targetId,targetType,planningDate且不能重复`);if(e[1].length!==i.length)throw Error(`${t}的数据列数与表头不一致`);const d=Object.fromEntries(i.map((o,u)=>[o,e[1][u]]));if(["targetId","targetType","planningDate"].some(o=>!d[o]))throw Error(`${t}的目标ID、目标类型和日期不能为空`);return d}function kt(n){var i;const t={},e=[...P.assets.groundTargets,...P.assets.spaceTargets].filter(d=>{const o=String(d.id)==="GT-QH-01"?["青海湖"]:String(d.id)==="GT-PY-02"?["鄱阳湖"]:String(d.id)==="8890"?["STARLINK"]:["遥感目标02"];return[String(d.id),d.name,...o].some(u=>n.toUpperCase().includes(u.toUpperCase()))});if(e.length>1)throw Error("文本包含多个明确目标，请保留一个目标后再规划");e.length?(t.targetId=e[0].id,t.targetType=e[0].type):/对地|地面目标/.test(n)?t.targetType="GROUND":/对空|空间目标/.test(n)&&(t.targetType="SPACE");const s=[...new Set(n.match(/\d{4}-\d{2}-\d{2}/g)||[])];if(s.length>1)throw Error("文本包含多个规划日期，请明确一个日期");s.length&&(t.planningDate=s[0]);const a=[...n.matchAll(/云量[^\d]{0,8}(\d+(?:\.\d+)?)\s*%?/g)].map(d=>Number(d[1]));if(new Set(a).size>1)throw Error("文本包含不同的云量限制，请统一后重试");a.length&&(t.maxCloudPercent=a[0]);const r=(i=n.match(/(?:只用|仅用|卫星ID[：:]?)[^\d]{0,6}(\d+)/))==null?void 0:i[1];return r&&(t.onlySatelliteId=Number(r)),ft(t,"对话文本")}async function Xt(n){const t=await Mt(n);if(!t)throw Error("文件不存在，请重新上传实际文件。");const e=t.content.replace(/^\uFEFF/,"").trim();if(!e)throw Error(`${t.name}内容为空`);let s;if(/\.csv$/i.test(t.name)||t.mediaType.includes("csv"))s=ks(e,t.name);else if(/\.(json|txt)$/i.test(t.name)||t.mediaType.includes("json")||t.mediaType.startsWith("text/"))try{s=Yt(JSON.parse(e))}catch{throw Error(`${t.name}必须为JSON对象；星历参数TXT支持satelliteId、epochAgeDays及轨道参数，请使用下载模板`)}else throw Error(`${t.name}格式不支持，请上传单目标CSV、约束JSON或星历参数TXT`);const a=ft(s,t.name);if(!Object.keys(a).length)throw Error(`${t.name}未包含可用规划字段，请使用目标、约束或星历参数模板`);return{file:t,parameters:a}}async function ys(n){const t=typeof n.message=="string"?n.message:"",e=n.attachments===void 0?[]:n.attachments;if(!Array.isArray(e))throw Error("attachments必须是附件引用数组");const s=n.parameters===void 0?{}:Yt(n.parameters);if(!e.length&&!Object.keys(s).length)return{message:t,toolCalls:[],parameters:{},attachments:[]};if(!e.length&&/提交|下传|数传|本体|依据|规则|取消|结果|进度|导出|下载/.test(t)&&!/规划.*(目标|青海湖|鄱阳湖|STARLINK)/i.test(t))return{message:t,toolCalls:[],parameters:s,attachments:[]};const a={},r=[],i=[];Zn(a,ft(s,"运行参数"),"运行参数");for(const u of e){if(!u||typeof u.artifactId!="string"||!u.artifactId)throw Error("附件缺少artifactId，请重新上传");const{file:c,parameters:I}=await Xt(u.artifactId);Zn(a,I,`附件${c.name}`),r.push({artifactId:c.artifactId,name:c.name,mediaType:c.mediaType,bytes:c.bytes});for(const[y,S,A,T]of[["planning-file-upload","读取已上传规划文件",{artifactId:c.artifactId},{name:c.name,bytes:c.bytes,contentLoaded:!0}],["planning-file-parse","解析目标与规划约束",{artifactId:c.artifactId,name:c.name},{parameters:I,rows:1,supportedTargetCount:1}]])i.push({id:`${y}-${i.length+1}`,skillCode:y,name:S,status:"SUCCEEDED",input:A,output:T})}if(Zn(a,kt(t),"对话文本"),!a.targetId&&!kt(t).targetId)throw Error("附件仅包含约束；请在文本或目标CSV中指定一个地面或空间目标");const d=Qt(a.targetId);if(!d)throw Error("请选择有效的规划目标");if(a.targetType!==d.type)throw Error("目标类型与目标ID不一致，请核对文本和附件");a.planningDate??(a.planningDate=P.planningDate);const o=[`规划${d.type==="GROUND"?"地面目标":"空间目标"}${d.id}（${d.name}）`,`日期${String(a.planningDate)}`];return a.maxCloudPercent!==void 0&&o.push(`云量不超过${String(a.maxCloudPercent)}%`),a.onlySatelliteId!==void 0&&o.push(`只用卫星${String(a.onlySatelliteId)}`),a.epochAgeDays!==void 0&&o.push(`星历年龄${String(a.epochAgeDays)}天${Number(a.epochAgeDays)>14?"，星历过期":""}`),{message:`${t}

已核对文件及运行参数：${o.join("，")}。`,toolCalls:i,parameters:a,attachments:r}}const qe=n=>n&&typeof n=="object"&&!Array.isArray(n)?n:{},je=n=>JSON.parse(JSON.stringify(n)),es={"space-target-query":"planning-space-target-query","space-visibility-plan":"planning-space-plan","space-visibility-submit":"planning-task-submit","space-visibility-status":"planning-task-status","space-visibility-result":"planning-task-result"},Ls=n=>n.startsWith("planning-")||n.startsWith("um-")||Object.hasOwn(es,n)||["orbit_query","telemetry_fetch","payload_compat"].includes(n);function ns(n,t){const e=(s,a)=>{if(a.toolParameterRevision===3)return;const r=qe(a.inputSchema),i=Object.keys(qe(r.properties));if(!(!i.length||i.length>12&&i.includes("observerSatelliteIds")&&i.includes("taskId")||i.length===3&&i.every(u=>["requestId","accountId","departmentId"].includes(u))||i.length===2&&i.includes("artifactId")&&i.includes("parameters")))return;const o=As(s.code,r);a.inputSchema=o.inputSchema,a.parameterTemplate=o.parameterTemplate,a.toolParameterRevision=3};for(const s of n.filter(a=>Ls(a.code))){e(s,s),Object.hasOwn(t,s.code)||(t[s.code]=Array.from({length:Number(s.currentVersion??s.version??1)},(a,r)=>({...je(s),version:r+1,versionNo:r+1,status:"PUBLISHED",definition:je(s)})));for(const a of t[s.code])e(s,a),a.definition&&e(s,qe(a.definition))}}function yt(n,t,e,s=""){ns(t,e);const a=Array.isArray(n.config.skillRefs)?n.config.skillRefs:[],r=F.conversations[`${n.code}:${s}`],i=O.conversations[`${n.code}:${s}`],d=i!=null&&i.requestId?O.requests[i.requestId]:void 0,o=O.actors.find(S=>S.id===O.actorId),u=Object.values(F.results).filter(S=>S.conversationId===s),c=u.flatMap(S=>S.artifacts).at(-1),I=u.flatMap(S=>S.toolCalls).map(S=>qe(S.input).artifactId).filter(Boolean).at(-1),y=r!=null&&r.lastTaskId?F.tasks[r.lastTaskId]:void 0;return a.flatMap(S=>{var E,R;const A=t.find(h=>h.code===S.code),T=Number(S.version??S.versionNo);if(!A||["DISABLED","ARCHIVED"].includes(A.status??""))return[];const v=((E=e[A.code])==null?void 0:E.find(h=>Number(h.versionNo??h.version)===T))??(Number(A.currentVersion??A.version)===T?A:void 0);if(!v||["DRAFT","DISABLED","ARCHIVED"].includes(String(v.status)))return[];const l={...A,...v,...qe(v.definition)},g=je(qe(l.inputSchema));Object.keys(g).length||Object.assign(g,{type:"object",properties:{}});const f=xt(g),p=qe(g.properties),m={planId:(R=r==null?void 0:r.plan)==null?void 0:R.id,plan:r==null?void 0:r.plan,taskId:r==null?void 0:r.lastTaskId,windowIds:y==null?void 0:y.plan.windows.slice(0,1).map(h=>h.id),requestId:d==null?void 0:d.id,accountId:d==null?void 0:d.resultAccountId,departmentId:(d==null?void 0:d.departmentId)??(o==null?void 0:o.departmentId),artifactId:(c==null?void 0:c.id)??I,releaseRef:n.config.ontology};for(const[h,L]of Object.entries(m))Object.hasOwn(p,h)&&L!==void 0&&(f[h]=je(L));return[{code:A.code,name:A.name,version:T,description:String(l.description??""),inputSchema:g,parameterTemplate:f}]})}function Vs(n,t){if(!t||typeof t!="object"||Array.isArray(t))throw Error("技能参数必须为对象。");const e=t,s=qe(n.inputSchema.properties);if(n.inputSchema.additionalProperties===!1&&Object.keys(e).some(a=>!Object.hasOwn(s,a)))throw Error("参数包含当前技能未声明的字段，请重新载入模板。");return rs(n.inputSchema,e)}async function Us({app:n,tool:t,parameters:e,conversationId:s,runId:a}){if(t.code.startsWith("um-"))return Ds({appCode:n.code,config:n.config,conversationId:s,runId:a},t.code,e);const r=es[t.code]??t.code;let i;if(r.startsWith("planning-file-"))if(r==="planning-file-export")i=Dt(n.code,n.config,"planning-artifact-export",e,s,a);else{const d=await Mt(String(e.artifactId));if(!d)throw Error("文件不存在，请先上传文件或生成结果制品。");let o={artifactId:d.artifactId,name:d.name,downloadUrl:d.downloadUrl,bytes:d.bytes};r==="planning-file-parse"&&(o=(await Xt(d.artifactId)).parameters),r==="planning-file-upload"&&(o={artifactId:d.artifactId,name:d.name,bytes:d.bytes,sha256:d.sha256}),i={runId:a,conversationId:s,status:"SUCCEEDED",reply:`### ${t.name}

\`\`\`json
${JSON.stringify(o,null,2)}
\`\`\``,ontology:null,artifacts:[],promptSnapshot:String(n.config.systemPrompt??""),toolCalls:[]},r==="planning-file-download"&&(i.artifacts=[{id:d.artifactId,name:d.name,mediaType:d.mediaType,content:d.content,size:d.bytes,downloadUrl:d.downloadUrl}]),i.toolCalls.push({id:a+"-tool",skillCode:t.code,name:t.name,input:e,output:r==="planning-file-download"?{artifactId:d.artifactId,name:d.name,downloadUrl:d.downloadUrl}:o,status:"SUCCEEDED"})}else if(r.startsWith("planning-"))i=Dt(n.code,n.config,r,e,s,a);else{const d=P.assets.satellites.find(u=>u.id===e.satelliteId);let o;if(r==="orbit_query"){if(!d)throw Error("卫星不在当前目录中。");o={satelliteId:d.id,name:d.name,noradCatId:d.noradCatId,orbitAltitudeKm:d.orbitAltitudeKm,epoch:P.clock}}else if(r==="telemetry_fetch"){if(!d)throw Error("卫星不在当前目录中。");const u=Date.parse(String(e.startTime)),c=Date.parse(String(e.endTime));if(!Number.isFinite(u)||!Number.isFinite(c)||c<=u)throw Error("遥测时间范围无效。");const I=Array.isArray(e.parameterCodes)?e.parameterCodes.map(String):["batteryVoltage","temperature"];o={satelliteId:d.id,items:Array.from({length:3},(y,S)=>({time:new Date(u+(c-u)*S/3).toISOString(),values:Object.fromEntries(I.map(A=>[A,/temperature/i.test(A)?22+S:28+S/10]))}))}}else r==="payload_compat"?o={satelliteId:e.satelliteId,payloadType:e.payloadType,compatible:!!(d!=null&&d.supportedPayloads.includes(String(e.payloadType)))}:o={skillCode:t.code,version:t.version,parameters:je(e),result:"已按所选技能版本完成处理"};i={runId:a,conversationId:s,status:"SUCCEEDED",reply:`### ${t.name}

\`\`\`json
${JSON.stringify(o,null,2)}
\`\`\``,toolCalls:[{id:a+"-tool",skillCode:t.code,name:t.name,input:e,output:o,status:"SUCCEEDED"}],ontology:null,artifacts:[],promptSnapshot:String(n.config.systemPrompt??"")}}for(const d of i.toolCalls)(d.skillCode===r||t.code==="planning-file-export"&&d.skillCode==="planning-artifact-export")&&(d.skillCode=t.code,d.name=t.name,d.input=je(e));return i.toolCalls.some(d=>d.skillCode===t.code)||i.toolCalls.unshift({id:a+"-selected",skillCode:t.code,name:t.name,input:je(e),output:{status:i.status},status:i.status}),Object.assign(i,{selectedTool:{code:t.code,version:t.version},parameters:je(e)}),i}const Ee=n=>JSON.parse(JSON.stringify(n)),Ce=()=>new Date().toISOString();function _s(n,t,e,s,a){var u,c,I,y,S,A,T,v,l,g;const r=f=>new Response(JSON.stringify({code:"OK",message:"",data:f}),{headers:{"Content-Type":"application/json"}}),i=(f,p)=>new Response(JSON.stringify({code:f===409?"CONFLICT":f===404?"NOT_FOUND":f===405?"METHOD_NOT_ALLOWED":"INVALID_ARGUMENT",message:p,data:null}),{status:f,headers:{"Content-Type":"application/json"}}),d=f=>(a.persist(),r(f));let o;if(o=n.match(/^\/admin\/debug-inputs(?:\/([^/]+))?(?:\/(promote))?$/)){const f=a.debugInputs,p=o[1]?f.find(m=>String(m.id)===o[1]):void 0;if(o[1]&&!p)return i(404,"命名输入不存在");if(t==="GET")return r(p??{items:f.filter(m=>(!s.get("appDraftId")||m.appDraftId===s.get("appDraftId"))&&Number(m.id)>Number(s.get("afterId")??0)).slice(0,Number(s.get("limit")??20)),hasMore:!1,nextAfterId:0});if(o[2]){if(t!=="POST")return i(405,"仅接受POST");if((e==null?void 0:e.revision)!==void 0&&e.revision!==p.revision)return i(409,"输入修订已变化");const m=(e==null?void 0:e.datasetVersion)??a.nextId("dataset"),E=a.apps.find(h=>h.draftId===p.appDraftId),R=[{id:(e==null?void 0:e.caseId)??`case-${p.id}`,caseId:(e==null?void 0:e.caseId)??`case-${p.id}`,name:(e==null?void 0:e.name)??p.name,inputs:Ee(p.inputs),expected:Ee((e==null?void 0:e.expected)??{}),assertion:Ee((e==null?void 0:e.assertion)??"EXACT"),assertions:Ee((e==null?void 0:e.assertions)??[]),safety:(e==null?void 0:e.safety)===!0}];return H.datasets[m]?JSON.stringify(H.datasets[m])===JSON.stringify(R)?r({datasetVersion:m,caseCount:1}):i(409,"数据集版本已冻结，请使用新版本编码"):(H.datasets[m]=R,a.evaluationDatasets.unshift({id:m,name:(e==null?void 0:e.name)??p.name,version:1,datasetVersion:m,appCode:(E==null?void 0:E.code)??p.appCode,cases:R,caseCount:1,updatedAt:Ce()}),d({datasetVersion:m,caseCount:1}))}if(!o[1]&&t==="POST"){if(!(e!=null&&e.name)||!(e!=null&&e.inputs))return i(400,"名称和输入必填");const m=s.get("appDraftId")??e.appDraftId,E=a.apps.find(h=>h.draftId===m),R={id:Number(a.nextId("input").split("-").at(-1)),appDraftId:m,appCode:E==null?void 0:E.code,name:e.name,inputs:Ee(e.inputs),revision:1,createdAt:Ce()};return f.unshift(R),d(R)}return p&&(t==="PUT"||t==="DELETE")?Number((e==null?void 0:e.revision)??s.get("revision"))!==p.revision?i(409,"命名输入修订已变化"):(t==="DELETE"?f.splice(f.indexOf(p),1):Object.assign(p,Ee(e??{}),{revision:p.revision+1}),d(t==="DELETE"?null:p)):i(405,"不支持该命名输入操作")}if(o=n.match(/^\/admin\/onboarding-templates(?:\/([^/]+))?(?:\/(publish|disable)|\/versions\/(\d+)\/preflight)?$/)){const f=a.demoOnboardingTemplates,p=o[1]?f.find(m=>m.code===o[1]):void 0;if(o[1]&&!p)return i(404,"模板不存在");if(t==="GET")return r(p??f.filter(m=>(!s.get("q")||`${m.code} ${m.name}`.includes(s.get("q")))&&(!s.get("kind")||m.kind===s.get("kind"))));if(t!=="POST"&&t!=="PUT"&&t!=="DELETE")return i(405,"不支持该方法");if(p&&(e==null?void 0:e.expectedRev)!==void 0&&e.expectedRev!==p.currentRev)return i(409,"模板修订已变化");if(o[4])return!(e!=null&&e.document)||!(e!=null&&e.connection)?i(400,"请选择规格文档与连接引用"):(p.preflightPassed=!0,d({documentSha256:Te(e.document),operations:[{operationId:"query",method:"GET",path:"/query",supported:!0,reasons:[]}],warnings:[],checks:[{result:"PASS",detail:"演示规格与连接已核对"}],ready:!0,passed:!0,zeroCreated:!0}));if(o[3])return o[3]==="publish"&&!p.preflightPassed?i(409,"请先完成模板预检"):(p.status=o[3]==="publish"?"PUBLISHED":"DISABLED",p.updatedAt=Ce(),d(p));if(!p){if(!(e!=null&&e.code)||!(e!=null&&e.name))return i(400,"模板编码和名称必填");const m={...Ee(e),status:"DRAFT",currentRev:1,latest:{...Ee(e),templateRev:1,revision:1,versionNo:1},revisions:[{...Ee(e),templateRev:1,revision:1,versionNo:1}],createdAt:Ce(),updatedAt:Ce()};return f.unshift(m),d(m)}return t==="DELETE"?(f.splice(f.indexOf(p),1),d(null)):(Object.assign(p,Ee(e??{}),{currentRev:p.currentRev+1,status:"DRAFT",updatedAt:Ce()}),p.latest={...Ee(e??{}),templateRev:p.currentRev},(p.revisions??(p.revisions=[])).push(p.latest),d(p))}if(o=n.match(/^\/admin\/onboarding-sessions(?:\/([^/]+))?(?:\/(preview|cancel)|\/steps\/([^/]+)\/execute)?$/)){if(o[1]==="templates")return t==="GET"?r(a.demoOnboardingTemplates.filter(p=>p.status==="PUBLISHED"&&p.kind==="QUERY")):i(405,"只读目录");const f=o[1]?H.sessions.find(p=>p.sessionId===o[1]):void 0;if(o[1]&&!f)return i(404,"接入会话不存在");if(t==="GET")return r(f??{items:H.sessions});if(!f&&t==="POST"){const p=a.demoOnboardingTemplates.find(h=>h.code===(e==null?void 0:e.templateCode)&&h.status==="PUBLISHED");if(!p)return i(409,"模板未发布");const m=H.sessions.find(h=>h.requestId===(e==null?void 0:e.requestId));if(m)return m.appCode===(e==null?void 0:e.appCode)?r(m):i(409,"同一请求号不能用于不同应用");if(!(e!=null&&e.appCode)||!(e!=null&&e.appName))return i(400,"应用编码和名称必填");const E={fieldsBundle:Ee(((u=p.latest)==null?void 0:u.fieldsBundle)??{fields:[],mappings:{},returns:{}}),values:{},selectedOperations:[]},R={sessionId:a.nextId("onboarding"),requestId:e.requestId,rev:1,status:"DRAFT",templateCode:p.code,templateRev:e.templateRev??p.currentRev,appCode:e.appCode,appName:e.appName,plan:E,planSha256:Te(E),steps:["IMPORT_SKILLS","ASSEMBLE","VERIFY","PUBLISH"].map(h=>({step:h,status:"PENDING"})),choices:{models:a.modelEntries.map(h=>({id:h.id,label:h.displayName})),connections:[],operations:[]}};return H.sessions.unshift(R),d(R)}if(!f||!["POST","PUT"].includes(t))return i(405,"不支持该方法");if((e==null?void 0:e.expectedRev)!==f.rev)return i(409,"会话修订已变化，请刷新");if(o[2]==="preview")return r({ready:!!f.plan.modelEntryId,missing:f.plan.modelEntryId?[]:[{code:"MODEL_REQUIRED",message:"请选择模型"}],operations:f.plan.selectedOperations.map(p=>({operationId:p,supported:!0}))});if(o[2]==="cancel")return f.status="CANCELLED",f.rev++,d(f);if(o[3]){if((e==null?void 0:e.planSha256)!==f.planSha256)return i(409,"计划摘要已变化");const p=f.steps.find(R=>R.step===o[3]);if(!p)return i(404,"会话步骤不存在");const m=f.steps.indexOf(p);if(m>0&&f.steps[m-1].status!=="SUCCEEDED")return i(409,"请先完成前一步骤");if(f.status==="CANCELLED")return i(409,"会话已取消");if(p.status==="SUCCEEDED")return r(f);let E=a.apps.find(R=>R.code===f.appCode);if(o[3]==="IMPORT_SKILLS"){const R=f.plan.selectedOperations.map((h,L)=>{const N=`${f.appCode}-${h}`.replace(/[^a-zA-Z0-9_-]/g,"-");return a.skills.some(D=>D.code===N)||a.skills.unshift({code:N,name:h,status:"PUBLISHED",currentVersion:1,kind:"HTTP",inputSchema:{type:"object"},outputSchema:{type:"object"},execution:{readonly:!0,sideEffect:!1}}),{operationId:h,code:N,version:1,skillVersionId:8e3+L,sha256:Te(h),status:"PUBLISHED",workspacePath:`/skills/${N}`}});p.receipt={drafts:R,missing:[],sourceResolved:!0}}if(o[3]==="ASSEMBLE"&&(E||(E={code:f.appCode,name:f.appName,displayName:f.appName,type:"CHAT",status:"DRAFT",currentVersionNo:null,draftRev:1,draftId:`draft-${f.appCode}-r1`,bizCategory:"GENERAL",config:{modelBinding:{modelEntryId:f.plan.modelEntryId},skillRefs:f.steps[0].receipt.drafts.map(R=>({code:R.code,version:R.version}))},createdAt:Ce(),updatedAt:Ce()},a.apps.unshift(E)),f.application={appCode:E.code,draftRev:E.draftRev,status:E.status,currentVersionNo:E.currentVersionNo},p.receipt={...f.application}),o[3]==="VERIFY"){if(!((I=(c=E==null?void 0:E.config)==null?void 0:c.modelBinding)!=null&&I.modelEntryId))return i(409,"尚未选择模型");p.receipt={passed:!0,checks:[{result:"PASS",detail:"演示资源引用已解析"}]}}if(o[3]==="PUBLISH"){if(!E)return i(409,"应用尚未组装");E.currentVersionNo=Number(E.currentVersionNo??0)+1,E.status="PUBLISHED",((y=H.versions)[S=E.code]??(y[S]=[])).unshift({versionNo:E.currentVersionNo,snapshot:Ee(E),snapshotDigest:Te(E.config),status:"PUBLISHED",publishedAt:Ce(),publishedBy:"u-demo"}),f.status="PUBLISHED",f.application={appCode:E.code,draftRev:E.draftRev,status:E.status,currentVersionNo:E.currentVersionNo},f.handover={templateCode:f.templateCode,appCode:E.code,appVersion:E.currentVersionNo,launchPath:`/apps/${E.code}`},p.receipt=f.handover}return p.status="SUCCEEDED",f.rev++,d(f)}return t==="PUT"?["PUBLISHED","CANCELLED"].includes(f.status)?i(409,"终态会话不可修改"):!((A=e==null?void 0:e.plan)!=null&&A.fieldsBundle)||!Array.isArray(e.plan.selectedOperations)?i(400,"会话计划不完整"):(f.plan=Ee(e.plan),f.planSha256=Te(f.plan),f.rev++,d(f)):i(405,"不支持该方法")}if(n==="/admin/statistics"||n.startsWith("/admin/statistics/")){if(o=n.match(/^\/admin\/statistics\/runs\/([^/]+)$/)){const N=a.runs.find(D=>D.id===o[1]);return N?r({...N,errorSemantic:N.errorMessage??((T=N.error)==null?void 0:T.code)??"FAILED",message:N.errorMessage??((v=N.error)==null?void 0:v.message),ownerPath:`/admin/runs/${N.id}`,nodeSummary:{total:N.nodeCount??0},error:N.error??N.errorMessage}):i(404,"运行不存在")}if(n==="/admin/statistics/snapshots")return t==="GET"?r(Object.values(H.snapshots).map(N=>({snapshotId:N.snapshotId,filters:N.filters,createdAt:N.createdAt,active:!0}))):i(405,"只读目录");if(o=n.match(/^\/admin\/statistics\/snapshots\/([^/]+)$/))return H.snapshots[o[1]]?r(H.snapshots[o[1]]):i(404,"快照不存在");if(n!=="/admin/statistics"&&n!=="/admin/statistics/rebuild"&&n!=="/admin/statistics/runs")return i(404,"统计接口不存在");if(n==="/admin/statistics/rebuild"&&t!=="POST"||n!=="/admin/statistics/rebuild"&&t!=="GET")return i(405,"不支持该方法");const f=e??Object.fromEntries(s),p=a.runs.filter(N=>(!f.appCode||N.appCode===f.appCode)&&(!f.from||N.startedAt>=f.from)&&(!f.to||N.startedAt<f.to)&&(!f.callKind||(N.triggerType==="DEBUG"?"DEBUG":N.triggerType==="EVALUATION"?"EVALUATION":"FORMAL")===f.callKind));if(n.endsWith("/runs"))return r(p);const m=[];for(const N of p){const D=N.startedAt.slice(0,10),$=N.triggerType==="DEBUG"?"DEBUG":N.triggerType==="EVALUATION"?"EVALUATION":"FORMAL";let U=m.find(V=>V.date===D&&V.appCode===N.appCode&&V.callKind===$);U||(U={date:D,appCode:N.appCode,bizSystemCode:N.bizSystemCode??null,callKind:$,runs:0,succeeded:0,failed:0,cancelled:0,unfinished:0,averageDurationMs:0,p95DurationMs:0,confirmationCount:0,model:{calls:0,knownTokens:0,usageMissingCalls:0}},m.push(U)),U.runs++,U.succeeded+=+(N.status==="SUCCEEDED"),U.failed+=+(N.status==="FAILED"),U.cancelled+=+(N.status==="CANCELLED"),U.unfinished+=+!["SUCCEEDED","FAILED","CANCELLED","REJECTED"].includes(N.status),U.successRate=U.succeeded/U.runs,U.averageDurationMs=(U.averageDurationMs*(U.runs-1)+Number(N.duration??0))/U.runs,U.p95DurationMs=Math.max(U.p95DurationMs,N.duration??0),U.model.calls++,U.model.knownTokens+=Number(((l=N.tokenUsage)==null?void 0:l.input)??0)+Number(((g=N.tokenUsage)==null?void 0:g.output)??0),U.model.totalTokens=U.model.knownTokens,U.confirmationCount+=a.approvals.filter(V=>V.runId===N.id&&V.status!=="PENDING").length}const E=a.skills.filter(N=>N.executionCount>0).map(N=>({date:Ce().slice(0,10),appCode:f.appCode??"*",callKind:"FORMAL",skillCode:N.code,skillVersionId:N.currentVersion,calls:N.executionCount,mockCalls:N.executionCount,failed:0,successRate:N.successRate,p95DurationMs:N.averageDurationMs})),R=Number(f.page??1),h=Number(f.pageSize??20),L={filters:f,page:R,pageSize:h,groups:m.slice((R-1)*h,R*h),groupsTotal:m.length,skillGroups:E.slice((R-1)*h,R*h),skillGroupsTotal:E.length,failures:p.filter(N=>N.status==="FAILED").map(N=>({runId:N.id,appCode:N.appCode,errorSemantic:N.errorMessage??"FAILED",count:1,href:`/admin/statistics/runs/${N.id}`,semantic:N.errorMessage??"FAILED",message:N.errorMessage,detailPath:`/admin/statistics/runs/${N.id}`,ownerPath:`/admin/runs/${N.id}`})),consistency:"DEMO_LOCAL_STATE",sourceSnapshots:{mock:Ce()},createdAt:Ce(),snapshotId:a.nextId("statistics")};return n.endsWith("/rebuild")?(H.snapshots[L.snapshotId]=Ee(L),d(L)):r(L)}}function ts({calls:n,skills:t,skillVersions:e,skillRefs:s,actor:a}){var d;if(!n.length)return[];if(!a||a.status!=="ACTIVE")return["原业务调用身份不存在或已停用，不能调用技能"];const r=Array.isArray(a.roles)?a.roles:[],i=[];for(const o of n){const u=String(o.skillCode??o.code??""),c=o.version??o.versionNo??o.skillVersion,I=(s??[]).find(l=>l.code===u&&(c===void 0||Number(l.version??l.versionNo)===Number(c))),y=Number((I==null?void 0:I.version)??(I==null?void 0:I.versionNo));if(!u||!I||!Number.isInteger(y)||y<1){i.push(`技能 ${u||"（未指定）"} 未绑定到应用的精确版本`);continue}const S=t.find(l=>l.code===u);if(!S||["DISABLED","ARCHIVED"].includes(S.status)){i.push(`技能 ${u} 不存在或已停用`);continue}const T=e&&Object.hasOwn(e,u)?e[u].find(l=>Number(l.versionNo??l.version)===y):Number(S.currentVersion??S.version)===y?S:void 0;if(!T||["DISABLED","ARCHIVED"].includes(T.status)){i.push(`技能 ${u} 的绑定版本 ${y} 不存在或已停用`);continue}const v=S.accessPolicy??T.accessPolicy??((d=T.definition)==null?void 0:d.accessPolicy);v&&Object.hasOwn(v,"invokeRoles")&&(!Array.isArray(v.invokeRoles)||!v.invokeRoles.some(l=>r.includes(l)))&&i.push(`业务身份 ${a.name??a.id} 没有调用技能 ${u}@${y} 的角色权限`)}return[...new Set(i)]}const Ve=n=>JSON.parse(JSON.stringify(n)),me=()=>new Date().toISOString();function $s(n,t,e,s,a){var S,A,T,v;const r=l=>new Response(JSON.stringify({code:"OK",message:"",data:l}),{headers:{"Content-Type":"application/json"}}),i=(l,g)=>new Response(JSON.stringify({code:l===409?"CONFLICT":l===404?"NOT_FOUND":l===405?"METHOD_NOT_ALLOWED":"INVALID_ARGUMENT",message:g,data:null}),{status:l,headers:{"Content-Type":"application/json"}}),d=l=>(a.persist(),r(l)),o=l=>a.apps.find(g=>g.code===l);let u;function c(l,g,f=!1){var R,h,L,N,D,$;const p=a.nextId(f?"dbg":"run"),m=Ve((f?(R=a.flowStore[l.code])==null?void 0:R.definition:void 0)??l.definition??{entry:"start",nodes:[{id:"start",type:"START"},{id:"answer",type:"LLM"},{id:"end",type:"END"}],edges:[{from:"start",to:"answer"},{from:"answer",to:"end"}]}),E={id:p,runId:p,publicId:p,appCode:l.code,appName:l.name,status:"RUNNING",triggerType:f?"DEBUG":"MANUAL",caller:"u-demo",startedAt:me(),duration:0,nodeCount:m.nodes.length,tokenUsage:{input:300,output:120},versionNo:l.currentVersionNo??l.draftRev,versionRefs:{app:`${l.code}@${l.currentVersionNo??l.draftRev}`,skills:Object.fromEntries((((h=l.config)==null?void 0:h.skillRefs)??[]).map(U=>[U.code,U.version]))},definition:m,inputs:Ve(g),nodes:m.nodes.map((U,V)=>({id:U.id,nodeDefId:U.id,nodeInstanceId:`${p}-${V+1}`,label:U.name??U.id,type:U.type,status:"PENDING",output:null})),events:[],eventsWatermark:0,nextNodeId:m.entry??((L=m.nodes[0])==null?void 0:L.id),revision:1,pauseSeq:0,controls:{breakpoints:(e==null?void 0:e.breakpoints)??[],mocks:(e==null?void 0:e.mocks)??[]},variables:Ve(g),output:null,error:null};return E.businessActorId=(D=(N=a.businessContext)==null?void 0:N.call(a))==null?void 0:D.currentActorId,E.skillRefs=Ve((($=l.config)==null?void 0:$.skillRefs)??[]),a.runs.unshift(E),f&&a.debugRuns.unshift(E),y(E,f&&(e==null?void 0:e.mode)==="STEP"?"PAUSE":"RUN_TO_END"),E}function I(l,g,f){const p=++l.eventsWatermark;l.events.push({seq:p,type:g,payload:f,data:f,created_at:me()})}function y(l,g){var p,m,E,R,h,L,N,D,$,U,V,j,W,G,z;l.paused=null,l.status="RUNNING";let f=0;for(;l.nextNodeId&&f++<100;){const se=l.definition,B=se.nodes.find(ne=>ne.id===l.nextNodeId),X=l.nodes.find(ne=>ne.nodeDefId===l.nextNodeId);if(!B||!X){l.status="FAILED",l.error={message:"图引用了不存在的节点"};break}if(g==="PAUSE"||g==="RUN_TO_BREAKPOINT"&&l.controls.breakpoints.includes(B.id)){l.status="WAITING_STEP",X.status="WAITING_STEP",l.pauseSeq++,l.paused={nodeInstanceId:X.nodeInstanceId,nodeDefId:B.id,pauseSeq:l.pauseSeq,allowedActions:["NEXT_NODE","RUN_TO_BREAKPOINT","RUN_TO_END","CANCEL"]},I(l,"WAITING_STEP",{...l.paused});break}if(X.status="RUNNING",X.started_at=me(),I(l,"NODE_STARTED",{nodeInstanceId:X.nodeInstanceId,nodeDefId:B.id}),B.type==="SKILL"){const ne=(p=a.businessContext)==null?void 0:p.call(a),be=(m=ne==null?void 0:ne.actors)==null?void 0:m.find(is=>is.id===l.businessActorId),pn=((E=B.skillRef)==null?void 0:E.code)??B.skillCode??(typeof B.skill=="string"?B.skill:(R=B.skill)==null?void 0:R.code),At=((h=B.skillRef)==null?void 0:h.version)??B.skillVersion??(typeof B.skill=="object"?(L=B.skill)==null?void 0:L.version:void 0),vt=ts({calls:[{skillCode:pn,...At!==void 0?{version:At}:{}}],skills:a.skills,skillVersions:H.skillVersions,skillRefs:l.skillRefs,actor:be});if(vt.length){X.status="FAILED",X.error={code:"SKILL_ACCESS_DENIED",message:vt.join("；")},l.status="FAILED",l.error=X.error,I(l,"NODE_FAILED",{nodeInstanceId:X.nodeInstanceId,nodeDefId:B.id,error:l.error});break}}const k=B.businessPolicy,_=(N=a.businessContext)==null?void 0:N.call(a),x=(D=_==null?void 0:_.actors)==null?void 0:D.find(ne=>ne.id===(B.type==="SKILL"?l.businessActorId:_.currentActorId));if(k&&x){const ne=x.roles??x.roleCodes??[],be=x.permissions??x.permissionCodes??[];if(($=k.roles)!=null&&$.length&&!k.roles.some(pn=>ne.includes(pn))||(U=k.requiredPermissions)!=null&&U.some(pn=>!be.includes(pn))){X.status="FAILED",l.status="FAILED",l.error={code:"BUSINESS_POLICY_DENIED",message:"当前业务身份不满足节点业务权限策略"},I(l,"NODE_FAILED",{nodeDefId:B.id,error:l.error});break}}if(B.type==="APPROVAL"){X.status="WAITING_APPROVAL",l.status="WAITING_APPROVAL",l.pauseSeq++,l.paused={nodeInstanceId:X.nodeInstanceId,nodeDefId:B.id,pauseSeq:l.pauseSeq,allowedActions:["CANCEL"]},a.approvals.some(ne=>ne.runId===l.runId&&ne.nodeId===B.id)||a.approvals.unshift({id:a.nextId("approval"),runId:l.runId,nodeId:B.id,nodeDefId:B.id,nodeInstanceId:X.nodeInstanceId,nodeName:B.name??B.id,status:"PENDING",requestedAt:me(),reason:"流程节点要求人工确认",parameterSnapshot:Ve(l.inputs)}),I(l,"WAITING_APPROVAL",{nodeDefId:B.id,nodeInstanceId:X.nodeInstanceId});break}X.status="SUCCEEDED",X.ended_at=me(),X.duration=120;const Se=(l.controls.mocks??[]).find(ne=>ne.nodeDefId===B.id||ne.nodeId===B.id);X.output=(Se==null?void 0:Se.output)??{nodeId:B.id,inputs:l.inputs,message:`${B.name??B.id}执行完成`},l.variables[B.id]=X.output,I(l,"NODE_FINISHED",{nodeDefId:B.id,nodeInstanceId:X.nodeInstanceId,status:X.status});const Y=se.edges.filter(ne=>(ne.from??ne.source)===B.id);let re=B.next??((V=Y[0])==null?void 0:V.to)??((j=Y[0])==null?void 0:j.target);B.type==="CONDITION"&&(re=!!(l.inputs.condition??l.inputs.approved??l.inputs.highRisk)?B.nextTrue??((W=Y.find(be=>/true|high|是/i.test(be.label??"")))==null?void 0:W.to)??re:B.nextFalse??((G=Y.find(be=>/false|low|否/i.test(be.label??"")))==null?void 0:G.to)??((z=Y.at(-1))==null?void 0:z.to)??re),l.nextNodeId=B.type==="END"?null:re,g==="NEXT_NODE"&&l.nextNodeId&&(g="PAUSE")}f>=100&&(l.status="FAILED",l.error={message:"流程超过演示执行上限，请检查环路"}),l.status==="RUNNING"&&(l.status="SUCCEEDED",l.output={result:l.variables,text:"按已保存流程执行完成"},l.endedAt=me(),I(l,"RUN_FINISHED",{status:l.status})),l.duration=l.nodes.filter(se=>se.status==="SUCCEEDED").length*120}if((u=n.match(/^\/admin\/apps\/([^/]+)\/runs$/))&&t==="POST"){const l=o(u[1]);if(!l)return i(404,"应用不存在");if(!l.currentVersionNo)return i(409,"请先发布应用版本");const g=Number((e==null?void 0:e.resolvedVersionNo)??(e==null?void 0:e.versionNo)??l.currentVersionNo),f=(e==null?void 0:e.resolvedSnapshot)??((A=(S=H.versions[l.code])==null?void 0:S.find(p=>p.versionNo===g))==null?void 0:A.snapshot);return!Number.isInteger(g)||g<1||!f?i(404,"应用版本不存在"):d(c({...f,currentVersionNo:g},(e==null?void 0:e.inputs)??{}))}if(n==="/admin/debug-runs"){if(t==="GET")return r(a.debugRuns);if(t!=="POST")return i(405,"仅支持GET/POST");const l=o(e==null?void 0:e.appCode);return l?d(c(l,(e==null?void 0:e.inputs)??{},!0)):i(404,"应用不存在")}if(u=n.match(/^\/admin\/debug-runs\/([^/]+)(?:\/(status|controls|resume|cancel|variables|events)|\/node\/([^/]+)\/prompt)?$/)){const l=a.debugRuns.find(g=>g.id===u[1]);return l?u[2]==="controls"?t==="GET"?r({revision:l.revision??1,controls:l.controls}):t!=="PUT"?i(405,"仅支持GET/PUT"):(e==null?void 0:e.expectedRevision)!==l.revision?i(409,"控制修订已变化，请重新载入"):(l.controls={breakpoints:Ve((e==null?void 0:e.breakpoints)??[]),mocks:Ve((e==null?void 0:e.mocks)??[])},l.revision++,d({revision:l.revision,controls:l.controls})):u[2]==="resume"?t!=="POST"?i(405,"仅接受POST"):!l.paused||(e==null?void 0:e.pauseSeq)!==l.paused.pauseSeq||String(e==null?void 0:e.nodeInstanceId)!==String(l.paused.nodeInstanceId)?i(409,"挂起节点或序列已变化，请刷新"):l.paused.allowedActions.includes(e==null?void 0:e.action)?(y(l,e==null?void 0:e.action),d(l)):i(409,"当前挂起点不允许此操作"):u[2]==="cancel"?t!=="POST"?i(405,"仅接受POST"):(l.status="CANCELLED",l.paused=null,I(l,"RUN_FINISHED",{status:l.status}),d(l)):t!=="GET"?i(405,"只读接口"):u[2]==="variables"?r(l.variables):u[3]?r({label:u[3],raw:JSON.stringify({node:l.definition.nodes.find(g=>g.id===u[3]),inputs:l.inputs}),status:l.status}):u[2]==="events"?a.sse((l.events??[]).map(g=>({seq:String(g.seq),type:g.type,data:g.payload}))):r(l):i(404,"调试运行不存在")}if(u=n.match(/^\/admin\/runs\/([^/]+)(?:\/(events))?$/)){const l=a.runs.find(g=>g.id===u[1]);if(l!=null&&l.definition)return t!=="GET"?i(405,"只读接口"):u[2]?a.sse(l.events.map(g=>({seq:String(g.seq),type:g.type,data:g.payload}))):r(l)}if((u=n.match(/^\/admin\/approvals\/([^/]+)\/([^/]+)$/))&&t==="POST"){const l=a.runs.find(g=>g.id===u[1]&&g.definition);if(l){const g=a.approvals.find(E=>E.runId===l.runId&&(E.nodeId===u[2]||E.nodeInstanceId===u[2]));if(!g)return i(404,"确认单不存在");if(g.status!=="PENDING")return i(409,"确认单已处理");const f=(e==null?void 0:e.approved)!==!1&&(e==null?void 0:e.decision)!=="REJECT";g.status=f?"APPROVED":"REJECTED",g.decidedAt=me();const p=l.definition.nodes.find(E=>E.id===g.nodeId),m=l.nodes.find(E=>E.nodeDefId===p.id);return m.status=f?"SUCCEEDED":"REJECTED",l.paused=null,f?(l.nextNodeId=p.next??((T=l.definition.edges.find(E=>E.from===p.id))==null?void 0:T.to),y(l,"RUN_TO_END")):(l.status="REJECTED",I(l,"RUN_FINISHED",{status:l.status})),d(g)}}if(n==="/admin/runs"||n==="/admin/runs/page"){if(t!=="GET")return;const l=a.runs.filter(p=>(!s.get("appCode")||p.appCode===s.get("appCode"))&&(!s.get("status")||p.status===s.get("status"))&&(!s.get("triggerType")||p.triggerType===s.get("triggerType"))),g=Number(s.get("page")??1),f=Number(s.get("size")??20);return r(n.endsWith("/page")?{items:l.slice((g-1)*f,g*f),total:l.length,page:g,size:f}:l)}if(u=n.match(/^\/admin\/evaluation-datasets(?:\/([^/]+)\/([^/]+))?$/)){if(t!=="GET")return;if(u[2]){const l=decodeURIComponent(u[2]);if(H.datasets[l])return r({datasetVersion:l,cases:H.datasets[l]});const g=a.evaluationDatasets.find(p=>a.datasetVersionOf(p)===l);if(!g)return i(404,"数据集版本不存在");const f=Array.from({length:g.caseCount},(p,m)=>({caseId:`${g.id}-${m}`,name:`用例${m+1}`,inputs:{target:"SAT-01"},input:{target:"SAT-01"},expected:{visible:!0},assertions:[{kind:"EQUALS",path:"visible",value:!0}]}));return r({datasetVersion:l,cases:f})}return r({items:a.evaluationDatasets.filter(l=>!l.appCode||!s.get("appCode")||l.appCode===s.get("appCode")).map(l=>({...l,datasetVersion:a.datasetVersionOf(l),savedAt:l.updatedAt??me()})),hasMore:!1,nextAfterVersion:null})}if(u=n.match(/^\/admin\/evaluations(?:\/([^/]+))?(?:\/(resume))?$/)){const l=u[1]?a.evaluations.find(L=>L.id===u[1]):void 0;if(u[1]&&!l)return i(404,"评测报告不存在");if(t==="GET")return r(l??a.evaluations);if(t!=="POST")return i(405,"仅支持GET/POST");if(l)return u[2]?d(l):i(405,"不支持该方法");const g=o(e==null?void 0:e.appCode),f=H.prechecks[e==null?void 0:e.appCode];if(!g||!f||(e==null?void 0:e.candidateSha256)!==f.candidateSha256||(e==null?void 0:e.draftRev)!==g.draftRev)return i(409,"评测候选已变化，请重新预检");if(!Array.isArray(e==null?void 0:e.cases)||!e.cases.length)return i(400,"评测用例不能为空");const p=e.cases.map((L,N)=>{const D=L.inputs??L.input??{},$=L.expected??{},U={...D,visible:!0,answer:String(D.question??D.message??"演示响应")},V=!L.forceFailure&&Object.entries($).every(([j,W])=>JSON.stringify(U[j])===JSON.stringify(W));return{caseId:L.caseId??`case-${N+1}`,name:L.name??`用例${N+1}`,status:"SUCCEEDED",passed:V,safety:!!L.safety,inputs:D,expected:$,actual:U,output:U,error:null,regression:!1,runId:a.nextId("eval-case")}}),m=p.filter(L=>L.passed).length,E=m===p.length,R=a.nextId("ev"),h={id:R,evalReportId:R,appCode:g.code,draftRev:e.draftRev,candidateSha256:e.candidateSha256,datasetVersion:e.datasetVersion,createdBy:"u-demo",createdAt:me(),startedAt:me(),finishedAt:me(),status:E?"ACCEPTED":"REJECTED",evaluationEnvironment:"DEMO",results:p,summary:{total:p.length,passed:m,failed:p.length-m,passRate:m/p.length,threshold:1,regressionCount:0,safetyPassed:p.filter(L=>L.safety&&L.passed).length,safetyFailed:p.filter(L=>L.safety&&!L.passed).length},total:p.length,passed:m,failed:p.length-m,passRate:m/p.length,duration:p.length*120};return a.evaluations.unshift(h),H.datasets[e.datasetVersion]=Ve(e.cases),d(h)}if(n.startsWith("/admin/operations/")){const l=a.runs.filter(p=>["SUCCEEDED","FAILED","CANCELLED","REJECTED"].includes(p.status)),g=l.filter(p=>p.status==="FAILED"),f=l.map(p=>Number(p.duration??0)).sort((p,m)=>p-m);if(n==="/admin/operations/overview")return t==="GET"?r({checkedAt:me(),windowMinutes:Number(s.get("windowMinutes")??1440),traceId:"mock-operations",openAlerts:H.alerts.filter(p=>p.status==="OPEN").length,checks:[{key:"demo",label:"前端演示引擎",ok:!0,detail:"本地演示状态可用",latencyMs:1}],runs:{from:(v=a.runs.at(-1))==null?void 0:v.startedAt,to:me(),runs:{completed:l.length,succeeded:l.filter(p=>p.status==="SUCCEEDED").length,failed:g.length,failureRate:l.length?g.length/l.length:0,averageDurationMs:f.reduce((p,m)=>p+m,0)/(f.length||1),p95DurationMs:f[Math.floor(f.length*.95)]??0},active:{active:a.runs.length-l.length},model:{calls:a.runs.length,failed:g.length},tools:{calls:a.skills.reduce((p,m)=>p+Number(m.executionCount??0),0),failed:g.length,unknown:0},topErrors:g.map(p=>{var m;return{semantic:p.errorMessage??((m=p.error)==null?void 0:m.message)??"运行失败",count:1}}),recentFailures:g.slice(0,5).map(p=>{var m;return{...p,message:p.errorMessage??((m=p.error)==null?void 0:m.message),endedAt:p.endedAt??p.startedAt}})},retention:{snapshotDays:30,alertDays:90}}):i(405,"只读接口");if(n==="/admin/operations/alerts"&&t==="GET")return r(H.alerts);if(n==="/admin/operations/alerts/evaluate"&&t==="POST")return g.length&&!H.alerts.some(p=>p.key==="failed-runs"&&p.status!=="RESOLVED")&&H.alerts.unshift({id:a.nextId("alert"),key:"failed-runs",title:"发现失败运行",detail:`${g.length}条运行失败，请查看运行详情`,severity:"WARNING",status:"OPEN",firstSeenAt:me(),lastSeenAt:me()}),d(H.alerts);if((u=n.match(/^\/admin\/operations\/alerts\/([^/]+)\/ack$/))&&t==="POST"){const p=H.alerts.find(m=>m.id===u[1]);return p?(p.status="ACKNOWLEDGED",p.acknowledgedAt=me(),d(p)):i(404,"告警不存在")}if(n==="/admin/operations/retention/purge"&&t==="POST"){if(s.get("confirm")!=="PURGE")return i(400,"请输入PURGE确认");const p=H.alerts.filter(m=>m.status==="RESOLVED").length;return H.alerts=H.alerts.filter(m=>m.status!=="RESOLVED"),d({snapshotsDeleted:0,alertsDeleted:p})}return i(405,"不支持该运行中心操作")}}async function vn(n){const t=H.artifactRefs??(H.artifactRefs={}),e=[];for(const s of n.artifacts??[]){const a=s.sourceArtifactId??s.id,r=n.runId+":"+a,i=t[r]??(t[r]=await os({name:s.name,mediaType:s.mediaType,content:s.content,runId:n.runId}));e.push({...s,...i,sourceArtifactId:a})}return n.artifacts=e,n.task&&(n.task.artifacts=e),n}function C(n){return new Response(JSON.stringify({code:"OK",message:"",data:n}),{status:200,headers:{"Content-Type":"application/json"}})}function q(){return new Response(JSON.stringify({code:"NOT_FOUND",message:"记录不存在",data:null}),{status:404,headers:{"Content-Type":"application/json"}})}function xe(n){return new Response(JSON.stringify({code:"INVALID_ARGUMENT",message:n,data:null}),{status:400,headers:{"Content-Type":"application/json"}})}function Re(n){return new Response(JSON.stringify({code:"CONFLICT",message:n,data:null}),{status:409,headers:{"Content-Type":"application/json"}})}const gt="2026-10-25T10:00:00Z",Gs=()=>({tenantId:"t-demo",principalId:"u-demo",roles:["ADMIN","EDITOR","APPROVER"],expiresAt:new Date(Date.now()+24*3600*1e3).toISOString().replace(/\.\d{3}Z$/,"Z")}),Q=[{code:"satellite-assistant",name:"卫星运行助手",displayName:"卫星运行助手",status:"PUBLISHED",type:"AGENT",domain:"航天",bizCategory:"运行支持",tags:["在轨管理","演示"],description:"面向卫星运行团队的问答与 orbit 查询助手。",currentVersionNo:3,draftRev:5,draftId:"draft-satellite-assistant-r5",createdAt:"2026-09-12T08:00:00Z",updatedAt:"2026-10-24T15:20:00Z",config:{modelBinding:{modelEntryId:"me-001"},promptSource:"TEMPLATE",promptRefs:[{code:"satellite-system",version:2,sha256:"sha256-prompt-satellite-system-v2"}],skillRefs:[{code:"orbit_query",version:1},{code:"telemetry_fetch",version:1}],guard:{roles:["OPERATOR","APPROVER"]},requiredLabels:["PUBLIC"],temperature:.3,maxTurns:12,knowledgeBases:["kb-orbit-runbook"],welcome:"我是卫星运行助手，可以查询轨道根数、提取遥测片段并解释处置口径。",suggestedQuestions:["SAT-01 当前轨道高度是多少？","帮我取 SAT-01 最近 30 分钟的遥测片段","这条告警属于哪一类异常？"]}},{code:"payload-advisor",name:"载荷选型顾问",displayName:"载荷选型顾问",status:"DRAFT",type:"AGENT",domain:"航天",bizCategory:"论证支持",tags:["选型"],description:"按任务需求推荐兼容载荷配置。",currentVersionNo:1,draftRev:2,draftId:"draft-payload-advisor-r2",createdAt:"2026-10-01T09:00:00Z",updatedAt:"2026-10-22T11:00:00Z",config:{modelBinding:{modelEntryId:"me-001"},promptSource:"TEMPLATE",promptRefs:[{code:"payload-advisor",version:1,sha256:"sha256-prompt-payload-advisor-v1"}],skillRefs:[{code:"payload_compat",version:1}],guard:{roles:["EDITOR","OPERATOR"]},requiredLabels:["PUBLIC"],temperature:.5,maxTurns:8,knowledgeBases:["kb-payload-catalog"],welcome:"描述任务轨道与载荷需求，我会给出兼容的载荷组合与频段约束。",suggestedQuestions:["Ku 频段 200Mbps 下行的兼容载荷有哪些？","比较 A/B 两套载荷方案的功耗"]}},{code:"anomaly-triage",name:"遥测异常分诊",displayName:"遥测异常分诊",status:"PUBLISHED",type:"FLOW",domain:"测控",bizCategory:"故障处置",tags:["遥测","分诊"],description:"对遥测告警执行规则分诊并生成处置建议。",currentVersionNo:2,draftRev:3,draftId:"draft-anomaly-triage-r3",createdAt:"2026-09-20T08:00:00Z",updatedAt:"2026-10-21T17:40:00Z",config:{modelBinding:{modelEntryId:"me-002"},promptSource:"TEMPLATE",promptRefs:[{code:"triage-user",version:1,sha256:"sha256-prompt-triage-user-v1"}],skillRefs:[{code:"telemetry_fetch",version:1}],guard:{roles:["OPERATOR"]},requiredLabels:["INTERNAL"],temperature:.1,maxTurns:6,flowRef:"flow-anomaly-triage"},definition:{definitionVersion:"1.0",trigger:"TASK",entry:"start",inputs:[{key:"telemetryId",type:"STRING",required:!0,description:"遥测告警编号"},{key:"window",type:"STRING",required:!1,description:"取样时间窗，默认最近 30 分钟"}],outputs:[{key:"diagnosis",type:"JSON",description:"分诊结论与处置建议"}],joins:[],executionRules:{parallel:!1,maxParallel:1,cyclesForbidden:!0},nodes:[{id:"start",type:"START",name:"接收告警"},{id:"fetch",type:"SKILL",name:"遥测片段提取",skill:"telemetry_fetch",next:"classify"},{id:"classify",type:"LLM",name:"异常分类",model:"deepseek-reasoner",promptTemplate:"triage-user",next:"decide"},{id:"decide",type:"CONDITION",name:"是否高风险",expression:'diagnosis.risk == "HIGH"',nextTrue:"confirm",nextFalse:"report"},{id:"confirm",type:"APPROVAL",name:"人工确认处置",next:"report"},{id:"report",type:"END",name:"输出处置建议"}],edges:[{from:"start",to:"fetch",label:"告警"},{from:"fetch",to:"classify",label:"遥测片段"},{from:"classify",to:"decide",label:"分类结果"},{from:"decide",to:"confirm",label:"HIGH"},{from:"decide",to:"report",label:"LOW/MEDIUM"},{from:"confirm",to:"report",label:"已确认"}]}},{code:"space-visibility-agent",name:"可见性规划智能体",displayName:"可见性规划智能体",status:"DRAFT",type:"CHAT",domain:"航天",bizCategory:"运行支持",tags:["可见性","能力包"],description:"按目标与观测星计算可见窗口；异步任务提交需人工确认。",currentVersionNo:null,draftRev:4,draftId:"draft-space-visibility-agent-r4",createdAt:"2026-10-27T09:10:00Z",updatedAt:"2026-10-27T10:20:00Z",config:{modelBinding:{modelEntryId:"me-001"},promptSource:"TEMPLATE",promptRefs:[{code:"visibility-system",version:1,sha256:"sha256-prompt-visibility-v1"}],skillRefs:[{code:"space-target-query",version:1},{code:"space-visibility-plan",version:1},{code:"space-visibility-submit",version:1},{code:"space-visibility-status",version:2},{code:"space-visibility-result",version:1}],guard:{roles:["OPERATOR","APPROVER"]},requiredLabels:["PUBLIC"],temperature:.2,maxTurns:8,systemPrompt:"你是空间目标可见性规划助手。查询或规划前先确认目标与观测星；提交异步任务前必须向用户复述参数并等待确认；引用结果时给出 taskId 与弧段数量。"}}],Fe=[{id:"mp-001",code:"deepseek",name:"DeepSeek 官方",protocol:"OPENAI_COMPATIBLE",baseUrl:"https://api.deepseek.com/v1",status:"ACTIVE",credentialStatus:"SET",quota:{tpm:2e5,rpd:2e4},updatedAt:gt},{id:"mp-002",code:"local-vllm",name:"内网 vLLM 网关",protocol:"OPENAI_COMPATIBLE",baseUrl:"http://127.0.0.1:18090/v1",status:"ACTIVE",credentialStatus:"SET",quota:{tpm:6e4,rpd:6e3},updatedAt:"2026-10-20T08:00:00Z"}],$e=[{id:"me-001",providerId:"mp-001",providerCode:"deepseek",modelId:"deepseek-chat",displayName:"DeepSeek Chat",contextWindow:65536,maxOutputTokens:8192,capabilities:["chat","tools"],status:"ACTIVE",metering:{inputPer1k:.001,outputPer1k:.002}},{id:"me-002",providerId:"mp-001",providerCode:"deepseek",modelId:"deepseek-reasoner",displayName:"DeepSeek Reasoner",contextWindow:65536,maxOutputTokens:8192,capabilities:["chat","json_mode"],status:"ACTIVE",metering:{inputPer1k:.002,outputPer1k:.004}},{id:"me-003",providerId:"mp-002",providerCode:"local-vllm",modelId:"qwen2.5-32b-instruct",displayName:"Qwen2.5-32B（内网）",contextWindow:32768,maxOutputTokens:4096,capabilities:["chat"],status:"ACTIVE",metering:{inputPer1k:0,outputPer1k:0}}],Ze=[{code:"satellite-system",name:"卫星助手系统提示",status:"PUBLISHED",currentVersion:2,updatedAt:"2026-10-18T09:00:00Z",description:"约束卫星领域回答口径与引用要求。",variables:["satelliteId","windowHours","operatorRole"],content:"你是卫星运行助手，面向{{operatorRole}}回答{{satelliteId}}的运行问题。要求：先给结论再给依据；引用轨道或遥测结果时写明数据时间与来源；只回答未来 {{windowHours}} 小时内的可见窗口，超出范围要说明。"},{code:"triage-user",name:"异常分诊用户提示",status:"DRAFT",currentVersion:1,updatedAt:"2026-10-15T14:00:00Z",description:"将遥测告警转成结构化分诊输入。",variables:["telemetryId","alarmCode"],content:"遥测点 {{telemetryId}} 触发告警 {{alarmCode}}。请按「现象 / 可能原因 / 建议动作」三段输出，并标注需要人工确认的步骤。"},{code:"visibility-system",name:"可见性规划系统提示",status:"PUBLISHED",currentVersion:1,updatedAt:"2026-10-27T09:00:00Z",description:"约束可见性规划助手的确认与引用口径。",variables:["targetId","stationCode"],content:"你负责计算 {{targetId}} 与 {{stationCode}} 的可见窗口。提交异步任务前必须复述目标、测站与时间范围，等待用户确认后再调用写操作。"},{code:"payload-advisor",name:"载荷选型顾问系统提示",status:"PUBLISHED",currentVersion:1,updatedAt:"2026-10-20T10:00:00Z",description:"约束载荷选型的频段、功耗与平台兼容口径。",variables:["missionType","platformCode"],content:"为 {{missionType}} 任务在 {{platformCode}} 平台上推荐载荷。必须先校验频段与功耗约束，再给出两个备选并说明取舍。"}],Ye={"satellite-system":[{version:2,status:"PUBLISHED",content:"你是卫星运行助手，回答需引用操作结果并注明时间。",publishedAt:"2026-10-18T09:00:00Z",publishedBy:"演示用户"},{version:1,status:"DEPRECATED",content:"你是卫星运行助手。",publishedAt:"2026-09-12T08:00:00Z",publishedBy:"演示用户"}],"triage-user":[{version:1,status:"DRAFT",content:"遥测点 {{telemetryId}} 触发告警 {{alarmCode}}。请按现象/原因/建议输出。",publishedAt:"2026-10-15T14:00:00Z",publishedBy:"演示用户"}],"visibility-system":[{version:1,status:"PUBLISHED",content:"你负责计算 {{targetId}} 与 {{stationCode}} 的可见窗口，写操作前必须复述并等待确认。",publishedAt:"2026-10-27T09:00:00Z",publishedBy:"演示用户"}],"payload-advisor":[{version:1,status:"PUBLISHED",content:"为 {{missionType}} 推荐载荷，先校验频段与功耗，再给两个备选。",publishedAt:"2026-10-20T10:00:00Z",publishedBy:"演示用户"}]},ie=[{code:"orbit_query",name:"轨道要素查询",status:"PUBLISHED",kind:"HTTP",description:"查询卫星当前轨道根数与星下点。",currentVersion:2,executionCount:1582,successRate:.993,averageDurationMs:246,updatedAt:"2026-10-24T10:00:00Z"},{code:"telemetry_fetch",name:"遥测片段提取",status:"PUBLISHED",kind:"HTTP",description:"按时间窗提取遥测参数片段。",currentVersion:1,executionCount:930,successRate:.981,averageDurationMs:512,updatedAt:"2026-10-22T10:00:00Z"},{code:"payload_compat",name:"载荷兼容检查",status:"DISABLED",kind:"HTTP",description:"校验载荷频段与平台约束。",currentVersion:1,executionCount:45,successRate:.955,averageDurationMs:780,updatedAt:"2026-10-12T10:00:00Z"},{code:"space-target-query",name:"目标目录查询",status:"PUBLISHED",kind:"HTTP",description:"按 ID 或条件筛选空间目标目录（25870 条）。",currentVersion:1,executionCount:214,successRate:1,averageDurationMs:180,updatedAt:"2026-10-27T09:00:00Z"},{code:"space-visibility-plan",name:"可见性规划（同步）",status:"PUBLISHED",kind:"HTTP",description:"计算观测星对目标的可见弧段，含光照段。",currentVersion:1,executionCount:96,successRate:.99,averageDurationMs:2400,updatedAt:"2026-10-27T09:00:00Z"},{code:"space-visibility-submit",name:"可见性任务提交（异步）",status:"PUBLISHED",kind:"HTTP",description:"受理异步观测规划任务并返回 taskId，平台自动对账。",currentVersion:1,executionCount:38,successRate:.97,averageDurationMs:320,updatedAt:"2026-10-27T09:00:00Z"},{code:"space-visibility-status",name:"可见性任务状态",status:"PUBLISHED",kind:"HTTP",description:"查询异步任务状态（SUCCEEDED/FAILED/CANCELLED）。",currentVersion:2,executionCount:120,successRate:1,averageDurationMs:90,updatedAt:"2026-10-27T09:00:00Z"},{code:"space-visibility-result",name:"可见性任务结果",status:"PUBLISHED",kind:"HTTP",description:"读取异步任务的规划结果。",currentVersion:1,executionCount:112,successRate:1,averageDurationMs:140,updatedAt:"2026-10-27T09:00:00Z"},{code:"space-satellite-options",name:"观测星目录",status:"DISABLED",kind:"HTTP",description:"查询 5414 颗观测星 TLE 目录；响应约 550KB 超输出预算，已停用。",currentVersion:1,executionCount:6,successRate:.83,averageDurationMs:1800,updatedAt:"2026-10-27T09:00:00Z"}].map(n=>({readonly:!0,sideEffect:!1,version:n.currentVersion,sha256:"c".repeat(64),...n})),ae=[{id:"run-101",runId:"run-101",publicId:"run-demo-101",appCode:"satellite-assistant",appName:"卫星运行助手",status:"SUCCEEDED",versionNo:3,versionRefs:{appVersion:3,model:"deepseek-chat",prompt:"satellite-system v2",skills:["orbit_query v1","telemetry_fetch v1"]},triggerType:"CHAT",caller:"门户用户",startedAt:"2026-10-25T08:12:00Z",duration:4218,errorMessage:null,nodeCount:6,tokenUsage:{input:3120,output:860}},{id:"run-102",runId:"run-102",publicId:"run-demo-102",appCode:"satellite-assistant",appName:"卫星运行助手",status:"RUNNING",versionNo:3,versionRefs:{appVersion:3,model:"deepseek-chat",prompt:"satellite-system v2",skills:["orbit_query v1","telemetry_fetch v1"]},triggerType:"CHAT",caller:"门户用户",startedAt:"2026-10-25T09:40:00Z",duration:9180,errorMessage:null,nodeCount:4,tokenUsage:{input:1980,output:220}},{id:"run-103",runId:"run-103",publicId:"run-demo-103",appCode:"anomaly-triage",appName:"遥测异常分诊",status:"FAILED",versionNo:2,versionRefs:{appVersion:2,model:"deepseek-reasoner",prompt:"triage-user v1",skills:["telemetry_fetch v1"]},triggerType:"API",caller:"service-account",startedAt:"2026-10-25T07:05:00Z",duration:1522,errorMessage:"SKILL_TIMEOUT: telemetry_fetch 超过 5000ms",nodeCount:3,tokenUsage:{input:940,output:0}},{id:"run-104",runId:"run-104",publicId:"run-vis-104",appCode:"space-visibility-agent",appName:"可见性规划智能体",status:"WAITING_APPROVAL",versionNo:1,versionRefs:{appVersion:1,model:"deepseek-chat",prompt:"visibility-system v1",skills:["space-target-query v1","space-visibility-plan v1"]},triggerType:"CHAT",caller:"门户用户",startedAt:"2026-10-27T10:02:00Z",duration:2860,errorMessage:null,nodeCount:6,tokenUsage:{input:2140,output:520}},{id:"run-105",runId:"run-105",publicId:"run-vis-105",appCode:"space-visibility-agent",appName:"可见性规划智能体",status:"SUCCEEDED",versionNo:1,versionRefs:{appVersion:1,model:"deepseek-chat",prompt:"visibility-system v1",skills:["space-target-query v1","space-visibility-plan v1"]},triggerType:"CHAT",caller:"门户用户",startedAt:"2026-10-27T09:41:00Z",duration:5120,errorMessage:null,nodeCount:7,tokenUsage:{input:2680,output:640}},{id:"run-106",runId:"run-106",publicId:"run-vis-106",appCode:"space-visibility-agent",appName:"可见性规划智能体",status:"FAILED",versionNo:1,versionRefs:{appVersion:1,model:"deepseek-chat",prompt:"visibility-system v1",skills:["space-target-query v1","space-visibility-plan v1"]},triggerType:"CHAT",caller:"门户用户",startedAt:"2026-10-27T09:28:00Z",duration:930,errorMessage:"TLE_CHECKSUM: 观测星两行根数校验和不匹配",nodeCount:4,tokenUsage:{input:1180,output:90}}],Ge=[{id:"dbg-201",status:"PAUSED",appCode:"payload-advisor",startedAt:"2026-10-25T09:00:00Z",controls:{paused:!0},variables:{task:"评估 Ku 频段载荷",budget:3}},{id:"dbg-202",status:"PAUSED",appCode:"satellite-assistant",startedAt:"2026-10-25T09:30:00Z",controls:{paused:!0},variables:{question:"SAT-01 当前轨道高度是多少？",orbitQuery:"SAT-01",window:"30m"}}],ws=(n,t,e)=>{const s=n==="WAITING_APPROVAL";return e==="space-visibility-agent"?[{id:"start",nodeDefId:"start",label:"开始",status:"SUCCEEDED",duration:10},{id:"target",nodeDefId:"target_query",label:"目标目录查询",status:"SUCCEEDED",duration:180},{id:"plan",nodeDefId:"visibility_plan",label:"可见性规划",status:t==="plan"?"FAILED":"SUCCEEDED",duration:2400,errorMessage:t==="plan"?"TLE_CHECKSUM: 观测星两行根数校验和不匹配":void 0},{id:"llm",nodeDefId:"llm",label:"模型推理",status:"SUCCEEDED",duration:1260,lastRun:{summary:"复述目标与窗口后请求确认",tokens:{input:2140,output:520}}},{id:"confirm",nodeDefId:"confirm_submit",label:"提交异步观测规划任务",status:s?"WAITING_APPROVAL":"SUCCEEDED",duration:s?0:320},{id:"status",nodeDefId:"visibility_status",label:"查询任务状态",status:s?"PENDING":"SUCCEEDED",duration:s?0:90},{id:"respond",nodeDefId:"respond",label:"生成答复",status:n,duration:180}]:[{id:"start",nodeDefId:"start",label:"开始",status:"SUCCEEDED",duration:12},{id:"retrieve",nodeDefId:"retrieve_context",label:"检索上下文",status:t==="retrieve"?"FAILED":"SUCCEEDED",duration:386,errorMessage:t==="retrieve"?"技能连接失败":void 0},{id:"llm",nodeDefId:"llm",label:"模型推理",status:t==="llm"?"FAILED":"SUCCEEDED",duration:2140,lastRun:{summary:"输出轨道参数分析 2 段",tokens:{input:3120,output:860}}},{id:"orbit",nodeDefId:"orbit_query",label:"轨道查询技能",status:t==="orbit"?"FAILED":"SUCCEEDED",duration:246},{id:"confirm",nodeDefId:"confirm-write",label:"写回确认",status:s?"WAITING_APPROVAL":"SUCCEEDED",duration:s?0:210},{id:"respond",nodeDefId:"respond",label:"生成答复",status:n,duration:210}]},Ms=n=>{const t=w(ae,n),e=O.responses[n];if(e)return{...t,planning:e,nodes:mt(n),events:Ot(n).map((r,i)=>({seq:i+1,type:r.type,payload:r})),eventsWatermark:Ot(n).length,output:{text:e.reply,workflow:e.workflow,businessChange:e.businessChange,artifacts:e.artifacts,fileRefs:e.artifacts},error:null};const s=F.results[n];if(s)return{...t,runId:n,status:(t==null?void 0:t.status)??s.status,planning:s,output:{text:s.reply,plan:s.plan,task:s.task,artifacts:s.artifacts,fileRefs:s.artifacts},nodes:Gn(s),events:it(s).map(r=>({...r,seq:Number(r.seq)})),eventsWatermark:it(s).length,versionRefs:t==null?void 0:t.versionRefs,error:null};const a=(t==null?void 0:t.status)==="FAILED"?t.appCode==="space-visibility-agent"?"plan":"orbit":void 0;return{...t,status:(t==null?void 0:t.status)??"SUCCEEDED",eventsWatermark:9,versionRefs:(()=>{var o;const r=t!=null&&t.appCode?w(Q,t.appCode):void 0,i=(t==null?void 0:t.versionNo)??(r==null?void 0:r.currentVersionNo)??(r==null?void 0:r.draftRev)??1,d=Array.isArray((o=r==null?void 0:r.config)==null?void 0:o.skillRefs)?r.config.skillRefs.map(u=>[String(u.code),Number(u.version??1)]):[];return{app:`${(t==null?void 0:t.appCode)??"unknown"}@${i}`,skills:Object.fromEntries(d)}})(),nodes:ws((t==null?void 0:t.status)??"SUCCEEDED",a,t==null?void 0:t.appCode),error:a?{code:(t==null?void 0:t.appCode)==="space-visibility-agent"?"TLE_CHECKSUM":"SKILL_TIMEOUT",message:(t==null?void 0:t.errorMessage)??"orbit_query 超过 5000ms"}:null}},ot=n=>new Response(n.map(t=>`id: ${t.seq}
event: ${t.type}
data: ${JSON.stringify(t.data)}

`).join(""),{status:200,headers:{"Content-Type":"text/event-stream"}}),ve=[{id:"ap-001",snapshotDigest:"sha256-demo-ap-001",runId:"run-102",nodeDefId:"confirm-orbit-write",nodeId:"confirm-orbit-write",nodeName:"轨道参数写回",parameterSnapshot:{snapshotDigest:"sha256-demo-ap-001",actionPlan:{summary:"更新 SAT-01 轨道根数 2 项",target:"SAT-01",diff:{field:"orbit",before:"TLE-OLD",after:"TLE-NEW"},risk:"STANDARD",approvalPolicy:"LEGACY_DECLARATION",expiresAt:"2026-12-31T00:00:00Z"}},status:"PENDING",requestedAt:"2026-10-25T09:42:00Z",reason:"该操作具有副作用，需要人工确认。",summary:"计划更新 SAT-01 轨道根数 2 项。"},{id:"ap-002",snapshotDigest:"sha256-demo-ap-002",runId:"run-104",nodeDefId:"confirm-dispatch",nodeId:"confirm-dispatch",nodeName:"处置工单派发",parameterSnapshot:{snapshotDigest:"sha256-demo-ap-002",actionPlan:{summary:"派发工单 GD-20261025-07",target:"测控一站",diff:{field:"orbit",before:"TLE-OLD",after:"TLE-NEW"},risk:"STANDARD",approvalPolicy:"LEGACY_DECLARATION",expiresAt:"2026-12-31T00:00:00Z"}},status:"PENDING",requestedAt:"2026-10-25T08:30:00Z",reason:"外部系统写入需审批。",summary:"派发工单 GD-20261025-07 至测控一站。"},{id:"ap-003",snapshotDigest:"sha256-demo-ap-003",runId:"run-104",nodeDefId:"confirm-submit",nodeId:"confirm-submit",nodeName:"提交异步观测规划任务",parameterSnapshot:{snapshotDigest:"sha256-demo-ap-003",actionPlan:{summary:"提交异步观测规划任务",target:"SAT-01 / SAT-A",diff:{field:"orbit",before:"TLE-OLD",after:"TLE-NEW"},risk:"STANDARD",approvalPolicy:"LEGACY_DECLARATION",expiresAt:"2026-12-31T00:00:00Z"}},status:"PENDING",requestedAt:"2026-10-27T10:02:03Z",reason:"space-visibility-submit 具有副作用（WRITE_WITH_APPROVAL），需 ADMIN/APPROVER 确认。",summary:"目标 SAT-01、观测星 SAT-A，规划窗口 2026-07-01 08:00–12:00 UTC。"},{id:"ap-004",snapshotDigest:"sha256-demo-ap-004",runId:"run-105",nodeDefId:"confirm-submit",nodeId:"confirm-submit",nodeName:"提交异步观测规划任务",parameterSnapshot:{snapshotDigest:"sha256-demo-ap-004",actionPlan:{summary:"提交异步观测规划任务",target:"SAT-02 / SAT-B",diff:{field:"orbit",before:"TLE-OLD",after:"TLE-NEW"},risk:"STANDARD",approvalPolicy:"LEGACY_DECLARATION",expiresAt:"2026-12-31T00:00:00Z"}},status:"APPROVED",requestedAt:"2026-10-27T09:41:02Z",decidedAt:"2026-10-27T09:41:40Z",reason:"space-visibility-submit 具有副作用，需人工确认。",summary:"目标 SAT-02、观测星 SAT-B，规划窗口 24 小时。"}],De=[{id:"ed-001",name:"卫星问答回归集",version:4,caseCount:60,status:"ACTIVE",updatedAt:"2026-10-20T10:00:00Z"},{id:"ed-002",name:"分诊准确率集",version:2,caseCount:35,status:"ACTIVE",updatedAt:"2026-10-16T10:00:00Z"},{id:"ed-003",name:"可见性规划回归集",version:1,caseCount:12,status:"ACTIVE",updatedAt:"2026-10-27T09:30:00Z"}],Je=[{id:"ev-301",datasetId:"ed-001",datasetName:"卫星问答回归集",status:"SUCCEEDED",passed:58,failed:2,total:60,passRate:.967,runId:"run-101",startedAt:"2026-10-24T20:00:00Z",duration:182e3},{id:"ev-302",datasetId:"ed-002",datasetName:"分诊准确率集",status:"RUNNING",passed:12,failed:0,total:35,passRate:1,runId:null,startedAt:"2026-10-25T09:50:00Z",duration:61e3},{id:"ev-303",datasetId:"ed-003",datasetName:"可见性规划回归集",status:"FAILED",passed:11,failed:1,total:12,passRate:.917,runId:"run-105",startedAt:"2026-10-27T10:10:00Z",duration:46e3}],Bs=[{snapshotId:"ss-20261025",bizSystemCode:"运行支持",model:"deepseek-chat",succeeded:128,failed:4,cancelled:2,active:3,successRate:.955,averageDurationMs:3860,p95DurationMs:9120,capturedAt:gt},{snapshotId:"ss-20261024",bizSystemCode:"故障处置",model:"deepseek-reasoner",succeeded:57,failed:9,cancelled:1,active:0,successRate:.851,averageDurationMs:7420,p95DurationMs:15400,capturedAt:"2026-10-24T10:00:00Z"}],Yn={succeeded:185,failed:13,cancelled:3,active:3,successRate:.921,averageDurationMs:4640,p95DurationMs:12260,series:[{date:"2026-10-23",succeeded:52,failed:5},{date:"2026-10-24",succeeded:57,failed:9},{date:"2026-10-25",succeeded:76,failed:4}]},nn=[{date:"2026-10-25",appCode:"satellite-assistant",bizSystemCode:"运行支持",purpose:"CHAT",runs:76,successRate:.947,failed:4,unfinished:0,p95DurationMs:9120,confirmCount:1,model:"deepseek-chat",tokens:{input:231e3,output:61e3}},{date:"2026-10-25",appCode:"anomaly-triage",bizSystemCode:"故障处置",purpose:"API",runs:38,successRate:.842,failed:6,unfinished:0,p95DurationMs:15400,confirmCount:0,model:"deepseek-reasoner",tokens:{input:98e3,output:24e3}},{date:"2026-10-24",appCode:"satellite-assistant",bizSystemCode:"运行支持",purpose:"CHAT",runs:57,successRate:.965,failed:2,unfinished:0,p95DurationMs:8800,confirmCount:2,model:"deepseek-chat",tokens:{input:176e3,output:47e3}}],tn=[{date:"2026-10-25",appCode:"satellite-assistant",bizSystemCode:"运行支持",purpose:"CHAT",skill:"orbit_query",version:2,calls:96,mock:0,failures:1,successRate:.99,p95DurationMs:1240},{date:"2026-10-25",appCode:"anomaly-triage",bizSystemCode:"故障处置",purpose:"API",skill:"telemetry_fetch",version:1,calls:41,mock:2,failures:4,successRate:.902,p95DurationMs:5210}],qs=[{id:"cls-01",name:"运行支持",kind:"BIZ_CATEGORY",children:[{id:"cls-01-1",name:"在轨管理"}]},{id:"cls-02",name:"故障处置",kind:"BIZ_CATEGORY",children:[]}],Sn=[{id:9001,actor:"u-demo",actorName:"演示用户",action:"APP_PUBLISH",targetType:"APP",targetId:"satellite-assistant",detail:"发布 v3",createdAt:"2026-10-24T15:20:00Z",result:"SUCCESS"},{id:9002,actor:"u-demo",actorName:"演示用户",action:"SKILL_DISABLE",targetType:"SKILL",targetId:"payload_compat",detail:"停用兼容检查技能",createdAt:"2026-10-23T11:00:00Z",result:"SUCCESS"}],js={apiVersion:"platform/v1",kind:"CapabilityPackage",metadata:{name:"route1-visibility",domain:"space-visibility",owner:"algorithm-team",version:"1.0.0",description:"空间—地面目标可见窗口计算系统 space-ground-visible 的能力声明；演示数据取自 2026-10-27 路线一验证的真实技能与应用配置。"},software:{system:"space-ground-visible",version:"space-target-visible-1.0.0-SNAPSHOT",environments:{dev:"http://127.0.0.1:19180"}},interfaces:[{id:"space-http-sync",kind:"HTTP_SYNC",endpoint:"http://127.0.0.1:19180",timeoutMs:3e4,description:"同步只读入口：目标目录查询、同步可见性规划、任务状态与结果读取。"},{id:"space-http-async",kind:"HTTP_ASYNC",endpoint:"http://127.0.0.1:19180/api/v1/space/visibility/tasks",httpMethod:"POST",timeoutMs:12e4,reconciliation:{endpoint:"http://127.0.0.1:19180/api/v1/space/visibility/tasks/{jobId}",method:"GET",jobIdPath:"$.taskId",donePath:"$.status",doneValue:"SUCCEEDED",failedValue:"FAILED",failureMessagePath:"$.errorMessage",idempotencyKeyEcho:!1},description:"异步受理 + 自动对账取结果。"}],ontology:{mode:"NONE",notes:"ontorepo 在本演示环境未接入：没有 REFERENCE/OWNED 的本体空间与发布版本，概念口径以应用 systemPrompt 注入；工具从 release operation 投影生成属后续能力。"},tools:[{id:"space-target-query",operation:"queryTargets",description:"筛选目标目录（25870 条）。",binding:"space-http-sync",exposure:"READ_ONLY",readonly:!0,sideEffect:!1,idempotent:!0,skillRef:{code:"space-target-query",version:1,sha256:"0f2db9df779cf468f86b98425107c1c1080bfdf32076c37849c113fd32cb3762"}},{id:"space-visibility-plan",operation:"planVisibility",description:"同步计算可见弧段（含光照段）。",binding:"space-http-sync",exposure:"READ_ONLY",readonly:!0,sideEffect:!1,idempotent:!0,skillRef:{code:"space-visibility-plan",version:1,sha256:"80889e9e0a1a180b81cbdb815465e4acdfd2b102f6925792bfc42b13652a4969"}},{id:"space-visibility-submit",operation:"submitVisibilityTask",description:"受理异步观测规划任务，返回 taskId。",binding:"space-http-async",exposure:"WRITE_WITH_APPROVAL",confirmRoles:["ADMIN","APPROVER"],readonly:!1,sideEffect:!0,idempotent:!0,skillRef:{code:"space-visibility-submit",version:1,sha256:"213b4d463a8cb189e186c1be7ac2b9de1cfbbde1432d3330d6f7c2e20c24492b"}},{id:"space-visibility-status",operation:"queryVisibilityTaskStatus",description:"查询异步任务状态。",binding:"space-http-sync",exposure:"READ_ONLY",readonly:!0,sideEffect:!1,idempotent:!0,skillRef:{code:"space-visibility-status",version:2,sha256:"0756fa0d303c23101fbf02aa60222f6faee7b3e8dc6f24c6ba984107c814d732"}},{id:"space-visibility-result",operation:"getVisibilityTaskResult",description:"读取异步任务的规划结果。",binding:"space-http-sync",exposure:"READ_ONLY",readonly:!0,sideEffect:!1,idempotent:!0,skillRef:{code:"space-visibility-result",version:1,sha256:"e601a7bc5f1f3407f57dbb85353708e588e48eef1e1ae4b897f555d192ac5521"}}],identity:{subjectMapping:"platform_user -> Actor.subject()",scopes:["region=LOCAL"],guard:{roles:["ADMIN","EDITOR","APPROVER","SERVICE"],attributes:{region:["LOCAL"]}},secrets:[]},agents:[{code:"space-visibility-agent",name:"可见性规划智能体",type:"CHAT",skillRefs:[{tool:"space-target-query",skillCode:"space-target-query",version:1},{tool:"space-visibility-plan",skillCode:"space-visibility-plan",version:1},{tool:"space-visibility-submit",skillCode:"space-visibility-submit",version:1},{tool:"space-visibility-status",skillCode:"space-visibility-status",version:2},{tool:"space-visibility-result",skillCode:"space-visibility-result",version:1}],modelBinding:{modelEntryId:1,entry:"deepseek-chat（capabilities chat,tools）"},guard:{roles:["ADMIN","EDITOR","APPROVER","SERVICE"],attributes:{region:["LOCAL"]}},requiredLabels:["PUBLIC"]}],verification:{connection:!0,contract:!0,model:!0,evaluation:!1,safety:!0,concurrency:!1,exemptions:[{gate:"evaluation",reason:"应用层评测门禁对自由文本 CHAT 智能体不可用：整份 run output 精确 JSON 相等断言含 usage/actualModel 等易变字段，正确回答仍判 REJECTED。"},{gate:"concurrency",reason:"本路线未覆盖并发隔离用例。"}]},operations:{notes:"演示环境未采集 SLO/成本/保留期数据；同步超时上限 30000ms，异步提交 120000ms。"},routeProfiles:[{id:"http-sync",execution:"space-http-sync",ontology:"none",tools:["space-target-query","space-visibility-plan","space-visibility-status","space-visibility-result"],identity:"platform-user-guarded（roles + region=LOCAL）"},{id:"http-async",execution:"space-http-async",ontology:"none",tools:["space-visibility-submit"],identity:"platform-user-guarded + approval（ADMIN/APPROVER）"}],implementationGaps:[{area:"本体协同：工具投影、contextPack 自动注入、统一主体映射",description:"本包 ontology.mode=NONE，概念口径手写进 systemPrompt。",ownerCard:"T22B"},{area:"执行适配器：PROCESS 适配器注册表",description:"一次性进程（stdio、超时杀树、产物回收）未注册，轨迹路线接入待办。",ownerCard:"T22C"},{area:"控制面收口：共享对象 ID、SLO 与成本归因",description:"package/route/tool/release 维度观测未落库。",ownerCard:"T22D"}]},Vn=[{packageVersionId:1,packageId:"route1-visibility",version:"1.0.0",sha256:"9f2c41a7d8e35b06c1f4a9d2e7b8035c6a1d4f8e2b9c7a05d3e6f1b8c2a4d790",softwareId:"space-ground-visible",softwareVersion:"space-target-visible-1.0.0-SNAPSHOT",bindingStatus:"RESOLVED",verificationStatus:"NOT_EVALUATED",applicationCount:1,createdAt:"2026-10-27T09:12:00Z",resolvedAt:"2026-10-27T09:18:00Z"}],Qn={...Vn[0],declaration:js,resolvedMapping:{environment:"dev",routeProfileId:"http-async",bindings:[{routeProfileId:"http-sync",toolId:"space-target-query",interfaceId:"space-http-sync",skillCode:"space-target-query",skillVersionId:501,version:1,sha256:"0f2db9df779cf468f86b98425107c1c1080bfdf32076c37849c113fd32cb3762"},{routeProfileId:"http-sync",toolId:"space-visibility-plan",interfaceId:"space-http-sync",skillCode:"space-visibility-plan",skillVersionId:502,version:1,sha256:"80889e9e0a1a180b81cbdb815465e4acdfd2b102f6925792bfc42b13652a4969"},{routeProfileId:"http-async",toolId:"space-visibility-submit",interfaceId:"space-http-async",skillCode:"space-visibility-submit",skillVersionId:503,version:1,sha256:"213b4d463a8cb189e186c1be7ac2b9de1cfbbde1432d3330d6f7c2e20c24492b"},{routeProfileId:"http-sync",toolId:"space-visibility-status",interfaceId:"space-http-sync",skillCode:"space-visibility-status",skillVersionId:504,version:2,sha256:"0756fa0d303c23101fbf02aa60222f6faee7b3e8dc6f24c6ba984107c814d732"},{routeProfileId:"http-sync",toolId:"space-visibility-result",interfaceId:"space-http-sync",skillCode:"space-visibility-result",skillVersionId:505,version:1,sha256:"e601a7bc5f1f3407f57dbb85353708e588e48eef1e1ae4b897f555d192ac5521"}]},applications:[{appId:104,appCode:"space-visibility-agent",name:"可见性规划智能体",type:"CHAT",status:"DRAFT",draftRev:4,currentVersionNo:null}],applicationsTruncated:!1},Bn="ap-demo-store-v1",ss=3;let Ne=900;const Lt={};let It=9100,an=[],rn={},We={},he=[{id:401,appDraftId:"draft-satellite-assistant-r5",appCode:"satellite-assistant",name:"SAT-01 轨道查询",revision:1,inputs:{question:"SAT-01 当前轨道高度是多少？",orbitQuery:"SAT-01"},createdAt:"2026-10-25T09:20:00Z"},{id:402,appDraftId:"draft-satellite-assistant-r5",appCode:"satellite-assistant",name:"遥测片段提取",revision:2,inputs:{question:"取 SAT-01 最近 30 分钟遥测",window:"30m"},createdAt:"2026-10-25T09:25:00Z"},{id:403,appDraftId:"draft-payload-advisor-r2",appCode:"payload-advisor",name:"Ku 频段选型",revision:1,inputs:{task:"评估 Ku 频段载荷",budget:3},createdAt:"2026-10-24T15:00:00Z"}];const ue=n=>`${n}-${++Ne}`,b=()=>new Date().toISOString().replace(/\.\d{3}Z$/,"Z"),K=(n,t,e,s)=>{Sn.unshift({id:++It,actor:"u-demo",actorName:"演示用户",action:n,targetType:t,targetId:e,detail:s,createdAt:b(),result:"SUCCESS"})},M=()=>{try{sessionStorage.setItem(Bn,JSON.stringify({version:ss,seq:Ne,auditSeq:It,apps:Q,skills:ie,runs:ae,approvals:ve,evaluations:Je,evaluationDatasets:De,auditLogs:Sn,importedPackages:an,importedDetails:rn,flowStore:We,debugInputs:he,demoServices:Ue,semanticBindings:Rn,demoReleases:we,demoIntegrationProfiles:Hn,demoConnections:xn,demoBindings:Fn,planningState:F,modelProviders:Fe,modelEntries:$e,promptTemplates:Ze,templateVersionSets:Ye,debugRuns:Ge,demoOnboardingTemplates:Me,mockExtensionState:H,governanceState:O}))}catch{}},ce=(n,t)=>{Array.isArray(t)&&(n.length=0,n.push(...t))},bs=()=>{var e,s,a;let n=null;for(const r of[()=>sessionStorage,()=>localStorage])try{const i=r().getItem(Bn),d=i?JSON.parse(i):null;if(d&&Number(d.version??1)>=1&&Number(d.version??1)<=ss){n=d;break}}catch{}if(!n)return;ce(Q,n.apps);for(const r of Cn){const i=Q.find(d=>d.code===r.code);if(!i){Q.push(JSON.parse(JSON.stringify(r)));continue}!i.definition&&r.definition&&(i.definition=r.definition),i.config={...r.config??{},...i.config??{}}}ce(ie,n.skills);for(const r of cn)ie.some(i=>i.code===r.code)||ie.push(r);for(const r of P.tools)ie.some(i=>i.code===r.code)||ie.push(as(r.code));for(const r of ln)ie.some(i=>i.code===r.code)||ie.push(JSON.parse(JSON.stringify(r)));if(Rs(n.planningState),gs(n.mockExtensionState),Number(H.demoSchemaVersion??0)<2){const r=ie.find(i=>i.code==="um-approval-decide");r!=null&&r.accessPolicy&&JSON.stringify(r.accessPolicy.invokeRoles)==='["UM_OPERATOR"]'&&(r.accessPolicy.invokeRoles=["UM_APPROVER","UM_SECURITY"]),H.demoSchemaVersion=2}const t=[...P.tools,...ln].map(r=>({code:r.code,version:r.version}));for(const r of Q.filter(i=>Ke.has(i.code))){const i=(e=r.config).skillRefs??(e.skillRefs=[]);for(const d of t)i.some(o=>o.code===d.code)||i.push(d)}for(const r of Ke)for(const i of H.versions[r]??[]){if(!String(i.note??"").includes("演示"))continue;const d=(a=(s=i.snapshot)==null?void 0:s.config)==null?void 0:a.skillRefs;if(Array.isArray(d))for(const o of t)d.some(u=>u.code===o.code)||d.push({...o})}Wt(n.governanceState),ce(Fe,n.modelProviders),ce($e,n.modelEntries),ce(Ze,n.promptTemplates),ce(Ge,n.debugRuns),ce(Me,n.demoOnboardingTemplates),n.templateVersionSets&&Object.assign(Ye,n.templateVersionSets),ce(ae,n.runs),ce(ve,n.approvals),ce(Je,n.evaluations),ce(De,n.evaluationDatasets),ce(Sn,n.auditLogs),Array.isArray(n.importedPackages)&&(an=n.importedPackages),n.importedDetails&&typeof n.importedDetails=="object"&&(rn=n.importedDetails),n.flowStore&&typeof n.flowStore=="object"&&(We=n.flowStore),ce(he,n.debugInputs),ce(Ue,n.demoServices),ce(Rn,n.semanticBindings),ce(we,n.demoReleases),ce(Hn,n.demoIntegrationProfiles),ce(xn,n.demoConnections),ce(Fn,n.demoBindings),typeof n.seq=="number"&&(Ne=n.seq),typeof n.auditSeq=="number"&&(It=n.auditSeq)},Cn=Q.map(n=>JSON.parse(JSON.stringify(n)));let Vt=!1;const Hs=()=>{Vt||(Vt=!0,bs())},gn=n=>n.datasetVersion??`${n.name} · v${n.version??1}`,w=(n,t)=>n.find(e=>String(e.id??"")===t||e.code===t),Ut=n=>{var e,s,a,r,i,d,o;const t=[];try{const u=JSON.parse(n);for(const[c,I]of Object.entries(u.paths??{}))for(const[y,S]of Object.entries(I??{})){if(!["get","post","put","delete","patch"].includes(y.toLowerCase()))continue;if(t.length>=50)break;const A=S??{};t.push({operationId:String(A.operationId??`${y}-${c.replace(/[^\w]+/g,"-")}`),method:y.toUpperCase(),path:c,summary:String(A.summary??""),supported:!0,diagnostics:[],requiresAuth:!1,inputSchema:((a=(s=(e=A.requestBody)==null?void 0:e.content)==null?void 0:s["application/json"])==null?void 0:a.schema)??{type:"object",properties:{}},outputSchema:((o=(d=(i=(r=A.responses)==null?void 0:r["200"])==null?void 0:i.content)==null?void 0:d["application/json"])==null?void 0:o.schema)??{type:"object"}})}}catch{}if(!t.length){const u=/^\s*(get|post|put|delete|patch)\s+(\S+)\s*$/gim;let c;for(;(c=u.exec(n))&&t.length<50;)t.push({operationId:c[2].replace(/[^\w]+/g,"-").replace(/^-|-$/g,""),method:c[1].toUpperCase(),path:c[2],supported:!0,diagnostics:[],inputSchema:{type:"object",properties:{}},outputSchema:{type:"object"}})}return t},Ue=[{code:"algorithm-worker",name:"轨道可见性算法服务",command:["java","-jar","demo/algorithm-worker.jar","--server.port=19180"],workDir:"D:/demo/algorithm-worker",port:19180,healthPath:"/health",startupTimeoutMs:3e4,stopTimeoutMs:5e3,enabled:!0,state:"RUNNING",healthUrl:"http://127.0.0.1:19180/health",stale:!1,lastError:null,updatedAt:"2026-10-25T09:40:00Z"},{code:"telemetry-gateway",name:"遥测接入网关",command:["node","gateway.js","--port=38271"],workDir:"D:/demo/telemetry-gateway",port:38271,healthPath:"/health",startupTimeoutMs:2e4,stopTimeoutMs:5e3,enabled:!0,state:"STOPPED",healthUrl:"http://127.0.0.1:38271/health",stale:!1,lastError:"上次停止由人工触发",updatedAt:"2026-10-24T18:20:00Z"}],qn={spaceCode:"sp-sat",releaseId:"rel-003",version:"v3",contentDigest:"a".repeat(64)},Tn={complete:!0,errors:[],releases:[{releaseRef:qn,semanticExtensions:[{id:"ext-visibility-1",extensionId:"ext-visibility-1",extensionSha256:"d".repeat(64),name:"可见性只读投影",opCode:"orbit-query",profile:"read-only@1.0",schemaVersion:"1.0",status:"ACTIVE",descriptor:{objectType:"Satellite",factFields:[{field:"noradId",kind:"attribute"},{field:"inclinationDeg",kind:"attribute"},{field:"operator",kind:"attribute"}],ruleRefs:["rule-orbit-window"]}}],operations:[{opCode:"orbit-query",summary:"轨迹查询",status:"ACTIVE"},{opCode:"health-score",summary:"健康评估",status:"ACTIVE"}],projection:{definitions:{actions:[{ref:{id:"op-orbit-query",name:"轨迹查询"},code:"orbit-query"},{ref:{id:"op-health-score",name:"健康评估"},code:"health-score"}],objects:[{ref:{id:"cls-satellite",name:"Satellite"}},{ref:{id:"cls-orbit",name:"Orbit"}},{ref:{id:"cls-ground-station",name:"GroundStation"}}],rules:[{ref:{id:"rule-orbit-window",name:"可见窗口约束"}}]}}}]},Rn=[{bindingId:"sb-001",appCode:"satellite-assistant",status:"ACTIVE",currentRev:1,releaseRef:qn,objectType:"Satellite",opCode:"orbit-query",skillCode:"orbit_query",skillVersion:2,factFields:[{field:"noradId",kind:"attribute"},{field:"inclinationDeg",kind:"attribute"}],ruleRefs:["rule-orbit-window"],revisions:[{bindingRev:1,releaseRef:qn,extensionId:"ext-visibility-1",extensionDigest:"sha256:"+"b".repeat(64),objectType:"Satellite",opCode:"orbit-query",skill:{skillCode:"orbit_query",skillVersion:2},factFields:[{field:"noradId",kind:"attribute"}],createdAt:"2026-10-24T16:10:00Z"}],createdAt:"2026-10-24T16:10:00Z"}],we=[{releaseId:"rel-003",appCode:"satellite-assistant",appVersion:3,candidateSha256:"sha256-demo-candidate-3",experienceRev:1,integrationProfileId:"ip-sat-1",integrationProfileRev:1,connectionProfileRefs:[{profileId:"cp-sat",profileRev:1}],activationScope:"production",status:"ACTIVE",digest:"sha256-demo-release-3",createdBy:"演示用户",createdAt:"2026-10-25T09:30:00Z",updatedAt:"2026-10-25T09:30:00Z"},{releaseId:"rel-002",appCode:"satellite-assistant",appVersion:2,candidateSha256:"sha256-demo-candidate-2",experienceRev:1,integrationProfileId:"ip-sat-1",integrationProfileRev:1,connectionProfileRefs:[{profileId:"cp-sat",profileRev:1}],activationScope:"production",status:"SUPERSEDED",digest:"sha256-demo-release-2",createdBy:"演示用户",createdAt:"2026-10-20T11:00:00Z",updatedAt:"2026-10-25T09:30:00Z"}],Hn=[{profileId:"ip-sat-1",name:"卫星运行助手接入档案",status:"ACTIVE",revision:1,profileVersion:1,environment:"production",notes:"HTTP 同步 + 审批回写",createdAt:"2026-10-20T10:00:00Z",updatedAt:"2026-10-25T09:30:00Z"}],xn=[{profileId:"cp-sat",name:"算法服务连接",status:"ACTIVE",revision:1,profileVersion:1,baseUrl:"http://127.0.0.1:19180",authType:"NONE",createdAt:"2026-10-20T10:00:00Z",updatedAt:"2026-10-24T12:00:00Z"}],Fn=[{bindingId:"eb-1",environment:"production",appCode:"satellite-assistant",integrationProfileId:"ip-sat-1",connectionProfileId:"cp-sat",status:"ACTIVE",createdAt:"2026-10-25T09:30:00Z",updatedAt:"2026-10-25T09:30:00Z"}];function xs(n,t){const e=t.trim();return e?n==="payload-advisor"?`按当前载荷目录，${e} 建议优先 Ku 频段方案：兼容性检查通过，剩余带宽满足任务需求；如需更高分辨率可切换 Ka 频段。`:n==="space-visibility-agent"?`已按目标「${e}」计算可见窗口：最近一次过境约 12 分钟后开始，持续 8 分 40 秒；建议提前 2 分钟下发观测指令。`:`已收到「${e}」。以 SAT-01 为例：轨道高度 550 km，倾角 97.6°，最近可见窗口 2026-10-26 09:12 UTC，持续 8 分 40 秒，仰角峰值 62°。`:"请描述你要查询的卫星或观测目标。"}function Fs(n){const t=w(ie,n);if(!t)return q();const e=Number(t.executionCount??120),s=Math.max(1,Math.round(e*.82)),a=Math.max(1,Math.round(e*.12)),r=Math.max(1,e-s-a);return C({from:"2026-10-07",to:"2026-11-06",snapshotAt:b(),consistency:"单快照一致读取",total:{calls:e,formal:s,debug:a,evaluation:r},mock:{calls:12},byMode:[{callKind:"正式",calls:s,mockCalls:0,successRate:.99,averageDurationMs:380,p95DurationMs:1240},{callKind:"调试",calls:a,mockCalls:8,successRate:.97,averageDurationMs:180,p95DurationMs:320},{callKind:"评测",calls:r,mockCalls:4,successRate:.95,averageDurationMs:260,p95DurationMs:520}],byDay:[{date:"2026-10-24",realFormalCalls:Math.round(s*.5),successRate:.99,p95:1180},{date:"2026-10-25",realFormalCalls:Math.round(s*.5),successRate:.98,p95:1260}],failures:[]})}const Me=[{code:"satellite-visibility-onboarding",name:"卫星可见性接入模板",kind:"QUERY",status:"PUBLISHED",currentRev:2,updatedAt:"2026-10-26T09:00:00Z",description:"把可见性查询能力接入业务软件的标准问法与判据。",connections:[],experiences:[]},{code:"telemetry-triage-onboarding",name:"遥测异常接入模板",kind:"QUERY",status:"DRAFT",currentRev:1,updatedAt:"2026-10-27T10:00:00Z",description:"遥测异常分诊的输入字段与输出判据草稿。",connections:[],experiences:[]}];function as(n){const t=jn(n);return{...t,kind:"HTTP",type:"HTTP",status:"PUBLISHED",currentVersion:1,executionCount:0,successRate:1,averageDurationMs:120,updatedAt:P.clock,execution:{readonly:t.readonly,sideEffect:t.sideEffect,idempotent:!0,cancellable:n==="planning-task-submit",timeoutMs:3e4},guard:{roles:t.sideEffect?["ADMIN","APPROVER"]:["ADMIN","EDITOR","OPERATOR"]}}}const un={code:"satellite-planning-assistant",name:"卫星规划助手",displayName:"卫星规划助手",type:"CHAT",status:"PUBLISHED",domain:"航天",bizCategory:"观测任务规划",tags:["对空","对地","下传","本体"],description:"从卫星与目标查询，到载荷约束、观测下传规划、人工确认、任务与结果的完整示例。",currentVersionNo:1,draftRev:1,draftId:"draft-satellite-planning-assistant-r1",createdAt:P.clock,updatedAt:P.clock,config:{modelBinding:{modelEntryId:"me-001"},promptSource:"TEMPLATE",promptRefs:[{code:"planning-system",version:1}],systemPrompt:P.systemPrompt,skillRefs:[...P.tools,...ln].map(n=>({code:n.code,version:n.version})),ontology:vs,guard:{roles:["ADMIN","EDITOR","OPERATOR","APPROVER"]},requiredLabels:["PUBLIC"],temperature:.1,maxTurns:20,planningScenarioVersion:1}};Q.unshift(un);Cn.unshift(JSON.parse(JSON.stringify(un)));ie.unshift(...P.tools.map(n=>as(n.code)));ie.unshift(...ln);var Gt;for(const n of[...Q,...Cn].filter(t=>Ke.has(t.code))){const t=(Gt=n.config).skillRefs??(Gt.skillRefs=[]);for(const e of[...P.tools,...ln])t.some(s=>s.code===e.code)||t.push({code:e.code,version:e.version})}Ze.unshift({code:"planning-system",name:"卫星规划助手系统提示词",description:"覆盖目标解析、18个规划工具、本体规则、下传、确认与结果表达。",status:"PUBLISHED",currentVersion:1,variables:[],content:P.systemPrompt,updatedAt:P.clock});Ye["planning-system"]=[{version:1,status:"PUBLISHED",content:P.systemPrompt,publishedAt:P.clock,publishedBy:"演示管理员"}];he.unshift(...P.examples.map((n,t)=>({id:5e3+t,appDraftId:un.draftId,appCode:un.code,name:n.title,revision:1,inputs:{message:n.message},createdAt:P.clock})));Tn.releases.unshift({releaseRef:P.releaseRef,name:P.ontology.space.name,spaceId:P.ontology.space.id,semanticExtensions:[{id:"ext-observation-v1",extensionId:"ext-observation-v1",extensionSha256:P.releaseRef.contentDigest,name:"观测规划事实与规则",opCode:"observation-catalog",profile:"read-only@1.0",schemaVersion:"1.0",status:"ACTIVE",descriptor:{objectType:"Satellite",factFields:[{field:"satelliteId",kind:"attribute"}],ruleRefs:P.ontology.rules.map(n=>n.id)}}],operations:P.ontology.operations.map(n=>({opCode:n.code,summary:n.name,status:"ACTIVE"})),projection:{definitions:{objects:P.ontology.classes.map(n=>({ref:{id:n.id,name:n.name},description:n.displayName})),actions:P.ontology.operations.map(n=>({ref:{id:n.code,name:n.name},code:n.code})),rules:P.ontology.rules.map(n=>({ref:{id:n.id,name:n.name}}))}}});Q.unshift(Ie);Cn.unshift(JSON.parse(JSON.stringify(Ie)));ie.unshift(...cn);const Et=(n=O.actorId)=>O.actors.find(t=>t.id===n);function on(n,t,e=O.actorId){return ts({calls:n,skills:ie,skillVersions:H.skillVersions,skillRefs:t,actor:Et(e)})}function Js(n,t){return n.status="BLOCKED",n.reply=`### 技能调用已阻断

`+t.map(e=>"- "+e).join(`
`),n.toolCalls=n.toolCalls.map(e=>({...e,status:"BLOCKED",output:{reason:"调用权限或版本校验未通过"}})),n.artifacts=[],delete n.plan,delete n.task,delete n.approval,n.ontology=null,n}Tn.releases.unshift({releaseRef:Ie.config.ontology,name:"用户与权限治理本体",spaceId:"sp-user-governance",semanticExtensions:[{id:"ext-user-governance-v1",extensionId:"ext-user-governance-v1",extensionSha256:Ie.config.ontology.contentDigest,name:"业务账号与权限约束",opCode:"user-governance-facts",profile:"read-only@1.0",schemaVersion:"1.0",status:"ACTIVE",descriptor:{objectType:"Account"}}],operations:[{opCode:"user-governance-facts",summary:"用户与权限事实查询",status:"ACTIVE"},{opCode:"user-governance-check",summary:"账号变更规则判定",status:"ACTIVE"}],projection:{definitions:{objects:["Account","Department","Role","Permission","ManagementRequest","ApprovalRecord"].map(n=>({ref:{id:"um-"+n,name:n},description:n})),actions:[],rules:[]}}});{const n=JSON.parse(JSON.stringify(Tn.releases[0]));n.releaseRef={...Ie.config.ontology,releaseId:"rel-user-governance-v2",version:"v2",contentDigest:Te({...Ie.config.ontology,versionNo:2})},n.semanticExtensions.forEach(t=>{t.id="ext-user-governance-v2",t.extensionId=t.id,t.extensionSha256=n.releaseRef.contentDigest}),Tn.releases.splice(1,0,n)}function Xn(n){const t=w(ae,n.runId),e={id:n.runId,runId:n.runId,publicId:n.runId,appCode:Ie.code,appName:Ie.name,status:["WAITING_APPROVAL","SUCCEEDED","REJECTED","BLOCKED"].includes(n.status)?n.status==="BLOCKED"?"FAILED":n.status:"SUCCEEDED",businessStatus:n.status,planning:n,startedAt:(t==null?void 0:t.startedAt)??b(),triggerType:"CHAT",caller:(t==null?void 0:t.caller)??O.actorId,duration:n.toolCalls.length*120,errorMessage:n.status==="BLOCKED"?n.reply:null,nodeCount:mt(n.runId).length,tokenUsage:{input:600,output:280},versionNo:(t==null?void 0:t.versionNo)??Ie.currentVersionNo,output:{text:n.reply,workflow:n.workflow,businessChange:n.businessChange,artifacts:n.artifacts,fileRefs:n.artifacts},versionRefs:(t==null?void 0:t.versionRefs)??{app:`${Ie.code}@${Ie.currentVersionNo}`,skills:Object.fromEntries(n.toolCalls.map(s=>[s.skillCode,1]))}};if(t?Object.assign(t,e):ae.unshift(e),n.approval){const s=n.approval.nodeId;for(const r of ve.filter(i=>i.runId===n.runId&&i.nodeId!==s&&i.status==="PENDING"))Object.assign(r,{status:"APPROVED",decidedAt:b()});const a=ve.find(r=>r.runId===n.runId&&r.nodeId===s);a?Object.assign(a,{status:n.approval.status,summary:n.approval.summary}):ve.unshift({id:`approval-${n.runId}-${s}`,runId:n.runId,nodeId:s,nodeDefId:s,nodeInstanceId:s,nodeName:"账号授权业务审批",status:n.approval.status,requestedAt:b(),summary:n.approval.summary,reason:"业务账号变更需要独立审批",parameterSnapshot:n.businessChange??{}})}}function dt(n,t){var i,d,o;const e=w(Q,n),s=w(ae,t.runId),a=["WAITING_APPROVAL","REJECTED","CANCELLED"].includes(t.status)?t.status:t.status==="BLOCKED"?"FAILED":"SUCCEEDED",r={id:t.runId,runId:t.runId,publicId:t.runId,appCode:n,appName:(e==null?void 0:e.name)??n,status:a,businessStatus:t.status,triggerType:"CHAT",caller:"u-demo",startedAt:(s==null?void 0:s.startedAt)??b(),duration:860+t.toolCalls.length*120,errorMessage:null,nodeCount:Gn(t).length,tokenUsage:{input:1240,output:420},versionNo:(s==null?void 0:s.versionNo)??(e==null?void 0:e.currentVersionNo)??1,versionRefs:(s==null?void 0:s.versionRefs)??{app:`${n}@${(e==null?void 0:e.currentVersionNo)??1}`,model:"deepseek-chat",prompt:"planning-system@1",skills:Object.fromEntries(t.toolCalls.map(u=>[u.skillCode,1])),ontologyRelease:((i=t.ontology)==null?void 0:i.releaseRef.releaseId)??null},output:{text:t.reply,plan:t.plan,task:t.task,artifacts:t.artifacts}};s?Object.assign(s,r):ae.unshift(r),t.approval&&!ve.some(u=>u.runId===t.runId&&u.nodeId===t.approval.nodeId)&&ve.unshift({id:`approval-${t.runId}`,runId:t.runId,nodeId:t.approval.nodeId,nodeDefId:t.approval.nodeId,nodeName:"卫星规划人工确认",status:"PENDING",snapshotDigest:P.releaseRef.contentDigest,requestedAt:b(),reason:"任务提交具有副作用，须人工确认冻结计划。",summary:t.approval.summary,parameterSnapshot:{plan:t.plan,actionPlan:{summary:t.approval.summary,target:(d=t.plan)==null?void 0:d.targetName,diff:{selectedWindows:(o=t.plan)==null?void 0:o.windows.map(u=>u.id)},risk:"STANDARD",approvalPolicy:"MOCK_PLAN_CONFIRMATION",expiresAt:"2026-12-31T00:00:00Z"}}})}const Ws=[[/^\/v1\/runs\/([^/]+)\/semantic-results$/,n=>{var t;return C({runId:n[1],results:[],rejected:[],planning:((t=F.results[n[1]])==null?void 0:t.ontology)??null})}],[/^\/admin\/apps\/([^/]+)\/planning-scenarios$/,n=>Ke.has(n[1])?C({clock:P.clock,planningDate:P.planningDate,examples:P.examples,tools:[...P.tools,...ln],ontology:P.releaseRef}):q()],[/^\/v1\/apps\/([^/]+)\/input-schema$/,n=>{var a;const t=w(Q,n[1]);if(!t)return q();const e=((a=t.definition)==null?void 0:a.inputs)??[],s={};for(const r of e)s[String(r.key)]={type:r.type==="NUMBER"?"number":r.type==="BOOLEAN"?"boolean":"string",description:r.description??""};return C({versionNo:t.currentVersionNo,inputSchema:{type:"object",required:e.filter(r=>r.required).map(r=>r.key),properties:s}})}],[/^\/auth\/dev-token$/,()=>C({accessToken:"mock-token-demo",expiresIn:86400,user:{tenantId:"t-demo",principalId:"u-demo",roles:["ADMIN","EDITOR","APPROVER"]}})],[/^\/auth\/session$/,()=>C(Gs())],[/^\/admin\/apps$/,(n,t,e)=>{if(t==="GET")return C(Q);const s={code:(e==null?void 0:e.code)??ue("app"),name:(e==null?void 0:e.name)??"新智能体",displayName:(e==null?void 0:e.name)??"新智能体",status:"DRAFT",type:(e==null?void 0:e.type)??"AGENT",domain:(e==null?void 0:e.domain)??"航天",bizCategory:(e==null?void 0:e.bizCategory)??"",tags:(e==null?void 0:e.tags)??[],description:(e==null?void 0:e.description)??"",currentVersionNo:null,draftRev:1,createdAt:b(),updatedAt:b(),draftId:`draft-${(e==null?void 0:e.code)??"new"}-r1`,config:{model:"deepseek-chat",temperature:.3,maxTurns:8,...(e==null?void 0:e.config)??{}}};return Q.unshift(s),K("APP_CREATE","APP",s.code,`创建应用「${s.name}」`),M(),C(s)}],[/^\/admin\/apps\/([^/]+)\/copy$/,(n,t,e)=>{const s=w(Q,n[1]);if(!s)return q();const a={...s,code:(e==null?void 0:e.code)??`${s.code}-copy`,name:(e==null?void 0:e.name)??`${s.name}（副本）`,displayName:(e==null?void 0:e.name)??`${s.name}（副本）`,status:"DRAFT",currentVersionNo:null,draftRev:1,createdAt:b(),updatedAt:b(),config:{...s.config??{}},draftId:`draft-${(e==null?void 0:e.code)??`${s.code}-copy`}-r1`};return Q.unshift(a),K("APP_COPY","APP",a.code,`从 ${s.code} 复制`),M(),C(a)}],[/^\/admin\/apps\/([^/]+)\/config$/,(n,t,e)=>{const s=w(Q,n[1]);if(!s)return q();if(t!=="GET"&&(e==null?void 0:e.draftRev)!==s.draftRev)return Re("草稿修订已变化，请刷新");if(t!=="GET"){const a=(e==null?void 0:e.config)??e??{};return s.config={...s.config??{},...a},Ke.has(s.code)&&(e!=null&&e.config)&&(s.config.ontology=a.ontology??null),s.draftRev=(s.draftRev??0)+1,s.draftId=`draft-${s.code}-r${s.draftRev}`,s.updatedAt=b(),K("APP_CONFIG_SAVE","APP",s.code,`保存会话配置（草稿 r${s.draftRev}）`),M(),C(s)}return C(s.config??{})}],[/^\/admin\/apps\/([^/]+)\/integration\/credentials$/,()=>C([])],[/^\/admin\/apps\/([^/]+)\/integration$/,n=>C({appCode:n[1],endpoints:[],credentials:[]})],[/^\/admin\/apps\/([^/]+)\/versions$/,n=>{const t=w(Q,n[1]),e=Number((t==null?void 0:t.currentVersionNo)??0);if(!e)return C([]);const s=Array.from({length:e},(a,r)=>{const i=e-r;return{versionNo:i,status:i===e?"PUBLISHED":"SUPERSEDED",snapshotDigest:`sha256-${n[1]}-v${i}`,publishedAt:"2026-10-24T15:20:00Z",publishedBy:"演示用户",note:""}});return C(s)}],[/^\/admin\/apps\/([^/]+)\/runs$/,(n,t,e)=>{if(t==="GET")return C(ae.filter(i=>i.appCode===n[1]));const s=w(Q,n[1]),a=ue("run"),r={id:a,runId:a,publicId:a.replace("run-","run-demo-"),appCode:n[1],appName:(s==null?void 0:s.name)??n[1],status:"SUCCEEDED",triggerType:"MANUAL",caller:"u-demo",startedAt:b(),duration:2140,errorMessage:null,nodeCount:6,tokenUsage:{input:1120,output:260},versionNo:Number((e==null?void 0:e.versionNo)??(s==null?void 0:s.currentVersionNo)??0)};return ae.unshift(r),K("RUN_FORMAL","RUN",a,`正式运行 ${(s==null?void 0:s.name)??n[1]} v${r.versionNo}`),M(),C({runId:a})}],[/^\/admin\/apps\/([^/]+)\/sessions$/,()=>C([])],[/^\/admin\/apps\/([^/]+)\/publish\/precheck$/,n=>{const t=w(Q,n[1]);if(!t)return q();const e={precheckId:ue("precheck"),draftRev:t.draftRev,candidateSha256:"a".repeat(64),evalReportId:"ev-303",passed:!0,result:"PASS",gateMode:"BLOCKING",publicationTarget:"NON_PRODUCTION",evaluationEnvironment:"REAL",environmentConfirmationRequired:!1,report:{checks:[{group:"工具与技能绑定齐全",result:"PASS",detail:"5 个技能均解析到精确版本"},{group:"评测报告与候选摘要一致",result:"PASS",detail:"报告 ev-303 与候选摘要一致"},{group:"无未解析的凭据引用",result:"PASS",detail:"未发现凭据引用"},{group:"评测门禁",result:"PASS",detail:"安全 100%、无新增回归"}],warnings:[]}};return Lt[t.code]={draftRev:t.draftRev,precheckId:String(e.precheckId),passed:!0},K("APP_PRECHECK","APP",t.code,"发布预检通过"),M(),C(e)}],[/^\/admin\/apps\/([^/]+)\/publish\/commit$/,n=>{const t=w(Q,n[1]);if(!t)return q();const e=Lt[t.code];return!e||e.draftRev!==t.draftRev?Re("缺少当前草稿修订的发布预检，请先运行预检"):(t.status="PUBLISHED",t.currentVersionNo=(t.currentVersionNo??0)+1,t.draftRev=(t.draftRev??1)+1,t.updatedAt=b(),K("APP_PUBLISH","APP",t.code,`发布 v${t.currentVersionNo}`),M(),C(t))}],[/^\/admin\/apps\/([^/]+)\/(submit|withdraw|approve|reject|disable|enable|archive|restore)$/,(n,t,e)=>{const s=w(Q,n[1]);if(!s)return q();const a={submit:"IN_REVIEW",withdraw:"DRAFT",approve:"PUBLISHED",reject:"DRAFT",disable:"DISABLED",enable:"PUBLISHED",archive:"ARCHIVED",restore:"DRAFT"};return s.status=a[n[2]]??s.status,s.updatedAt=b(),K(`APP_${n[2].toUpperCase()}`,"APP",s.code,(e==null?void 0:e.note)??`生命周期动作：${n[2]}`),M(),C(s)}],[/^\/admin\/flows\/([^/]+)$/,(n,t,e)=>{const s=w(Q,n[1]);if(!s)return q();if(t!=="GET"&&(e==null?void 0:e.draftRev)!==s.draftRev)return Re("流程修订已变化，请刷新");if(t==="GET")return C(We[n[1]]??{definition:s.definition??null,layout:{},draftRev:s.draftRev});const a=(e==null?void 0:e.definition)??e;return!a||!Array.isArray(a.nodes)?xe("编排缺少节点"):(We[n[1]]={definition:a,layout:(e==null?void 0:e.layout)??{},draftRev:(s.draftRev??1)+1},s.definition=a,s.draftRev=We[n[1]].draftRev,s.updatedAt=b(),K("APP_FLOW_SAVE","APP",s.code,`保存流程编排（草稿 r${s.draftRev}）`),M(),C(We[n[1]]))}],[/^\/admin\/apps\/([^/]+)$/,(n,t,e)=>{const s=w(Q,n[1]);return s?(t==="GET"||(Object.assign(s,e??{}),s.draftRev=(s.draftRev??1)+1,s.updatedAt=b(),K("APP_UPDATE","APP",s.code,"编辑应用基本信息"),M()),C(s)):q()}],[/^\/admin\/model-providers$/,(n,t)=>C(t==="GET"?Fe:Fe[0])],[/^\/admin\/model-providers\/([^/]+)\/(credentials\/rotate|disable|quota|test)$/,n=>C(w(Fe,n[1])??{id:n[1]})],[/^\/admin\/model-providers\/([^/]+)$/,(n,t)=>{const e=w(Fe,n[1]);return t==="GET"?e?C(e):q():C(e??{id:n[1]})}],[/^\/admin\/model-entries$/,(n,t)=>C(t==="GET"?$e:$e[0])],[/^\/admin\/model-entries\/([^/]+)\/metering$/,n=>{var t;return C(((t=w($e,n[1]))==null?void 0:t.metering)??{})}],[/^\/admin\/model-entries\/([^/]+)$/,(n,t)=>{const e=w($e,n[1]);return t==="GET"?e?C(e):q():C(e??{id:n[1]})}],[/^\/admin\/prompt-templates$/,(n,t)=>C(t==="GET"?Ze:Ze[0])],[/^\/admin\/prompt-templates\/([^/]+)\/versions$/,n=>C(Ye[n[1]]??[])],[/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/render$/,()=>C({rendered:"（渲染后的提示词内容示例）"})],[/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/publish$/,n=>C({code:n[1],version:Number(n[2]),status:"PUBLISHED"})],[/^\/admin\/prompt-templates\/([^/]+)$/,(n,t,e)=>{const s=w(Ze,n[1]);return s?t==="GET"?C({...s,versions:Ye[s.code]??[]}):(Object.assign(s,e??{}),M(),C({...s,versions:Ye[s.code]??[]})):q()}],[/^\/admin\/skills\/import-openapi\/preview$/,(n,t,e)=>{const s=Ut(String((e==null?void 0:e.document)??""));return C(s.length?{operations:s}:{operations:[],message:"未解析到操作（需 OpenAPI paths，或 METHOD /path 形式的接口行）"})}],[/^\/admin\/skills\/import-openapi$/,(n,t,e)=>{var u;const s=Ut(String((e==null?void 0:e.document)??"")),a=Array.isArray(e==null?void 0:e.includeOps)?e.includeOps:[],r=(e==null?void 0:e.overrides)??{},i=String(((u=e==null?void 0:e.defaults)==null?void 0:u.baseUrl)??""),d=a.map((c,I)=>{const y=s.find(v=>v.operationId===c),S=r[c]??{},A=String(S.code??c).replace(/[^a-z0-9_]/g,"_").slice(0,64)||`openapi_${++Ne}`,T={code:A,name:String(S.name??(y==null?void 0:y.summary)??`导入技能 ${I+1}`),status:"DRAFT",kind:"HTTP",description:String(S.description??"由 OpenAPI 文档导入。"),currentVersion:1,executionCount:0,successRate:0,averageDurationMs:0,updatedAt:b(),readonly:!0,sideEffect:!1,version:1,sha256:"c".repeat(64),method:(y==null?void 0:y.method)??"GET",path:(y==null?void 0:y.path)??"",baseUrl:i,guard:S.guard??{roles:[],attributes:{}},timeoutMs:S.timeoutMs??5e3,returnSpec:S.returnSpec??null,errorSemantics:S.errorSemantics??[]};return ie.unshift(T),{code:A,name:T.name,status:"DRAFT"}}),o=s.length===0?[{code:"NO_OPERATIONS",message:"文档未解析到可用操作"}]:[];return K("SKILL_IMPORT","SKILL",d.map(c=>c.code).join(","),`导入 ${d.length} 个技能草稿`),M(),C({drafts:d,warnings:o})}],[/^\/admin\/skills$/,(n,t,e)=>{if(t==="GET")return C(ie);const s={code:(e==null?void 0:e.code)??ue("skill"),name:(e==null?void 0:e.name)??"新技能",status:"DRAFT",kind:(e==null?void 0:e.kind)??"HTTP",description:(e==null?void 0:e.description)??"",currentVersion:1,executionCount:0,successRate:0,averageDurationMs:0,updatedAt:b(),...e??{}};return ie.unshift(s),K("SKILL_CREATE","SKILL",s.code,`注册技能「${s.name}」`),M(),C(s)}],[/^\/admin\/skills\/semantic-catalog$/,()=>C(Tn)],[/^\/admin\/skills\/([^/]+)\/stats$/,n=>Fs(n[1])],[/^\/admin\/skills\/([^/]+)\/referenced-by$/,()=>C([])],[/^\/admin\/skills\/([^/]+)\/versions$/,()=>C([{version:2,status:"PUBLISHED",publishedAt:"2026-10-20T10:00:00Z"},{version:1,status:"DEPRECATED",publishedAt:"2026-09-01T10:00:00Z"}])],[/^\/admin\/skills\/([^/]+)\/drafts\/([^/]+)\/(acceptance|connection-test|publish)$/,n=>{const t=w(ie,n[1]);return t&&(t.status=n[3]==="publish"?"PUBLISHED":t.status,t.updatedAt=b()),M(),C({code:n[1],version:Number(n[2]),status:n[3]==="publish"?"PUBLISHED":"ACCEPTED"})}],[/^\/admin\/skills\/([^/]+)\/(disable|draft)$/,n=>{const t=w(ie,n[1]);return t?(t.status=n[2]==="disable"?"DISABLED":"DRAFT",t.updatedAt=b(),K(`SKILL_${n[2].toUpperCase()}`,"SKILL",t.code,n[2]==="disable"?"停用技能":"转为草稿"),M(),C(t)):q()}],[/^\/admin\/skills\/([^/]+)$/,(n,t,e)=>{const s=w(ie,n[1]);if(!s)return q();if(t==="GET"){const a=jn(n[1]);if(a)return C({...s,...a,versions:[{versionNo:1,version:1,status:"PUBLISHED",sha256:P.releaseRef.contentDigest,inputSchema:a.inputSchema,outputSchema:a.outputSchema,execution:s.execution,definition:{inputSchema:a.inputSchema,outputSchema:a.outputSchema,execution:s.execution}}]});const r={readonly:!0,sideEffect:!1,idempotent:!0,timeoutMs:5e3};return C({...s,versions:[{versionNo:s.currentVersion,version:s.currentVersion,status:s.status==="DISABLED"?"DISABLED":"PUBLISHED",publishedAt:s.updatedAt,sha256:s.sha256,inputSchema:{type:"object",properties:{target:{type:"string"}}},outputSchema:{type:"object",properties:{visible:{type:"boolean"}}},definition:{inputSchema:{type:"object",properties:{target:{type:"string"}}},execution:r},execution:r}]})}return Object.assign(s,e??{},{updatedAt:b()}),K("SKILL_UPDATE","SKILL",s.code,"编辑技能"),M(),C(s)}],[/^\/admin\/runs\/page$/,(n,t)=>C(t==="GET"?{items:ae,total:ae.length,page:1,size:20}:null)],[/^\/admin\/runs$/,(n,t,e)=>{if(t==="GET")return C(ae);const s=String((e==null?void 0:e.appCode)??Q[0].code),a=w(Q,s),r={id:ue("run"),runId:"",publicId:ue("run-demo"),appCode:s,appName:(a==null?void 0:a.name)??s,status:"SUCCEEDED",triggerType:(a==null?void 0:a.type)==="CHAT"?"CHAT":"API",caller:"u-demo",startedAt:b(),duration:2380,errorMessage:null,nodeCount:6,tokenUsage:{input:1240,output:320}};return r.runId=r.id,ae.unshift(r),K("RUN_CREATE","RUN",r.id,`运行应用 ${r.appName}`),M(),C(r)}],[/^\/admin\/debug-inputs$/,(n,t,e)=>{if(t==="GET")return C({items:he,hasMore:!1,nextAfterId:0});const s={id:++Ne,appDraftId:String((e==null?void 0:e.appDraftId)??""),appCode:"",name:String((e==null?void 0:e.name)??"未命名输入"),revision:1,inputs:(e==null?void 0:e.inputs)??{},createdAt:b()};return he.unshift(s),K("DEBUG_INPUT_SAVE","APP",s.appDraftId,`保存命名输入「${s.name}」`),M(),C(s)}],[/^\/admin\/debug-inputs\/([^/]+)\/promote$/,(n,t,e)=>{const s=w(he,n[1]);if(!s)return q();const a={id:ue("ed"),name:String((e==null?void 0:e.name)??s.name),version:1,caseCount:1,status:"ACTIVE",updatedAt:b()};return De.unshift(a),K("DEBUG_INPUT_PROMOTE","DATASET",a.id,`将「${s.name}」提升为评测用例`),M(),C({datasetVersion:gn(a),caseCount:1})}],[/^\/admin\/debug-inputs\/([^/]+)$/,(n,t,e)=>{const s=w(he,n[1]);return s?t==="GET"?C(s):(t==="DELETE"?he=he.filter(a=>a!==s):(Object.assign(s,e??{}),s.revision=(s.revision??1)+1),M(),C(t==="DELETE"?null:s)):q()}],[/^\/admin\/debug-runs$/,(n,t,e)=>{if(t==="GET")return C(Ge);const s=String((e==null?void 0:e.appCode)??"payload-advisor"),a={id:ue("dbg"),status:"PAUSED",appCode:s,startedAt:b(),controls:{paused:!0},variables:(e==null?void 0:e.variables)??{task:"演示任务",budget:3}};return Ge.unshift(a),K("DEBUG_RUN_CREATE","RUN",a.id,`调试运行 ${s}`),M(),C(a)}],[/^\/admin\/debug-runs\/([^/]+)\/events$/,n=>{var t;return ot($t(String(((t=w(Ge,n[1]))==null?void 0:t.status)??"PAUSED")))}],[/^\/admin\/debug-runs\/([^/]+)\/(controls|variables|status|resume|cancel)$/,(n,t,e)=>{const s=w(Ge,n[1]);return s?(n[2]==="resume"&&(s.status="RUNNING"),n[2]==="cancel"&&(s.status="CANCELLED"),n[2]==="controls"&&Object.assign(s.controls??{},e??{},{paused:!1}),n[2]==="variables"&&Object.assign(s.variables??{},e??{}),M(),C(s)):q()}],[/^\/admin\/debug-runs\/([^/]+)\/node\/([^/]+)\/prompt$/,()=>C({label:"轨道分析",raw:"（本地固定数据：节点提示词内容）",status:"SUCCEEDED",duration:1820})],[/^\/admin\/debug-runs\/([^/]+)$/,(n,t,e)=>{const s=w(Ge,n[1]);return s?(t==="GET"||(Object.assign(s,e??{}),M()),C(s)):q()}],[/^\/admin\/runs\/([^/]+)\/events$/,n=>{var t;return ot(F.results[n[1]]?it(F.results[n[1]]):$t(String(((t=w(ae,n[1]))==null?void 0:t.status)??"SUCCEEDED")))}],[/^\/admin\/runs\/([^/]+)\/(cancel|reexecute|replay|reproduce)$/,n=>{const t=w(ae,n[1]);if(!t)return q();if(n[2]==="cancel")Ss(n[1]),t.status="CANCELLED",ve.filter(e=>e.runId===t.id&&e.status==="PENDING").forEach(e=>{e.status="REJECTED",e.decidedAt=b()}),K("RUN_CANCEL","RUN",t.id,"取消运行");else{const e={...t,id:ue("run"),publicId:ue("run-demo"),status:"SUCCEEDED",startedAt:b(),errorMessage:null};return e.runId=e.id,ae.unshift(e),K("RUN_REEXECUTE","RUN",String(t.id),`重新执行（新运行 ${e.id}）`),M(),C(e)}return M(),C(t)}],[/^\/admin\/runs\/([^/]+)$/,n=>w(ae,n[1])?C(Ms(n[1])):q()],[/^\/admin\/capability-packages$/,()=>C({items:[...Vn,...an]})],[/^\/admin\/capability-packages\/import$/,(n,t,e)=>{const s=(e==null?void 0:e.package)??{},a=s.metadata??{},r=++Ne,i={packageVersionId:r,packageId:String(a.name??`package-${r}`),version:String(a.version??"0.0.1"),sha256:String(r).padStart(64,"0"),softwareId:String((s.software??{}).system??"unknown-software"),softwareVersion:String((s.software??{}).version??""),bindingStatus:"UNRESOLVED",verificationStatus:"NOT_EVALUATED",applicationCount:0,createdAt:b()};return an.unshift(i),rn[String(r)]={...i,declaration:s,resolvedMapping:{environment:"",routeProfileId:"",bindings:[]},applications:[],applicationsTruncated:!1},K("CAPABILITY_IMPORT","PACKAGE",i.packageId,`登记能力包 ${i.packageId} ${i.version}`),M(),C({packageVersionId:r})}],[/^\/admin\/capability-packages\/([^/]+)\/bindings\/resolve$/,(n,t,e)=>{const s=String(n[1]),a=s==="1"?Qn:rn[s];if(!a)return q();a.resolvedMapping={environment:String((e==null?void 0:e.environment)??"dev"),routeProfileId:String((e==null?void 0:e.routeProfileId)??""),bindings:Array.isArray(e==null?void 0:e.bindings)?e.bindings:[]};const r=[...Vn,...an].find(i=>String(i.packageVersionId)===s);return r&&(r.bindingStatus="RESOLVED",r.resolvedAt=b()),K("CAPABILITY_RESOLVE","PACKAGE",String(a.packageId??s),"解析技能绑定"),M(),C(a)}],[/^\/admin\/capability-packages\/([^/]+)\/applications$/,(n,t,e)=>{const s=String(n[1]),a=s==="1"?Qn:rn[s];if(!a)return q();const r=a.declaration??{},i=Array.isArray(r.agents)?r.agents:[],d=Array.isArray(e==null?void 0:e.applications)?e.applications:[],u=(d.length?d:i).map((I,y)=>{const S=String(I.code??I.appCode??`package-${s}-app-${y+1}`),A=w(Q,S);if(A)return A;const T={code:S,name:String(I.name??S),displayName:String(I.name??S),status:"DRAFT",type:String(I.type??"CHAT"),domain:"航天",bizCategory:"能力包",tags:[String(a.packageId??"能力包")],description:`由能力包 ${a.packageId} ${a.version} 生成。`,currentVersionNo:null,draftRev:1,draftId:`draft-${S}-r1`,createdAt:b(),updatedAt:b(),config:{modelBinding:{modelEntryId:"me-001"},promptSource:"TEMPLATE",promptRefs:[],skillRefs:(I.skillRefs??[]).map(v=>({code:v.skillCode??v.code,version:v.version??1})),guard:{roles:["OPERATOR"]},requiredLabels:["PUBLIC"],temperature:.2,maxTurns:8,systemPrompt:"由能力包生成的对话助手，按声明的工具契约执行。"}};return Q.unshift(T),T});a.applications=u.map((I,y)=>({appId:200+y,appCode:I.code,name:I.name,type:I.type??"CHAT",status:I.status,draftRev:I.draftRev??1,currentVersionNo:I.currentVersionNo??null})),a.applicationCount=u.length;const c=[...Vn,...an].find(I=>String(I.packageVersionId)===s);return c&&(c.applicationCount=u.length),K("CAPABILITY_APPLICATIONS","PACKAGE",String(a.packageId??s),`生成 ${u.length} 个应用草稿`),M(),C(a.applications)}],[/^\/admin\/capability-packages\/([^/]+)$/,n=>{const t=String(n[1]);if(t==="1")return C(Qn);const e=rn[t];return e?C(e):q()}],[/^\/admin\/_demo\/reset$/,async()=>{await ls();try{sessionStorage.removeItem(Bn),sessionStorage.removeItem("ap-business-governance-v1")}catch{}try{localStorage.removeItem(Bn)}catch{}return C({ok:!0,reload:!0})}],[/^\/admin\/approvals\/([^/]+)\/([^/]+)$/,async(n,t,e)=>{var i,d,o;if(n[2]==="planning-confirm"){const u=JSON.parse(JSON.stringify(F)),c=(i=F.pending[n[1]])==null?void 0:i.response,I=Ts(n[1],(e==null?void 0:e.approved)!==!1&&(e==null?void 0:e.decision)!=="REJECT");if(I!=null&&I.conflict)return new Response(JSON.stringify({code:"APPROVAL_CONFLICT",message:I.conflict,data:null}),{status:409,headers:{"Content-Type":"application/json"}});if(I!=null&&I.response){const y=I.response,S=on(y.toolCalls.slice(((d=c==null?void 0:c.toolCalls)==null?void 0:d.length)??0),(c==null?void 0:c.boundSkillRefs)??((o=w(Q,un.code))==null?void 0:o.config.skillRefs)??[],c==null?void 0:c.businessActorId);if(S.length)return Object.assign(F,u),M(),Re(S.join("；"));await vn(y),F.results[y.runId]=JSON.parse(JSON.stringify(y));const A=w(ae,n[1]);dt((A==null?void 0:A.appCode)??un.code,y);const T=ve.find(v=>v.runId===n[1]&&v.nodeId===n[2]);return T&&Object.assign(T,{status:y.approval.status,decidedAt:b()}),K(y.status==="REJECTED"?"PLANNING_REJECT":"PLANNING_APPROVE","RUN",n[1],y.reply.split(`
`)[0]),M(),C(T)}}const s=ve.find(u=>u.runId===n[1]&&(u.nodeInstanceId===n[2]||u.nodeId===n[2]));if(!s)return q();if(s.status!=="PENDING")return Re("该确认单已处理，请刷新后查看最新状态");const a=(e==null?void 0:e.approved)!==!1&&(e==null?void 0:e.decision)!=="REJECT";s.status=a?"APPROVED":"REJECTED",s.decidedAt=b();const r=w(ae,s.runId);return r&&s.runId==="run-104"?(r.status=a?"SUCCEEDED":"REJECTED",r.duration=3200,a&&(r.tokenUsage={input:2680,output:640})):r&&r.status==="WAITING_APPROVAL"&&(r.status=a?"SUCCEEDED":"REJECTED"),K(a?"APPROVAL_APPROVE":"APPROVAL_REJECT","APPROVAL",String(s.id),`${s.nodeName}：${a?"批准":"拒绝"}`),M(),C(s)}],[/^\/admin\/approvals/,()=>C(ve)],[/^\/admin\/evaluation-datasets\/([^/]+)\/([^/]+)$/,n=>{const t=decodeURIComponent(n[2]),e=De.find(a=>gn(a)===t);if(!e)return q();const s=Array.from({length:Number(e.caseCount??0)},(a,r)=>({caseId:`${e.id}-case-${r+1}`,name:`用例 ${r+1}`,input:{target:"SAT-01",station:"ST-02"},expected:{visible:!0},assertions:[{kind:"EQUALS",path:"visible",value:!0}]}));return C({datasetVersion:t,cases:s})}],[/^\/admin\/evaluation-datasets\/([^/]+)$/,(n,t,e)=>{const s=w(De,n[1]);return s?(t!=="GET"&&Object.assign(s,e??{}),M(),C(s)):q()}],[/^\/admin\/evaluation-datasets$/,(n,t,e)=>{if(t==="GET")return C({items:De.map(a=>({datasetVersion:gn(a),appCode:"space-visibility-agent",caseCount:a.caseCount,savedAt:a.updatedAt??b()})),hasMore:!1,nextAfterVersion:null});const s={id:ue("ed"),name:String((e==null?void 0:e.name)??"新评测数据集"),version:1,caseCount:Number((e==null?void 0:e.caseCount)??0),status:"ACTIVE",updatedAt:b()};return De.unshift(s),K("DATASET_CREATE","DATASET",s.id,`新建评测数据集「${s.name}」`),M(),C({datasetVersion:gn(s),caseCount:s.caseCount})}],[/^\/admin\/evaluations$/,(n,t,e)=>{var r;if(t==="GET")return C(Je);const s=w(De,String((e==null?void 0:e.datasetId)??((r=De[0])==null?void 0:r.id)??"")),a={id:ue("ev"),datasetId:(s==null?void 0:s.id)??"",datasetName:(s==null?void 0:s.name)??"",status:"RUNNING",passed:0,failed:0,total:(s==null?void 0:s.caseCount)??0,passRate:0,runId:null,startedAt:b(),duration:0};return Je.unshift(a),K("EVALUATION_CREATE","EVALUATION",a.id,`发起评测「${a.datasetName}」`),M(),C(a)}],[/^\/admin\/evaluations\/([^/]+)\/resume$/,(n,t,e)=>{const s=w(Je,n[1]);return s?(s.status="SUCCEEDED",s.passed=Number((e==null?void 0:e.passed)??s.total),s.failed=Number((e==null?void 0:e.failed)??0),s.passRate=s.total?s.passed/s.total:1,s.duration=42e3,K("EVALUATION_RESUME","EVALUATION",String(s.id),"继续并完成评测"),M(),C(s)):q()}],[/^\/admin\/evaluations\/([^/]+)$/,(n,t)=>{const e=w(Je,n[1]);return t==="GET"?e?C(e):q():C(e??{id:n[1]})}],[/^\/admin\/services$/,(n,t,e)=>{if(t==="GET")return C(Ue);const s={code:String((e==null?void 0:e.code)??"new-service"),name:String((e==null?void 0:e.name)??"新服务"),command:Array.isArray(e==null?void 0:e.command)?e.command:["java","-jar","service.jar"],workDir:String((e==null?void 0:e.workDir)??"D:/demo"),port:Number((e==null?void 0:e.port)??8080),healthPath:String((e==null?void 0:e.healthPath)??"/health"),startupTimeoutMs:3e4,stopTimeoutMs:5e3,enabled:!0,state:"STOPPED",healthUrl:`http://127.0.0.1:${Number((e==null?void 0:e.port)??8080)}${String((e==null?void 0:e.healthPath)??"/health")}`,updatedAt:b()};return Ue.unshift(s),K("SERVICE_CREATE","SERVICE",s.code,`登记服务 ${s.name}`),M(),C(s)}],[/^\/admin\/services\/([^/]+)\/health$/,n=>{const t=Ue.find(e=>e.code===n[1]);return t?C({reachable:t.state==="RUNNING",statusCode:t.state==="RUNNING"?200:null,latencyMs:18,checkedAt:b()}):q()}],[/^\/admin\/services\/([^/]+)\/logs$/,n=>{const t=Ue.find(e=>e.code===n[1]);return t?C({available:!0,lines:[`[demo] ${t.code} 启动于 ${t.updatedAt}`,"[demo] 已加载配置 config/application.yml","[demo] started on port "+t.port]}):q()}],[/^\/admin\/services\/([^/]+)\/(start|stop|restart)$/,(n,t)=>{const e=Ue.find(s=>s.code===n[1]);return e?(e.state=n[2]==="stop"?"STOPPED":"RUNNING",e.updatedAt=b(),K("SERVICE_"+n[2].toUpperCase(),"SERVICE",e.code,`${e.name} ${e.state}`),M(),C(e)):q()}],[/^\/admin\/services\/([^/]+)$/,(n,t,e)=>{const s=Ue.find(a=>a.code===n[1]);return s?(t==="PUT"&&(Object.assign(s,e??{}),K("SERVICE_UPDATE","SERVICE",s.code,`更新服务 ${s.name}`),M()),C(s)):q()}],[/^\/admin\/semantic-bindings$/,(n,t,e)=>{if(t==="GET")return C(Rn);const s={bindingId:`sb-${++Ne}`,appCode:String((e==null?void 0:e.appCode)??"satellite-assistant"),status:"ACTIVE",releaseRef:(e==null?void 0:e.releaseRef)??qn,objectType:String((e==null?void 0:e.objectType)??"Satellite"),opCode:String((e==null?void 0:e.opCode)??"orbit-query"),skillCode:String((e==null?void 0:e.skillCode)??"orbit_query"),skillVersion:Number((e==null?void 0:e.skillVersion)??2),factFields:(e==null?void 0:e.factFields)??[],ruleRefs:(e==null?void 0:e.ruleRefs)??[],createdAt:b()};return Rn.unshift(s),K("SEMANTIC_BINDING_CREATE","APP",s.appCode,`语义绑定 ${s.objectType}/${s.opCode}`),M(),C(s)}],[/^\/admin\/semantic-bindings\/([^/]+)$/,(n,t,e)=>{const s=Rn.find(a=>a.bindingId===n[1]);return s?(t!=="GET"&&Object.assign(s,e??{}),M(),C(s)):q()}],[/^\/admin\/config-releases$/,(n,t,e)=>{if(t==="GET")return C(we);const s={releaseId:`rel-${++Ne}`,appCode:String((e==null?void 0:e.appCode)??"satellite-assistant"),appVersion:Number((e==null?void 0:e.appVersion)??3),candidateSha256:`sha256-demo-cand-${Ne}`,experienceRev:1,integrationProfileId:"ip-sat-1",integrationProfileRev:1,connectionProfileRefs:[],activationScope:String((e==null?void 0:e.environment)??"production"),status:"DRAFT",digest:`sha256-demo-rel-${Ne}`,createdBy:"演示用户",createdAt:b(),updatedAt:b()};return we.unshift(s),K("CONFIG_RELEASE_CREATE","APP",s.appCode,`新建配置发布 ${s.releaseId}`),M(),C(s)}],[/^\/admin\/config-releases\/([^/]+)\/(activate|disable)$/,n=>{const t=we.find(e=>e.releaseId===n[1]);return t?(n[2]==="activate"?(we.forEach(e=>{e.appCode===t.appCode&&e.activationScope===t.activationScope&&(e.status="SUPERSEDED")}),t.status="ACTIVE"):t.status="DISABLED",t.updatedAt=b(),M(),C(t)):q()}],[/^\/admin\/config-releases\/([^/]+)$/,(n,t,e)=>{const s=we.find(a=>a.releaseId===n[1]);return s?(t!=="GET"&&Object.assign(s,e??{}),C(s)):q()}],[/^\/admin\/integration-profiles$/,()=>C(Hn)],[/^\/admin\/connection-profiles$/,()=>C(xn)],[/^\/admin\/environment-bindings$/,()=>C(Fn)],[/^\/admin\/apps\/([^/]+)\/experiences$/,n=>C({appCode:n[1],experienceRev:1,status:"ACTIVE",createdBy:"演示用户",createdAt:"2026-10-24T10:00:00Z",updatedAt:"2026-10-24T10:00:00Z",revisions:[{experienceRev:1,status:"ACTIVE",createdBy:"演示用户",createdAt:"2026-10-24T10:00:00Z",channels:["console"],notes:"默认体验"}]})],[/^\/admin\/apps\/([^/]+)\/demo-chat$/,async(n,t,e)=>{var d,o,u,c;const s=w(Q,n[1]),a=String((e==null?void 0:e.message)??"");if(Ke.has(n[1])){if(!s)return q();let I;try{I=await ys(e??{})}catch(v){return xe(v instanceof Error?v.message:"文件参数无法解析")}const y=(e==null?void 0:e.resolvedSnapshot)??s,S=JSON.parse(JSON.stringify(F)),A=Ln(n[1],y.config??{},I.message,String((e==null?void 0:e.conversationId)??ue("conversation")),ue("run"));if(A.toolCalls.unshift(...I.toolCalls),A.artifacts.length)for(const[v,l]of[["planning-file-export","生成结果文件"],["planning-file-download","提供受控下载入口"]])A.toolCalls.push({id:A.runId+"-"+v,skillCode:v,name:l,status:"SUCCEEDED",input:{planId:(d=A.plan)==null?void 0:d.id},output:{files:A.artifacts.map(g=>({artifactId:g.id,name:g.name,downloadUrl:g.downloadUrl}))}});const T=on(A.toolCalls,((o=y.config)==null?void 0:o.skillRefs)??[]);(u=Et())!=null&&u.permissions.includes("space.plan")||T.push("当前业务身份没有观测规划权限 space.plan"),T.length?(Object.assign(F,S),Js(A,T)):await vn(A),Object.assign(A,{boundSkillRefs:((c=y.config)==null?void 0:c.skillRefs)??[],businessActorId:O.actorId}),F.results[A.runId]=JSON.parse(JSON.stringify(A)),F.pending[A.runId]&&(F.pending[A.runId].response=JSON.parse(JSON.stringify(A))),dt(n[1],A),Object.assign(w(ae,A.runId),{inputs:{message:a,attachments:I.attachments,parameters:I.parameters},...e!=null&&e.resolvedVersionNo?{versionNo:e.resolvedVersionNo,versionRefs:{...w(ae,A.runId).versionRefs,app:`${n[1]}@${e.resolvedVersionNo}`}}:{}}),K("PLANNING_CHAT","RUN",A.runId,a.slice(0,80));for(const v of A.toolCalls.filter(l=>l.status==="SUCCEEDED")){const l=w(ie,v.skillCode);l&&(l.executionCount=(l.executionCount??0)+1)}return M(),C(A)}const r=xs(n[1],a),i=ue("run");return ae.unshift({id:i,runId:i,publicId:ue("run-demo"),appCode:n[1],appName:(s==null?void 0:s.name)??n[1],status:"SUCCEEDED",triggerType:"CHAT",caller:"u-demo",startedAt:b(),duration:1860,errorMessage:null,nodeCount:4,tokenUsage:{input:820,output:210}}),K("CHAT_TURN","APP",n[1],a.slice(0,40)),M(),C({runId:i,reply:r,status:"SUCCEEDED",citations:[]})}],[/^\/admin\/onboarding-templates$/,(n,t,e)=>{if(t==="GET")return C(Me);const s={code:String((e==null?void 0:e.code)??`template-${++Ne}`),name:String((e==null?void 0:e.name)??"新接入模板"),kind:"QUERY",status:"DRAFT",currentRev:1,updatedAt:b(),description:String((e==null?void 0:e.description)??""),connections:[],experiences:[]};return Me.unshift(s),K("ONBOARDING_TEMPLATE_CREATE","TEMPLATE",s.code,`新建接入模板「${s.name}」`),M(),C(s)}],[/^\/admin\/onboarding-templates\/([^/]+)\/(publish|disable)$/,n=>{const t=w(Me,n[1]);return t?(t.status=n[2]==="publish"?"PUBLISHED":"DISABLED",t.updatedAt=b(),K("ONBOARDING_TEMPLATE_"+n[2].toUpperCase(),"TEMPLATE",t.code,`模板状态改为 ${t.status}`),M(),C(t)):q()}],[/^\/admin\/onboarding-templates\/([^/]+)$/,n=>{const t=w(Me,n[1]);return t?C(t):q()}],[/^\/admin\/onboarding-sessions\/templates$/,()=>C(Me.filter(n=>n.kind==="QUERY"&&n.status==="PUBLISHED"))],[/^\/admin\/onboarding-sessions$/,(n,t)=>t==="GET"?C({items:[]}):q()],[/^\/admin\/model-health$/,()=>{const n=$e.map(t=>({entryId:t.id,model:t.modelId,status:"HEALTHY",latencyMs:42,cached:!1,checks:[{key:"chat",label:"对话",status:"PASS",message:"最小对话请求返回 200"}]}));return C({status:"HEALTHY",counts:{total:n.length,healthy:n.length,degraded:0,unhealthy:0,unknown:0},entries:n,ttlSeconds:60,checkedAt:b()})}],[/^\/admin\/statistics\/snapshots$/,()=>C(Bs)],[/^\/admin\/statistics\/snapshots\/([^/]+)$/,()=>C({...Yn,snapshotId:"ss-20261025",page:1,pageSize:20,groups:nn,groupsTotal:nn.length,skillGroups:tn,skillGroupsTotal:tn.length})],[/^\/admin\/statistics\/runs/,()=>C(ae)],[/^\/admin\/statistics\/rebuild$/,()=>C({...Yn,snapshotId:"ss-new",page:1,pageSize:20,groups:nn,groupsTotal:nn.length,skillGroups:tn,skillGroupsTotal:tn.length})],[/^\/admin\/statistics$/,()=>C({...Yn,page:1,pageSize:20,groups:nn,groupsTotal:nn.length,skillGroups:tn,skillGroupsTotal:tn.length})],[/^\/admin\/system\/status$/,()=>C({ok:!1,checkedAt:gt,version:"demo",checks:[{name:"PostgreSQL",ok:!0,detail:"连接可用"},{name:"Redis",ok:!1,detail:"本地未连接 Redis"},{name:"model-gateway",ok:!0,detail:"HTTP 200"}]})],[/^\/admin\/audit$/,()=>C(Sn)],[/^\/admin\/classifications$/,()=>C(qs)]];async function _t(n,t){var A,T,v,l,g,f,p,m,E,R,h,L,N,D,$,U;Hs();const e=((t==null?void 0:t.method)??"GET").toUpperCase(),s=new URL(String(n),"http://mock.local"),a=s.pathname,r=await ds(a,t);if(r)return r;let i;const d=t==null?void 0:t.body;if(typeof d=="string"&&d.trim())try{i=JSON.parse(d)}catch{i=void 0}else d&&typeof d=="object"&&!(d instanceof FormData)&&!(d instanceof Blob)&&(i=d);const o={apps:Q,skills:ie,runs:ae,approvals:ve,evaluations:Je,evaluationDatasets:De,auditLogs:Sn,modelProviders:Fe,modelEntries:$e,promptTemplates:Ze,templateVersionSets:Ye,debugRuns:Ge,demoReleases:we,demoIntegrationProfiles:Hn,demoConnections:xn,demoBindings:Fn,demoOnboardingTemplates:Me,flowStore:We,debugInputs:he,audit:K,persist:M,nextId:ue,sse:ot,datasetVersionOf:gn};ns(ie,H.skillVersions),i&&(delete i.resolvedSnapshot,delete i.resolvedVersionNo);const u=a.match(/^\/admin\/apps\/([^/]+)\/available-tools$/);if(u){if(e!=="GET")return xe("可用技能目录仅支持读取。");const V=w(Q,u[1]);if(!V)return q();Un(o);const j=s.searchParams.get("versionNo"),W=j?(T=(A=H.versions[V.code])==null?void 0:A.find(G=>G.versionNo===Number(j)))==null?void 0:T.snapshot:V;return W?C(yt(W,ie,H.skillVersions,s.searchParams.get("conversationId")??"")):q()}const c=a.match(/^\/admin\/apps\/([^/]+)\/(demo-chat|runs)$/);if(c&&c[2]==="runs"&&Ke.has(c[1])&&e==="POST"){const V=w(Q,c[1]);return V?_t(`/admin/apps/${c[1]}/demo-chat`,{...t,method:"POST",body:JSON.stringify({...i,versionNo:(i==null?void 0:i.versionNo)??V.currentVersionNo,message:((v=i==null?void 0:i.inputs)==null?void 0:v.message)??(i==null?void 0:i.message)??P.examples[1].message,parameters:(i==null?void 0:i.parameters)??((l=i==null?void 0:i.inputs)==null?void 0:l.parameters)??{},attachments:(i==null?void 0:i.attachments)??((g=i==null?void 0:i.inputs)==null?void 0:g.attachments)??[]})}):q()}if(c&&e==="POST"&&((i==null?void 0:i.versionNo)!==void 0||c[2]==="runs")){const V=w(Q,c[1]);if(!V)return q();Rt(`/admin/apps/${V.code}/versions`,"GET",void 0,new URLSearchParams,o);const j=Number((i==null?void 0:i.versionNo)??V.currentVersionNo),W=(p=(f=H.versions[V.code])==null?void 0:f.find(se=>se.versionNo===j))==null?void 0:p.snapshot;if(!W)return q();const G=V.code+":"+String((i==null?void 0:i.conversationId)??""),z=H.chatVersions??(H.chatVersions={});if(i!=null&&i.conversationId&&z[G]&&z[G]!==j)return Re("此会话已固定运行版本，请新建对话后切换版本。");i!=null&&i.conversationId&&(z[G]=j),i={...i??{},resolvedSnapshot:JSON.parse(JSON.stringify(W)),resolvedVersionNo:j}}if((c==null?void 0:c[2])==="demo-chat"&&e==="POST"&&(i!=null&&i.toolSelection)){const V=w(Q,c[1]);if(!V)return q();Un(o);const j=i.resolvedSnapshot??V,W=String(i.conversationId??ue("conversation")),z=yt(j,ie,H.skillVersions,W).find(x=>x.code===i.toolSelection.code&&x.version===Number(i.toolSelection.version));if(!z)return Re("所选技能版本未绑定到当前智能体、尚未发布或已停用，请刷新可用技能。");let se;try{se=Vs(z,i.parameters??{})}catch(x){return xe(x instanceof Error?x.message:"技能参数校验未通过。")}const B=on([{skillCode:z.code,version:z.version}],j.config.skillRefs??[]);if(B.length)return Re(B.join("；"));if(z.code==="um-approval-decide"){const x=Object.values(O.pending).find(re=>re.requestId===se.requestId);if(!(x!=null&&x.response.approval))return Re("没有找到该申请当前待处理的审批节点。");if(se.approved===!1&&!String(se.reason??"").trim())return xe("拒绝申请时请填写审批意见。");const Se=await _t(`/admin/approvals/${x.response.runId}/${x.response.approval.nodeId}`,{method:"POST",body:JSON.stringify({approved:se.approved,reason:se.reason})});if(!Se.ok)return Se;const Y=O.responses[x.response.runId];return K("SELECTED_TOOL_APPROVAL","RUN",Y.runId,JSON.stringify({code:z.code,parameters:se})),M(),C({...Y,selectedTool:{code:z.code,version:z.version},parameters:se})}const X=JSON.parse(JSON.stringify(F)),k=JSON.parse(JSON.stringify(O)),_=()=>{Object.assign(F,X),Object.assign(O,k)};try{const x=await Us({app:j,tool:z,parameters:se,conversationId:W,runId:ue("run")}),Se=on(x.toolCalls,j.config.skillRefs??[]);if(/planning-(ground-plan|space-plan|task-submit|task-cancel|selection-save)/.test(z.code)&&!((m=Et())!=null&&m.permissions.includes("space.plan"))&&Se.push("当前业务身份没有观测规划权限 space.plan"),Se.length)return _(),Re(Se.join("；"));await vn(x),Object.assign(x,{boundSkillRefs:j.config.skillRefs??[],businessActorId:O.actorId});for(const re of x.toolCalls)Object.assign(re,{version:Number(((E=j.config.skillRefs.find(ne=>ne.code===re.skillCode))==null?void 0:E.version)??z.version)});V.code===Ie.code?(O.responses[x.runId]=JSON.parse(JSON.stringify(x)),O.pending[x.runId]&&(O.pending[x.runId].response=JSON.parse(JSON.stringify(x))),Xn(x)):(F.results[x.runId]=JSON.parse(JSON.stringify(x)),F.pending[x.runId]&&(F.pending[x.runId].response=JSON.parse(JSON.stringify(x))),dt(V.code,x));const Y=w(ae,x.runId);Object.assign(Y,{inputs:{message:i.message,toolSelection:i.toolSelection,parameters:se},...i.resolvedVersionNo?{versionNo:i.resolvedVersionNo}:{}}),Y.versionRefs={...Y.versionRefs,app:`${V.code}@${i.resolvedVersionNo??V.currentVersionNo??V.draftRev}`,skills:Object.fromEntries(x.toolCalls.map(re=>[re.skillCode,re.version]))};for(const re of x.toolCalls.filter(ne=>ne.status==="SUCCEEDED")){const ne=w(ie,re.skillCode);ne&&(ne.executionCount=(ne.executionCount??0)+1)}return K("SELECTED_TOOL_EXECUTE","RUN",x.runId,`${z.code}@${z.version}`),M(),C(x)}catch(x){return _(),M(),xe(x instanceof Error?x.message:"技能执行失败，请检查参数后重试。")}}const I=kn(a,e,i??{});if(I){if(a==="/admin/business/reset"&&e==="POST")for(const V of ae.filter(j=>j.appCode===Ie.code&&j.status==="WAITING_APPROVAL")){V.status="CANCELLED";for(const j of ve.filter(W=>W.runId===V.id&&W.status==="PENDING"))j.status="CANCELLED"}return M(),I.error?new Response(JSON.stringify({code:"BUSINESS_CONFLICT",message:I.error,data:null}),{status:I.status??409,headers:{"Content-Type":"application/json"}}):C(I.data)}if(a==="/admin/apps/user-governance-assistant/planning-scenarios")return e==="GET"?C(ht):xe("只读目录");if(/^\/admin\/apps\/user-governance-assistant\/(demo-chat|runs)$/.test(a)&&e==="POST"){const V=w(Q,Ie.code),j=(i==null?void 0:i.resolvedSnapshot)??V,W=JSON.parse(JSON.stringify(O)),G=rt({appCode:V.code,conversationId:String((i==null?void 0:i.conversationId)??ue("conversation")),message:String(((R=i==null?void 0:i.inputs)==null?void 0:R.message)??(i==null?void 0:i.message)??ht.examples[1].message),runId:ue("run"),config:j.config??{}}),z=on(G.toolCalls,((h=j.config)==null?void 0:h.skillRefs)??[]);return z.length?(Object.assign(O,W),kn("/admin/business/actor","POST",{actorId:W.actorId}),G.status="BLOCKED",G.reply=`### 技能调用已阻断

`+z.map(se=>"- "+se).join(`
`),G.toolCalls=G.toolCalls.map(se=>({...se,status:"BLOCKED",output:{reason:"调用权限或版本校验未通过"}})),G.artifacts=[],G.ontology=null,G.workflow=[],delete G.approval,delete G.task,delete G.businessChange):await vn(G),Object.assign(G,{boundSkillRefs:((L=j.config)==null?void 0:L.skillRefs)??[],businessActorId:O.actorId}),O.responses[G.runId]=JSON.parse(JSON.stringify(G)),O.pending[G.runId]&&(O.pending[G.runId].response=JSON.parse(JSON.stringify(G))),Xn(G),Object.assign(w(ae,G.runId),{inputs:{message:i==null?void 0:i.message,inputs:i==null?void 0:i.inputs,parameters:i==null?void 0:i.parameters,attachments:i==null?void 0:i.attachments},...i!=null&&i.resolvedVersionNo?{versionNo:i.resolvedVersionNo,versionRefs:{...w(ae,G.runId).versionRefs,app:`${V.code}@${i.resolvedVersionNo}`}}:{}}),M(),C(G)}const y=a.match(/^\/admin\/approvals\/([^/]+)\/([^/]+)$/);if(y&&O.responses[y[1]]&&e==="POST"){const V=O.pending[y[1]];if(V&&((N=V.response.approval)==null?void 0:N.nodeId)!==y[2])return Re("审批节点已变化，请刷新当前待办。");const j=JSON.parse(JSON.stringify(O)),W=V==null?void 0:V.response,G=hs(y[1],(i==null?void 0:i.approved)!==!1&&(i==null?void 0:i.decision)!=="REJECT");if(G!=null&&G.conflict)return Re(G.conflict);if(G!=null&&G.response){const z=G.response,se=z.toolCalls.slice(((D=W==null?void 0:W.toolCalls)==null?void 0:D.length)??0),B=String((($=W==null?void 0:W.businessChange)==null?void 0:$.requester)??(W==null?void 0:W.businessActorId)??O.actorId),X=(W==null?void 0:W.boundSkillRefs)??((U=Cn.find(_=>_.code===Ie.code))==null?void 0:U.config.skillRefs)??[],k=se.flatMap(_=>on([_],X,_.skillCode==="um-approval-decide"?j.actorId:B));return k.length?(Object.assign(O,j),kn("/admin/business/actor","POST",{actorId:j.actorId}),M(),Re(k.join("；"))):(await vn(z),O.responses[z.runId]=JSON.parse(JSON.stringify(z)),O.pending[z.runId]&&(O.pending[z.runId].response=JSON.parse(JSON.stringify(z))),Xn(z),M(),C(ve.find(_=>_.runId===y[1]&&_.nodeId===y[2])))}}if(a.startsWith("/admin/business/"))return q();Object.assign(o,{businessContext:()=>{var V;return(V=kn("/admin/business/context","GET",{}))==null?void 0:V.data},actorRoles:()=>{var V;return((V=O.actors.find(j=>j.id===O.actorId))==null?void 0:V.roles)??[]}});const S=Rt(a,e,i,s.searchParams,o)??$s(a,e,i,s.searchParams,o)??_s(a,e,i,s.searchParams,o);if(S)return S;for(const[V,j]of Ws){const W=a.match(V);if(W){const G=/\/(planning-scenarios|input-schema|semantic-results|semantic-catalog|stats|referenced-by|lifecycle-logs|events|model-health|status|audit|classifications|integration|sessions)$/.test(a),z=/\/(copy|precheck|commit|submit|withdraw|approve|reject|disable|enable|archive|restore|render|publish|resolve|applications|promote|resume|cancel|reexecute|replay|reproduce|import|preview|reset|start|stop|restart|test|rotate)$/.test(a);return(G?["GET"]:z?["POST"]:/\/(config|flows\/[^/]+)$/.test(a)?["GET","PUT"]:["GET","POST","PUT","DELETE"]).includes(e)?a.startsWith("/admin/approvals")&&a!=="/admin/approvals"&&!/^\/admin\/approvals\/[^/]+\/[^/]+$/.test(a)?q():j(W,e,i):new Response(JSON.stringify({code:"METHOD_NOT_ALLOWED",message:"该演示资源不支持此方法",data:null}),{status:405,headers:{"Content-Type":"application/json"}})}}return console.warn("[mock] 未实现的接口",e,a),e==="GET"?q():new Response(JSON.stringify({code:"METHOD_NOT_ALLOWED",message:`演示数据不支持该方法：${e} ${a}`,data:null}),{status:405,headers:{"Content-Type":"application/json"}})}function $t(n="SUCCEEDED"){const t=[{seq:"1",type:"NODE_STARTED",data:{nodeId:"start",nodeDefId:"start"}},{seq:"2",type:"NODE_FINISHED",data:{nodeId:"start",nodeDefId:"start"}},{seq:"3",type:"NODE_STARTED",data:{nodeId:"llm",nodeDefId:"llm"}},{seq:"4",type:"CHUNK",data:{text:"正在分析轨道参数…"}},{seq:"5",type:"CHUNK",data:{text:"正在检查载荷兼容性…"}},{seq:"6",type:"NODE_FINISHED",data:{nodeId:"llm",nodeDefId:"llm"}},{seq:"7",type:"NODE_STARTED",data:{nodeId:"skill",nodeDefId:"skill"}},{seq:"8",type:"NODE_FINISHED",data:{nodeId:"skill",nodeDefId:"skill"}}];return t.push(n==="WAITING_APPROVAL"?{seq:"9",type:"RUN_WAITING",data:{status:n}}:{seq:"9",type:"RUN_FINISHED",data:{status:n}}),t}export{$t as mockRunEvents,_t as mockTransport};
