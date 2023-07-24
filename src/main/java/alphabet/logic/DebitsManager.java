package alphabet.logic;

import dal.TransactionRepository;
import enums.DbTransactionStatus;
import external.api.TransactionDirection;
import logger.BillingSystemLogger;
import models.db.BillingTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Service
@EnableScheduling
public class DebitsManager {

    private final TransactionRepository transactionRepository;
    private final RepaymentPlanCreator repaymentPlanCreator;
    private final BillingSystemLogger logger;

    @Autowired
    public DebitsManager(TransactionRepository transactionRepository,
                         RepaymentPlanCreator repaymentPlanCreator,
                         BillingSystemLogger logger) {
        this.transactionRepository = transactionRepository;
        this.repaymentPlanCreator = repaymentPlanCreator;
        this.logger = logger;
    }

    @Scheduled(fixedRateString = "${handle.debits.scheduling.in.ms}")
    public void handleDebits() {
        logger.log(Level.FINE, "First, going to check which transactions have failed and move them to " +
                "week before last payment. Then, going to change the status of each debit that needs to be " +
                "performed to WAITING_TO_BE_SENT. ");

        try {
            tryHandlingDebits();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unknown exception has occurred while trying to handle the debits" +
                    e.getMessage());
        }
    }

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    private void tryHandlingDebits() {
        List<BillingTransaction> newRepaymentsPlansTransactions =
                repaymentPlanCreator.createRepaymentPlansTransactionsForAdvancesWithoutOne();
        saveNewRepaymentPlans(newRepaymentsPlansTransactions);

        handleSuccessfulAdvancesDebits(fetchSuccessfulAdvances());
    }

    private void saveNewRepaymentPlans(List<BillingTransaction> repaymentsPlansTransactions) {
        if (!repaymentsPlansTransactions.isEmpty()) {
            transactionRepository.saveAll(repaymentsPlansTransactions);
            logger.log(Level.INFO, "Successfully saved all repayment plans in the database");
        } else {
            logger.log(Level.FINE, "There aren't new repayment plans to be saved in the database");
        }
    }

    private void handleSuccessfulAdvancesDebits(List<BillingTransaction> successfulAdvances) {
        for (BillingTransaction advance : successfulAdvances) {
            handleAdvanceDebits(advance);
        }
    }

    private List<BillingTransaction> fetchSuccessfulAdvances() {
        return transactionRepository.findByTransactionStatusAndTransactionDirection(DbTransactionStatus.SUCCESS,
                TransactionDirection.CREDIT);
    }

    private void handleAdvanceDebits(BillingTransaction advance) {
        List<BillingTransaction> advanceDebitsSortedByTransactionTime = fetchAdvanceDebits(advance);

        moveAdvanceFailedDebitsToWeekFromLastPayment(advanceDebitsSortedByTransactionTime);
        updateAdvanceDebitsToPerformStatusToWaitingToBeSent(advanceDebitsSortedByTransactionTime);
    }

    private List<BillingTransaction> fetchAdvanceDebits(BillingTransaction advance) {
        String bankAccount = advance.getDstBankAccount();

        return transactionRepository.findDebitTransactionsByDstBankAccountOrderByTransactionTime(bankAccount);
    }

    private void moveAdvanceFailedDebitsToWeekFromLastPayment(List<BillingTransaction>
                                                                      allAdvanceDebitsSortedByTransactionTime) {
        Instant weekFromLastPaymentDate = computeWeekFromLastPaymentDate(allAdvanceDebitsSortedByTransactionTime);
        List<BillingTransaction> failedDebits = extractFailedDebits(allAdvanceDebitsSortedByTransactionTime);

        failedDebits.forEach(debit -> debit.setTransactionTime(weekFromLastPaymentDate));

        if (!failedDebits.isEmpty()) {
            transactionRepository.saveAll(failedDebits);
            logDebitsThatWereMoved(failedDebits);
        }
    }

    private void logDebitsThatWereMoved(List<BillingTransaction> debits) {
        for (BillingTransaction debit : debits) {
            logger.log(Level.INFO, "The debit transaction of " + debit.getAmount() + "to bank account "
                    + debit.getDstBankAccount() + " failed.  Consequently, it has been rescheduled to one week " +
                    "after the last debit payment, which is on " + debit.getTransactionTime());
        }
    }

    private void updateAdvanceDebitsToPerformStatusToWaitingToBeSent(List<BillingTransaction> allAdvanceDebits) {
        List<BillingTransaction> debitsToPerform = extractDebitsToPerform(allAdvanceDebits);
        debitsToPerform.forEach(this::updateDebitStatusToWaitingToBeSent);

        transactionRepository.saveAll(debitsToPerform);
        logDebitsThatNeedToBePerformed(debitsToPerform);
    }

    private void logDebitsThatNeedToBePerformed(List<BillingTransaction> debits) {
        for (BillingTransaction debit : debits) {
            logger.log(Level.INFO, "The debit transaction for bank account " + debit.getDstBankAccount() +
                    " amounting to " + debit.getAmount() + " needs to be performed, its transaction status has " +
                    "been updated to WAITING_TO_BE_SENT");
        }
    }

    private Instant computeWeekFromLastPaymentDate(List<BillingTransaction> advancesDebitsSortedByTransactionTime) {
        int lastDebitIndex = advancesDebitsSortedByTransactionTime.size() - 1;
        BillingTransaction lastDebit = advancesDebitsSortedByTransactionTime.get(lastDebitIndex);
        Instant lastPaymentDate = lastDebit.getTransactionTime();

        return lastPaymentDate.minus(1, ChronoUnit.WEEKS);
    }

    private void updateDebitStatusToWaitingToBeSent(BillingTransaction debit) {
        debit.setTransactionStatus(DbTransactionStatus.WAITING_TO_BE_SENT);
    }

    private List<BillingTransaction> extractFailedDebits(List<BillingTransaction> debits) {
        return debits.stream()
                .filter(this::isDebitStatusFailure)
                .collect(Collectors.toList());
    }

    private boolean isDebitStatusFailure(BillingTransaction debit) {
        DbTransactionStatus debitStatus = debit.getTransactionStatus();

        return debitStatus.equals(DbTransactionStatus.FAILURE);
    }

    private List<BillingTransaction> extractDebitsToPerform(List<BillingTransaction> advanceDebits) {
        return advanceDebits.stream()
                .filter(this::isDebitTransactionTimePassed)
                .collect(Collectors.toList());
    }

    private boolean isDebitTransactionTimePassed(BillingTransaction billingTransaction) {
        Instant debitTransactionTime = billingTransaction.getTransactionTime();
        Instant currentTime = Instant.now();

        return debitTransactionTime.isAfter(currentTime);
    }
}
