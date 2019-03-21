/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.data;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.MonetaryFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider {

    public static final String KEY_CURRENCY_CODE = "currency_code";
    private static final String KEY_RATE_COIN = "rate_coin";
    private static final String KEY_RATE_FIAT = "rate_fiat";
    private static final String KEY_SOURCE = "source";

    public static final String QUERY_PARAM_Q = "q";
    private static final String QUERY_PARAM_OFFLINE = "offline";

    private Configuration config;
    private String userAgent;
    private PinRetryController pinRetryController;

    @Nullable
    private Map<String, ExchangeRate> exchangeRates = null;
    private long lastUpdated = 0;

    private static final HttpUrl BITCOINAVERAGE_URL = HttpUrl
            .parse("https://apiv2.bitcoinaverage.com/indices/global/ticker/short?crypto=BTC");
    private static final HttpUrl BITCOINAVERAGE_DASHBTC_URL = HttpUrl
            .parse("https://apiv2.bitcoinaverage.com/indices/crypto/ticker/DASHBTC");
    private static final String BITCOINAVERAGE_SOURCE = "BitcoinAverage.com";

    private static final HttpUrl POLONIEX_URL = HttpUrl.parse("https://poloniex.com/public?command=returnTradeHistory&currencyPair="+CoinDefinition.cryptsyMarketCurrency +"_" + CoinDefinition.coinTicker);
    private static final String POLONIEX_SOURCE = "Poloniex";

    private static final long UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30);

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        this.pinRetryController = new PinRetryController(getContext());

        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context), context.getResources());
        this.userAgent = WalletApplication.httpUserAgent(WalletApplication.packageInfoFromContext(context).versionName);

        final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
        if (cachedExchangeRate != null) {
            exchangeRates = new TreeMap<String, ExchangeRate>();
            exchangeRates.put(cachedExchangeRate.getCurrencyCode(), cachedExchangeRate);
        }

        return true;
    }

    public static Uri contentUri(final String packageName, final boolean offline) {
        final Uri.Builder uri = Uri.parse("content://" + packageName + '.' + "exchange_rates").buildUpon();
        if (offline)
            uri.appendQueryParameter(QUERY_PARAM_OFFLINE, "1");
        return uri.build();
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs,
            final String sortOrder) {
        final long now = System.currentTimeMillis();

        final boolean offline = uri.getQueryParameter(QUERY_PARAM_OFFLINE) != null;

        if (!offline && (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS)) {
            Map<String, ExchangeRate> newExchangeRates = null;
            if (newExchangeRates == null)
                newExchangeRates = requestExchangeRatesSib();

            if (newExchangeRates != null) {
                exchangeRates = newExchangeRates;
                lastUpdated = now;

                final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
                if (exchangeRateToCache != null)
                    config.setCachedExchangeRate(exchangeRateToCache);
            }
        }

        if (exchangeRates == null)
            return null;

        final MatrixCursor cursor = new MatrixCursor(
                new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE_COIN, KEY_RATE_FIAT, KEY_SOURCE });

        if (selection == null) {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value)
                        .add(exchangeRate.source);
            }
        } else if (selection.equals(QUERY_PARAM_Q)) {
            final String selectionArg = selectionArgs[0].toLowerCase(Locale.US);
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                final String currencySymbol = GenericUtils.currencySymbol(currencyCode);
                if (currencyCode.toLowerCase(Locale.US).contains(selectionArg)
                        || currencySymbol.toLowerCase(Locale.US).contains(selectionArg))
                    cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value)
                            .add(rate.fiat.value).add(exchangeRate.source);
            }
        } else if (selection.equals(KEY_CURRENCY_CODE)) {
            final String selectionArg = selectionArgs[0];
            final ExchangeRate exchangeRate = bestExchangeRate(selectionArg);
            if (exchangeRate != null) {
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value)
                        .add(exchangeRate.source);
            }
        }

        return cursor;
    }

    private ExchangeRate bestExchangeRate(final String currencyCode) {
        ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
        if (rate != null)
            return rate;

        final String defaultCode = defaultCurrencyCode();
        rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

        if (rate != null)
            return rate;

        return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String defaultCurrencyCode() {
        try {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (final IllegalArgumentException x) {
            return null;
        }
    }

    public static ExchangeRate getExchangeRate(final Cursor cursor) {
        final String currencyCode = cursor
                .getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
        final Coin rateCoin = Coin
                .valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN)));
        final Fiat rateFiat = Fiat.valueOf(currencyCode,
                cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        return new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(rateCoin, rateFiat), source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri) {
        throw new UnsupportedOperationException();
    }

     private Map<String, ExchangeRate> requestExchangeRatesSib() {
         Double sibPerBTC;

         sibPerBTC = getCoinValueBTC_BITTREX();
         if (sibPerBTC == null) {
             sibPerBTC = getCoinValueBTC_LIVECOIN();
             if (sibPerBTC == null) {
                 sibPerBTC = getCoinValueBTC_YOBIT();
                 if (sibPerBTC == null) {
                     return null;
                 }
             }
         }

         final Stopwatch watch = Stopwatch.createStarted();

         final Request.Builder request = new Request.Builder();
         request.url(BITCOINAVERAGE_URL);
         request.header("User-Agent", userAgent);

         final Call call = Constants.HTTP_CLIENT.newCall(request.build());
         try {
             final Response response = call.execute();
             if (response.isSuccessful()) {
                 pinRetryController.storeSecureTime(response.headers().getDate("date"));
                 final String content = response.body().string();
                 final JSONObject head = new JSONObject(content);
                 final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                 for (final Iterator<String> i = head.keys(); i.hasNext();) {
                     final String currencyCode = i.next();
                     if (currencyCode.startsWith("BTC")) {
                         final String fiatCurrencyCode = currencyCode.substring(3);
                         if (!fiatCurrencyCode.equals(MonetaryFormat.CODE_BTC)
                                 && !fiatCurrencyCode.equals(MonetaryFormat.CODE_MBTC)
                                 && !fiatCurrencyCode.equals(MonetaryFormat.CODE_UBTC)) {
                             final JSONObject exchangeRate = head.getJSONObject(currencyCode);
                             final String average = exchangeRate.getString("last");
                             try {
                                 Double _rate = sibPerBTC * Double.parseDouble(average);
                                 final Fiat rate = parseFiatInexact(fiatCurrencyCode, _rate.toString());
                                 if (rate.signum() > 0)
                                     rates.put(fiatCurrencyCode, new ExchangeRate(
                                             new org.bitcoinj.utils.ExchangeRate(rate), BITCOINAVERAGE_SOURCE));
                             } catch (final IllegalArgumentException x) {
                                 log.warn("problem fetching {} exchange rate from {}: {}", currencyCode,
                                         BITCOINAVERAGE_URL, x.getMessage());
                             }
                         }
                     }
                 }

                 watch.stop();
                 log.info("fetched exchange rates from {}, {} chars, took {}", BITCOINAVERAGE_URL, content.length(),
                         watch);

                 return rates;
             } else {
                 log.warn("http status {} when fetching exchange rates from {}", response.code(), BITCOINAVERAGE_URL);
             }
         } catch (final Exception x) {
             log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_URL, x);
         }

         return null;
     }

     private Map<String, ExchangeRate> requestExchangeRates() {
         Double dashPerBTC = 0.0;
         try {
             dashPerBTC = requestExchangeRateOfDashInBTC_poloniex();

             if (dashPerBTC == null) {
                 Map<String, ExchangeRate> dashRates = requestExchangeRatesForDashInBTC();
                 dashPerBTC = Double.parseDouble(dashRates.get("DASH").rate.fiat.toString()) / Double.parseDouble(dashRates.get("DASH").rate.coin.toString());
             }
         }
         catch(Exception x)
         {
             log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_DASHBTC_URL, x);
             return null;
         }

        final Stopwatch watch = Stopwatch.createStarted();

        final Request.Builder request = new Request.Builder();
        request.url(BITCOINAVERAGE_URL);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                pinRetryController.storeSecureTime(response.headers().getDate("date"));
                final String content = response.body().string();
                final JSONObject head = new JSONObject(content);
                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                for (final Iterator<String> i = head.keys(); i.hasNext();) {
                    final String currencyCode = i.next();
                    if (currencyCode.startsWith("BTC")) {
                        final String fiatCurrencyCode = currencyCode.substring(3);
                        if (!fiatCurrencyCode.equals(MonetaryFormat.CODE_BTC)
                                && !fiatCurrencyCode.equals(MonetaryFormat.CODE_MBTC)
                                && !fiatCurrencyCode.equals(MonetaryFormat.CODE_UBTC)) {
                            final JSONObject exchangeRate = head.getJSONObject(currencyCode);
                            final String average = exchangeRate.getString("last");
                            try {
                                Double _rate = dashPerBTC * Double.parseDouble(average);
                                final Fiat rate = parseFiatInexact(fiatCurrencyCode, _rate.toString());
                                if (rate.signum() > 0)
                                    rates.put(fiatCurrencyCode, new ExchangeRate(
                                            new org.bitcoinj.utils.ExchangeRate(rate), BITCOINAVERAGE_SOURCE));
                            } catch (final IllegalArgumentException x) {
                                log.warn("problem fetching {} exchange rate from {}: {}", currencyCode,
                                        BITCOINAVERAGE_URL, x.getMessage());
                            }
                        }
                    }
                }

                watch.stop();
                log.info("fetched exchange rates from {}, {} chars, took {}", BITCOINAVERAGE_URL, content.length(),
                        watch);

                return rates;
            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), BITCOINAVERAGE_URL);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_URL, x);
        }

        return null;
    }

    private Double requestExchangeRateOfDashInBTC_poloniex() {
        final Stopwatch watch = Stopwatch.createStarted();

        final Request.Builder request = new Request.Builder();
        request.url(POLONIEX_URL);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                pinRetryController.storeSecureTime(response.headers().getDate("date"));
                final String content = response.body().string();

                JSONArray recenttrades = new JSONArray(content);

                double btcTraded = 0.0;
                double coinTraded = 0.0;

                for(int i = 0; i < recenttrades.length(); ++i)
                {
                    JSONObject trade = (JSONObject)recenttrades.get(i);

                    btcTraded += trade.getDouble("total");
                    coinTraded += trade.getDouble("amount");

                }

                Double averageTrade = btcTraded / coinTraded;


                watch.stop();
                log.info("fetched exchange rates from {}, {} chars, took {}", BITCOINAVERAGE_URL, content.length(),
                        watch);

                return averageTrade;
            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), BITCOINAVERAGE_URL);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + POLONIEX_SOURCE, x);
        }

        return null;
    }

    private Map<String, ExchangeRate> requestExchangeRatesForDashInBTC() {
        final Stopwatch watch = Stopwatch.createStarted();

        final Request.Builder request = new Request.Builder();
        request.url(BITCOINAVERAGE_DASHBTC_URL);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                pinRetryController.storeSecureTime(response.headers().getDate("date"));
                final String content = response.body().string();
                final JSONObject head = new JSONObject(content);
                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                final JSONObject averages = head.getJSONObject("averages");
                try {
                    final Fiat rate = parseFiatInexact("DASH",  averages.getString("day"));
                    if (rate.signum() > 0)
                        rates.put("DASH", new ExchangeRate(
                                new org.bitcoinj.utils.ExchangeRate(rate), BITCOINAVERAGE_SOURCE));
                } catch (final IllegalArgumentException x) {
                    log.warn("problem fetching {} exchange rate from {}: {}", "DASH",
                            BITCOINAVERAGE_DASHBTC_URL, x.getMessage());
                }

                watch.stop();
                log.info("fetched exchange rates from {}, {} chars, took {}", BITCOINAVERAGE_DASHBTC_URL, content.length(),
                        watch);

                return rates;
            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), BITCOINAVERAGE_DASHBTC_URL);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_DASHBTC_URL, x);
        }

        return null;
    }

    // backport from bitcoinj 0.15
    private static Fiat parseFiatInexact(final String currencyCode, final String str) {
        final long val = new BigDecimal(str).movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).longValue();
        return Fiat.valueOf(currencyCode, val);
    }

    //
    // ======= SIB ==========
    //

    private static Double getCoinValueBTC() {

        //final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        // Keep the LTC rate around for a bit
        Double btcRate = 0.0;
        String currencyCryptsy = CoinDefinition.cryptsyMarketCurrency;
        String urlCryptsy = "http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=" + CoinDefinition.cryptsyMarketId;

        try {
            // final String currencyCode = currencies[i];
            final URL URLCryptsy = new URL(urlCryptsy);
            final HttpURLConnection connectionCryptsy = (HttpURLConnection) URLCryptsy.openConnection();
            connectionCryptsy.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connectionCryptsy.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connectionCryptsy.connect();

            final StringBuilder contentCryptsy = new StringBuilder();

            Reader reader = null;
            try {
                reader = new InputStreamReader(new BufferedInputStream(connectionCryptsy.getInputStream(), 1024));
                Io.copy(reader, contentCryptsy);
                final JSONObject head = new JSONObject(contentCryptsy.toString());
                JSONObject returnObject = head.getJSONObject("return");
                JSONObject markets = returnObject.getJSONObject("markets");
                JSONObject coinInfo = markets.getJSONObject("DRK"/*CoinDefinition.coinTicker*/);

                JSONArray recenttrades = coinInfo.getJSONArray("recenttrades");

                double btcTraded = 0.0;
                double coinTraded = 0.0;

                for (int i = 0; i < recenttrades.length(); ++i) {
                    JSONObject trade = (JSONObject) recenttrades.get(i);

                    btcTraded += trade.getDouble("total");
                    coinTraded += trade.getDouble("quantity");

                }

                Double averageTrade = btcTraded / coinTraded;

                //Double lastTrade = GLD.getDouble("lasttradeprice");

                //String euros = String.format("%.7f", averageTrade);
                // Fix things like 3,1250
                //euros = euros.replace(",", ".");
                //rates.put(currencyCryptsy, new ExchangeRate(currencyCryptsy, Utils.toNanoCoins(euros), URLCryptsy.getHost()));
                if (currencyCryptsy.equalsIgnoreCase("BTC")) {
                    btcRate = averageTrade;
                }

            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
            return btcRate;
        } catch (final IOException x) {
            x.printStackTrace();
        } catch (final JSONException x) {
            x.printStackTrace();
        }

        return null;
    }

    private static Double getCoinValueBTC_BTER() {
        //final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        // Keep the LTC rate around for a bit
        Double btcRate = 0.0;
        String currency = CoinDefinition.cryptsyMarketCurrency;
        String url = "http://data.bter.com/api/1/ticker/" + CoinDefinition.coinTicker.toLowerCase() + "_" + CoinDefinition.cryptsyMarketCurrency.toLowerCase();

        try {
            // final String currencyCode = currencies[i];
            final URL URL_bter = new URL(url);
            final HttpURLConnection connection = (HttpURLConnection) URL_bter.openConnection();
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());
                String result = head.getString("result");
                if (result.equals("true")) {

                    Double averageTrade = head.getDouble("avg");

                    if (currency.equalsIgnoreCase("BTC")) {
                        btcRate = averageTrade;
                    }
                }
                return btcRate;
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }

        } catch (final IOException x) {
            x.printStackTrace();
        } catch (final JSONException x) {
            x.printStackTrace();
        }

        return null;
    }

    private static Double getCoinValueBTC_YOBIT() {
        Double btcRate = 0.0;
        String currency = CoinDefinition.cryptsyMarketCurrency;
        String tickerName = CoinDefinition.coinTicker.toLowerCase() + "_" + CoinDefinition.cryptsyMarketCurrency.toLowerCase();
        String url = "http://yobit.net/api/3/ticker/" + tickerName;

        try {
            final URL URL_yobit = new URL(url);
            final HttpURLConnection connection = (HttpURLConnection) URL_yobit.openConnection();
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());
                final JSONObject res = head.getJSONObject(tickerName);

                Double averageTrade = res.getDouble("avg");
                Double last = res.getDouble("last");
                Double buy = res.getDouble("buy");
                Double sell = res.getDouble("sell");
                Double current;

                if (last <= sell && last >= buy) {
                    current = (sell + buy + last) / 3;
                } else {
                    current = (sell + buy) / 2;
                }

                if (currency.equalsIgnoreCase("BTC")) {
                    btcRate = (averageTrade + current) / 2;
                }

                return btcRate;
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }

        } catch (final IOException x) {
            x.printStackTrace();
        } catch (final JSONException x) {
            x.printStackTrace();
        }

        return null;
    }

    private static Double getCoinValueBTC_BITTREX() {
        Double btcRate = 0.0;
        String currency = CoinDefinition.cryptsyMarketCurrency;
        String tickerName = CoinDefinition.cryptsyMarketCurrency.toUpperCase() + "-" + CoinDefinition.coinTicker.toUpperCase();
        String url = "https://bittrex.com/api/v1.1/public/getticker?market=" + tickerName;

        try {
            final URL URL_bittrex = new URL(url);
            final HttpURLConnection connection = (HttpURLConnection) URL_bittrex.openConnection();
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());

                if (head.getBoolean("success") == true) {

                    final JSONObject res = head.getJSONObject("result");

                    Double last = res.getDouble("Last");
                    Double buy = res.getDouble("Bid");
                    Double sell = res.getDouble("Ask");

                    if (last <= sell && last >= buy) {
                        btcRate = (sell + buy + last) / 3;
                    } else {
                        btcRate = (sell + buy) / 2;
                    }

                    return btcRate;
                }
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }

        } catch (final IOException x) {
            x.printStackTrace();
        } catch (final JSONException x) {
            x.printStackTrace();
        }

        return null;
    }

    private static Double getCoinValueBTC_LIVECOIN() {
        Double btcRate = 0.0;
        String currency = CoinDefinition.cryptsyMarketCurrency;
        String tickerName = CoinDefinition.coinTicker.toUpperCase() + "/" + CoinDefinition.cryptsyMarketCurrency.toUpperCase();
        String url = "https://api.livecoin.net/exchange/ticker?currencyPair=" + tickerName;

        try {
            final URL URL_bittrex = new URL(url);
            final HttpURLConnection connection = (HttpURLConnection) URL_bittrex.openConnection();
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());

                if (head.getDouble("last") > 0.0) {

                    Double last = head.getDouble("last");
                    Double buy = head.getDouble("best_bid");
                    Double sell = head.getDouble("best_ask");

                    if (last <= sell && last >= buy) {
                        btcRate = (sell + buy + last) / 3;
                    } else {
                        btcRate = (sell + buy) / 2;
                    }

                    return btcRate;
                }
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }

        } catch (final IOException x) {
            x.printStackTrace();
        } catch (final JSONException x) {
            x.printStackTrace();
        }

        return null;
    }
}