/*
 * #%L
 * de.metas.banking.base
 * %%
 * Copyright (C) 2021 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package de.metas.banking.remittanceadvice.process;

import ch.qos.logback.classic.Level;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import de.metas.banking.payment.paymentallocation.PaymentAllocationCriteria;
import de.metas.banking.payment.paymentallocation.PaymentAllocationPayableItem;
import de.metas.banking.payment.paymentallocation.service.AllocationLineCandidate;
import de.metas.banking.payment.paymentallocation.service.PaymentAllocationResult;
import de.metas.banking.payment.paymentallocation.service.PaymentAllocationService;
import de.metas.currency.Amount;
import de.metas.invoice.InvoiceId;
import de.metas.invoice.invoiceProcessingServiceCompany.InvoiceProcessingFeeCalculation;
import de.metas.invoice.service.IInvoiceDAO;
import de.metas.logging.LogManager;
import de.metas.money.MoneyService;
import de.metas.payment.PaymentId;
import de.metas.payment.TenderType;
import de.metas.payment.api.DefaultPaymentBuilder;
import de.metas.payment.api.IPaymentBL;
import de.metas.process.JavaProcess;
import de.metas.remittanceadvice.RemittanceAdvice;
import de.metas.remittanceadvice.RemittanceAdviceLine;
import de.metas.remittanceadvice.RemittanceAdviceLineServiceFee;
import de.metas.remittanceadvice.RemittanceAdviceRepository;
import de.metas.report.jasper.client.RemoteServletInvoker;
import de.metas.tax.api.TaxId;
import de.metas.util.Check;
import de.metas.util.Loggables;
import de.metas.util.Services;
import lombok.NonNull;
import org.adempiere.ad.dao.ConstantQueryFilter;
import org.adempiere.ad.dao.IQueryFilter;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.compiere.SpringContextHolder;
import org.compiere.model.I_C_Invoice;
import org.compiere.model.I_C_InvoiceLine;
import org.compiere.model.I_C_Payment;
import org.compiere.model.I_C_RemittanceAdvice;
import org.compiere.model.X_C_RemittanceAdvice;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;
import org.springframework.util.CollectionUtils;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class C_RemittanceAdvice_CreateAndAllocatePayment extends JavaProcess
{
	private static final Logger logger = LogManager.getLogger(RemoteServletInvoker.class);

	private final RemittanceAdviceRepository remittanceAdviceRepo = SpringContextHolder.instance.getBean(RemittanceAdviceRepository.class);
	private final MoneyService moneyService = SpringContextHolder.instance.getBean(MoneyService.class);

	private final PaymentAllocationService paymentAllocationService = SpringContextHolder.instance.getBean(PaymentAllocationService.class);
	private final IPaymentBL paymentBL = Services.get(IPaymentBL.class);
	private final IInvoiceDAO invoiceDAO = Services.get(IInvoiceDAO.class);
	private final ITrxManager trxManager = Services.get(ITrxManager.class);

	@Override
	protected String doIt() throws Exception
	{

		final IQueryFilter<I_C_RemittanceAdvice> processFilter = getProcessInfo().getQueryFilterOrElse(ConstantQueryFilter.of(false));
		if (processFilter == null)
		{
			throw new AdempiereException("@NoSelection@");
		}
		final List<RemittanceAdvice> remittanceAdvices = remittanceAdviceRepo.getRemittanceAdvices(processFilter);

		for (final RemittanceAdvice remittanceAdvice : remittanceAdvices)
		{
			try{
				trxManager.runInNewTrx(() -> runForRemittanceAdvice(remittanceAdvice));
			}
			catch (final Exception e)
			{
				logger.error("*** ERROR: failed to create and allocate payments for remittance advice id: {} ", remittanceAdvice.getRemittanceAdviceId());
			}
		}

		return MSG_OK;
	}

	@NonNull
	private PaymentAllocationCriteria getPaymentAllocationCriteria(
			@NonNull final RemittanceAdvice remittanceAdvice,
			@NonNull final I_C_Payment payment)
	{
		final Map<InvoiceId, I_C_Invoice> invoiceMapById = getInvoiceMapById(remittanceAdvice.getLines());

		final List<PaymentAllocationPayableItem> paymentAllocationPayableItems = remittanceAdvice.getLines()
				.stream()
				.map(line -> createPaymentAllocationPayableItem(line, remittanceAdvice, invoiceMapById.get(line.getInvoiceId())))
				.collect(Collectors.toList());

		return PaymentAllocationCriteria.builder()
				.payment(payment)
				.dateTrx(remittanceAdvice.getSendDate() != null ? remittanceAdvice.getSendDate() : Instant.now())
				.paymentAllocationPayableItems(paymentAllocationPayableItems)
				.allowPartialAllocations(true)
				.build();
	}

	private void populateRemittanceWithAllocationData(
			@NonNull final Map<InvoiceId, RemittanceAdviceLine> remittanceAdviceLineMap,
			@NonNull final PaymentAllocationResult paymentAllocationResult)
	{
		final Map<Integer, InvoiceId> serviceFeeInvoiceIdsByAssignedInvoiceId =
				paymentAllocationResult
						.getPaymentAllocationIds()
						.values()
						.stream()
						.filter(paymentAllocationResultItem -> AllocationLineCandidate.AllocationLineCandidateType.SalesInvoiceToPurchaseInvoice.equals(paymentAllocationResultItem.getType()))
						.collect(Collectors.toMap(paymentAllocationResultItem -> paymentAllocationResultItem.getPayableDocumentRef().getRecord_ID(),
												  paymentAllocationResultItem -> InvoiceId.ofRepoId(paymentAllocationResultItem.getPaymentDocumentRef().getRecord_ID())));

		paymentAllocationResult.getCandidates()
				.stream()
				.filter(allocationLineCandidate -> AllocationLineCandidate.AllocationLineCandidateType.InvoiceProcessingFee.equals(allocationLineCandidate.getType()))
				.forEach(allocationLineCandidate -> {
					final InvoiceProcessingFeeCalculation invoiceProcessingFeeCalculation = allocationLineCandidate.getInvoiceProcessingFeeCalculation();
					if (invoiceProcessingFeeCalculation != null)
					{
						final RemittanceAdviceLine remittanceAdviceLine = remittanceAdviceLineMap.get(invoiceProcessingFeeCalculation.getInvoiceId());
						if (remittanceAdviceLine != null)
						{
							final InvoiceId serviceFeeInvoiceId = serviceFeeInvoiceIdsByAssignedInvoiceId.get(invoiceProcessingFeeCalculation.getInvoiceId().getRepoId());

							TaxId serviceFeeTaxId = null;
							final List<I_C_InvoiceLine> serviceFeeInvoiceLines = getInvoiceLines(serviceFeeInvoiceId);

							if (!CollectionUtils.isEmpty(serviceFeeInvoiceLines))
							{
								serviceFeeTaxId = TaxId.ofRepoId(serviceFeeInvoiceLines.get(0).getC_Tax_ID());
							}

							final RemittanceAdviceLineServiceFee remittanceAdviceLineServiceFee = RemittanceAdviceLineServiceFee.builder()
									.serviceFeeInvoiceId(serviceFeeInvoiceId)
									.serviceProductId(invoiceProcessingFeeCalculation.getServiceFeeProductId())
									.serviceBPartnerId(invoiceProcessingFeeCalculation.getServiceCompanyBPartnerId())
									.serviceFeeTaxId(serviceFeeTaxId)
									.build();

							remittanceAdviceLine.setServiceFeeDetails(remittanceAdviceLineServiceFee);
						}
					}
				});
	}

	private Map<InvoiceId, RemittanceAdviceLine> getRemittanceAdviceLinesByInvoiceId(final List<RemittanceAdviceLine> remittanceAdviceLines)
	{
		return remittanceAdviceLines
				.stream()
				.filter(remittanceAdviceLine -> remittanceAdviceLine.getInvoiceId() != null)
				.collect(Collectors.toMap(RemittanceAdviceLine::getInvoiceId, remittanceAdviceLine -> remittanceAdviceLine));
	}

	@NonNull
	private Map<InvoiceId, I_C_Invoice> getInvoiceMapById(@NonNull final List<RemittanceAdviceLine> remittanceAdviceLines)
	{
		final ImmutableSet<InvoiceId> invoiceIds = remittanceAdviceLines
				.stream()
				.map(RemittanceAdviceLine::getInvoiceId)
				.filter(Objects::nonNull)
				.collect(ImmutableSet.toImmutableSet());

		return getInvoicesById(invoiceIds);
	}

	@NonNull
	private Map<InvoiceId, I_C_Invoice> getInvoicesById(final Set<InvoiceId> invoiceIds)
	{
		return invoiceDAO.getByIdsInTrx(invoiceIds)
				.stream()
				.collect(Collectors.toMap(invoice -> InvoiceId.ofRepoId(invoice.getC_Invoice_ID()), invoice -> invoice));
	}

	@NonNull
	private I_C_Payment createPayment(@NonNull final RemittanceAdvice remittanceAdvice)
	{
		final DefaultPaymentBuilder paymentBuilder = remittanceAdvice.isSOTrx()
				? paymentBL.newInboundReceiptBuilder()
				: paymentBL.newOutboundPaymentBuilder();

		return paymentBuilder
				.adOrgId(remittanceAdvice.getOrgId())
				.bpartnerId(remittanceAdvice.isSOTrx() ? remittanceAdvice.getSourceBPartnerId() : remittanceAdvice.getDestinationBPartnerId())
				.currencyId(remittanceAdvice.getRemittedAmountCurrencyId())
				.payAmt(remittanceAdvice.getRemittedAmountSum())
				.dateAcct(TimeUtil.asLocalDate(remittanceAdvice.getDocumentDate()))
				.dateTrx(TimeUtil.asLocalDate(remittanceAdvice.getDocumentDate()))
				.tenderType(TenderType.DirectDeposit)
				.createAndProcess();

	}

	@NonNull
	private PaymentAllocationPayableItem createPaymentAllocationPayableItem(
			@NonNull final RemittanceAdviceLine remittanceAdviceLine,
			@NonNull final RemittanceAdvice remittanceAdvice,
			@Nullable final I_C_Invoice invoice)
	{
		if (invoice == null)
		{
			throw new AdempiereException("Missing invoice for remittance line!")
					.appendParametersToMessage()
					.setParameter("RemittanceAdviceLineId", remittanceAdviceLine.getRemittanceAdviceLineId());
		}

		final Amount paymentDiscountAmt = remittanceAdviceLine.getPaymentDiscountAmount() != null
				? remittanceAdviceLine.getPaymentDiscountAmount()
				: Amount.zero(moneyService.getCurrencyCodeByCurrencyId(remittanceAdvice.getRemittedAmountCurrencyId()));

		Check.assumeNotNull(remittanceAdviceLine.getInvoiceAmtInREMADVCurrency(), "Amount cannot be null if the invoice is resolved!");

		return PaymentAllocationPayableItem.builder()
					.payAmt(remittanceAdviceLine.getRemittedAmount())
					.openAmt(remittanceAdviceLine.getInvoiceAmtInREMADVCurrency())
					.serviceFeeAmt(remittanceAdviceLine.getServiceFeeAmount())
					.discountAmt(paymentDiscountAmt)
					.invoiceId(InvoiceId.ofRepoId(invoice.getC_Invoice_ID()))
					.orgId(remittanceAdvice.getOrgId())
					.clientId(remittanceAdvice.getClientId())
					.bPartnerId(remittanceAdvice.getSourceBPartnerId())//todo cif-ps: don't forget to fix it
					.documentNo(invoice.getDocumentNo())
					.isSOTrx(remittanceAdvice.isSOTrx())
					.dateInvoiced(TimeUtil.asLocalDate(invoice.getDateInvoiced()))
					.build();
	}

	@NonNull
	private List<I_C_InvoiceLine> getInvoiceLines(final InvoiceId invoiceId)
	{
		return invoiceDAO.retrieveLines(invoiceId)
				.stream()
				.map(invoiceLine -> InterfaceWrapperHelper.create(invoiceLine, I_C_InvoiceLine.class))
				.collect(ImmutableList.toImmutableList());
	}

	private void runForRemittanceAdvice(@NonNull final RemittanceAdvice remittanceAdvice)
	{
		if (remittanceAdvice.getPaymentId() == null && X_C_RemittanceAdvice.DOCSTATUS_Completed.equals(remittanceAdvice.getDocStatus()))
		{
			final I_C_Payment payment = createPayment(remittanceAdvice);

			final PaymentAllocationCriteria paymentAllocationCriteria = getPaymentAllocationCriteria(remittanceAdvice, payment);

			final PaymentAllocationResult paymentAllocationResult = paymentAllocationService.allocatePayment(paymentAllocationCriteria);

			final Map<InvoiceId, RemittanceAdviceLine> remittanceAdviceLineMap = getRemittanceAdviceLinesByInvoiceId(remittanceAdvice.getLines());
			populateRemittanceWithAllocationData(remittanceAdviceLineMap, paymentAllocationResult);

			remittanceAdvice.setPaymentId(PaymentId.ofRepoId(payment.getC_Payment_ID()));

			remittanceAdviceRepo.updateRemittanceAdvice(remittanceAdvice);
		}
		else
		{
			Loggables.withLogger(logger, Level.INFO).addLog("Skipping Remittance Advice, RemittanceAdviceId={}, DocStatus={}, PaymentId={}",
															remittanceAdvice.getRemittanceAdviceId(), remittanceAdvice.getDocStatus(), remittanceAdvice.getPaymentId());
		}
	}
}
