package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private final AuthService auth;
    private final MediaService media;

    public MediaController(AuthService auth, MediaService media) {
        this.auth = auth;
        this.media = media;
    }

    @PostMapping("/admin/materials/uploads")
    public Map<String, Object> create(
            @RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(media.create(input, Data.str(auth.admin(request), "actorId")));
    }

    @PutMapping("/media/uploads/{id}")
    public Map<String, Object> upload(
            @PathVariable String id, @RequestParam String ticket, HttpServletRequest request)
            throws IOException {
        media.upload(id, ticket, request.getInputStream());
        return ApiAdvice.ok(Data.map("uploaded", true));
    }

    @PostMapping("/admin/materials/{id}/upload-complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return ResponseEntity.accepted().body(ApiAdvice.ok(media.completeUpload(id)));
    }

    @GetMapping("/admin/materials")
    public Object list(HttpServletRequest request) {
        auth.admin(request);
        return ApiController.page(media.list(), request);
    }

    @GetMapping("/admin/materials/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(media.get(id));
    }

    @PostMapping("/admin/materials/{id}/compatibility-check")
    public Map<String, Object> compatibility(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(
                media.compatibility(
                        id, Data.bool(input, "passed"), Data.str(auth.admin(request), "actorId")));
    }

    @PostMapping("/admin/materials/{id}/media-ticket")
    public Map<String, Object> ticket(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(
                media.ticket(
                        id,
                        input.containsKey("pageNo") ? (int) Data.num(input, "pageNo", 1) : null));
    }

    @RequestMapping(
            value = "/media/{ticket}/file",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void download(
            @PathVariable String ticket, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        MediaService.DownloadFile file = media.download(ticket);
        long first = 0;
        long last = file.size() - 1;
        String range = request.getHeader("Range");
        if (range != null) {
            try {
                if (!range.matches("bytes=\\d*-\\d*") || "bytes=-".equals(range)) {
                    throw new IllegalArgumentException("Unsupported range");
                }
                String[] values = range.substring(6).split("-", -1);
                if (values[0].isEmpty()) {
                    first = Math.max(0, file.size() - Long.parseLong(values[1]));
                } else {
                    first = Long.parseLong(values[0]);
                    if (!values[1].isEmpty()) {
                        last = Math.min(last, Long.parseLong(values[1]));
                    }
                }
                if (first < 0 || first > last || first >= file.size()) {
                    throw new IllegalArgumentException("Unsatisfiable range");
                }
                response.setStatus(206);
                response.setHeader(
                        "Content-Range", "bytes " + first + "-" + last + "/" + file.size());
            } catch (IllegalArgumentException exception) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + file.size());
                return;
            }
        }
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(file.contentType());
        response.setContentLengthLong(last - first + 1);
        if ("HEAD".equals(request.getMethod())) {
            return;
        }
        try (RandomAccessFile source = new RandomAccessFile(file.path().toFile(), "r")) {
            source.seek(first);
            long remaining = last - first + 1;
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                response.getOutputStream().write(buffer, 0, read);
                remaining -= read;
            }
        }
    }
}
