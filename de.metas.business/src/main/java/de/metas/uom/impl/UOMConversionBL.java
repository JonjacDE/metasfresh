package de.metas.uom.impl;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2015 metas GmbH
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

import java.math.BigDecimal;
import java.util.Optional;

import javax.annotation.Nullable;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;

import de.metas.currency.CurrencyPrecision;
import de.metas.logging.LogManager;
import de.metas.product.IProductBL;
import de.metas.product.IProductDAO;
import de.metas.product.ProductId;
import de.metas.product.ProductPrice;
import de.metas.quantity.Quantity;
import de.metas.uom.IUOMConversionBL;
import de.metas.uom.IUOMConversionDAO;
import de.metas.uom.IUOMDAO;
import de.metas.uom.UOMConversionContext;
import de.metas.uom.UOMConversionsMap;
import de.metas.uom.UOMUtil;
import de.metas.uom.UomId;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;

public class UOMConversionBL implements IUOMConversionBL
{
	private final transient Logger logger = LogManager.getLogger(getClass());

	@Override
	public BigDecimal convertQty(
			@Nullable final ProductId productId,
			final BigDecimal qty,
			@NonNull final I_C_UOM uomFrom,
			@NonNull final I_C_UOM uomTo)
	{
		if (qty.signum() == 0)
		{
			return roundToUOMPrecisionIfPossible(qty, uomTo);
		}

		if (uomFrom.getC_UOM_ID() == uomTo.getC_UOM_ID())
		{
			return roundToUOMPrecisionIfPossible(qty, uomTo);
		}
		else
		{
			final BigDecimal qtyConverted = convertQty0(productId, qty, uomFrom, uomTo);
			return roundToUOMPrecisionIfPossible(qtyConverted, uomTo);
		}
	}

	@Override
	public BigDecimal convertQty(@NonNull final UOMConversionContext conversionCtx, final BigDecimal qty, final I_C_UOM uomFrom, final I_C_UOM uomTo)
	{
		return convertQty(conversionCtx.getProductId(), qty, uomFrom, uomTo);
	}

	@Override
	public Quantity convertQuantityTo(@NonNull final Quantity quantity, final UOMConversionContext conversionCtx, @NonNull final UomId uomToId)
	{
		final I_C_UOM uomTo = Services.get(IUOMDAO.class).getById(uomToId);
		return convertQuantityTo(quantity, conversionCtx, uomTo);
	}

	@Override
	public Quantity convertQuantityTo(@NonNull final Quantity quantity, final UOMConversionContext conversionCtx, @NonNull final I_C_UOM uomTo)
	{
		final UomId uomToId = UomId.ofRepoId(uomTo.getC_UOM_ID());

		// If the Source UOM of this quantity is the same as the UOM to which we need to convert
		// we just need to return the Quantity with current/source switched
		if (quantity.getSource_UOM_ID() == uomToId.getRepoId())
		{
			return quantity.switchToSource();
		}

		// If current UOM is the same as the UOM to which we need to convert, we shall do nothing
		final int currentUOMId = quantity.getUOMId();
		if (currentUOMId == uomToId.getRepoId())
		{
			return quantity;
		}

		//
		// Convert current quantity to "uomTo"
		final BigDecimal sourceQtyNew = quantity.getAsBigDecimal();
		final int sourceUOMNewId = currentUOMId;
		final I_C_UOM sourceUOMNew = Services.get(IUOMDAO.class).getById(sourceUOMNewId);
		final BigDecimal qtyNew = convertQty(conversionCtx,
				sourceQtyNew,
				sourceUOMNew, // From UOM
				uomTo // To UOM
		);
		// Create an return the new quantity
		return new Quantity(qtyNew, uomTo, sourceQtyNew, sourceUOMNew);
	}

	@Override
	public BigDecimal convertQtyToProductUOM(final UOMConversionContext conversionCtx, final BigDecimal qty, final I_C_UOM uomFrom)
	{
		Check.assumeNotNull(conversionCtx, "conversionCtx not null");

		// Get Product's stocking UOM
		final ProductId productId = conversionCtx.getProductId();
		final I_C_UOM uomTo = Services.get(IProductBL.class).getStockingUOM(productId);

		return convertQty(conversionCtx, qty, uomFrom, uomTo);
	}

