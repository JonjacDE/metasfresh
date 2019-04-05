package de.metas.security.permissions.record_access;

import java.util.Set;

import javax.annotation.Nullable;

import org.adempiere.util.lang.impl.TableRecordReference;

import com.google.common.collect.ImmutableSet;

import de.metas.security.permissions.Access;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2019 metas GmbH
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

@Value
public class UserGroupRecordAccessQuery
{
	ImmutableSet<TableRecordReference> recordRefs;
	ImmutableSet<Access> permissions;
	Principal principal;

	@Builder
	private UserGroupRecordAccessQuery(
			@Singular final Set<TableRecordReference> recordRefs,
			@Singular final Set<Access> permissions,
			@Nullable final Principal principal)
	{
		this.recordRefs = ImmutableSet.copyOf(recordRefs);
		this.permissions = ImmutableSet.copyOf(permissions);
		this.principal = principal;
	}
}
