package com.company.oms.masterdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessLicenseRecognitionServiceTest {
    private final BusinessLicenseRecognitionService service = new BusinessLicenseRecognitionService();

    @Test
    void parsesStructuredInvoiceTextWithoutCallingModel() {
        BusinessLicenseRecognitionService.InvoiceTextRequest request = request("""
                开票名称：南京测试包装制品有限公司
                统一社会信用代码：91320115MA1234567X
                开票地址：江苏省南京市江宁区秣陵街88号
                开票电话：025-87654321
                开户行：中国工商银行南京江宁支行
                银行账号：1029384756102938475
                联系人：张三
                联系电话：13812345678
                """);

        BusinessLicenseRecognitionService.InvoiceInfo info = service.recognizeInvoiceInfo(request);

        assertEquals("南京测试包装制品有限公司", info.invoiceTitle);
        assertEquals("91320115MA1234567X", info.taxNo);
        assertEquals("江苏省南京市江宁区秣陵街88号", info.invoiceAddress);
        assertEquals("025-87654321", info.invoicePhone);
        assertEquals("中国工商银行南京江宁支行", info.publicBankName);
        assertEquals("1029384756102938475", info.publicBankAccount);
        assertEquals("张三", info.contactPerson);
        assertEquals("13812345678", info.contactPhone);
        assertEquals("LOCAL", info.recognitionSource);
        assertTrue(info.elapsedMs < 1000);
    }

    @Test
    void splitsCombinedAddressPhoneAndBankAccountLines() {
        BusinessLicenseRecognitionService.InvoiceInfo info = service.parseInvoiceInfoLocally("""
                名称：江苏测试科技有限公司
                纳税人识别号：91320000MA7654321Q
                地址、电话：南京市雨花台区软件大道1号 025-88886666
                开户行及账号：中国建设银行南京分行  3200 1234 5678 9012 345
                """);

        assertEquals("江苏测试科技有限公司", info.invoiceTitle);
        assertEquals("91320000MA7654321Q", info.taxNo);
        assertEquals("南京市雨花台区软件大道1号", info.invoiceAddress);
        assertEquals("025-88886666", info.invoicePhone);
        assertEquals("中国建设银行南京分行", info.publicBankName);
        assertEquals("3200123456789012345", info.publicBankAccount);
    }

    @Test
    void parsesInlineFieldsAndCompanyNameWithoutExplicitNameLabel() {
        BusinessLicenseRecognitionService.InvoiceInfo inline = service.parseInvoiceInfoLocally(
                "公司名称：上海测试实业有限公司，税号：91310100MA12345678，联系人：李四，手机：13912345678");
        assertEquals("上海测试实业有限公司", inline.invoiceTitle);
        assertEquals("91310100MA12345678", inline.taxNo);
        assertEquals("李四", inline.contactPerson);
        assertEquals("13912345678", inline.contactPhone);

        BusinessLicenseRecognitionService.InvoiceInfo unlabelled = service.parseInvoiceInfoLocally(
                "广州测试包装有限公司 91440101MA9876543Y");
        assertEquals("广州测试包装有限公司", unlabelled.invoiceTitle);
        assertEquals("91440101MA9876543Y", unlabelled.taxNo);
    }

    @Test
    void parsesInvoiceNumberAndTaxInclusiveAmountFromModelResponse() {
        BusinessLicenseRecognitionService.InvoiceDocumentInfo info = service.parseInvoiceDocumentResponse("""
                {"output_text":"{\\"invoiceNo\\":\\" 25322000000012345678 \\",\\"totalAmount\\":\\"￥1,234.567\\"}"}
                """);

        assertEquals("25322000000012345678", info.invoiceNo);
        assertEquals(new BigDecimal("1234.567"), info.amount);
    }

    @Test
    void acceptsNumericInvoiceAmountAndRejectsNonPositiveAmount() {
        BusinessLicenseRecognitionService.InvoiceDocumentInfo numeric = service.parseInvoiceDocumentResponse("""
                {"output_text":"{\\"invoiceNo\\":\\"12345678\\",\\"totalAmount\\":88.5}"}
                """);
        BusinessLicenseRecognitionService.InvoiceDocumentInfo invalid = service.parseInvoiceDocumentResponse("""
                {"output_text":"{\\"invoiceNo\\":\\"\\",\\"totalAmount\\":\\"0\\"}"}
                """);

        assertEquals(new BigDecimal("88.5"), numeric.amount);
        assertEquals(null, invalid.amount);
        assertTrue(!invalid.rawText.isBlank());
    }

    private BusinessLicenseRecognitionService.InvoiceTextRequest request(String text) {
        BusinessLicenseRecognitionService.InvoiceTextRequest request = new BusinessLicenseRecognitionService.InvoiceTextRequest();
        request.text = text;
        return request;
    }
}
