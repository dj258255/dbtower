package io.dbtower.backup.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 백업 원격 보관 (3-2-1의 오프사이트) — 성공한 백업 산출물을 S3 호환 스토리지에 올린다.
 *
 * 3-2-1 원칙에서 지금까지 비어 있던 조각: 로컬 디스크에만 있는 백업은 서버가 죽으면 같이 죽는다.
 * S3 "호환"으로 잡은 이유 — AWS S3뿐 아니라 MinIO(로컬/온프레미스)·R2 등이 같은 API라,
 * 셀프호스트 사용자가 클라우드 계정 없이도 MinIO 컨테이너 하나로 오프사이트를 갖출 수 있다.
 *
 * 실패 정책: 업로드 실패는 백업 실패가 아니다 — 로컬 백업 성공은 유효한 사실이므로 이력은
 * SUCCESS로 남기고 remoteLocation만 비운다(WARN 로그). 원격 보관 여부는 별개 컬럼으로 구분 기록.
 *
 * 자격증명은 환경변수(DBTOWER_S3_ACCESS_KEY/SECRET_KEY)로만 — 설정 파일에 넣지 않는다.
 */
@Component
public class RemoteBackupStore {

    private static final Logger log = LoggerFactory.getLogger(RemoteBackupStore.class);

    private final boolean enabled;
    private final String bucket;
    private final S3Client s3;

    public RemoteBackupStore(@Value("${dbtower.backup.remote.enabled:false}") boolean enabled,
                             @Value("${dbtower.backup.remote.endpoint:}") String endpoint,
                             @Value("${dbtower.backup.remote.bucket:dbtower-backups}") String bucket,
                             @Value("${dbtower.backup.remote.region:us-east-1}") String region,
                             @Value("${DBTOWER_S3_ACCESS_KEY:}") String accessKey,
                             @Value("${DBTOWER_S3_SECRET_KEY:}") String secretKey) {
        this.bucket = bucket;
        if (!enabled) {
            this.enabled = false;
            this.s3 = null;
            log.info("백업 원격 보관 비활성 — dbtower.backup.remote.enabled=false");
            return;
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            // fail-closed 대신 명시적 비활성 — 백업 자체(로컬)는 계속 되므로 기동은 막지 않되 크게 알린다
            this.enabled = false;
            this.s3 = null;
            log.warn("백업 원격 보관 설정됐지만 자격증명 없음(DBTOWER_S3_ACCESS_KEY/SECRET_KEY) — 원격 보관 비활성");
            return;
        }
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));
        if (!endpoint.isBlank()) {
            // MinIO 등 커스텀 엔드포인트는 가상 호스트 스타일(bucket.host) DNS가 없어 path-style 필수
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3 = builder.build();
        this.enabled = true;
        log.info("백업 원격 보관 활성 — bucket={} endpoint={}", bucket, endpoint.isBlank() ? "AWS" : endpoint);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 백업 산출물 업로드. 성공 시 원격 위치(s3://bucket/key), 실패 시 empty — 예외를 밖으로
     * 던지지 않는다(로컬 백업 성공을 업로드 실패가 뒤집으면 안 된다).
     */
    public Optional<String> upload(Long instanceId, String localLocation) {
        if (!enabled || localLocation == null || localLocation.isBlank()) {
            return Optional.empty();
        }
        try {
            Path file = Path.of(localLocation);
            if (!Files.isRegularFile(file)) {
                log.warn("원격 보관 건너뜀 — 산출물이 파일이 아님(서버 사이드 백업 등): {}", localLocation);
                return Optional.empty();
            }
            ensureBucket();
            String key = "instance-%d/%s".formatted(instanceId, file.getFileName());
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), file);
            String remote = "s3://%s/%s".formatted(bucket, key);
            log.info("백업 원격 보관 완료 instance={} {} ({} bytes)", instanceId, remote, Files.size(file));
            return Optional.of(remote);
        } catch (Exception e) {
            log.warn("백업 원격 보관 실패(로컬 백업은 유효) instance={} cause={}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("원격 보관 버킷 생성: {}", bucket);
        }
    }
}
