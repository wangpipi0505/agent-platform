package cn.agentplatform.app.domain;

/** Only persisted identity/version fields; no draft, snapshot or executable configuration. */
public record PublishedMetadata(String appCode,long versionId,int versionNo,String versionSha256) {}
