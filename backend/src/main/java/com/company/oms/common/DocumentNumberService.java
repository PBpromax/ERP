package com.company.oms.common;

import com.company.oms.finance.Statement;
import com.company.oms.finance.ReceiptRepository;
import com.company.oms.finance.StatementRepository;
import com.company.oms.production.ProductionPlanRepository;
import com.company.oms.purchase.PurchaseRequestRepository;
import com.company.oms.quality.QualityReportRepository;
import com.company.oms.sales.DeliveryNote;
import com.company.oms.sales.DeliveryNoteRepository;
import com.company.oms.sales.Quotation;
import com.company.oms.sales.QuotationRepository;
import com.company.oms.sales.SalesOrder;
import com.company.oms.sales.SalesOrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Predicate;

@Service
public class DocumentNumberService {
    private final QuotationRepository quotationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final StatementRepository statementRepository;
    private final ReceiptRepository receiptRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final QualityReportRepository qualityReportRepository;
    private final ProductionPlanRepository productionPlanRepository;

    public DocumentNumberService(QuotationRepository quotationRepository,
                                 SalesOrderRepository salesOrderRepository,
                                 DeliveryNoteRepository deliveryNoteRepository,
                                 StatementRepository statementRepository,
                                 ReceiptRepository receiptRepository,
                                 PurchaseRequestRepository purchaseRequestRepository,
                                 QualityReportRepository qualityReportRepository,
                                 ProductionPlanRepository productionPlanRepository) {
        this.quotationRepository = quotationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.statementRepository = statementRepository;
        this.receiptRepository = receiptRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.qualityReportRepository = qualityReportRepository;
        this.productionPlanRepository = productionPlanRepository;
    }

    public String nextQuotation(LocalDate date, String errorMessage) {
        String prefix = prefix("A", date);
        int maxSeq = quotationRepository.findByQuoteNoStartingWith(prefix).stream()
                .map(quotation -> parseSeq(prefix, quotation.quoteNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, quotationRepository::existsByQuoteNo, errorMessage);
    }

    public String nextOrder(LocalDate date, String errorMessage) {
        String prefix = prefix("B", date);
        int maxSeq = salesOrderRepository.findByOrderNoStartingWith(prefix).stream()
                .map(order -> parseSeq(prefix, order.orderNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, salesOrderRepository::existsByOrderNo, errorMessage);
    }

    public String nextDelivery(LocalDate date, String errorMessage) {
        String prefix = prefix("C", date);
        int maxSeq = deliveryNoteRepository.findByDeliveryNoStartingWith(prefix).stream()
                .map(delivery -> parseSeq(prefix, delivery.deliveryNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, deliveryNoteRepository::existsByDeliveryNo, errorMessage);
    }

    public String nextStatement(LocalDate date, String errorMessage) {
        String prefix = prefix("D", date);
        int maxSeq = statementRepository.findByStatementNoStartingWith(prefix).stream()
                .map(statement -> parseSeq(prefix, statement.statementNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, statementRepository::existsByStatementNo, errorMessage);
    }

    public String nextReceipt(LocalDate date, String errorMessage) {
        String prefix = prefix("E", date);
        int maxSeq = receiptRepository.findByReceiptNoStartingWith(prefix).stream()
                .map(receipt -> parseSeq(prefix, receipt.receiptNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, receiptRepository::existsByReceiptNo, errorMessage);
    }

    public String nextPurchaseRequest(LocalDate date, String errorMessage) {
        String prefix = prefix("F", date);
        int maxSeq = purchaseRequestRepository.findByRequestNoStartingWith(prefix).stream()
                .map(request -> parseSeq(prefix, request.requestNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, purchaseRequestRepository::existsByRequestNo, errorMessage);
    }

    public String nextQualityReport(LocalDate date, String errorMessage) {
        String prefix = prefix("G", date);
        int maxSeq = qualityReportRepository.findByReportNoStartingWith(prefix).stream()
                .map(report -> parseSeq(prefix, report.reportNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, qualityReportRepository::existsByReportNo, errorMessage);
    }

    public String nextProductionPlan(LocalDate date, String errorMessage) {
        String prefix = prefix("H", date);
        int maxSeq = productionPlanRepository.findByPlanNoStartingWith(prefix).stream()
                .map(plan -> parseSeq(prefix, plan.planNo))
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return next(prefix, maxSeq, productionPlanRepository::existsByPlanNo, errorMessage);
    }

    private String next(String prefix, int maxSeq, Predicate<String> exists, String errorMessage) {
        for (int seq = maxSeq + 1; seq <= 999; seq++) {
            String candidate = prefix + String.format("%03d", seq);
            if (!exists.test(candidate)) return candidate;
        }
        throw new BusinessException(errorMessage);
    }

    public boolean quotationExists(String number) {
        return quotationRepository.existsByQuoteNo(number);
    }

    public boolean quotationExistsExcept(String number, Long quotationId) {
        return quotationRepository.existsByQuoteNoAndIdNot(number, quotationId);
    }

    public boolean orderExists(String number) {
        return salesOrderRepository.existsByOrderNo(number);
    }

    public boolean orderExistsExcept(String number, Long orderId) {
        return salesOrderRepository.existsByOrderNoAndIdNot(number, orderId);
    }

    private String prefix(String typeCode, LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        return "R" + typeCode + targetDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private Integer parseSeq(String prefix, String value) {
        if (value == null || !value.startsWith(prefix)) return null;
        String suffix = value.substring(prefix.length());
        if (!suffix.matches("\\d{3}")) return null;
        return Integer.parseInt(suffix);
    }
}
