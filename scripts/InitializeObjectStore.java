import io.minio.*;

/** 本地基础设施初始化，只创建新工程配置的桶，不读取或迁移任何旧存储。 */
public class InitializeObjectStore {
    public static void main(String[] args) throws Exception {
        var client =
                MinioClient.builder()
                        .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                        .credentials(
                                System.getenv("AGENT_MINIO_ACCESS_KEY"),
                                System.getenv("AGENT_MINIO_SECRET_KEY"))
                        .build();
        String bucket = System.getenv("AGENT_MINIO_BUCKET");
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        System.out.println("Dedicated object store bucket is ready");
        // SDK的连接回收线程可保活约一分钟；一次性初始化进程在完成后明确退出。
        System.exit(0);
    }
}
