/*
 * Copyright 2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.send;

/**
 * @author Andreas Schildbach
 *
 * NOTE: bitcoinj uses {@link de.schildbach.wallet.data.DynamicFeeLoader} to load fees dynamically
 * and fees.txt as a fallback. Dynamic part is commented out in dashj, so only fees.txt is currently used.
 */
public enum FeeCategory {
    ZERO,
    /**
     * We don't care when it confirms, but it should confirm at some time. Can be days or weeks.
     */
    ECONOMIC,

    /**
     * Under normal network conditions, confirms within the next 15 minutes. Can take longer, but this should
     * be an exception. And it should not take days or weeks.
     */
    NORMAL,

    /**
     * Confirms within the next 2-10 minutes.
     */
    PRIORITY;
}
