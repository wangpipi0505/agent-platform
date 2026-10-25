from pathlib import Path
p=Path('admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java');data=p.read_bytes();old=b'        if(!result.actor().roles().contains("APPROVER"))throw AuthorityProtocol.denied();\n';assert data.count(old)==1;p.write_bytes(data.replace(old,b''))
