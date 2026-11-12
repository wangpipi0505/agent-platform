import{A as k}from"./2020-CT3TCxRe.js";const f=e=>e!==null&&typeof e=="object"&&!Array.isArray(e)?e:{};function O(e){return{releaseRef:e.releaseRef,objectTypes:e.projection.definitions.objects.map(n=>n.ref.name),actions:e.operations.filter(n=>n.status==="ACTIVE").map(n=>n.opCode),extensions:e.semanticExtensions.filter(n=>{var t;return!!((t=n.descriptor)!=null&&t.objectType)}).map(n=>({id:n.extensionId,opCode:n.opCode,profile:n.profile,schemaVersion:n.schemaVersion,status:"ACTIVE",descriptor:n.descriptor}))}}function g(e,n="$",t=0){if(t>6)return[];const r=f(e),i=f(r.properties),s=Object.entries(i).flatMap(([a,l])=>g(l,`${n}.${a}`,t+1));return s.length?s:r.type==="array"?g(r.items,`${n}[0]`,t+1):n==="$"?[]:[n]}function V(e,n,t,r,i){const s=t.semanticExtensions.find(a=>a.extensionId===n.extensionId&&a.opCode===n.opCode);if(!s||s.descriptor.objectType!==n.objectType||!r.readonly||r.sideEffect||r.code!==n.skill.skillCode||r.version!==n.skill.skillVersion)throw Error("请选择当前正式扩展与只读技能版本");return{bindingId:`sb-${crypto.randomUUID().replaceAll("-","")}`,appCode:e,requestId:i,releaseRef:t.releaseRef,extensionId:s.extensionId,extensionDigest:s.extensionSha256,opCode:s.opCode,skill:{skillCode:r.code,skillVersion:r.version,skillSha256:r.sha256},profiles:["read-only@1.0"]}}function _(e,n){const t=Array.isArray(e.goals)?e.goals:[];return{...e,goals:[...t.filter(r=>f(r).goalId!==n.goalId),n]}}function T(e,n){const t=Array.isArray(e.skillRefs)?e.skillRefs.map(f):[];for(const r of n){const i=t.find(s=>s.code===r.code);if(i&&(i.version!==r.version||i.enabled===!1))throw Error("技能已有其他版本或已停用，请先在应用配置中确认");i||t.push({code:r.code,version:r.version})}return{...e,skillRefs:t}}const N=`{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:agent-platform:semantic-runtime:goal:1.0",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "profile": {
      "const": "goal"
    },
    "schemaVersion": {
      "const": "1.0"
    },
    "runId": {
      "type": "integer",
      "minimum": 1
    },
    "runStatus": {
      "type": "string"
    },
    "goals": {
      "type": "array",
      "maxItems": 32,
      "items": {
        "$ref": "#/$defs/outcome"
      }
    },
    "counts": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "ACHIEVED": {
          "type": "integer",
          "minimum": 0
        },
        "NOT_ACHIEVED": {
          "type": "integer",
          "minimum": 0
        },
        "PENDING": {
          "type": "integer",
          "minimum": 0
        },
        "FAILED": {
          "type": "integer",
          "minimum": 0
        },
        "UNKNOWN": {
          "type": "integer",
          "minimum": 0
        }
      },
      "required": [
        "ACHIEVED",
        "NOT_ACHIEVED",
        "PENDING",
        "FAILED",
        "UNKNOWN"
      ]
    },
    "complete": {
      "type": "boolean"
    },
    "budget": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "maxGoals": {
          "const": 32
        },
        "declaredGoals": {
          "type": "integer",
          "minimum": 0
        },
        "verifiedGoals": {
          "type": "integer",
          "minimum": 0,
          "maximum": 32
        },
        "truncated": {
          "type": "boolean"
        }
      },
      "required": [
        "maxGoals",
        "declaredGoals",
        "verifiedGoals",
        "truncated"
      ]
    },
    "skipped": {
      "type": "boolean"
    }
  },
  "required": [
    "profile",
    "schemaVersion",
    "goals"
  ],
  "$defs": {
    "path": {
      "type": "string",
      "maxLength": 256,
      "pattern": "^\\\\$(?:\\\\.[A-Za-z_][A-Za-z0-9_]*(?:\\\\[(?:0|[1-9][0-9]{0,8})\\\\])*)+$"
    },
    "operation": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "skillCode": {
          "type": "string",
          "pattern": "^[A-Za-z][A-Za-z0-9._-]{0,63}$"
        },
        "arguments": {
          "type": "object"
        },
        "objectIdPath": {
          "$ref": "#/$defs/path"
        }
      },
      "required": [
        "skillCode",
        "arguments",
        "objectIdPath"
      ]
    },
    "readback": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "skillCode": {
          "type": "string",
          "pattern": "^[A-Za-z][A-Za-z0-9._-]{0,63}$"
        },
        "arguments": {
          "type": "object"
        },
        "objectIdPath": {
          "$ref": "#/$defs/path"
        },
        "resultObjectIdPath": {
          "$ref": "#/$defs/path"
        }
      },
      "required": [
        "skillCode",
        "arguments",
        "objectIdPath",
        "resultObjectIdPath"
      ]
    },
    "predicate": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "path": {
          "$ref": "#/$defs/path"
        },
        "operator": {
          "enum": [
            "equals",
            "nonEmpty",
            "exists"
          ]
        },
        "value": {
          "type": [
            "string",
            "number",
            "boolean",
            "null"
          ]
        }
      },
      "required": [
        "path",
        "operator"
      ],
      "allOf": [
        {
          "if": {
            "properties": {
              "operator": {
                "const": "equals"
              }
            }
          },
          "then": {
            "required": [
              "value"
            ]
          },
          "else": {
            "not": {
              "required": [
                "value"
              ]
            }
          }
        }
      ]
    },
    "criterion": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "target": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "objectType": {
              "type": "string",
              "minLength": 1,
              "maxLength": 128,
              "pattern": "\\\\S"
            },
            "objectId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 256,
              "pattern": "\\\\S"
            }
          },
          "required": [
            "objectType",
            "objectId"
          ]
        },
        "readback": {
          "$ref": "#/$defs/readback"
        },
        "action": {
          "$ref": "#/$defs/operation"
        },
        "predicate": {
          "$ref": "#/$defs/predicate"
        }
      },
      "required": [
        "target",
        "readback",
        "predicate"
      ]
    },
    "declaration": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "goalId": {
          "type": "string",
          "pattern": "^[A-Za-z0-9._:-]{1,64}$"
        },
        "criterion": {
          "$ref": "#/$defs/criterion"
        },
        "nextStep": {
          "type": "string",
          "maxLength": 256
        }
      },
      "required": [
        "goalId",
        "criterion"
      ]
    },
    "readbackRef": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "evidenceKind": {
          "const": "call"
        },
        "callId": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": [
        "evidenceKind",
        "callId"
      ]
    },
    "detail": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "callId": {
          "type": "integer",
          "minimum": 1
        },
        "callStatus": {
          "type": "string"
        },
        "skillCode": {
          "type": "string",
          "pattern": "^[A-Za-z][A-Za-z0-9._-]{0,63}$"
        },
        "predicate": {
          "$ref": "#/$defs/path"
        },
        "matched": {
          "type": "boolean"
        },
        "readbackValue": {},
        "reason": {
          "type": "string"
        },
        "errors": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "errorSemantic": {
          "type": "string"
        }
      },
      "required": []
    },
    "outcome": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "goalId": {
          "type": "string",
          "pattern": "^[A-Za-z0-9._:-]{1,64}$"
        },
        "criterion": {
          "$ref": "#/$defs/criterion"
        },
        "nextStep": {
          "type": [
            "string",
            "null"
          ],
          "maxLength": 256
        },
        "outcomeState": {
          "enum": [
            "ACHIEVED",
            "NOT_ACHIEVED",
            "PENDING",
            "FAILED",
            "UNKNOWN"
          ]
        },
        "readbackRef": {
          "anyOf": [
            {
              "$ref": "#/$defs/readbackRef"
            },
            {
              "type": "null"
            }
          ]
        },
        "detail": {
          "$ref": "#/$defs/detail"
        },
        "runId": {
          "type": "integer",
          "minimum": 1
        },
        "revision": {
          "type": "integer",
          "minimum": 1
        },
        "updatedAt": {
          "type": [
            "string",
            "number"
          ]
        }
      },
      "required": [
        "goalId",
        "criterion",
        "outcomeState",
        "detail"
      ],
      "allOf": [
        {
          "if": {
            "properties": {
              "outcomeState": {
                "const": "ACHIEVED"
              }
            }
          },
          "then": {
            "required": [
              "readbackRef"
            ],
            "properties": {
              "readbackRef": {
                "$ref": "#/$defs/readbackRef"
              }
            }
          }
        },
        {
          "if": {
            "properties": {
              "outcomeState": {
                "enum": [
                  "PENDING",
                  "UNKNOWN"
                ]
              }
            }
          },
          "then": {
            "required": [
              "nextStep"
            ],
            "properties": {
              "nextStep": {
                "type": "string",
                "minLength": 1,
                "maxLength": 256
              }
            }
          }
        }
      ]
    }
  }
}
`,S=JSON.parse(N),h="read-only@1.0";class o extends Error{constructor(n,t){super(t),this.field=n,this.name="SemanticFormError"}}const C=/^[0-9a-f]{64}$/,R=/^[A-Za-z0-9._:-]{1,64}$/,j=/^[A-Za-z0-9._:-]{1,80}$/,b=32,y=16;function v(e){return e.some(n=>["VIEWER","EDITOR","APPROVER","ADMIN"].includes(n))}function F(e){return e.some(n=>["EDITOR","APPROVER","ADMIN"].includes(n))}function d(e,n,t,r=128){const i=typeof e=="string"?e.trim():"";if(!i||i.length>r||t&&!t.test(i))throw new o(n,`${n} 不是有效的已发布取值`);return i}function $(e,n){const t=typeof e=="string"?e.trim():"";if(!C.test(t))throw new o(n,`${n} 必须为 64 位小写十六进制摘要`);return t}function I(e){return{spaceCode:d(e==null?void 0:e.spaceCode,"releaseRef.spaceCode",/^[A-Za-z0-9._-]{1,64}$/,64),releaseId:d(e==null?void 0:e.releaseId,"releaseRef.releaseId"),version:d(e==null?void 0:e.version,"releaseRef.version",/^v[1-9][0-9]*$/,64),contentDigest:$(e==null?void 0:e.contentDigest,"releaseRef.contentDigest")}}function w(e){const n=new Set,t=[];for(const r of e??[]){const i=d(r==null?void 0:r.field,"factFields.field",/^[A-Za-z_][A-Za-z0-9_-]{0,63}$/,64);if(r.kind!=="attribute"&&r.kind!=="relation")throw new o("factFields.kind","事实字段类型只能是 attribute 或 relation");const s=`${r.kind}:${i}`;n.has(s)||(n.add(s),t.push({field:i,kind:r.kind}))}if(t.length>b)throw new o("factFields",`事实字段最多 ${b} 项`);return t}function z(e,n){var m;const t=I(e==null?void 0:e.releaseRef);if(JSON.stringify(t)!==JSON.stringify(I(n==null?void 0:n.releaseRef)))throw new o("releaseRef","绑定必须引用当前选择的固定发布版本");const r=d(e.objectType,"objectType",/^[A-Za-z_][A-Za-z0-9_]{0,63}$/,64);if(!n.objectTypes.includes(r))throw new o("objectType","对象类型不在当前发布的可用定义中");const i=d(e.opCode,"opCode",j,80);if(!n.actions.includes(i))throw new o("opCode","动作不在当前发布且已授权的操作中");const s=n.extensions.find(c=>c.id===e.extensionId);if(!s||s.status!=="ACTIVE"||s.profile!==h)throw new o("extensionId","语义扩展未激活或 profile 不匹配");if(s.opCode!==i)throw new o("extensionId","语义扩展与所选动作不匹配");const a={skillCode:d(e.skillCode,"skill.skillCode",R,64),skillVersion:Number(e.skillVersion)};if(!Number.isInteger(a.skillVersion)||a.skillVersion<1)throw new o("skill.skillVersion","技能版本必须为正整数");e.skillSha256!==void 0&&e.skillSha256!==""&&(a.skillSha256=$(e.skillSha256,"skill.skillSha256"));const l=w((m=e.factFields)!=null&&m.length?e.factFields:s.descriptor.factFields);if(!l.length)throw new o("factFields","至少选择一个可投影事实字段");const p=[...new Set((e.ruleRefs??s.descriptor.ruleRefs??[]).map(c=>String(c)))];if(p.length>y)throw new o("ruleRefs",`规则引用最多 ${y} 项`);if(p.some(c=>!/^[A-Za-z0-9:._/-]{1,128}$/.test(c)))throw new o("ruleRefs","规则引用含非法字符");const u={releaseRef:t,extensionId:s.id,opCode:i,objectType:r,skill:a,profiles:[h],factFields:l};return p.length&&(u.ruleRefs=p),u}function A(e){switch(e.evidenceKind){case"call":return typeof e.callId=="string"&&e.callId.length>0;case"artifact":return Number.isInteger(e.artifactId)&&e.artifactId>=1;case"business-record":return!!e.recordSource&&!!e.recordId;default:return!1}}function L(e,n){var s,a,l;const t=typeof n=="string"&&n.trim().length>0;if(!e)return{state:"UNVERIFIED",authoritative:!1,evidence:[],note:"未取得服务端执行回执；模型文本不作为完成依据",modelTextIgnored:t};const r=(e.evidence??[]).filter(A);if(e.state==="SUCCEEDED"&&r.length===0)return{state:"UNVERIFIED",authoritative:!0,evidence:[],note:"服务端报告成功但没有可核验回执；不能视为已完成",modelTextIgnored:t};const i=e.state==="PENDING"?((s=e.message)==null?void 0:s.trim())||"服务端仍在等待业务侧结果":e.state==="UNKNOWN"?((a=e.message)==null?void 0:a.trim())||"服务端无法判定当前结果；请按批准的重查方式核对":((l=e.message)==null?void 0:l.trim())||"";return{state:e.state,authoritative:!0,evidence:r,note:i,modelTextIgnored:t&&e.state!=="SUCCEEDED"}}const x={ACHIEVED:"已达成（有业务回执）",NOT_ACHIEVED:"未达成",PENDING:"等待业务侧结果",FAILED:"执行失败",UNKNOWN:"无法判定"};function Z(e){return(e??[]).map(n=>{const t=[];let r=n.outcomeState;const i=n.readbackRef?[n.readbackRef]:[];return r==="ACHIEVED"&&(!n.readbackRef||!A(n.readbackRef))&&(t.push("ACHIEVED 缺少可核验的 call 回执"),r="UNKNOWN"),(r==="PENDING"||r==="UNKNOWN")&&!String(n.nextStep??"").trim()&&t.push("等待/未知状态缺少下一步说明"),{goalId:n.goalId,outcomeState:r,label:x[r]??r,nextStep:String(n.nextStep??"").trim(),issues:t,evidence:i}})}function q(e,n){var s,a;if(!n.canRead)return{visible:!1,reason:"当前账号无权读取该空间的本体定义",objects:[],actions:[],rules:[],attention:[]};if(!e)return{visible:!0,reason:"尚未加载正式定义",objects:[],actions:[],rules:[],attention:[]};const t=e.definitions.actions.filter(l=>!n.allowedActions||n.allowedActions.includes(l.code)),r=e.definitions.objects.filter(l=>!n.allowedObjectTypes||n.allowedObjectTypes.includes(l.ref.name)),i=[];return e.emptyResult&&i.push("当前发布没有可用定义；请确认版本与查询范围"),e.truncated&&i.push(`定义已按 ${((s=e.truncation)==null?void 0:s.reason)??"预算"} 截断（上限 ${((a=e.truncation)==null?void 0:a.limit)??"?"}）`),n.allowedActions&&t.length===0&&i.push("没有你已获授权的动作；请联系管理员而非扩大表单权限"),{visible:!0,reason:"",objects:r,actions:t,rules:e.definitions.rules,attention:i}}const E=new k({allErrors:!0,strict:!1}).compile({$defs:S.$defs,$ref:"#/$defs/declaration"});function D(e,n){let t=e;for(const r of n.slice(2).split("."))for(const i of r.match(/[A-Za-z_][A-Za-z0-9_]*|\[\d+\]/g)??[]){if(t===null||typeof t!="object")return;const s=i.startsWith("[")?i.slice(1,-1):i;t=t[s]}return t}function G(e){var t,r;const n={goalId:e.goalId,criterion:{target:e.target,readback:{skillCode:e.skillCode,arguments:e.readbackArguments,objectIdPath:e.objectIdPath,resultObjectIdPath:e.resultObjectIdPath},...e.action?{action:e.action}:{},predicate:{path:e.predicatePath,operator:e.operator,...e.operator==="equals"&&e.expectedValue!==void 0?{value:e.expectedValue}:{}}},...(t=e.nextStep)!=null&&t.trim()?{nextStep:e.nextStep.trim()}:{}};if(!E(n)){const i=(r=E.errors)==null?void 0:r[0];throw new o((i==null?void 0:i.instancePath)??"goal","目标配置不符合goal@1.0："+((i==null?void 0:i.message)??"字段不合法"))}for(const i of[n.criterion.readback,...n.criterion.action?[n.criterion.action]:[]])if(D(i.arguments,i.objectIdPath)!==n.criterion.target.objectId)throw new o("arguments","请求参数的对象标识必须与目标对象一致");try{return JSON.parse(JSON.stringify(n,(i,s)=>{if(["undefined","function","symbol","bigint"].includes(typeof s)||typeof s=="number"&&!Number.isFinite(s))throw new Error("non-JSON value");return s}))}catch{throw new o("goal","目标配置只能包含有限数值及JSON数据")}}export{o as S,L as a,z as b,v as c,F as d,G as e,q as f,Z as g,T as h,V as i,_ as m,f as o,O as r,g as s};
