/*
 * #%L
 * de.metas.business
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

package de.metas.remittanceadvice;

import de.metas.bpartner.BPartnerBankAccountId;
import de.metas.bpartner.BPartnerId;
import de.metas.document.DocTypeId;
import de.metas.money.CurrencyId;
import de.metas.organization.OrgId;
import lombok.Builder;
import lombok.Value;
import org.adempiere.service.ClientId;

import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class CreateRemittanceAdviceRequest
{
	OrgId orgId;

	ClientId clientId;

	BPartnerId sourceBPartnerId;

	BPartnerBankAccountId sourceBPartnerBankAccountId;

	BPartnerId destinationBPartnerId;

	BPartnerBankAccountId destinationBPartnerBankAccountId;

	String documentNumber;

	Instant sendDate;

	Instant documentDate;

	DocTypeId docTypeId;

	BigDecimal remittedAmountSum;

	CurrencyId remittedAmountCurrencyId;

	BigDecimal serviceFeeAmount;

	CurrencyId serviceFeeCurrencyId;

	BigDecimal paymentDiscountAmountSum;

	String additionalNotes;
}