	@Override
	public Quantity convertToProductUOM(@NonNull final Quantity quantity, final ProductId productId)
	{
		final BigDecimal sourceQty = quantity.getAsBigDecimal();
		final I_C_UOM sourceUOM = quantity.getUOM();

		final UOMConversionContext conversionCtx = UOMConversionContext.of(productId);
		final I_C_UOM uomTo = Services.get(IProductBL.class).getStockingUOM(productId);
		final BigDecimal qty = convertQty(conversionCtx, sourceQty, sourceUOM, uomTo);
		return new Quantity(qty, uomTo, sourceQty, sourceUOM);
	}

	/**
	 * Round quantity based on uom's standard precision if stdPrecision = true, uom's costing precision otherwise.
	 *
	 * @param uom
	 * @param qty quantity
	 * @param useStdPrecision true if standard precision
	 * @return rounded quantity
	 */
	private BigDecimal roundQty(final I_C_UOM uom, final BigDecimal qty, final boolean useStdPrecision)
	{
		final int precision;
		if (useStdPrecision)
		{
			precision = uom.getStdPrecision();
		}
		else
		{
			precision = uom.getCostingPrecision();
		}

		if (qty.scale() > precision)
		{
			return qty.setScale(precision, BigDecimal.ROUND_HALF_UP);
		}
		return qty;
	}

	@Override
	public BigDecimal roundToUOMPrecisionIfPossible(@NonNull final BigDecimal qty, @NonNull final I_C_UOM uom)
	{
		final int precision = uom.getStdPrecision();
		// NOTE: negative precision is not supported atm
		Check.assume(precision >= 0, "UOM {} shall have positive precision", uom);

		// NOTE: it seems that ZERO is a special case of BigDecimal, so we are computing it right away
		if (qty == null || qty.signum() == 0)
		{
			return BigDecimal.ZERO.setScale(precision);
		}

		final BigDecimal qtyNoZero = qty.stripTrailingZeros();
		final int qtyScale = qtyNoZero.scale();
		if (qtyScale >= precision)
		{
			// Qty's actual scale is bigger than UOM precision, don't touch it
			return qtyNoZero;
		}
		else
		{
			// Qty's actual scale is less than UOM precision. Try to convert it to UOM precision
			// NOTE: we are using without scale because it shall be scaled without any problem
			return qtyNoZero.setScale(precision, BigDecimal.ROUND_HALF_UP);
		}
	}

	private BigDecimal convertQty0(final ProductId productId, final BigDecimal qty, final I_C_UOM uomFrom, final I_C_UOM uomTo)
	{
		final I_M_Product product = productId != null
				? Services.get(IProductDAO.class).getById(productId)
				: null;

		BigDecimal result;

		if (product == null)
		{
			result = convert(uomFrom, uomTo, qty);
		}

		//
		// Case: uomFrom is the stocking UOM
		else if (product.getC_UOM_ID() == uomFrom.getC_UOM_ID())
		{
			// convertProductFrom: converts Qty from stocking UOM to given UOM
			result = convertFromProductUOM(productId, uomTo, qty);
		}
		//
		// Case: uomTo is the stocking UOM
		else if (product.getC_UOM_ID() == uomTo.getC_UOM_ID())
		{
			// convertProductTo: converts Qty from given UOM to stocking UOM
			result = convertToProductUOM(productId, uomFrom, qty);
		}
		//
		//
		else
		{
			final I_C_UOM productUOM = product.getC_UOM();
			throw new AdempiereException("Case not supported: product's UOM is not " + uomFrom.getUOMSymbol()
					+ " and not " + uomTo.getUOMSymbol()
					+ " but it is " + (productUOM == null ? "NULL" : productUOM.getUOMSymbol()));
		}

		//
		// If result is null throw an exception
		// NOTE: we check the result first and then we gather more debug info
		if (result == null)
		{
			throw new AdempiereException("Failed to convert Qty=" + qty
					+ " of product=" + (product != null ? product.getValue() : null)
					+ " from UOM=" + uomFrom.getName()
					+ " to UOM=" + uomTo.getName());
		}

		return result;

	}

