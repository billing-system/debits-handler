package alphabet.logic;

import dal.TransactionRepository;
import enums.DbTransactionStatus;
import external.api.TransactionDirection;
import logger.BillingSystemLogger;
import models.db.BillingTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@Service
public class RepaymentPlanCreator {

    private final TransactionRepository transactionRepository;
    private final BillingSystemLogger logger;
    private final int numberOfDebits;

    @Autowired
    public RepaymentPlanCreator(TransactionRepository transactionRepository,
                                BillingSystemLogger logger,
                                @Value("${number.of.debits}") int numberOfDebits) {
        this.transactionRepository = transactionRepository;
        this.logger = logger;
        this.numberOfDebits = numberOfDebits;
    }

    public List<BillingTransaction> createRepaymentPlansTransactionsForAdvancesWithoutOne() {
        List<BillingTransaction> advancesWithoutRepaymentPlan =
                transactionRepository.findTransactionsForAdvancesWithoutRepaymentPlan();

        return createRepaymentPlans(advancesWithoutRepaymentPlan);
    }

    private List<BillingTransaction> createRepaymentPlans(List<BillingTransaction> advances) {
        List<BillingTransaction> repayments = new ArrayList<>();

        for (BillingTransaction advance : advances) {
            repayments.addAll(createRepaymentPlanForAdvance(advance));
        }

        return repayments;
    }

    private List<BillingTransaction> createRepaymentPlanForAdvance(BillingTransaction advance) {
        List<BillingTransaction> repaymentsForAdvance = new ArrayList<>();

        for (int i = 1; i <= numberOfDebits; i++) {
            BillingTransaction debitTransaction = createWeeklyDebitTransaction(advance, i);
            repaymentsForAdvance.add(debitTransaction);
        }

        logger.log(Level.INFO, "Created repayment plan for bank account " + advance.getDstBankAccount() +
                " with a total amount of " + advance.getAmount() + ". This plan consists of " + numberOfDebits +
                " payments. Each amounting to " + advance.getAmount() / numberOfDebits);

        return repaymentsForAdvance;
    }

    private BillingTransaction createWeeklyDebitTransaction(BillingTransaction advance, int week) {
        BillingTransaction debitTransaction = new BillingTransaction();

        debitTransaction.setTransactionTime(computeWeeklyDebitTransactionTime(advance, week));
        debitTransaction.setDstBankAccount(advance.getDstBankAccount());
        debitTransaction.setTransactionDirection(TransactionDirection.DEBIT);
        debitTransaction.setTransactionStatus(DbTransactionStatus.ON_HOLD);
        debitTransaction.setAmount(advance.getAmount() / numberOfDebits);

        return debitTransaction;
    }

    private Instant computeWeeklyDebitTransactionTime(BillingTransaction advance, int week) {
        Instant advanceTransactionTime = advance.getTransactionTime();

        return advanceTransactionTime.plus(week, ChronoUnit.WEEKS);
    }
}
