package com.company.oms.sales;

import com.company.oms.common.*;
import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

@RestController
@RequestMapping("/qrcode")
@PreAuthorize("@menuPermissionService.has('quotations','orders','deliveries','statements','invoices')")
public class QrCodeController {
    @GetMapping
    public ApiResponse<String> qrcode(@RequestParam String text) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 220, 220);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return ApiResponse.ok("data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()));
    }
}
