package com.company.oms.importer;

import com.company.oms.finance.CustomerLedgerAccount;
import com.company.oms.finance.CustomerLedgerAccountRepository;
import com.company.oms.finance.CustomerLedgerEntry;
import com.company.oms.finance.CustomerLedgerEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class CustomerLedgerImportService {
    private final ObjectMapper objectMapper;
    private final CustomerLedgerAccountRepository accountRepository;
    private final CustomerLedgerEntryRepository entryRepository;

    public CustomerLedgerImportService(ObjectMapper objectMapper,
                                       CustomerLedgerAccountRepository accountRepository,
                                       CustomerLedgerEntryRepository entryRepository) {
        this.objectMapper = objectMapper;
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public Map<String, Object> importPlan(Path planPath) throws IOException {
        JsonNode root = objectMapper.readTree(planPath.toFile());
        LocalDate cutoff = LocalDate.parse(root.path("invoiceCutoff").asText());
        Set<String> accountKeys = new HashSet<>();
        Set<String> entryKeys = new HashSet<>();
        int accountsCreated = 0;
        int accountsUpdated = 0;
        int entriesCreated = 0;
        int entriesUpdated = 0;

        for (JsonNode accountNode : root.path("accounts")) {
            String accountKey = accountNode.path("sourceKey").asText();
            accountKeys.add(accountKey);
            CustomerLedgerAccount account = accountRepository.findBySourceKey(accountKey).orElse(null);
            if (account == null) {
                account = new CustomerLedgerAccount();
                account.sourceKey = accountKey;
                accountsCreated++;
            } else {
                accountsUpdated++;
            }
            account.deleted = false;
            account.sheetName = accountNode.path("sheetName").asText();
            account.statementAmount = decimal(accountNode, "statementAmount");
            account.currentDebt = decimal(accountNode, "currentDebt");
            account.entryCount = accountNode.path("entryCount").asInt();
            account.openEntryCount = accountNode.path("openEntryCount").asInt();
            account.creditBalance = decimal(accountNode, "creditBalance");
            account.invoiceCutoff = cutoff;
            account.importedAt = LocalDateTime.now();
            account = accountRepository.save(account);

            for (JsonNode entryNode : accountNode.path("entries")) {
                String entryKey = entryNode.path("sourceKey").asText();
                entryKeys.add(entryKey);
                CustomerLedgerEntry entry = entryRepository.findBySourceKey(entryKey).orElse(null);
                if (entry == null) {
                    entry = new CustomerLedgerEntry();
                    entry.sourceKey = entryKey;
                    entriesCreated++;
                } else {
                    entriesUpdated++;
                }
                entry.deleted = false;
                entry.accountId = account.id;
                entry.sourceRow = entryNode.path("sourceRow").asInt();
                entry.transactionDate = nullableDate(entryNode.path("transactionDate").asText(null));
                entry.sourceDate = entryNode.path("sourceDate").asText();
                entry.summary = entryNode.path("summary").asText();
                entry.amount = decimal(entryNode, "amount");
                entry.invoiceNo = entryNode.path("invoiceNo").asText();
                entry.notes = entryNode.path("notes").asText();
                entry.paidAmount = decimal(entryNode, "paidAmount");
                entry.remainingAmount = decimal(entryNode, "remainingAmount");
                entry.lastPaymentDate = nullableDate(entryNode.path("lastPaymentDate").asText(null));
                entryRepository.save(entry);
            }
        }

        int accountsRemoved = 0;
        for (CustomerLedgerAccount account : accountRepository.findAll()) {
            if (!accountKeys.contains(account.sourceKey) && !Boolean.TRUE.equals(account.deleted)) {
                account.deleted = true;
                accountRepository.save(account);
                accountsRemoved++;
            }
        }
        int entriesRemoved = 0;
        for (CustomerLedgerEntry entry : entryRepository.findAll()) {
            if (!entryKeys.contains(entry.sourceKey) && !Boolean.TRUE.equals(entry.deleted)) {
                entry.deleted = true;
                entryRepository.save(entry);
                entriesRemoved++;
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("accountsCreated", accountsCreated);
        counts.put("accountsUpdated", accountsUpdated);
        counts.put("accountsRemoved", accountsRemoved);
        counts.put("entriesCreated", entriesCreated);
        counts.put("entriesUpdated", entriesUpdated);
        counts.put("entriesRemoved", entriesRemoved);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", planPath.toAbsolutePath().toString());
        result.put("completedAt", LocalDateTime.now().toString());
        result.put("invoiceCutoff", cutoff);
        result.put("counts", counts);
        result.put("activeAccounts", accountRepository.countByDeletedFalse());
        result.put("activeEntries", entryRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("sourceSummary", objectMapper.convertValue(root.path("summary"), Map.class));
        return result;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText("0"));
    }

    private LocalDate nullableDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