	@Override
	public BigDecimal convertPrice(
			final int productId,
			BigDecimal price,
			I_C_UOM uomFrom,
			I_C_UOM uomTo,
			int pricePrecision)
	{
		BigDecimal priceConv = convertQty(ProductId.ofRepoIdOrNull(productId), price, uomFrom, uomTo);
		if (priceConv.scale() > pricePrecision)
		{
			priceConv = priceConv.setScale(pricePrecision, BigDecimal.ROUND_HALF_UP);
		}

		return priceConv;
	}

	@Override
	public BigDecimal convert(
			final I_C_UOM fromUOM,
			final I_C_UOM toUOM,
			final BigDecimal qty,
			final boolean useStdPrecision)
	{
		// Nothing to do
		if (qty == null || qty.signum() == 0 || fromUOM == null || toUOM == null)
		{
			return qty;
		}

		final UomId fromUomId = UomId.ofRepoId(fromUOM.getC_UOM_ID());
		final UomId toUomId = UomId.ofRepoId(toUOM.getC_UOM_ID());

		final UOMConversionsMap conversions = getGenericRates();
		final BigDecimal multiplyRate = conversions.getRate(fromUomId, toUomId);

		final int precision = useStdPrecision ? toUOM.getStdPrecision() : toUOM.getCostingPrecision();

		// Calculate & Scale
		BigDecimal qtyConv = multiplyRate.multiply(qty);
		if (qtyConv.scale() > precision)
		{
			qtyConv = qtyConv.setScale(precision, BigDecimal.ROUND_HALF_UP);
		}

		return qtyConv;
	}   // convert

	@Override
	public BigDecimal convertFromProductUOM(
			final ProductId productId,
			final I_C_UOM uomDest,
			final BigDecimal qtyToConvert)
	{

		if (qtyToConvert == null || qtyToConvert.signum() == 0 || productId == null || uomDest == null)
		{
			return qtyToConvert;
		}

		final BigDecimal rate = getRateForConversionFromProductUOM(productId, uomDest);
		if (rate != null)
		{
			if (BigDecimal.ONE.compareTo(rate) == 0)
			{
				return qtyToConvert;
			}

			return roundQty(uomDest, rate.multiply(qtyToConvert), true);
		}

		// metas: tsa: begin: 01428
		// Fallback: check general conversion rates
		final I_C_UOM productUOM = Services.get(IProductBL.class).getStockingUOM(productId);
		final BigDecimal qtyConv = convert(productUOM, uomDest, qtyToConvert);
		if (qtyConv != null)
		{
			return qtyConv;
		}
		// metas: tsa: end: 01428

		return null;
	}	// convertProductTo

	private UOMConversionsMap getProductConversions(@NonNull final ProductId productId)
	{
		final IUOMConversionDAO uomConversionsRepo = Services.get(IUOMConversionDAO.class);
		return uomConversionsRepo.getProductConversions(productId);
	}

	private UOMConversionsMap getGenericRates()
	{
		final IUOMConversionDAO uomConversionsRepo = Services.get(IUOMConversionDAO.class);
		return uomConversionsRepo.getGenericConversions();
	}

	private BigDecimal getRate(I_C_UOM uomFrom, I_C_UOM uomTo)
	{
		final UomId fromUomId = UomId.ofRepoId(uomFrom.getC_UOM_ID());
		final UomId toUomId = UomId.ofRepoId(uomTo.getC_UOM_ID());
		if (fromUomId.equals(toUomId))
		{
			return BigDecimal.ONE;
		}

		final UOMConversionsMap conversions = getGenericRates();
		final Optional<BigDecimal> rate = conversions.getRateIfExists(fromUomId, toUomId);
		if (rate.isPresent())
		{
			return rate.get();
		}

		// try to derive
		return getTimeConversionRate(uomFrom, uomTo);
	}	// getConversion

	/**
	 * Get rate to convert a qty from the stocking UOM of the given <code>M_Product_ID</code>'s product to the given <code>C_UOM_Dest_ID</code> to.
	 *
	 * @param ctx context
	 * @param product product from whose stocking UOM we want to convert
	 * @param uomDest uom we want to convert to
	 * @return multiplier or null
	 */
	@VisibleForTesting
	BigDecimal getRateForConversionFromProductUOM(final ProductId productId, final I_C_UOM uomDest)
	{
		if (productId == null)
		{
			return null;
		}

		final UOMConversionsMap rates = getProductConversions(productId);
		if (rates.isEmpty())
		{
			logger.debug("None found");
			return null;
		}

		final UomId fromUomId = Services.get(IProductBL.class).getStockingUOMId(productId);
		final UomId toUomId = UomId.ofRepoId(uomDest.getC_UOM_ID());

		final Optional<BigDecimal> rate = rates.getRateIfExists(fromUomId, toUomId);
		if (rate.isPresent())
		{
			return rate.get();
		}
		else
		{
			logger.debug("None applied");
			return null;
		}
	}

