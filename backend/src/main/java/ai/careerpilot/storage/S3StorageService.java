package ai.careerpilot.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.UUID;

@Service
public class S3StorageService {

    private final S3Client s3;
    private final String bucket;

    public S3StorageService(S3Client s3, @Value("${storage.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ignored) {
            // Bucket may exist or endpoint may not be ready yet; PutObject will fail loudly if so.
        }
    }

    public String upload(MultipartFile file, String prefix) throws IOException {
        String key = prefix + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromBytes(file.getBytes()));
        return key;
    }

    /** Upload raw bytes (e.g. a generated DOCX) under a deterministic prefix; returns the key. */
    public String uploadBytes(byte[] data, String prefix, String filename, String contentType) {
        String key = prefix + "/" + UUID.randomUUID() + "-" + filename;
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
        return key;
    }

    /** Fetch an object's bytes by key. */
    public byte[] download(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /**
     * P2 WI3 — keys under {@code prefix} last modified before {@code before}, capped at {@code max}.
     *
     * <p>Paginates rather than assuming one page: the default list returns 1000 keys, and a bucket
     * that has been running for a year holds far more than that, so a single-page implementation
     * would silently stop reclaiming once the prefix grew past the page size.
     *
     * <p>The cap exists because this runs on a scheduler on a 1-vCPU box; reclaiming a bounded batch
     * every sweep converges just as surely as one unbounded pass and cannot monopolise the host.
     */
    public java.util.List<String> listKeysOlderThan(String prefix, java.time.Instant before, int max) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        String continuation = null;
        do {
            software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder request =
                    software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                            .bucket(bucket).prefix(prefix).maxKeys(1000);
            if (continuation != null) request.continuationToken(continuation);

            software.amazon.awssdk.services.s3.model.ListObjectsV2Response response =
                    s3.listObjectsV2(request.build());
            for (software.amazon.awssdk.services.s3.model.S3Object object : response.contents()) {
                if (object.lastModified() != null && object.lastModified().isBefore(before)) {
                    keys.add(object.key());
                    if (keys.size() >= max) return keys;
                }
            }
            continuation = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuation != null);
        return keys;
    }

    /**
     * Delete one object. Idempotent by S3's own contract — deleting an absent key succeeds — which is
     * what makes the retention sweep safe to retry after a partial failure.
     */
    public void delete(String key) {
        s3.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(bucket).key(key).build());
    }
}
