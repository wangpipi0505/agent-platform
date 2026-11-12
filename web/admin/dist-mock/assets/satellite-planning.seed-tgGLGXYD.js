const n=`{
  "id": "satellite-planning-demo",
  "version": 1,
  "mock": true,
  "clock": "2026-11-05T09:00:00+08:00",
  "planningDate": "2026-11-06",
  "timeZone": "Asia/Shanghai",
  "dataOrigin": "全部业务目录、窗口、数值与结果均为合成Mock；接口字段参考现有空间目标服务公开合同。",
  "releaseRef": {
    "spaceCode": "observation-planning",
    "releaseId": "rel-observation-v1",
    "version": "v1",
    "contentDigest": "a20cbd6f31c869c0cd10440fb3cad04eea9bd60092bf0b615c2382bc27002947"
  },
  "ontology": {
    "space": {
      "id": "sp-observation",
      "code": "observation-planning",
      "name": "卫星与空间地面目标观测规划本体",
      "namespaceUri": "https://demo.example/observation#",
      "domain": "空间目标与地面目标观测",
      "status": "ACTIVE",
      "draftRev": 8,
      "role": "OWNER",
      "description": "覆盖卫星、载荷、空间目标、地面目标、地面站、规划任务、观测窗口、下传窗口与规则。纯Mock合成业务示例。",
      "createdAt": "2026-11-05T09:00:00+08:00"
    },
    "classes": [
      {
        "id": "obs-Satellite",
        "spaceId": "sp-observation",
        "name": "Satellite",
        "displayName": "观测卫星",
        "parentId": null,
        "description": "观测卫星的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-Payload",
        "spaceId": "sp-observation",
        "name": "Payload",
        "displayName": "星载传感器",
        "parentId": null,
        "description": "星载传感器的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-SpaceTarget",
        "spaceId": "sp-observation",
        "name": "SpaceTarget",
        "displayName": "空间目标",
        "parentId": null,
        "description": "空间目标的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-GroundTarget",
        "spaceId": "sp-observation",
        "name": "GroundTarget",
        "displayName": "地面目标",
        "parentId": null,
        "description": "地面目标的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-GroundStation",
        "spaceId": "sp-observation",
        "name": "GroundStation",
        "displayName": "地面接收站",
        "parentId": null,
        "description": "地面接收站的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-ObservationTask",
        "spaceId": "sp-observation",
        "name": "ObservationTask",
        "displayName": "观测任务",
        "parentId": null,
        "description": "观测任务的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-ObservationWindow",
        "spaceId": "sp-observation",
        "name": "ObservationWindow",
        "displayName": "观测窗口",
        "parentId": null,
        "description": "观测窗口的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-DownlinkWindow",
        "spaceId": "sp-observation",
        "name": "DownlinkWindow",
        "displayName": "下传窗口",
        "parentId": null,
        "description": "下传窗口的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-PlanningConstraint",
        "spaceId": "sp-observation",
        "name": "PlanningConstraint",
        "displayName": "规划约束",
        "parentId": null,
        "description": "规划约束的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      },
      {
        "id": "obs-PlanArtifact",
        "spaceId": "sp-observation",
        "name": "PlanArtifact",
        "displayName": "规划制品",
        "parentId": null,
        "description": "规划制品的业务定义、稳定标识及规划属性。",
        "draftRev": 8
      }
    ],
    "properties": [
      {
        "id": "obs-prop-1",
        "spaceId": "sp-observation",
        "classId": "obs-Satellite",
        "name": "satelliteId",
        "displayName": "卫星ID",
        "rangeType": "INTEGER",
        "facets": {
          "primaryKey": true
        }
      },
      {
        "id": "obs-prop-2",
        "spaceId": "sp-observation",
        "classId": "obs-Satellite",
        "name": "name",
        "displayName": "卫星名称",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-3",
        "spaceId": "sp-observation",
        "classId": "obs-Satellite",
        "name": "orbitAltitudeKm",
        "displayName": "轨道高度/km",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-4",
        "spaceId": "sp-observation",
        "classId": "obs-Satellite",
        "name": "supportedPayloads",
        "displayName": "可用载荷类别",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-5",
        "spaceId": "sp-observation",
        "classId": "obs-Payload",
        "name": "payloadType",
        "displayName": "SPACE/GROUND",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-6",
        "spaceId": "sp-observation",
        "classId": "obs-Payload",
        "name": "maxDistanceKm",
        "displayName": "最大作用距离/km",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-7",
        "spaceId": "sp-observation",
        "classId": "obs-Payload",
        "name": "angleRateDegPerSec",
        "displayName": "跟踪角速度上限/度每秒",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-8",
        "spaceId": "sp-observation",
        "classId": "obs-Payload",
        "name": "sunAvoidHalfAngleDeg",
        "displayName": "太阳回避半角/度",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-9",
        "spaceId": "sp-observation",
        "classId": "obs-SpaceTarget",
        "name": "targetId",
        "displayName": "目标目录ID",
        "rangeType": "INTEGER",
        "facets": {
          "primaryKey": true
        }
      },
      {
        "id": "obs-prop-10",
        "spaceId": "sp-observation",
        "classId": "obs-SpaceTarget",
        "name": "name",
        "displayName": "目标名称",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-11",
        "spaceId": "sp-observation",
        "classId": "obs-SpaceTarget",
        "name": "noradCatId",
        "displayName": "NORAD编号",
        "rangeType": "INTEGER"
      },
      {
        "id": "obs-prop-12",
        "spaceId": "sp-observation",
        "classId": "obs-SpaceTarget",
        "name": "epochTime",
        "displayName": "TLE历元",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-13",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "targetId",
        "displayName": "地面目标编码",
        "rangeType": "STRING",
        "facets": {
          "primaryKey": true
        }
      },
      {
        "id": "obs-prop-14",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "name",
        "displayName": "区域名称",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-15",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "longitude",
        "displayName": "中心经度",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-16",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "latitude",
        "displayName": "中心纬度",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-17",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "maxCloudPercent",
        "displayName": "云量上限/%",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-18",
        "spaceId": "sp-observation",
        "classId": "obs-GroundTarget",
        "name": "requiredResolutionM",
        "displayName": "分辨率要求/m",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-19",
        "spaceId": "sp-observation",
        "classId": "obs-GroundStation",
        "name": "stationId",
        "displayName": "站点编码",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-20",
        "spaceId": "sp-observation",
        "classId": "obs-GroundStation",
        "name": "minElevationDeg",
        "displayName": "最低仰角/度",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-21",
        "spaceId": "sp-observation",
        "classId": "obs-GroundStation",
        "name": "maxRateMbps",
        "displayName": "链路速率/Mbps",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-22",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationTask",
        "name": "taskId",
        "displayName": "任务ID",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-23",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationTask",
        "name": "status",
        "displayName": "任务状态",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-24",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationTask",
        "name": "approvalRequired",
        "displayName": "是否人工确认",
        "rangeType": "BOOLEAN"
      },
      {
        "id": "obs-prop-25",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationWindow",
        "name": "startTime",
        "displayName": "观测开始时间",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-26",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationWindow",
        "name": "endTime",
        "displayName": "观测结束时间",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-27",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationWindow",
        "name": "durationSec",
        "displayName": "窗口时长/s",
        "rangeType": "INTEGER"
      },
      {
        "id": "obs-prop-28",
        "spaceId": "sp-observation",
        "classId": "obs-ObservationWindow",
        "name": "score",
        "displayName": "推荐分数",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-29",
        "spaceId": "sp-observation",
        "classId": "obs-DownlinkWindow",
        "name": "capacityGb",
        "displayName": "下传容量/GB",
        "rangeType": "DECIMAL"
      },
      {
        "id": "obs-prop-30",
        "spaceId": "sp-observation",
        "classId": "obs-DownlinkWindow",
        "name": "startTime",
        "displayName": "下传开始时间",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-31",
        "spaceId": "sp-observation",
        "classId": "obs-PlanningConstraint",
        "name": "ruleId",
        "displayName": "规则编码",
        "rangeType": "STRING"
      },
      {
        "id": "obs-prop-32",
        "spaceId": "sp-observation",
        "classId": "obs-PlanArtifact",
        "name": "sha256",
        "displayName": "制品内容摘要",
        "rangeType": "STRING"
      }
    ],
    "relations": [
      {
        "id": "obs-rel-1",
        "spaceId": "sp-observation",
        "name": "carries",
        "fromClassId": "obs-Satellite",
        "toClassId": "obs-Payload",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "搭载"
      },
      {
        "id": "obs-rel-2",
        "spaceId": "sp-observation",
        "name": "provides",
        "fromClassId": "obs-Satellite",
        "toClassId": "obs-ObservationWindow",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "提供观测窗口"
      },
      {
        "id": "obs-rel-3",
        "spaceId": "sp-observation",
        "name": "observesSpace",
        "fromClassId": "obs-ObservationWindow",
        "toClassId": "obs-SpaceTarget",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "观测空间目标"
      },
      {
        "id": "obs-rel-4",
        "spaceId": "sp-observation",
        "name": "observesGround",
        "fromClassId": "obs-ObservationWindow",
        "toClassId": "obs-GroundTarget",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "观测地面目标"
      },
      {
        "id": "obs-rel-5",
        "spaceId": "sp-observation",
        "name": "selects",
        "fromClassId": "obs-ObservationTask",
        "toClassId": "obs-ObservationWindow",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "选择窗口"
      },
      {
        "id": "obs-rel-6",
        "spaceId": "sp-observation",
        "name": "uses",
        "fromClassId": "obs-ObservationTask",
        "toClassId": "obs-Satellite",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "使用卫星"
      },
      {
        "id": "obs-rel-7",
        "spaceId": "sp-observation",
        "name": "constrainedBy",
        "fromClassId": "obs-ObservationTask",
        "toClassId": "obs-PlanningConstraint",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "受规则约束"
      },
      {
        "id": "obs-rel-8",
        "spaceId": "sp-observation",
        "name": "followedBy",
        "fromClassId": "obs-ObservationWindow",
        "toClassId": "obs-DownlinkWindow",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "后续下传"
      },
      {
        "id": "obs-rel-9",
        "spaceId": "sp-observation",
        "name": "receivedBy",
        "fromClassId": "obs-DownlinkWindow",
        "toClassId": "obs-GroundStation",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "由站点接收"
      },
      {
        "id": "obs-rel-10",
        "spaceId": "sp-observation",
        "name": "produces",
        "fromClassId": "obs-ObservationTask",
        "toClassId": "obs-PlanArtifact",
        "cardinality": "ONE_TO_MANY",
        "inverseOf": null,
        "transitive": false,
        "symmetric": false,
        "description": "生成制品"
      }
    ],
    "rules": [
      {
        "id": "rule-payload",
        "spaceId": "sp-observation",
        "name": "载荷类别匹配",
        "description": "卫星必须具备目标类型对应的SPACE或GROUND载荷",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "payloadType == targetType",
        "action": {
          "severity": "ERROR",
          "message": "卫星必须具备目标类型对应的SPACE或GROUND载荷"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-epoch",
        "spaceId": "sp-observation",
        "name": "星历时效",
        "description": "星历距离规划时间不超过14天；超过时返回风险与阻断示例",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "epochAgeDays <= 14",
        "action": {
          "severity": "ERROR",
          "message": "星历距离规划时间不超过14天；超过时返回风险与阻断示例"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-cloud",
        "spaceId": "sp-observation",
        "name": "对地云量约束",
        "description": "光学对地观测云量不得高于任务上限30%",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "cloudPercent <= 30",
        "action": {
          "severity": "ERROR",
          "message": "光学对地观测云量不得高于任务上限30%"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-sunlight",
        "spaceId": "sp-observation",
        "name": "光学光照约束",
        "description": "光学对地观测必须满足太阳高度角与光照要求",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "illumination == SUNLIT",
        "action": {
          "severity": "ERROR",
          "message": "光学对地观测必须满足太阳高度角与光照要求"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-resolution",
        "spaceId": "sp-observation",
        "name": "空间分辨率约束",
        "description": "对地分辨率必须不劣于1m",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "resolutionM <= 1",
        "action": {
          "severity": "ERROR",
          "message": "对地分辨率必须不劣于1m"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-downlink",
        "spaceId": "sp-observation",
        "name": "观测下传时序与容量",
        "description": "下传晚于观测结束且容量覆盖任务数据量",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "downlinkStart > observationEnd && capacityGb >= dataGb",
        "action": {
          "severity": "ERROR",
          "message": "下传晚于观测结束且容量覆盖任务数据量"
        },
        "scoring": null,
        "severity": "ERROR"
      },
      {
        "id": "rule-approval",
        "spaceId": "sp-observation",
        "name": "任务提交人工确认",
        "description": "副作用工具必须先生成冻结计划并等待人工确认",
        "applicableClassId": "obs-ObservationTask",
        "kind": "VALIDATION",
        "condition": "approved == true",
        "action": {
          "severity": "ERROR",
          "message": "副作用工具必须先生成冻结计划并等待人工确认"
        },
        "scoring": null,
        "severity": "ERROR"
      }
    ],
    "operations": [
      {
        "id": "obs-op-1",
        "spaceId": "sp-observation",
        "code": "observation-catalog",
        "name": "对象与事实查询",
        "kind": "OBJECT_QUERY",
        "description": "对象与事实查询",
        "precondition": "已选正式本体版本，目标与卫星标识存在",
        "strategyRef": "obs-strategy-main",
        "ruleIds": [
          "rule-payload",
          "rule-epoch",
          "rule-cloud",
          "rule-sunlight",
          "rule-resolution",
          "rule-downlink",
          "rule-approval"
        ],
        "inputSchema": {
          "type": "object",
          "properties": {
            "satelliteId": {
              "type": "integer"
            },
            "targetId": {
              "type": "string"
            },
            "planningDate": {
              "type": "string"
            }
          }
        }
      },
      {
        "id": "obs-op-2",
        "spaceId": "sp-observation",
        "code": "observation-rules",
        "name": "规划规则检查",
        "kind": "RULE_CHECK",
        "description": "规划规则检查",
        "precondition": "已选正式本体版本，目标与卫星标识存在",
        "strategyRef": "obs-strategy-main",
        "ruleIds": [
          "rule-payload",
          "rule-epoch",
          "rule-cloud",
          "rule-sunlight",
          "rule-resolution",
          "rule-downlink",
          "rule-approval"
        ],
        "inputSchema": {
          "type": "object",
          "properties": {
            "satelliteId": {
              "type": "integer"
            },
            "targetId": {
              "type": "string"
            },
            "planningDate": {
              "type": "string"
            }
          }
        }
      },
      {
        "id": "obs-op-3",
        "spaceId": "sp-observation",
        "code": "observation-score",
        "name": "窗口综合评分",
        "kind": "SCORING",
        "description": "窗口综合评分",
        "precondition": "已选正式本体版本，目标与卫星标识存在",
        "strategyRef": "obs-strategy-main",
        "ruleIds": [
          "rule-payload",
          "rule-epoch",
          "rule-cloud",
          "rule-sunlight",
          "rule-resolution",
          "rule-downlink",
          "rule-approval"
        ],
        "inputSchema": {
          "type": "object",
          "properties": {
            "satelliteId": {
              "type": "integer"
            },
            "targetId": {
              "type": "string"
            },
            "planningDate": {
              "type": "string"
            }
          }
        }
      },
      {
        "id": "obs-op-4",
        "spaceId": "sp-observation",
        "code": "observation-relations",
        "name": "星-载荷-目标-站关联查询",
        "kind": "RELATION_QUERY",
        "description": "星-载荷-目标-站关联查询",
        "precondition": "已选正式本体版本，目标与卫星标识存在",
        "strategyRef": "obs-strategy-main",
        "ruleIds": [
          "rule-payload",
          "rule-epoch",
          "rule-cloud",
          "rule-sunlight",
          "rule-resolution",
          "rule-downlink",
          "rule-approval"
        ],
        "inputSchema": {
          "type": "object",
          "properties": {
            "satelliteId": {
              "type": "integer"
            },
            "targetId": {
              "type": "string"
            },
            "planningDate": {
              "type": "string"
            }
          }
        }
      }
    ],
    "instances": [
      {
        "className": "Satellite",
        "stableId": "sat-2068",
        "values": {
          "id": 2068,
          "name": "试验二十号A",
          "catalogName": "SHIYAN 20A (SY-20A)",
          "noradCatId": 54699,
          "orbitAltitudeKm": 793.5,
          "inclinationDeg": 60,
          "status": "AVAILABLE",
          "supportedPayloads": [
            "SPACE",
            "GROUND"
          ],
          "storageGb": 128
        }
      },
      {
        "className": "Satellite",
        "stableId": "sat-845",
        "values": {
          "id": 845,
          "name": "空间监测一号",
          "catalogName": "DEMO-SPACE-845",
          "noradCatId": 49001,
          "orbitAltitudeKm": 550,
          "inclinationDeg": 97.6,
          "status": "AVAILABLE",
          "supportedPayloads": [
            "SPACE"
          ],
          "storageGb": 96
        }
      },
      {
        "className": "Satellite",
        "stableId": "sat-3150",
        "values": {
          "id": 3150,
          "name": "高分观测一号",
          "catalogName": "DEMO-GROUND-3150",
          "noradCatId": 59002,
          "orbitAltitudeKm": 620,
          "inclinationDeg": 97.8,
          "status": "AVAILABLE",
          "supportedPayloads": [
            "GROUND"
          ],
          "storageGb": 256
        }
      },
      {
        "className": "Payload",
        "stableId": "payload-2068-SPACE",
        "values": {
          "id": "payload-2068-SPACE",
          "satelliteId": 2068,
          "payloadType": "SPACE",
          "minDistanceKm": 100,
          "maxDistanceKm": 4000,
          "angleRateDegPerSec": 0.08,
          "sunAvoidHalfAngleDeg": 30,
          "resolutionM": null,
          "active": true
        }
      },
      {
        "className": "Payload",
        "stableId": "payload-2068-GROUND",
        "values": {
          "id": "payload-2068-GROUND",
          "satelliteId": 2068,
          "payloadType": "GROUND",
          "minDistanceKm": 0,
          "maxDistanceKm": 1600,
          "angleRateDegPerSec": 0.1,
          "sunAvoidHalfAngleDeg": 10,
          "resolutionM": 0.8,
          "active": true
        }
      },
      {
        "className": "Payload",
        "stableId": "payload-845-SPACE",
        "values": {
          "id": "payload-845-SPACE",
          "satelliteId": 845,
          "payloadType": "SPACE",
          "minDistanceKm": 100,
          "maxDistanceKm": 4000,
          "angleRateDegPerSec": 0.08,
          "sunAvoidHalfAngleDeg": 30,
          "resolutionM": null,
          "active": true
        }
      },
      {
        "className": "Payload",
        "stableId": "payload-3150-GROUND",
        "values": {
          "id": "payload-3150-GROUND",
          "satelliteId": 3150,
          "payloadType": "GROUND",
          "minDistanceKm": 0,
          "maxDistanceKm": 1600,
          "angleRateDegPerSec": 0.1,
          "sunAvoidHalfAngleDeg": 10,
          "resolutionM": 0.8,
          "active": true
        }
      },
      {
        "className": "SpaceTarget",
        "stableId": "target-8890",
        "values": {
          "id": 8890,
          "name": "STARLINK-31091",
          "noradCatId": 58629,
          "type": "SPACE",
          "epochTime": "2026-11-04T10:38:42+08:00",
          "country": "US",
          "orbitAltitudeKm": 483,
          "priority": 90
        }
      },
      {
        "className": "SpaceTarget",
        "stableId": "target-9002",
        "values": {
          "id": 9002,
          "name": "演示遥感目标02",
          "noradCatId": 59003,
          "type": "SPACE",
          "epochTime": "2026-11-04T08:00:00+08:00",
          "country": "PRC",
          "orbitAltitudeKm": 690,
          "priority": 75
        }
      },
      {
        "className": "GroundTarget",
        "stableId": "GT-QH-01",
        "values": {
          "id": "GT-QH-01",
          "name": "青海湖生态观测区",
          "type": "GROUND",
          "longitude": 100.15,
          "latitude": 36.9,
          "areaKm2": 12,
          "maxCloudPercent": 30,
          "requiredResolutionM": 1,
          "sensorMode": "OPTICAL",
          "priority": 95
        }
      },
      {
        "className": "GroundTarget",
        "stableId": "GT-PY-02",
        "values": {
          "id": "GT-PY-02",
          "name": "鄱阳湖湿地",
          "type": "GROUND",
          "longitude": 116.29,
          "latitude": 29.12,
          "areaKm2": 18,
          "maxCloudPercent": 30,
          "requiredResolutionM": 1,
          "sensorMode": "OPTICAL",
          "priority": 80
        }
      },
      {
        "className": "GroundStation",
        "stableId": "ST-KS-01",
        "values": {
          "id": "ST-KS-01",
          "name": "喀什站",
          "longitude": 76,
          "latitude": 39.5,
          "minElevationDeg": 5,
          "maxRateMbps": 1600,
          "status": "AVAILABLE"
        }
      },
      {
        "className": "GroundStation",
        "stableId": "ST-MY-01",
        "values": {
          "id": "ST-MY-01",
          "name": "密云站",
          "longitude": 116.98,
          "latitude": 40.45,
          "minElevationDeg": 7,
          "maxRateMbps": 1200,
          "status": "AVAILABLE"
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "SP-W01",
        "values": {
          "id": "SP-W01",
          "kind": "SPACE",
          "satelliteId": 2068,
          "targetId": 8890,
          "startTime": "2026-11-06T08:12:00+08:00",
          "endTime": "2026-11-06T08:19:00+08:00",
          "durationSec": 420,
          "score": 94,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "minDistanceKm": 1382,
          "maxDistanceKm": 2148,
          "sunAngleDeg": 103,
          "angleRateDegPerSec": 0.021,
          "dataGb": 12
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "SP-W02",
        "values": {
          "id": "SP-W02",
          "kind": "SPACE",
          "satelliteId": 845,
          "targetId": 8890,
          "startTime": "2026-11-06T08:43:00+08:00",
          "endTime": "2026-11-06T08:48:30+08:00",
          "durationSec": 330,
          "score": 89,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "minDistanceKm": 952,
          "maxDistanceKm": 1820,
          "sunAngleDeg": 85,
          "angleRateDegPerSec": 0.032,
          "dataGb": 10
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "SP-W03",
        "values": {
          "id": "SP-W03",
          "kind": "SPACE",
          "satelliteId": 2068,
          "targetId": 9002,
          "startTime": "2026-11-06T10:18:00+08:00",
          "endTime": "2026-11-06T10:24:00+08:00",
          "durationSec": 360,
          "score": 86,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "minDistanceKm": 1180,
          "maxDistanceKm": 2460,
          "sunAngleDeg": 72,
          "angleRateDegPerSec": 0.018,
          "dataGb": 9
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "GD-W01",
        "values": {
          "id": "GD-W01",
          "kind": "GROUND",
          "satelliteId": 3150,
          "targetId": "GT-QH-01",
          "startTime": "2026-11-06T09:10:00+08:00",
          "endTime": "2026-11-06T09:15:30+08:00",
          "durationSec": 330,
          "score": 96,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "cloudPercent": 22,
          "resolutionM": 0.8,
          "offNadirDeg": 27,
          "dataGb": 24
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "GD-W02",
        "values": {
          "id": "GD-W02",
          "kind": "GROUND",
          "satelliteId": 2068,
          "targetId": "GT-QH-01",
          "startTime": "2026-11-06T10:02:00+08:00",
          "endTime": "2026-11-06T10:07:00+08:00",
          "durationSec": 300,
          "score": 79,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "cloudPercent": 46,
          "resolutionM": 0.9,
          "offNadirDeg": 32,
          "dataGb": 20
        }
      },
      {
        "className": "ObservationWindow",
        "stableId": "GD-W03",
        "values": {
          "id": "GD-W03",
          "kind": "GROUND",
          "satelliteId": 3150,
          "targetId": "GT-PY-02",
          "startTime": "2026-11-06T11:05:00+08:00",
          "endTime": "2026-11-06T11:10:00+08:00",
          "durationSec": 300,
          "score": 88,
          "illumination": "SUNLIT",
          "epochAgeDays": 2,
          "cloudPercent": 18,
          "resolutionM": 0.8,
          "offNadirDeg": 22,
          "dataGb": 18
        }
      },
      {
        "className": "DownlinkWindow",
        "stableId": "DL-01",
        "values": {
          "id": "DL-01",
          "satelliteId": 2068,
          "stationId": "ST-KS-01",
          "startTime": "2026-11-06T08:25:00+08:00",
          "endTime": "2026-11-06T08:33:00+08:00",
          "durationSec": 480,
          "capacityGb": 96
        }
      },
      {
        "className": "DownlinkWindow",
        "stableId": "DL-02",
        "values": {
          "id": "DL-02",
          "satelliteId": 845,
          "stationId": "ST-MY-01",
          "startTime": "2026-11-06T09:05:00+08:00",
          "endTime": "2026-11-06T09:13:00+08:00",
          "durationSec": 480,
          "capacityGb": 72
        }
      },
      {
        "className": "DownlinkWindow",
        "stableId": "DL-03",
        "values": {
          "id": "DL-03",
          "satelliteId": 3150,
          "stationId": "ST-KS-01",
          "startTime": "2026-11-06T09:28:00+08:00",
          "endTime": "2026-11-06T09:35:00+08:00",
          "durationSec": 420,
          "capacityGb": 84
        }
      },
      {
        "className": "DownlinkWindow",
        "stableId": "DL-04",
        "values": {
          "id": "DL-04",
          "satelliteId": 3150,
          "stationId": "ST-MY-01",
          "startTime": "2026-11-06T11:24:00+08:00",
          "endTime": "2026-11-06T11:32:00+08:00",
          "durationSec": 480,
          "capacityGb": 72
        }
      }
    ],
    "constraints": [
      {
        "id": "obs-cst-duration",
        "spaceId": "sp-observation",
        "name": "窗口结束晚于开始",
        "kind": "RANGE",
        "targetType": "CLASS",
        "targetRef": "obs-ObservationWindow",
        "expr": {
          "expression": "endTime > startTime"
        }
      },
      {
        "id": "obs-cst-cloud",
        "spaceId": "sp-observation",
        "name": "云量百分比范围",
        "kind": "RANGE",
        "targetType": "PROPERTY",
        "targetRef": "obs-prop-17",
        "expr": {
          "min": 0,
          "max": 100
        }
      }
    ],
    "strategies": [
      {
        "id": "obs-strategy-main",
        "spaceId": "sp-observation",
        "name": "确定性观测规划策略",
        "policy": {
          "maxRows": 200,
          "timeoutMs": 30000,
          "resultLimit": 20,
          "failureMode": "FAIL",
          "readScope": {
            "domains": [
              "空间目标与地面目标观测"
            ]
          }
        },
        "boundOps": [
          "obs-op-1",
          "obs-op-2",
          "obs-op-3",
          "obs-op-4"
        ]
      }
    ],
    "codebooks": [
      {
        "id": "obs-cb-type",
        "spaceId": "sp-observation",
        "code": "payload-type",
        "name": "观测载荷类型",
        "kind": "VALUE",
        "entries": [
          {
            "code": "SPACE",
            "label": "对空观测"
          },
          {
            "code": "GROUND",
            "label": "对地观测"
          }
        ]
      }
    ],
    "knowledge": [
      {
        "id": "obs-kb-1",
        "spaceId": "sp-observation",
        "term": "对空观测",
        "definition": "星载SPACE载荷观测轨道空间目标，目标与观测星拥有不同稳定标识。",
        "aliases": [
          "空间目标观测",
          "SPACE"
        ],
        "source": "纯Mock业务词典"
      },
      {
        "id": "obs-kb-2",
        "spaceId": "sp-observation",
        "term": "对地观测",
        "definition": "GROUND光学载荷对指定经纬度区域成像，需要检查光照、云量与分辨率。",
        "aliases": [
          "遥感成像",
          "GROUND"
        ],
        "source": "纯Mock业务词典"
      },
      {
        "id": "obs-kb-3",
        "spaceId": "sp-observation",
        "term": "下传窗口",
        "definition": "观测之后，卫星与地面接收站建立通信的可用区间；不同于对地成像窗口。",
        "aliases": [
          "数传",
          "downlink"
        ],
        "source": "纯Mock业务词典"
      }
    ],
    "testCases": [
      {
        "id": "obs-test-1",
        "spaceId": "sp-observation",
        "name": "空间目标观测",
        "kind": "NL",
        "enabled": true,
        "input": {
          "question": "规划明天上午对空间目标 STARLINK-31091 的观测窗口"
        },
        "expected": {
          "intent": "SPACE"
        },
        "operationCode": "observation-catalog"
      },
      {
        "id": "obs-test-2",
        "spaceId": "sp-observation",
        "name": "地面目标观测",
        "kind": "NL",
        "enabled": true,
        "input": {
          "question": "规划明天上午青海湖生态观测区的对地观测，云量不超过30%"
        },
        "expected": {
          "intent": "GROUND"
        },
        "operationCode": "observation-rules"
      },
      {
        "id": "obs-test-3",
        "spaceId": "sp-observation",
        "name": "无窗口的业务解释",
        "kind": "NL",
        "enabled": true,
        "input": {
          "question": "规划鄱阳湖湿地夜间光学观测"
        },
        "expected": {
          "intent": "NO_WINDOW"
        },
        "operationCode": "observation-rules"
      },
      {
        "id": "obs-test-4",
        "spaceId": "sp-observation",
        "name": "载荷不匹配",
        "kind": "NL",
        "enabled": true,
        "input": {
          "question": "只用卫星845规划青海湖对地观测"
        },
        "expected": {
          "intent": "BLOCKED"
        },
        "operationCode": "observation-rules"
      },
      {
        "id": "obs-test-5",
        "spaceId": "sp-observation",
        "name": "星历时效异常",
        "kind": "NL",
        "enabled": true,
        "input": {
          "question": "模拟星历过期，规划 STARLINK-31091 的观测"
        },
        "expected": {
          "intent": "BLOCKED"
        },
        "operationCode": "observation-rules"
      }
    ],
    "datasources": [
      {
        "id": "obs-ds-catalog",
        "spaceId": "sp-observation",
        "code": "observation_mock_catalog",
        "name": "星目标站业务目录",
        "kind": "HTTP",
        "status": "ACTIVE",
        "config": {
          "baseUrl": "mock://planning/catalog",
          "secretRef": "demo:no-secret"
        },
        "scope": {
          "domain": "观测规划"
        },
        "createdAt": "2026-11-05T09:00:00+08:00"
      }
    ],
    "mappings": {
      "objects": [
        {
          "id": "obs-map-1",
          "spaceId": "sp-observation",
          "classId": "obs-Satellite",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "Satellite",
          "keyField": "id"
        },
        {
          "id": "obs-map-2",
          "spaceId": "sp-observation",
          "classId": "obs-Payload",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "Payload",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-3",
          "spaceId": "sp-observation",
          "classId": "obs-SpaceTarget",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "SpaceTarget",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-4",
          "spaceId": "sp-observation",
          "classId": "obs-GroundTarget",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "GroundTarget",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-5",
          "spaceId": "sp-observation",
          "classId": "obs-GroundStation",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "GroundStation",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-6",
          "spaceId": "sp-observation",
          "classId": "obs-ObservationTask",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "ObservationTask",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-7",
          "spaceId": "sp-observation",
          "classId": "obs-ObservationWindow",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "ObservationWindow",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-8",
          "spaceId": "sp-observation",
          "classId": "obs-DownlinkWindow",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "DownlinkWindow",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-9",
          "spaceId": "sp-observation",
          "classId": "obs-PlanningConstraint",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "PlanningConstraint",
          "keyField": "stableId"
        },
        {
          "id": "obs-map-10",
          "spaceId": "sp-observation",
          "classId": "obs-PlanArtifact",
          "datasourceId": "obs-ds-catalog",
          "snapshotId": "obs-meta-v1",
          "source": "PlanArtifact",
          "keyField": "stableId"
        }
      ],
      "properties": [
        {
          "id": "map-obs-prop-1",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-1",
          "classId": "obs-Satellite",
          "sourceField": "satelliteId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-2",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-2",
          "classId": "obs-Satellite",
          "sourceField": "name",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-3",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-3",
          "classId": "obs-Satellite",
          "sourceField": "orbitAltitudeKm",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-4",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-4",
          "classId": "obs-Satellite",
          "sourceField": "supportedPayloads",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-5",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-5",
          "classId": "obs-Payload",
          "sourceField": "payloadType",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-6",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-6",
          "classId": "obs-Payload",
          "sourceField": "maxDistanceKm",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-7",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-7",
          "classId": "obs-Payload",
          "sourceField": "angleRateDegPerSec",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-8",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-8",
          "classId": "obs-Payload",
          "sourceField": "sunAvoidHalfAngleDeg",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-9",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-9",
          "classId": "obs-SpaceTarget",
          "sourceField": "targetId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-10",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-10",
          "classId": "obs-SpaceTarget",
          "sourceField": "name",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-11",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-11",
          "classId": "obs-SpaceTarget",
          "sourceField": "noradCatId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-12",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-12",
          "classId": "obs-SpaceTarget",
          "sourceField": "epochTime",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-13",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-13",
          "classId": "obs-GroundTarget",
          "sourceField": "targetId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-14",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-14",
          "classId": "obs-GroundTarget",
          "sourceField": "name",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-15",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-15",
          "classId": "obs-GroundTarget",
          "sourceField": "longitude",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-16",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-16",
          "classId": "obs-GroundTarget",
          "sourceField": "latitude",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-17",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-17",
          "classId": "obs-GroundTarget",
          "sourceField": "maxCloudPercent",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-18",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-18",
          "classId": "obs-GroundTarget",
          "sourceField": "requiredResolutionM",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-19",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-19",
          "classId": "obs-GroundStation",
          "sourceField": "stationId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-20",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-20",
          "classId": "obs-GroundStation",
          "sourceField": "minElevationDeg",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-21",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-21",
          "classId": "obs-GroundStation",
          "sourceField": "maxRateMbps",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-22",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-22",
          "classId": "obs-ObservationTask",
          "sourceField": "taskId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-23",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-23",
          "classId": "obs-ObservationTask",
          "sourceField": "status",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-24",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-24",
          "classId": "obs-ObservationTask",
          "sourceField": "approvalRequired",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-25",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-25",
          "classId": "obs-ObservationWindow",
          "sourceField": "startTime",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-26",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-26",
          "classId": "obs-ObservationWindow",
          "sourceField": "endTime",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-27",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-27",
          "classId": "obs-ObservationWindow",
          "sourceField": "durationSec",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-28",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-28",
          "classId": "obs-ObservationWindow",
          "sourceField": "score",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-29",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-29",
          "classId": "obs-DownlinkWindow",
          "sourceField": "capacityGb",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-30",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-30",
          "classId": "obs-DownlinkWindow",
          "sourceField": "startTime",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-31",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-31",
          "classId": "obs-PlanningConstraint",
          "sourceField": "ruleId",
          "conversion": "IDENTITY"
        },
        {
          "id": "map-obs-prop-32",
          "spaceId": "sp-observation",
          "propertyId": "obs-prop-32",
          "classId": "obs-PlanArtifact",
          "sourceField": "sha256",
          "conversion": "IDENTITY"
        }
      ],
      "relations": [
        {
          "id": "map-obs-rel-1",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-1",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-2",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-2",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-3",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-3",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-4",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-4",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-5",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-5",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-6",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-6",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-7",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-7",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-8",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-8",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-9",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-9",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        },
        {
          "id": "map-obs-rel-10",
          "spaceId": "sp-observation",
          "relationId": "obs-rel-10",
          "sourceKey": "stableId",
          "targetKey": "stableId"
        }
      ]
    }
  },
  "assets": {
    "satellites": [
      {
        "id": 2068,
        "name": "试验二十号A",
        "catalogName": "SHIYAN 20A (SY-20A)",
        "noradCatId": 54699,
        "orbitAltitudeKm": 793.5,
        "inclinationDeg": 60,
        "status": "AVAILABLE",
        "supportedPayloads": [
          "SPACE",
          "GROUND"
        ],
        "storageGb": 128
      },
      {
        "id": 845,
        "name": "空间监测一号",
        "catalogName": "DEMO-SPACE-845",
        "noradCatId": 49001,
        "orbitAltitudeKm": 550,
        "inclinationDeg": 97.6,
        "status": "AVAILABLE",
        "supportedPayloads": [
          "SPACE"
        ],
        "storageGb": 96
      },
      {
        "id": 3150,
        "name": "高分观测一号",
        "catalogName": "DEMO-GROUND-3150",
        "noradCatId": 59002,
        "orbitAltitudeKm": 620,
        "inclinationDeg": 97.8,
        "status": "AVAILABLE",
        "supportedPayloads": [
          "GROUND"
        ],
        "storageGb": 256
      }
    ],
    "payloads": [
      {
        "id": "payload-2068-SPACE",
        "satelliteId": 2068,
        "payloadType": "SPACE",
        "minDistanceKm": 100,
        "maxDistanceKm": 4000,
        "angleRateDegPerSec": 0.08,
        "sunAvoidHalfAngleDeg": 30,
        "resolutionM": null,
        "active": true
      },
      {
        "id": "payload-2068-GROUND",
        "satelliteId": 2068,
        "payloadType": "GROUND",
        "minDistanceKm": 0,
        "maxDistanceKm": 1600,
        "angleRateDegPerSec": 0.1,
        "sunAvoidHalfAngleDeg": 10,
        "resolutionM": 0.8,
        "active": true
      },
      {
        "id": "payload-845-SPACE",
        "satelliteId": 845,
        "payloadType": "SPACE",
        "minDistanceKm": 100,
        "maxDistanceKm": 4000,
        "angleRateDegPerSec": 0.08,
        "sunAvoidHalfAngleDeg": 30,
        "resolutionM": null,
        "active": true
      },
      {
        "id": "payload-3150-GROUND",
        "satelliteId": 3150,
        "payloadType": "GROUND",
        "minDistanceKm": 0,
        "maxDistanceKm": 1600,
        "angleRateDegPerSec": 0.1,
        "sunAvoidHalfAngleDeg": 10,
        "resolutionM": 0.8,
        "active": true
      }
    ],
    "spaceTargets": [
      {
        "id": 8890,
        "name": "STARLINK-31091",
        "noradCatId": 58629,
        "type": "SPACE",
        "epochTime": "2026-11-04T10:38:42+08:00",
        "country": "US",
        "orbitAltitudeKm": 483,
        "priority": 90
      },
      {
        "id": 9002,
        "name": "演示遥感目标02",
        "noradCatId": 59003,
        "type": "SPACE",
        "epochTime": "2026-11-04T08:00:00+08:00",
        "country": "PRC",
        "orbitAltitudeKm": 690,
        "priority": 75
      }
    ],
    "groundTargets": [
      {
        "id": "GT-QH-01",
        "name": "青海湖生态观测区",
        "type": "GROUND",
        "longitude": 100.15,
        "latitude": 36.9,
        "areaKm2": 12,
        "maxCloudPercent": 30,
        "requiredResolutionM": 1,
        "sensorMode": "OPTICAL",
        "priority": 95
      },
      {
        "id": "GT-PY-02",
        "name": "鄱阳湖湿地",
        "type": "GROUND",
        "longitude": 116.29,
        "latitude": 29.12,
        "areaKm2": 18,
        "maxCloudPercent": 30,
        "requiredResolutionM": 1,
        "sensorMode": "OPTICAL",
        "priority": 80
      }
    ],
    "stations": [
      {
        "id": "ST-KS-01",
        "name": "喀什站",
        "longitude": 76,
        "latitude": 39.5,
        "minElevationDeg": 5,
        "maxRateMbps": 1600,
        "status": "AVAILABLE"
      },
      {
        "id": "ST-MY-01",
        "name": "密云站",
        "longitude": 116.98,
        "latitude": 40.45,
        "minElevationDeg": 7,
        "maxRateMbps": 1200,
        "status": "AVAILABLE"
      }
    ]
  },
  "windows": [
    {
      "id": "SP-W01",
      "kind": "SPACE",
      "satelliteId": 2068,
      "targetId": 8890,
      "startTime": "2026-11-06T08:12:00+08:00",
      "endTime": "2026-11-06T08:19:00+08:00",
      "durationSec": 420,
      "score": 94,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "minDistanceKm": 1382,
      "maxDistanceKm": 2148,
      "sunAngleDeg": 103,
      "angleRateDegPerSec": 0.021,
      "dataGb": 12
    },
    {
      "id": "SP-W02",
      "kind": "SPACE",
      "satelliteId": 845,
      "targetId": 8890,
      "startTime": "2026-11-06T08:43:00+08:00",
      "endTime": "2026-11-06T08:48:30+08:00",
      "durationSec": 330,
      "score": 89,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "minDistanceKm": 952,
      "maxDistanceKm": 1820,
      "sunAngleDeg": 85,
      "angleRateDegPerSec": 0.032,
      "dataGb": 10
    },
    {
      "id": "SP-W03",
      "kind": "SPACE",
      "satelliteId": 2068,
      "targetId": 9002,
      "startTime": "2026-11-06T10:18:00+08:00",
      "endTime": "2026-11-06T10:24:00+08:00",
      "durationSec": 360,
      "score": 86,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "minDistanceKm": 1180,
      "maxDistanceKm": 2460,
      "sunAngleDeg": 72,
      "angleRateDegPerSec": 0.018,
      "dataGb": 9
    },
    {
      "id": "GD-W01",
      "kind": "GROUND",
      "satelliteId": 3150,
      "targetId": "GT-QH-01",
      "startTime": "2026-11-06T09:10:00+08:00",
      "endTime": "2026-11-06T09:15:30+08:00",
      "durationSec": 330,
      "score": 96,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "cloudPercent": 22,
      "resolutionM": 0.8,
      "offNadirDeg": 27,
      "dataGb": 24
    },
    {
      "id": "GD-W02",
      "kind": "GROUND",
      "satelliteId": 2068,
      "targetId": "GT-QH-01",
      "startTime": "2026-11-06T10:02:00+08:00",
      "endTime": "2026-11-06T10:07:00+08:00",
      "durationSec": 300,
      "score": 79,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "cloudPercent": 46,
      "resolutionM": 0.9,
      "offNadirDeg": 32,
      "dataGb": 20
    },
    {
      "id": "GD-W03",
      "kind": "GROUND",
      "satelliteId": 3150,
      "targetId": "GT-PY-02",
      "startTime": "2026-11-06T11:05:00+08:00",
      "endTime": "2026-11-06T11:10:00+08:00",
      "durationSec": 300,
      "score": 88,
      "illumination": "SUNLIT",
      "epochAgeDays": 2,
      "cloudPercent": 18,
      "resolutionM": 0.8,
      "offNadirDeg": 22,
      "dataGb": 18
    }
  ],
  "downlinks": [
    {
      "id": "DL-01",
      "satelliteId": 2068,
      "stationId": "ST-KS-01",
      "startTime": "2026-11-06T08:25:00+08:00",
      "endTime": "2026-11-06T08:33:00+08:00",
      "durationSec": 480,
      "capacityGb": 96
    },
    {
      "id": "DL-02",
      "satelliteId": 845,
      "stationId": "ST-MY-01",
      "startTime": "2026-11-06T09:05:00+08:00",
      "endTime": "2026-11-06T09:13:00+08:00",
      "durationSec": 480,
      "capacityGb": 72
    },
    {
      "id": "DL-03",
      "satelliteId": 3150,
      "stationId": "ST-KS-01",
      "startTime": "2026-11-06T09:28:00+08:00",
      "endTime": "2026-11-06T09:35:00+08:00",
      "durationSec": 420,
      "capacityGb": 84
    },
    {
      "id": "DL-04",
      "satelliteId": 3150,
      "stationId": "ST-MY-01",
      "startTime": "2026-11-06T11:24:00+08:00",
      "endTime": "2026-11-06T11:32:00+08:00",
      "durationSec": 480,
      "capacityGb": 72
    }
  ],
  "tools": [
    {
      "code": "planning-satellite-query",
      "name": "卫星目录查询",
      "method": "GET",
      "endpoint": "/api/v1/space/satellites/page",
      "version": 1,
      "description": "卫星目录查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-payload-query",
      "name": "独立载荷配置查询",
      "method": "GET",
      "endpoint": "/api/v1/space/satellites/{satelliteId}/payload-profiles",
      "version": 1,
      "description": "独立载荷配置查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "satelliteIds"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "profiles": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-space-target-query",
      "name": "空间目标筛选",
      "method": "POST",
      "endpoint": "/api/v1/space/targets/query",
      "version": 1,
      "description": "空间目标筛选，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "target": {
            "type": "object"
          }
        }
      }
    },
    {
      "code": "planning-ground-target-query",
      "name": "地面目标区域查询",
      "method": "GET",
      "endpoint": "mock://catalog/ground-targets",
      "version": 1,
      "description": "地面目标区域查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "target": {
            "type": "object"
          }
        }
      }
    },
    {
      "code": "planning-station-query",
      "name": "地面站目录查询",
      "method": "GET",
      "endpoint": "/api/v1/space/stations",
      "version": 1,
      "description": "地面站目录查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-defaults-read",
      "name": "规划默认约束",
      "method": "GET",
      "endpoint": "/api/v1/space/defaults",
      "version": 1,
      "description": "规划默认约束，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "items": {
            "type": "object"
          }
        }
      }
    },
    {
      "code": "planning-space-plan",
      "name": "对空观测窗口规划",
      "method": "POST",
      "endpoint": "/api/v1/space/visibility/plan",
      "version": 1,
      "description": "对空观测窗口规划，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "targetId",
          "observerSatelliteIds",
          "startTime",
          "endTime"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "windows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "excludedWindows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-ground-plan",
      "name": "对地观测窗口规划",
      "method": "POST",
      "endpoint": "mock://ground/visibility/plan",
      "version": 1,
      "description": "对地观测窗口规划，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "targetId",
          "observerSatelliteIds",
          "startTime",
          "endTime"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "windows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "excludedWindows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-downlink-plan",
      "name": "站星下传窗口规划",
      "method": "POST",
      "endpoint": "/api/v1/space/downlink/plan",
      "version": 1,
      "description": "站星下传窗口规划，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "satelliteIds"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "windows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-downlink-validate",
      "name": "完整时段与容量校验",
      "method": "POST",
      "endpoint": "/api/v1/space/downlink/validate-window",
      "version": 1,
      "description": "完整时段与容量校验，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "planId"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "covered": {
            "type": "boolean"
          },
          "windows": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-ontology-facts",
      "name": "本体对象事实与关联",
      "method": "POST",
      "endpoint": "/onto/releases/{releaseId}/semantic/facts",
      "version": 1,
      "description": "本体对象事实与关联，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "releaseRef"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "facts": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "relations": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      }
    },
    {
      "code": "planning-ontology-rules",
      "name": "本体规则与评分",
      "method": "POST",
      "endpoint": "/onto/releases/{releaseId}/semantic/decisions",
      "version": 1,
      "description": "本体规则与评分，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "decisions": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "rules": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-task-submit",
      "name": "观测任务提交",
      "method": "POST",
      "endpoint": "/api/v1/space/visibility/tasks",
      "version": 1,
      "description": "观测任务提交，按当前演示场景返回完整业务数据。",
      "readonly": false,
      "sideEffect": true,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "plan"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "taskId": {
            "type": "string"
          },
          "accepted": {
            "type": "boolean"
          },
          "reason": {
            "type": "string"
          }
        }
      }
    },
    {
      "code": "planning-task-status",
      "name": "任务进度查询",
      "method": "GET",
      "endpoint": "/api/v1/space/visibility/tasks/{taskId}",
      "version": 1,
      "description": "任务进度查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "taskId"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "status": {
            "type": "string",
            "enum": [
              "SUBMITTED",
              "RUNNING",
              "SUCCEEDED",
              "FAILED",
              "CANCELLED"
            ]
          },
          "progress": {
            "type": "number"
          },
          "stages": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      }
    },
    {
      "code": "planning-task-result",
      "name": "任务结果查询",
      "method": "GET",
      "endpoint": "/api/v1/space/visibility/tasks/{taskId}/result",
      "version": 1,
      "description": "任务结果查询，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "taskId"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "plan": {
            "type": "object"
          },
          "artifacts": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    },
    {
      "code": "planning-task-cancel",
      "name": "受控取消任务",
      "method": "DELETE",
      "endpoint": "/api/v1/space/visibility/tasks/{taskId}",
      "version": 1,
      "description": "受控取消任务，按当前演示场景返回完整业务数据。",
      "readonly": false,
      "sideEffect": true,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "taskId"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "status": {
            "type": "string"
          }
        }
      }
    },
    {
      "code": "planning-selection-save",
      "name": "确认规划窗口选择",
      "method": "PUT",
      "endpoint": "/api/v1/space/visibility/task-results/{id}/selection",
      "version": 1,
      "description": "确认规划窗口选择，按当前演示场景返回完整业务数据。",
      "readonly": false,
      "sideEffect": true,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": [
          "taskId",
          "windowIds"
        ]
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "saved": {
            "type": "boolean"
          }
        }
      }
    },
    {
      "code": "planning-artifact-export",
      "name": "导出规划报告与窗口CSV",
      "method": "POST",
      "endpoint": "mock://planning/artifacts",
      "version": 1,
      "description": "导出规划报告与窗口CSV，按当前演示场景返回完整业务数据。",
      "readonly": true,
      "sideEffect": false,
      "inputSchema": {
        "type": "object",
        "properties": {
          "satelliteId": {
            "type": "integer",
            "description": "观测卫星目录ID"
          },
          "satelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            },
            "description": "卫星ID集合"
          },
          "observerSatelliteIds": {
            "type": "array",
            "items": {
              "type": "integer"
            }
          },
          "targetId": {
            "type": [
              "string",
              "integer"
            ],
            "description": "空间目标数字ID或地面目标GT编码"
          },
          "startTime": {
            "type": "string",
            "format": "date-time"
          },
          "endTime": {
            "type": "string",
            "format": "date-time"
          },
          "stationId": {
            "type": "string"
          },
          "taskId": {
            "type": "string"
          },
          "planId": {
            "type": "string"
          },
          "plan": {
            "type": "object",
            "description": "已经冻结的规划、窗口、下传与约束"
          },
          "cloudLimit": {
            "type": "number",
            "minimum": 0,
            "maximum": 100
          },
          "releaseRef": {
            "type": "object",
            "description": "所选本体正式版本四元组"
          },
          "windowIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "pageNumber": {
            "type": "integer",
            "minimum": 1
          },
          "pageSize": {
            "type": "integer",
            "minimum": 1,
            "maximum": 200
          }
        },
        "required": []
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "files": {
            "type": "array",
            "items": {
              "type": "object"
            }
          },
          "artifacts": {
            "type": "array",
            "items": {
              "type": "object"
            }
          }
        }
      }
    }
  ],
  "examples": [
    {
      "id": "catalog",
      "title": "业务资源总览",
      "message": "列出可用的卫星、空间目标、地面目标和地面站",
      "intent": "CATALOG"
    },
    {
      "id": "space",
      "title": "空间目标观测",
      "message": "规划明天上午对空间目标 STARLINK-31091 的观测窗口",
      "intent": "SPACE"
    },
    {
      "id": "ground",
      "title": "地面目标观测",
      "message": "规划明天上午青海湖生态观测区的对地观测，云量不超过30%",
      "intent": "GROUND"
    },
    {
      "id": "downlink",
      "title": "下传与最优方案",
      "message": "检查这套规划能否通过喀什站下传，推荐最优方案",
      "intent": "DOWNLINK"
    },
    {
      "id": "ontology",
      "title": "本体如何参与",
      "message": "说明当前本体的对象关系、规则和这次规划的判定依据",
      "intent": "ONTOLOGY"
    },
    {
      "id": "submit",
      "title": "受控提交任务",
      "message": "提交这套规划",
      "intent": "SUBMIT"
    },
    {
      "id": "result",
      "title": "任务结果与制品",
      "message": "查询刚才任务的结果",
      "intent": "RESULT"
    },
    {
      "id": "export",
      "title": "导出窗口与报告",
      "message": "导出当前规划窗口CSV与报告",
      "intent": "EXPORT"
    },
    {
      "id": "progress",
      "title": "执行中任务进度",
      "message": "查询任务 DEMO-RUNNING-0001 的进度",
      "intent": "PROGRESS"
    },
    {
      "id": "cancel",
      "title": "受控取消任务",
      "message": "取消任务 DEMO-RUNNING-0001",
      "intent": "CANCEL"
    },
    {
      "id": "no-window",
      "title": "无窗口的业务解释",
      "message": "规划鄱阳湖湿地夜间光学观测",
      "intent": "NO_WINDOW"
    },
    {
      "id": "payload-mismatch",
      "title": "载荷不匹配",
      "message": "只用卫星845规划青海湖对地观测",
      "intent": "BLOCKED"
    },
    {
      "id": "stale",
      "title": "星历时效异常",
      "message": "模拟星历过期，规划 STARLINK-31091 的观测",
      "intent": "BLOCKED"
    }
  ],
  "systemPrompt": "你是卫星规划助手，面向空间目标监测与地面目标遥感观测。先确认目标、日期、时间范围、观测星及载荷；区分空间目标SPACE、地面目标GROUND与地面站，下传不是对地成像。先查询目录和载荷，再引用选定正式本体的对象事实、规则与策略，分别调用对空/对地规划工具。检查星历时效、载荷匹配、光照、云量、分辨率和下传时序及容量，列出可用与排除窗口，给出推荐理由。任务提交、取消与确认选择具有副作用，必须人工确认；提交前冻结计划，不得把模型建议当审批通过。每次回答先给结论，再给窗口表、工具调用、规则判定和本体版本来源。无窗口、信息缺失和约束不满足要明确说明与建议。当前为纯Mock：所有目录、参数和计算结果都是合成示例，只匹配场景2026-11-06；不得宣称实时计算或真实调度。"
}
`;export{n as p};
