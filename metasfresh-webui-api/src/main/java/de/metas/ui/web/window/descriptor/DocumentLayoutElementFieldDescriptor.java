package de.metas.ui.web.window.descriptor;

import java.io.Serializable;
import java.util.Objects;

import org.adempiere.util.Check;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@SuppressWarnings("serial")
public final class DocumentLayoutElementFieldDescriptor implements Serializable
{
	public static final Builder builder(final String fieldName)
	{
		return new Builder(fieldName);
	}

	public static enum LookupSource
	{
		lookup //
		, list //
	};

	public static enum FieldType
	{
		ActionButtonStatus, ActionButton //
	}

	private final String field;
	private final LookupSource lookupSource;
	private final FieldType fieldType;

	private DocumentLayoutElementFieldDescriptor(final Builder builder)
	{
		super();

		field = Preconditions.checkNotNull(builder.getFieldName(), "field not null");
		lookupSource = builder.lookupSource;
		fieldType = builder.fieldType;
	}

	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("field", field)
				.toString();
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(field);
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (!(obj instanceof DocumentLayoutElementFieldDescriptor))
		{
			return false;
		}
		final DocumentLayoutElementFieldDescriptor other = (DocumentLayoutElementFieldDescriptor)obj;
		return Objects.equals(field, other.field); // only the field name shall be matched
	}

	public String getField()
	{
		return field;
	}

	public LookupSource getLookupSource()
	{
		return lookupSource;
	}

	public FieldType getFieldType()
	{
		return fieldType;
	}

	public static final class Builder
	{
		private final String fieldName;
		private LookupSource lookupSource;
		private FieldType fieldType;
		private boolean displayable = true;
		private boolean consumed = false;

		private Builder(final String fieldName)
		{
			super();
			Check.assumeNotEmpty(fieldName, "fieldName is not empty");
			this.fieldName = fieldName;
		}

		public DocumentLayoutElementFieldDescriptor build()
		{
			setConsumed();
			return new DocumentLayoutElementFieldDescriptor(this);
		}

		public String getFieldName()
		{
			return fieldName;
		}

		public Builder setLookupSource(final LookupSource lookupSource)
		{
			this.lookupSource = lookupSource;
			return this;
		}

		public Builder setFieldType(final FieldType fieldType)
		{
			this.fieldType = fieldType;
			return this;
		}
		
		public Builder setDisplayable(final boolean displayable)
		{
			this.displayable = displayable;
			return this;
		}
		
		public boolean isDisplayable()
		{
			return displayable;
		}

		public Builder setConsumed()
		{
			this.consumed = true;
			return this;
		}

		public boolean isConsumed()
		{
			return consumed;
		}
	}
}
