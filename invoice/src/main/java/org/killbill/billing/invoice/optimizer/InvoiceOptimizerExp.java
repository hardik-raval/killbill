/*
 * Copyright 2020-2021 Equinix, Inc
 * Copyright 2014-2021 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.optimizer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.joda.time.Period;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingMode;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.dao.InvoiceDao;
import org.killbill.billing.invoice.dao.InvoiceModelDao;
import org.killbill.billing.invoice.model.DefaultInvoice;
import org.killbill.billing.junction.BillingEvent;
import org.killbill.billing.junction.BillingEventSet;
import org.killbill.billing.util.config.definition.InvoiceConfig;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class InvoiceOptimizerExp extends InvoiceOptimizerBase {

    private static Logger logger = LoggerFactory.getLogger(InvoiceOptimizerExp.class);

    @Inject
    public InvoiceOptimizerExp(final InvoiceDao invoiceDao,
                               final Clock clock,
                               final InvoiceConfig invoiceConfig) {
        super(invoiceDao, clock, invoiceConfig);
        logger.info("Feature InvoiceOptimizer is ON");
    }

    @Override
    public AccountInvoices getInvoices(final InternalCallContext callContext) {
        final Period maxInvoiceLimit = invoiceConfig.getMaxInvoiceLimit(callContext);
        final LocalDate fromDate = maxInvoiceLimit != null ? callContext.toLocalDate(clock.getUTCNow()).minus(maxInvoiceLimit) : null;
        final List<Invoice> existingInvoices = new LinkedList<Invoice>();
        final List<InvoiceModelDao> invoicesByAccount = invoiceDao.getInvoicesByAccount(false, fromDate, null, callContext);
        for (final InvoiceModelDao invoiceModelDao : invoicesByAccount) {
            existingInvoices.add(new DefaultInvoice(invoiceModelDao));
        }
        return new AccountInvoicesExp(fromDate, existingInvoices);
    }

    public static class AccountInvoicesExp extends AccountInvoices {
        public AccountInvoicesExp(final LocalDate cutoffDate, final List<Invoice> invoices) {
            super(cutoffDate, invoices);
        }

        public AccountInvoicesExp() {
            super();
        }

        @Override
        public void filterProposedItems(final List<InvoiceItem> proposedItems, final BillingEventSet eventSet, final InternalCallContext internalCallContext) {
            if (cutoffDate != null) {

                final Map<String, BillingMode> billingModes = new HashMap<>();

                final Iterable<InvoiceItem> filtered = Iterables.filter(proposedItems, new Predicate<InvoiceItem>() {
                    @Override
                    public boolean apply(final InvoiceItem invoiceItem) {
                        if (invoiceItem.getInvoiceItemType() == InvoiceItemType.FIXED) {
                            return invoiceItem.getStartDate().compareTo(cutoffDate) >= 0;
                        }
                        Preconditions.checkState(invoiceItem.getInvoiceItemType() == InvoiceItemType.RECURRING, "Expected (proposed) item %s to be a RECURRING invoice item", invoiceItem);

                        // Extract Plan info associated with item by correlating with list of billing events
                        // From plan info, retrieve billing mode.
                        BillingMode billingMode = billingModes.get(invoiceItem.getPlanName());
                        if (billingMode == null) {

                            // Best effort logic to find the correct billing event ('be'):
                            // We could simplify and look for any 'be' whose Plan matches the one from the invoiceItem,
                            // but in unlikely scenarios where there are multiple Plans across catalog versions with different BillingMode,
                            // we could end up with the wrong billing event (and therefore billing mode). Therefore, the complexity.
                            // (all this because catalog is not available in this layer)
                            //
                            final Iterator<BillingEvent> it = ((NavigableSet<BillingEvent>) eventSet).descendingIterator();
                            while (it.hasNext()) {
                                final BillingEvent be = it.next();
                                if (!be.getSubscriptionId().equals(invoiceItem.getSubscriptionId()) /* wrong subscription ID */ ||
                                        /* Not the correct plan */
                                    !(be.getPlan() != null && be.getPlan().getName().equals(invoiceItem.getPlanName())) ||
                                        /* Whether in-advance or in-arrear (what we are trying to find out), the 'be' we want is the one where ii.endDate >= be.effDt */
                                    invoiceItem.getEndDate().compareTo(internalCallContext.toLocalDate(be.getEffectiveDate())) < 0) {
                                    continue;
                                }
                                billingMode = be.getPlan().getRecurringBillingMode();
                                billingModes.put(invoiceItem.getPlanName(), billingMode);
                                break;
                            }
                        }

                        // Any cutoff date 't' will return invoices with all items where:
                        // - If IN_ADVANCE, all items where startDate >= t
                        // - If IN_ARREAR, all items where endDate >= t
                        final LocalDate startOrEndDate = (billingMode == BillingMode.IN_ADVANCE) ? invoiceItem.getStartDate() : invoiceItem.getEndDate();
                        return startOrEndDate.compareTo(cutoffDate) >= 0;
                    }
                });
                final List<InvoiceItem> filteredProposed = ImmutableList.copyOf(filtered);
                proposedItems.clear();
                proposedItems.addAll(filteredProposed);
            }
        }

    }

}
