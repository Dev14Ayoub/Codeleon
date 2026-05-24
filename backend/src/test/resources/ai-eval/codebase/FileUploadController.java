package fake.web;

import java.util.UUID;

/**
 * REST endpoint for uploading user files to S3. Validates the MIME
 * type against an allow-list, applies the per-user rate limiter, and
 * streams the body straight to S3 without buffering the whole file in
 * memory (large file uploads previously OOM'd this service).
 */
public class FileUploadController {

    private final RateLimiter rateLimiter;
    private final S3Client s3;
    private final ContentTypeAllowList allowList;

    public FileUploadController(RateLimiter rateLimiter, S3Client s3, ContentTypeAllowList allowList) {
        this.rateLimiter = rateLimiter;
        this.s3 = s3;
        this.allowList = allowList;
    }

    public UploadResponse upload(UUID userId, String contentType, java.io.InputStream body, long contentLength) {
        if (!rateLimiter.tryAcquire(userId.toString())) {
            throw new RateLimitExceededException("upload");
        }
        if (!allowList.isPermitted(contentType)) {
            throw new UnsupportedMediaTypeException(contentType);
        }
        String key = "uploads/" + userId + "/" + UUID.randomUUID();
        s3.streamPut(key, body, contentLength);
        return new UploadResponse(key);
    }
}
