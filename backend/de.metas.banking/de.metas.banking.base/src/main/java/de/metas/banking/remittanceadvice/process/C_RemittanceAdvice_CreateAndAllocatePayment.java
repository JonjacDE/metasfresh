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
import com.google.common.collect.ImmutableSet;
import de.metas.banking.payment.paymentallocation.PaymentAllocationCriteria;
import de.metas.banking.payment.paymentallocation.PaymentAllocationPayableItem;
import de.metas.banking.payment.paymentallocation.service.AllocationLineCandidate;
import de.metas.banking.payment.paymentallocation.service.PaymentAllocationResult;
import de.metas.banking.payment.paymentallocation.service.PaymentAllocationService;
import de.metas.currency.Amount;
import de.metas.currency.CurrencyCode;
import de.metas.invoice.InvoiceId;
import de.metas.invoice.invoiceProcessingServiceCompany.InvoiceProcessingFeeCalculation;
import de.metas.logging.LogManager;
import de.metas.money.CurrencyId;
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
import de.metas.util.Loggables;
import de.metas.util.Services;
import lombok.NonNull;
import org.adempiere.ad.dao.ConstantQueryFilter;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryFilter;
import org.adempiere.exceptions.AdempiereException;
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
	final RemittanceAdviceRepository remittanceAdviceRepo = SpringContextHolder.instance.getBean(RemittanceAdviceRepository.class);
	final MoneyService moneyService = SpringContextHolder.instance.getBean(MoneyService.class);
	final PaymentAllocationService paymentAllocationService = SpringContextHolder.instance.getBean(PaymentAllocationService.class);
	private final IPaymentBL paymentBL = Services.get(IPaymentBL.class);
	private static final Logger logger = LogManager.getLogger(RemoteServletInvoker.class);

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
			final List<RemittanceAdviceLine> remittanceAdviceLines = remittanceAdvice.getLines();

			if (remittanceAdvice.getPaymentId() == null && X_C_RemittanceAdvice.DOCSTATUS_Completed.equals(remittanceAdvice.getDocStatus()))
			{
				final I_C_Payment payment = createPayment(remittanceAdvice);

				final PaymentAllocationCriteria paymentAllocationCriteria = getPaymentAllocationCriteria(remittanceAdvice, remittanceAdviceLines, payment);

				final PaymentAllocationResult paymentAllocationResult = paymentAllocationService.allocatePayment(paymentAllocationCriteria);

				final Map<InvoiceId, RemittanceAdviceLine> remittanceAdviceLineMap = getRemittanceAdviceLinesByInvoiceId(remittanceAdviceLines);
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

		return MSG_OK;
	}

	@NonNull
	private PaymentAllocationCriteria getPaymentAllocationCriteria(final RemittanceAdvice remittanceAdvice, final List<RemittanceAdviceLine> remittanceAdviceLines, final I_C_Payment payment)
	{
		final Map<InvoiceId, I_C_Invoice> invoiceMapById = getInvoiceMapById(remittanceAdviceLines);

		final List<PaymentAllocationPayableItem> paymentAllocationPayableItems = remittanceAdviceLines.stream()
				.map(item -> createPaymentAllocationPayableItem(item, remittanceAdvice, invoiceMapById.get(item.getInvoiceId())))
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

		return PaymentAllocationCriteria.builder()
				.payment(payment)
				.dateTrx(remittanceAdvice.getSendDate() != null ? remittanceAdvice.getSendDate() : Instant.now())
				.paymentAllocationPayableItems(paymentAllocationPayableItems)
				.allowPartialAllocations(true)
				.build();
	}

	private void populateRemittanceWithAllocationData(final Map<InvoiceId, RemittanceAdviceLine> remittanceAdviceLineMap, final PaymentAllocationResult paymentAllocationResult)
	{
		final Map<Integer, InvoiceId> serviceFeeInvoiceIdsByAssignedInvoiceId =
				paymentAllocationResult
						.getPaymentAllocationIds()
						.values()
						.stream()
						.filter(paymentAllocationResultItem -> paymentAllocationResultItem.getType() == AllocationLineCandidate.AllocationLineCandidateType.SalesInvoiceToPurchaseInvoice)
						.collect(Collectors.toMap(paymentAllocationResultItem -> paymentAllocationResultItem.getPayableDocumentRef().getRecord_ID(),
												  paymentAllocationResultItem -> InvoiceId.ofRepoId(paymentAllocationResultItem.getPaymentDocumentRef().getRecord_ID())));

		paymentAllocationResult.getCandidates()
				.stream()
				.filter(allocationLineCandidate -> allocationLineCandidate.getType() == AllocationLineCandidate.AllocationLineCandidateType.InvoiceProcessingFee)
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
	private Map<InvoiceId, I_C_Invoice> getInvoiceMapById(final List<RemittanceAdviceLine> remittanceAdviceLines)
	{
		final ImmutableSet<InvoiceId> invoiceIds = remittanceAdviceLines
				.stream()
				.map(RemittanceAdviceLine::getInvoiceId)
				.filter(Objects::nonNull)
				.collect(ImmutableSet.toImmutableSet());

		return getInvoicesById(invoiceIds);
	}

	private Map<InvoiceId, I_C_Invoice> getInvoicesById(final Set<InvoiceId> invoiceIds)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(de.metas.adempiere.model.I_C_Invoice.class)
				.addInArrayFilter(I_C_Invoice.COLUMNNAME_C_Invoice_ID, invoiceIds)
				.create()
				.list()
				.stream()
				.collect(Collectors.toMap(invoice -> InvoiceId.ofRepoId(invoice.getC_Invoice_ID()), invoice -> invoice));
	}

	private I_C_Payment createPayment(final RemittanceAdvice remittanceAdvice)
	{
		final DefaultPaymentBuilder paymentBuilder = remittanceAdvice.isSOTrx() ? paymentBL.newInboundReceiptBuilder() : paymentBL.newOutboundPaymentBuilder();

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

	@Nullable
	private PaymentAllocationPayableItem createPaymentAllocationPayableItem(final RemittanceAdviceLine remittanceAdviceLine,
			final RemittanceAdvice remittanceAdvice, final I_C_Invoice invoice)
	{
		final Amount paymentDiscountAmt = remittanceAdviceLine.getPaymentDiscountAmount() != null ?
				remittanceAdviceLine.getPaymentDiscountAmount() : Amount.zero(getCurrencyCode(remittanceAdvice.getRemittedAmountCurrencyId().getRepoId()));

		PaymentAllocationPayableItem paymentAllocationPayableItem = null;
		if(remittanceAdviceLine.getInvoiceAmtInREMADVCurrency() != null){
			paymentAllocationPayableItem = PaymentAllocationPayableItem.builder()
					.payAmt(remittanceAdviceLine.getRemittedAmount())
					.openAmt(Amount.of(remittanceAdviceLine.getInvoiceAmtInREMADVCurrency(), remittanceAdviceLine.getRemittedAmount().getCurrencyCode()))
					.serviceFeeAmt(remittanceAdviceLine.getServiceFeeAmount())
					.discountAmt(paymentDiscountAmt)
					.invoiceId(remittanceAdviceLine.getInvoiceId() != null ? remittanceAdviceLine.getInvoiceId() : InvoiceId.ofRepoId(invoice.getC_Invoice_ID()))
					.orgId(remittanceAdvice.getOrgId())
					.clientId(remittanceAdvice.getClientId())
					.bPartnerId(remittanceAdvice.getSourceBPartnerId())
					.documentNo(remittanceAdvice.getDocumentNumber())
					.isSOTrx(remittanceAdvice.isSOTrx())
					.dateInvoiced(TimeUtil.asLocalDate(invoice.getDateInvoiced()))
					.build();
		}
		return paymentAllocationPayableItem;
	}

	private CurrencyCode getCurrencyCode(final int currencyId)
	{
		return moneyService.getCurrencyCodeByCurrencyId(CurrencyId.ofRepoId(currencyId));
	}

	private List<I_C_InvoiceLine> getInvoiceLines(final InvoiceId invoiceId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_C_InvoiceLine.class)
				.addInArrayFilter(I_C_InvoiceLine.COLUMNNAME_C_Invoice_ID, invoiceId)
				.create()
				.list();
	}
}
