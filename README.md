(1) Periodically runs to retrieve credit transactions with a status of "SUCCESS" from the database.
(2) For each credit transaction:
	(2.1.) If the transaction does not have a repayment plan, it creates one and puts all the plan debits with statuses "ON_HOLD."
	(2.2) Moves any failed debit transaction time to the week before the last payment.
	(2.3) Checks if it's time for a debit based on the repayment plan.
		(2.3.1) If it's time for a debit, it changes the status to "WAITING_TO_BE_SENT" so the "Transaction Performer" service can handle it.