	/**
	 * Get rate to convert a qty from the given <code>C_UOM_Source_ID</code> to the stocking UOM of the given <code>M_Product_ID</code>'s product.
	 *
	 * @param ctx context
	 * @param M_Product_ID product to whose stocking UOM we want to convert
	 * @param C_UOM_Source_ID UOM we want to convert from
	 *
	 * @return multiplier or null
	 */
	@VisibleForTesting
	BigDecimal getRateForConversionToProductUOM(final ProductId productId, final I_C_UOM uomSource)
	{
		final UOMConversionsMap rates = getProductConversions(productId);
		if (rates.isEmpty())
		{
			logger.debug("getProductRateFrom - none found");
			return null;
		}

		final UomId fromUomId = UomId.ofRepoId(uomSource.getC_UOM_ID());
		final UomId toUomId = Services.get(IProductBL.class).getStockingUOMId(productId);

		final Optional<BigDecimal> rate = rates.getRateIfExists(fromUomId, toUomId);
		if (rate.isPresent())
		{
			return rate.get();
		}
		else
		{
			logger.debug("None applied");
			return null;
		}
	}

	@Override
	public BigDecimal convertToProductUOM(
			final ProductId productId,
			final I_C_UOM uomSource,
			final BigDecimal qtyToConvert)
	{

		// No conversion
		if (qtyToConvert == null || qtyToConvert.signum() == 0 || uomSource == null || productId == null)
		{
			logger.debug("No Conversion - QtyPrice={}", qtyToConvert);
			return qtyToConvert;
		}

		final BigDecimal rate = getRateForConversionToProductUOM(productId, uomSource);
		if (rate != null)
		{
			if (BigDecimal.ONE.compareTo(rate) == 0)
			{
				return qtyToConvert;
			}

			BigDecimal qtyConv = rate.multiply(qtyToConvert);

			// Round converted quantity to product UOM precision
			// NOTE: product UOM is the UOM in which we converted
			final I_C_UOM productUOM = Services.get(IProductBL.class).getStockingUOM(productId);
			qtyConv = roundQty(productUOM, qtyConv, true);

			return qtyConv;
		}

		// metas: tsa: begin: 01428
		// Fallback: check general conversion rates

		final I_C_UOM productUOM = Services.get(IProductBL.class).getStockingUOM(productId);
		final BigDecimal conversion = convert(uomSource, productUOM, qtyToConvert);
		if (conversion != null)
		{
			return conversion;
		}
		// metas: tsa: end: 01428

		logger.debug("No Rate found for product: {}", productId);
		return null;
	}	// convertProductFrom

	@Override
	public BigDecimal convert(
			final I_C_UOM uomFrom, // int C_UOM_ID,
			final I_C_UOM uomTo, // int C_UOM_To_ID,
			final BigDecimal qty)
	{
		final int uomFromID = uomFrom.getC_UOM_ID();
		final int uomToID = uomTo.getC_UOM_ID();

		if (qty == null || qty.signum() == 0 || uomFromID == uomToID)
		{
			return qty;
		}

		final BigDecimal rate = getRate(uomFrom, uomTo);
		if (rate != null)
		{
			BigDecimal qtyConv = rate.multiply(qty);
			final boolean useStdPrecision = true;
			qtyConv = roundQty(uomTo, qtyConv, useStdPrecision);
			return qtyConv;
		}

		return null;
	}	// convert

