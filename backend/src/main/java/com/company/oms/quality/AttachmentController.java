package com.company.oms.quality;

import com.company.oms.common.*;
import com.company.oms.system.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.UUID;

@RestController
@RequestMapping("/files")
@PreAuthorize("@menuPermissionService.has('customers','deliveries','statements','invoices')")
public class AttachmentController extends CrudController<Attachment> {
    private final AttachmentRepository attachmentRepository;
    @org.springframework.beans.factory.annotation.Autowired private com.company.oms.system.MenuPermissionService permissions;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    private void authorize(String type, Long businessId, boolean writing) {
        boolean allowed;
        Class<?> entityType;
        switch (type == null ? "" : type) {
            case "DELIVERY_ITEM_QUALITY_REPORT" -> { allowed = writing ? permissions.has("deliveries") : permissions.has("deliveries", "statements", "invoices"); entityType = com.company.oms.sales.DeliveryNoteItem.class; }
            case "DELIVERY_QUALITY_REPORT" -> { allowed = writing ? permissions.has("deliveries") : permissions.has("deliveries", "statements", "invoices"); entityType = com.company.oms.sales.DeliveryNote.class; }
            case "INVOICE_ATTACHMENT" -> { allowed = permissions.has("invoices"); entityType = com.company.oms.finance.Invoice.class; }
            default -> throw new org.springframework.security.access.AccessDeniedException("无权限访问该附件类型");
        }
        if (!allowed) throw new org.springframework.security.access.AccessDeniedException("无权限访问该附件");
        Object business = businessId == null ? null : entityManager.find(entityType, businessId);
        if (!(business instanceof BaseEntity entity) || Boolean.TRUE.equals(entity.deleted)) throw new BusinessException("关联业务记录不存在");
    }
    private Attachment active(Long id) {
        return attachmentRepository.findById(id).filter(a -> !Boolean.TRUE.equals(a.deleted))
            .orElseThrow(() -> new BusinessException("附件不存在"));
    }
    @Override @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Attachment attachment = active(id); authorize(attachment.businessType, attachment.businessId, true);
        attachment.deleted = true; attachmentRepository.save(attachment); return ApiResponse.ok();
    }

    private final Path uploadDir;

    public AttachmentController(AttachmentRepository repository,
                                @Value("${oms.upload-dir}") String uploadDir) {
        super(repository);
        this.attachmentRepository = repository;
        this.uploadDir = Path.of(uploadDir);
    }

    @AuditLogAction(module = "附件", action = "上传附件")
    @PostMapping("/upload")
    public ApiResponse<Attachment> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam String businessType,
                                          @RequestParam(required = false) Long businessId) throws Exception {
        authorize(businessType, businessId, true);
        Files.createDirectories(uploadDir);
        String key = UUID.randomUUID() + "-" + file.getOriginalFilename();
        Path target = uploadDir.resolve(key).normalize();
        if (!target.startsWith(uploadDir.toAbsolutePath().normalize()) && !target.startsWith(uploadDir.normalize())) throw new BusinessException("附件文件名无效");
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        Attachment attachment = new Attachment();
        attachment.businessType = businessType;
        attachment.businessId = businessId;
        attachment.fileKey = key;
        attachment.originalName = file.getOriginalFilename();
        attachment.contentType = file.getContentType();
        attachment.sizeBytes = file.getSize();
        attachment.uploadedBy = CurrentUser.username();
        return ApiResponse.ok(attachmentRepository.save(attachment));
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) throws Exception {
        Attachment attachment = active(id);
        authorize(attachment.businessType, attachment.businessId, false);
        Resource resource = new UrlResource(uploadDir.resolve(attachment.fileKey).toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.originalName + "\"")
                .contentType(MediaType.parseMediaType(attachment.contentType == null ? "application/octet-stream" : attachment.contentType))
                .body(resource);
    }

    @GetMapping("/attachments")
    public ApiResponse<?> attachments() {
        return list();
    }

    @GetMapping("/by-business")
    public ApiResponse<?> byBusiness(@RequestParam String businessType, @RequestParam Long businessId) {
        authorize(businessType, businessId, false);
        return ApiResponse.ok(attachmentRepository.findByBusinessTypeAndBusinessIdAndDeletedFalse(businessType, businessId));
    }
}
