package com.company.oms.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.oms.common.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BusinessLicenseRecognitionService {
    private static final Logger log = LoggerFactory.getLogger(BusinessLicenseRecognitionService.class);
    private static final String ARK_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/responses";
    private static final String MODEL = System.getenv("ARK_MODEL");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder()
            .requestFactory(requestFactory())
            .build();

    public BusinessLicenseInfo recognize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请先上传营业执照图片");
        }
        String apiKey = System.getenv("ARK_API_KEY");
        if (apiKey == null || apiKey.isBlank() || MODEL == null || MODEL.isBlank()) {
            throw new BusinessException("未配置 ARK_API_KEY 或 ARK_MODEL，无法调用营业执照识别");
        }

        try {
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            Map<String, Object> request = Map.of(
                    "model", MODEL,
                    "thinking", Map.of("type", "disabled"),
                    "max_output_tokens", 256,
                    "input", List.of(Map.of(
                            "role", "user",
                            "content", businessLicenseContent(file, filename)
                    ))
            );
            String raw = callArk(apiKey, request);
            BusinessLicenseInfo info = parseResponse(raw);
            log.info("Business license recognition parsed result: customerName={}, legalRepresentative={}, address={}, unifiedSocialCreditCode={}, rawTextPresent={}",
                    info.customerName, info.legalRepresentative, info.address, info.unifiedSocialCreditCode, !isBlank(info.rawText));
            return info;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("营业执照识别失败：" + e.getMessage());
        }
    }

    public InvoiceInfo recognizeInvoiceInfo(InvoiceTextRequest request) {
        if (request == null || isBlank(request.text)) {
            throw new BusinessException("请先输入开票信息");
        }
        long startedAt = System.nanoTime();
        InvoiceInfo local = parseInvoiceInfoLocally(request.text);
        if (hasInvoiceInfo(local)) {
            local.recognitionSource = "LOCAL";
            local.elapsedMs = elapsedMillis(startedAt);
            log.info("Invoice info parsed locally in {} ms: invoiceTitle={}, taxNo={}, publicBankName={}, contactPerson={}",
                    local.elapsedMs, local.invoiceTitle, local.taxNo, local.publicBankName, local.contactPerson);
            return local;
        }

        String apiKey = System.getenv("ARK_API_KEY");
        if (apiKey == null || apiKey.isBlank() || MODEL == null || MODEL.isBlank()) {
            local.rawText = request.text.trim();
            local.recognitionSource = "LOCAL";
            local.elapsedMs = elapsedMillis(startedAt);
            return local;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "thinking", Map.of("type", "disabled"),
                    "max_output_tokens", 256,
                    "input", List.of(Map.of(
                            "role", "user",
                            "content", List.of(Map.of("type", "input_text", "text", """
                                    请从下面这段客户开票信息中抽取字段，只返回 JSON，不要解释。
                                    字段：
                                    invoiceTitle：开票名称/名称
                                    taxNo：纳税人识别号/税号
                                    invoiceAddress：地址
                                    invoicePhone：电话
                                    publicBankName：开户行
                                    publicBankAccount：账号/帐号/银行账号
                                    contactPerson：联系人姓名
                                    contactPhone：联系人手机号
                                    如果某项无法识别，值为空字符串。

                                    原文：
                                    """ + request.text))
                    ))
            );
            InvoiceInfo info = parseInvoiceInfo(callArk(apiKey, body), request.text);
            info.recognitionSource = "ARK_FALLBACK";
            info.elapsedMs = elapsedMillis(startedAt);
            log.info("Invoice info recognition parsed result: invoiceTitle={}, taxNo={}, invoiceAddress={}, invoicePhone={}, publicBankName={}, publicBankAccount={}, contactPerson={}, contactPhone={}",
                    info.invoiceTitle, info.taxNo, info.invoiceAddress, info.invoicePhone, info.publicBankName, info.publicBankAccount, info.contactPerson, info.contactPhone);
            return info;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("开票信息识别失败：" + e.getMessage());
        }
    }

    public InvoiceDocumentInfo recognizeInvoiceDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请先上传发票图片或 PDF 文件");
        }
        String apiKey = System.getenv("ARK_API_KEY");
        if (apiKey == null || apiKey.isBlank() || MODEL == null || MODEL.isBlank()) {
            throw new BusinessException("未配置 ARK_API_KEY，无法调用发票识别");
        }

        long startedAt = System.nanoTime();
        try {
            EncodedDocument encodedDocument = encodeAsImage(file, 150, 0.78f);
            String dataUrl = "data:" + encodedDocument.contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(encodedDocument.bytes);
            Map<String, Object> request = Map.of(
                    "model", MODEL,
                    "thinking", Map.of("type", "disabled"),
                    "max_output_tokens", 128,
                    "input", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "input_image", "image_url", dataUrl),
                                    Map.of("type", "input_text", "text", """
                                            请识别这张中国发票，只返回 JSON，不要解释。
                                            字段值都使用字符串：
                                            invoiceNo：发票号码。优先读取“发票号码”；数电票读取20位发票号码。不要填写发票代码、机器编号、校验码或订单号。
                                            totalAmount：价税合计（小写）的数字金额，不带人民币符号和千分位。不要填写税额或不含税金额。
                                            如果某项无法可靠识别，值为空字符串。
                                            返回格式：{"invoiceNo":"","totalAmount":""}
                                            """)
                            )
                    ))
            );
            InvoiceDocumentInfo info = parseInvoiceDocumentResponse(callArk(apiKey, request));
            info.elapsedMs = elapsedMillis(startedAt);
            log.info("Invoice document recognition completed in {} ms: invoiceNoPresent={}, amountPresent={}",
                    info.elapsedMs, !isBlank(info.invoiceNo), info.amount != null);
            return info;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("发票识别失败：" + e.getMessage());
        }
    }

    private List<Map<String, String>> businessLicenseContent(MultipartFile file, String filename) throws Exception {
        if (isWordFile(file)) {
            String docText = extractWordText(file);
            log.info("Recognizing business license word file={}, chars={}", filename, docText.length());
            return List.of(Map.of("type", "input_text", "text", """
                    请从下面这个营业执照/客户资料 Word 文档文本中识别信息，只返回 JSON，不要解释。
                    字段：
                    customerName：客户名称/公司名称
                    legalRepresentative：法人名称/法定代表人
                    address：住所/地址
                    unifiedSocialCreditCode：统一社会信用代码
                    如果某项无法识别，值为空字符串。

                    文档文本：
                    """ + docText));
        }

        EncodedDocument encodedDocument = encodeAsImage(file);
        log.info("Recognizing business license file={}, contentType={}, encodedType={}, bytes={}",
                filename, file.getContentType(), encodedDocument.contentType, encodedDocument.bytes.length);
        String dataUrl = "data:" + encodedDocument.contentType + ";base64," + Base64.getEncoder().encodeToString(encodedDocument.bytes);
        return List.of(
                Map.of("type", "input_image", "image_url", dataUrl),
                Map.of("type", "input_text", "text", """
                                            请识别这张中国营业执照图片，只返回 JSON，不要解释。
                                            字段：
                                            customerName：客户名称/公司名称
                                            legalRepresentative：法人名称/法定代表人
                                            address：住所/地址
                                            unifiedSocialCreditCode：统一社会信用代码
                                            如果某项无法识别，值为空字符串。
                                            """)
        );
    }

    private String callArk(String apiKey, Map<String, Object> request) {
        byte[] response = restClient.post()
                .uri(ARK_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(byte[].class);
        return response == null ? "" : new String(response, StandardCharsets.UTF_8);
    }

    private BusinessLicenseInfo parseResponse(String raw) {
        String text = extractOutputText(raw == null ? "" : raw);
        String normalized = normalizeJsonText(text);
        BusinessLicenseInfo info = new BusinessLicenseInfo();
        info.customerName = firstNonBlank(
                jsonValue(normalized, "customerName"),
                jsonValue(normalized, "客户名称"),
                jsonValue(normalized, "公司名称"),
                labeledValue(text, "客户名称"),
                labeledValue(text, "公司名称"),
                labeledValue(text, "名称")
        );
        info.legalRepresentative = firstNonBlank(
                jsonValue(normalized, "legalRepresentative"),
                jsonValue(normalized, "法人名称"),
                jsonValue(normalized, "法定代表人"),
                labeledValue(text, "法人名称"),
                labeledValue(text, "法定代表人")
        );
        info.address = firstNonBlank(
                jsonValue(normalized, "address"),
                jsonValue(normalized, "地址"),
                jsonValue(normalized, "住所"),
                labeledValue(text, "地址"),
                labeledValue(text, "住所")
        );
        info.unifiedSocialCreditCode = firstNonBlank(
                jsonValue(normalized, "unifiedSocialCreditCode"),
                jsonValue(normalized, "统一社会信用代码"),
                labeledValue(text, "统一社会信用代码"),
                creditCodeValue(text)
        );
        if (isBlank(info.customerName) && isBlank(info.legalRepresentative) && isBlank(info.address) && isBlank(info.unifiedSocialCreditCode)) {
            info.rawText = text.isBlank() ? raw : text;
        }
        return info;
    }

    private InvoiceInfo parseInvoiceInfo(String raw, String fallbackText) {
        String text = extractOutputText(raw == null ? "" : raw);
        String source = text.isBlank() ? fallbackText : text;
        String normalized = normalizeJsonText(source);
        InvoiceInfo info = new InvoiceInfo();
        info.invoiceTitle = firstNonBlank(
                jsonValue(normalized, "invoiceTitle"),
                jsonValue(normalized, "名称"),
                jsonValue(normalized, "开票名称"),
                labeledValue(source, "名称"),
                labeledValue(source, "开票名称")
        );
        info.taxNo = firstNonBlank(
                jsonValue(normalized, "taxNo"),
                jsonValue(normalized, "纳税人识别号"),
                jsonValue(normalized, "税号"),
                labeledValue(source, "纳税人识别号"),
                labeledValue(source, "税号"),
                creditCodeValue(source)
        );
        info.invoiceAddress = firstNonBlank(
                jsonValue(normalized, "invoiceAddress"),
                jsonValue(normalized, "地址"),
                labeledValue(source, "地址")
        );
        info.invoicePhone = firstNonBlank(
                jsonValue(normalized, "invoicePhone"),
                jsonValue(normalized, "电话"),
                labeledValue(source, "电话")
        );
        info.publicBankName = firstNonBlank(
                jsonValue(normalized, "publicBankName"),
                jsonValue(normalized, "开户行"),
                labeledValue(source, "开户行")
        );
        info.publicBankAccount = firstNonBlank(
                jsonValue(normalized, "publicBankAccount"),
                jsonValue(normalized, "账号"),
                jsonValue(normalized, "帐号"),
                labeledValue(source, "账号"),
                labeledValue(source, "帐号")
        );
        info.contactPerson = firstNonBlank(
                jsonValue(normalized, "contactPerson"),
                jsonValue(normalized, "联系人"),
                trailingContact(source)
        );
        info.contactPhone = firstNonBlank(
                jsonValue(normalized, "contactPhone"),
                jsonValue(normalized, "联系人手机号"),
                mobileValue(source)
        );
        if (isBlank(info.invoiceTitle) && isBlank(info.taxNo) && isBlank(info.invoiceAddress) && isBlank(info.invoicePhone)
                && isBlank(info.publicBankName) && isBlank(info.publicBankAccount) && isBlank(info.contactPerson) && isBlank(info.contactPhone)) {
            info.rawText = source;
        }
        return info;
    }

    InvoiceDocumentInfo parseInvoiceDocumentResponse(String raw) {
        String text = extractJsonResponseText(raw == null ? "" : raw);
        if (text.isBlank()) text = extractOutputText(raw == null ? "" : raw);
        String source = text.isBlank() ? raw : text;
        String normalized = normalizeJsonText(source);
        InvoiceDocumentInfo info = new InvoiceDocumentInfo();
        try {
            JsonNode root = objectMapper.readTree(normalized);
            info.invoiceNo = normalizeInvoiceNo(firstNonBlank(
                    jsonNodeText(root, "invoiceNo"),
                    jsonNodeText(root, "发票号码"),
                    jsonNodeText(root, "发票号")));
            info.amount = parseMoney(firstNonBlank(
                    jsonNodeText(root, "totalAmount"),
                    jsonNodeText(root, "价税合计"),
                    jsonNodeText(root, "amount")));
        } catch (Exception ignored) {
            info.invoiceNo = normalizeInvoiceNo(firstNonBlank(
                    jsonValue(normalized, "invoiceNo"),
                    jsonValue(normalized, "发票号码"),
                    labeledValue(source, "发票号码"),
                    labeledValue(source, "发票号")));
            info.amount = parseMoney(firstNonBlank(
                    jsonValue(normalized, "totalAmount"),
                    jsonValue(normalized, "价税合计"),
                    labeledValue(source, "价税合计（小写）"),
                    labeledValue(source, "价税合计")));
        }
        if (isBlank(info.invoiceNo) && info.amount == null) {
            info.rawText = source == null ? "" : source;
        }
        return info;
    }

    private String extractJsonResponseText(String raw) {
        if (isBlank(raw)) return "";
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<String> values = new ArrayList<>();
            collectJsonText(root, "output_text", values);
            if (values.isEmpty()) collectJsonText(root, "text", values);
            return String.join("\n", values);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void collectJsonText(JsonNode node, String fieldName, List<String> values) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (fieldName.equals(entry.getKey()) && entry.getValue().isTextual()) {
                    values.add(entry.getValue().asText());
                } else {
                    collectJsonText(entry.getValue(), fieldName, values);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectJsonText(item, fieldName, values));
        }
    }

    private String jsonNodeText(JsonNode root, String key) {
        if (root == null || !root.has(key) || root.get(key).isNull()) return "";
        return root.get(key).asText("").trim();
    }

    private String normalizeInvoiceNo(String value) {
        return value == null ? "" : cleanupValue(value).replaceAll("\\s+", "");
    }

    private BigDecimal parseMoney(String value) {
        if (isBlank(value)) return null;
        String normalized = value.replace(",", "")
                .replace("￥", "")
                .replace("¥", "")
                .replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank() || normalized.equals("-") || normalized.chars().filter(ch -> ch == '.').count() > 1) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(normalized);
            return amount.compareTo(BigDecimal.ZERO) > 0 ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    InvoiceInfo parseInvoiceInfoLocally(String input) {
        String source = normalizeBusinessText(input);
        InvoiceInfo info = new InvoiceInfo();
        info.invoiceTitle = firstNonBlank(
                labeledBusinessValue(source, "开票名称", "开票抬头", "公司名称", "客户名称", "单位名称", "账户名称", "名称"),
                companyNameValue(source)
        );
        info.taxNo = firstNonBlank(
                labeledBusinessValue(source, "统一社会信用代码", "纳税人识别号", "纳税人识别码", "信用代码", "税号"),
                creditCodeValue(source)
        ).replaceAll("\\s+", "").toUpperCase();

        String addressAndPhone = labeledBusinessValue(source, "地址、电话", "地址及电话", "地址电话");
        info.invoiceAddress = firstNonBlank(
                addressFromCombinedValue(addressAndPhone),
                labeledBusinessValue(source, "开票地址", "注册地址", "经营场所", "住所", "地址")
        );
        info.invoicePhone = firstNonBlank(
                phoneValue(addressAndPhone),
                phoneValue(labeledBusinessValue(source, "开票电话", "单位电话", "电话"))
        );

        String bankAndAccount = labeledBusinessValue(source, "开户行及账号", "开户行和账号", "开户银行及账号", "开户银行和账号");
        info.publicBankName = firstNonBlank(
                bankNameFromCombinedValue(bankAndAccount),
                labeledBusinessValue(source, "对公开户行", "开户银行", "开户行", "银行名称")
        );
        info.publicBankAccount = firstNonBlank(
                bankAccountValue(bankAndAccount),
                bankAccountValue(labeledBusinessValue(source, "对公账号", "银行账号", "银行账号", "账号", "账号"))
        );
        info.contactPerson = labeledBusinessValue(source, "联系人姓名", "联系人");
        String labeledContactPhone = labeledBusinessValue(source, "联系电话", "联系人电话", "手机号码", "手机号", "手机");
        info.contactPhone = firstNonBlank(phoneValue(labeledContactPhone), mobileValue(source));
        if (isBlank(info.contactPerson)) {
            info.contactPerson = trailingContact(source);
        }
        return info;
    }

    private boolean hasInvoiceInfo(InvoiceInfo info) {
        return !isBlank(info.invoiceTitle) || !isBlank(info.taxNo) || !isBlank(info.invoiceAddress)
                || !isBlank(info.invoicePhone) || !isBlank(info.publicBankName)
                || !isBlank(info.publicBankAccount) || !isBlank(info.contactPerson)
                || !isBlank(info.contactPhone);
    }

    private String normalizeBusinessText(String input) {
        if (input == null) return "";
        return input.replace('\u3000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private String labeledBusinessValue(String source, String... labels) {
        if (isBlank(source)) return "";
        Set<String> uniqueLabels = new LinkedHashSet<>(List.of(labels));
        String labelPattern = uniqueLabels.stream()
                .map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String allLabels = "开票名称|开票抬头|公司名称|客户名称|单位名称|账户名称|名称|"
                + "统一社会信用代码|纳税人识别号|纳税人识别码|信用代码|税号|"
                + "地址、电话|地址及电话|地址电话|开票地址|注册地址|经营场所|住所|地址|开票电话|单位电话|电话|"
                + "开户行及账号|开户行和账号|开户银行及账号|开户银行和账号|对公开户行|开户银行|开户行|银行名称|对公账号|银行账号|银行账号|账号|账号|"
                + "联系人姓名|联系人|联系电话|联系人电话|手机号码|手机号|手机";
        Pattern pattern = Pattern.compile("(?is)(?:^|[\\n,，;；])\\s*(?:" + labelPattern + ")\\s*[:：]?\\s*(.+?)(?=\\s*(?:\\n|[,，;；]\\s*(?:" + allLabels + ")\\s*[:：]|$))");
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) return cleanupBusinessValue(matcher.group(1));

        pattern = Pattern.compile("(?is)(?:" + labelPattern + ")\\s*[:：]\\s*(.+?)(?=\\s*(?:(?:" + allLabels + ")\\s*[:：]|\\n|$))");
        matcher = pattern.matcher(source);
        return matcher.find() ? cleanupBusinessValue(matcher.group(1)) : "";
    }

    private String companyNameValue(String source) {
        Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9（）()]{3,80}(?:股份有限公司|有限责任公司|有限公司|集团公司|合作社))").matcher(source);
        return matcher.find() ? cleanupBusinessValue(matcher.group(1)) : "";
    }

    private String addressFromCombinedValue(String value) {
        if (isBlank(value)) return "";
        String phone = phoneValue(value);
        return cleanupBusinessValue(phone.isBlank() ? value : value.replace(phone, ""));
    }

    private String bankNameFromCombinedValue(String value) {
        if (isBlank(value)) return "";
        Matcher account = Pattern.compile("(?<![0-9A-Z])(?:\\d[\\d \\-]{7,30}\\d)(?![0-9A-Z])").matcher(value);
        return cleanupBusinessValue(account.find() ? value.substring(0, account.start()) : value);
    }

    private String bankAccountValue(String value) {
        if (isBlank(value)) return "";
        Matcher matcher = Pattern.compile("(?<![0-9A-Z])(?:\\d[\\d \\-]{7,30}\\d)(?![0-9A-Z])").matcher(value);
        return matcher.find() ? matcher.group().replaceAll("[ \\-]", "") : "";
    }

    private String phoneValue(String value) {
        if (isBlank(value)) return "";
        Matcher mobile = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)").matcher(value);
        if (mobile.find()) return mobile.group();
        Matcher landline = Pattern.compile("(?<!\\d)(?:0\\d{2,3}[- ]?)?\\d{7,8}(?:[- ]\\d{1,6})?(?!\\d)").matcher(value);
        return landline.find() ? landline.group().replace(" ", "") : "";
    }

    private String cleanupBusinessValue(String value) {
        if (value == null) return "";
        return cleanupValue(value)
                .replaceAll("^[：:、,，;；\\s]+", "")
                .replaceAll("[：:、,，;；\\s]+$", "")
                .trim();
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private String extractOutputText(String raw) {
        Matcher outputText = Pattern.compile("\"output_text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(raw);
        StringBuilder builder = new StringBuilder();
        while (outputText.find()) {
            if (!builder.isEmpty()) builder.append('\n');
            builder.append(unescapeJson(outputText.group(1)));
        }
        if (!builder.isEmpty()) return builder.toString();

        Matcher text = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(raw);
        while (text.find()) {
            if (!builder.isEmpty()) builder.append('\n');
            builder.append(unescapeJson(text.group(1)));
        }
        return builder.toString();
    }

    private String jsonValue(String text, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(text);
        return matcher.find() ? unescapeJson(matcher.group(1)).trim() : "";
    }

    private String normalizeJsonText(String text) {
        if (text == null) return "";
        String normalized = text.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            normalized = normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private String labeledValue(String text, String label) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "\\s*[：:]\\s*([^\\n\\r,，}]+)").matcher(text);
        return matcher.find() ? cleanupValue(matcher.group(1)) : "";
    }

    private String creditCodeValue(String text) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile("[0-9A-Z]{18}").matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private String mobileValue(String text) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile("1[3-9]\\d{9}").matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private String trailingContact(String text) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile("1[3-9]\\d{9}\\s*([\\u4e00-\\u9fa5]{2,5})").matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return cleanupValue(value);
        }
        return "";
    }

    private String cleanupValue(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("^[\"'“”]+", "")
                .replaceAll("[\"'“”，,。；;]+$", "")
                .trim();
    }

    private String unescapeJson(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isWordFile(MultipartFile file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        return filename.endsWith(".docx") || filename.endsWith(".doc")
                || contentType.contains("wordprocessingml")
                || contentType.equals("application/msword");
    }

    private String extractWordText(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        byte[] bytes = file.getBytes();
        if (filename.endsWith(".doc")) {
            try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private EncodedDocument encodeAsImage(MultipartFile file) throws Exception {
        return encodeAsImage(file, 85, 0.65f);
    }

    private EncodedDocument encodeAsImage(MultipartFile file, int pdfDpi, float jpegQuality) throws Exception {
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        byte[] bytes = file.getBytes();
        if (MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(contentType) || filename.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(bytes); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (document.getNumberOfPages() == 0) {
                    throw new BusinessException("PDF 文件没有可识别页面");
                }
                PDFRenderer renderer = new PDFRenderer(document);
                BufferedImage image = renderer.renderImageWithDPI(0, pdfDpi, ImageType.RGB);
                writeJpeg(image, out, jpegQuality);
                return new EncodedDocument(MediaType.IMAGE_JPEG_VALUE, out.toByteArray());
            }
        }
        if (contentType == null || contentType.isBlank() || !contentType.toLowerCase().startsWith("image/")) {
            contentType = guessImageContentType(filename);
        }
        if (!contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException("仅支持图片或 PDF 格式文件");
        }
        return new EncodedDocument(contentType, bytes);
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return factory;
    }

    private void writeJpeg(BufferedImage image, ByteArrayOutputStream out, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", out);
            return;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private String guessImageContentType(String filename) {
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (filename.endsWith(".webp")) return "image/webp";
        if (filename.endsWith(".bmp")) return "image/bmp";
        if (filename.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private record EncodedDocument(String contentType, byte[] bytes) {}

    public static class BusinessLicenseInfo {
        public String customerName = "";
        public String legalRepresentative = "";
        public String address = "";
        public String unifiedSocialCreditCode = "";
        public String rawText = "";
    }

    public static class InvoiceTextRequest {
        public String text;
    }

    public static class InvoiceInfo {
        public String invoiceTitle = "";
        public String taxNo = "";
        public String invoiceAddress = "";
        public String invoicePhone = "";
        public String publicBankName = "";
        public String publicBankAccount = "";
        public String contactPerson = "";
        public String contactPhone = "";
        public String rawText = "";
        public String recognitionSource = "";
        public long elapsedMs;
    }

    public static class InvoiceDocumentInfo {
        public String invoiceNo = "";
        public BigDecimal amount;
        public String rawText = "";
        public long elapsedMs;
    }
}