	@VisibleForTesting
	BigDecimal getTimeConversionRate(@NonNull final I_C_UOM fromTimeUom, @NonNull final I_C_UOM toTimeUom)
	{
		final UomId fromTimeUomId = UomId.ofRepoId(fromTimeUom.getC_UOM_ID());
		final UomId toTimeUomId = UomId.ofRepoId(toTimeUom.getC_UOM_ID());
		if (fromTimeUomId.equals(toTimeUomId))
		{
			return BigDecimal.ONE;
		}

		// Time - Minute
		if (UOMUtil.isMinute(fromTimeUom))
		{
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(1.0 / 60.0);
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(1.0 / 1440.0); // 24 * 60
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(1.0 / 480.0); // 8 * 60
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(1.0 / 10080.0); // 7 * 24 * 60
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 43200.0); // 30 * 24 * 60
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 9600.0); // 4 * 5 * 8 * 60
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 525600.0); // 365 * 24 * 60
			}
		}

		// Time - Hour
		if (UOMUtil.isHour(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(60.0);
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(1.0 / 24.0);
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(1.0 / 8.0);
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(1.0 / 168.0); // 7 * 24
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 720.0); // 30 * 24
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 160.0); // 4 * 5 * 8
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 8760.0); // 365 * 24
			}
		}

		// Time - Day
		if (UOMUtil.isDay(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(1440.0); // 24 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(24.0);
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(3.0); // 24 / 8
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(1.0 / 7.0); // 7
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 30.0); // 30
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 20.0); // 4 * 5
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 365.0); // 365
			}
		}

		// Time - WorkDay
		if (UOMUtil.isWorkDay(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(480.0); // 8 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(8.0); // 8
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(1.0 / 3.0); // 24 / 8
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(1.0 / 5); // 5
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 20.0); // 4 * 5
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 20.0); // 4 * 5
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 240.0); // 4 * 5 * 12
			}
		}

		// Time - Week
		if (UOMUtil.isWeek(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(10080.0); // 7 * 24 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(168.0); // 7 * 24
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(7.0);
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(5.0);
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 4.0); // 4
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.0 / 4.0); // 4
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 50.0); // 50
			}
		}

		// Time - Month
		if (UOMUtil.isMonth(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(43200.0); // 30 * 24 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(720.0); // 30 * 24
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(30.0); // 30
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(20.0); // 4 * 5
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(4.0); // 4
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(1.5); // 30 / 20
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 12.0); // 12
			}
		}

		// Time - WorkMonth
		if (UOMUtil.isWorkMonth(fromTimeUom))
		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(9600.0); // 4 * 5 * 8 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(160.0); // 4 * 5 * 8
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(20.0); // 4 * 5
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(20.0); // 4 * 5
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(4.0); // 4
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(20.0 / 30.0); // 20 / 30
			}
			if (UOMUtil.isYear(toTimeUom))
			{
				return new BigDecimal(1.0 / 12.0); // 12
			}
		}

		// Time - Year
		if (UOMUtil.isYear(fromTimeUom))

		{
			if (UOMUtil.isMinute(toTimeUom))
			{
				return new BigDecimal(518400.0); // 12 * 30 * 24 * 60
			}
			if (UOMUtil.isHour(toTimeUom))
			{
				return new BigDecimal(8640.0); // 12 * 30 * 24
			}
			if (UOMUtil.isDay(toTimeUom))
			{
				return new BigDecimal(365.0); // 365
			}
			if (UOMUtil.isWorkDay(toTimeUom))
			{
				return new BigDecimal(240.0); // 12 * 4 * 5
			}
			if (UOMUtil.isWeek(toTimeUom))
			{
				return new BigDecimal(50.0); // 52
			}
			if (UOMUtil.isMonth(toTimeUom))
			{
				return new BigDecimal(12.0); // 12
			}
			if (UOMUtil.isWorkMonth(toTimeUom))
			{
				return new BigDecimal(12.0); // 12
			}
		}

		return null;
	}

	@Override
	public ProductPrice convertProductPriceToUom(
			@NonNull final ProductPrice price,
			@NonNull final UomId toUomId,
			@NonNull final CurrencyPrecision pricePrecision)
	{
		if (price.getUomId().equals(toUomId))
		{
			return price;
		}

		final IUOMDAO uomsRepo = Services.get(IUOMDAO.class);

		final BigDecimal factor = convertQty(
				price.getProductId(),
				BigDecimal.ONE,
				uomsRepo.getById(price.getUomId()),
				uomsRepo.getById(toUomId));

		return price.withValueAndUomId(
				price.toMoney().multiply(factor),
				toUomId);
	}
}
