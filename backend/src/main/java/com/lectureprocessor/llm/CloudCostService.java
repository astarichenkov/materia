package com.lectureprocessor.llm;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;

/**
 * Cloud cost ESTIMATES from configurable pricing. Never hardcode a tariff: the
 * operator supplies per-million-token prices via {@code app.deepseek.pricing}.
 *
 * <p>When the cache-hit price is configured the provider-reported cache split is
 * used; otherwise all prompt tokens are billed at the standard input rate. All
 * values are estimates and are always labelled as such in the UI.</p>
 */
@Service
public class CloudCostService {

    private final AppProperties properties;

    public CloudCostService(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * @param inputTokens     total prompt tokens reported by the provider
     * @param outputTokens    completion tokens reported by the provider
     * @param cacheHitTokens  prompt tokens served from cache (0 when unknown)
     * @param cacheMissTokens prompt tokens billed at the miss rate (0 when unknown)
     */
    public CostEstimate estimate(long inputTokens, long outputTokens, long cacheHitTokens,
            long cacheMissTokens) {
        AppProperties.DeepSeek.Pricing pricing = properties.deepseek().pricing();
        boolean cacheAware = cacheHitTokens > 0 || cacheMissTokens > 0;
        long billableInput = inputTokens;
        double inputCost;
        if (cacheAware) {
            long cacheHit = cacheHitTokens;
            long cacheMiss = cacheMissTokens > 0 ? cacheMissTokens : Math.max(0, inputTokens - cacheHit);
            inputCost = cacheHit / 1_000_000.0 * pricing.cacheHitPerMillionTokens()
                    + cacheMiss / 1_000_000.0 * pricing.inputPerMillionTokens();
        } else {
            inputCost = billableInput / 1_000_000.0 * pricing.inputPerMillionTokens();
        }
        double outputCost = outputTokens / 1_000_000.0 * pricing.outputPerMillionTokens();
        double total = inputCost + outputCost;
        String currency = pricing.currency() == null || pricing.currency().isBlank() ? "USD" : pricing.currency();
        boolean priced = pricing.inputPerMillionTokens() > 0 || pricing.outputPerMillionTokens() > 0
                || pricing.cacheHitPerMillionTokens() > 0;
        return new CostEstimate(total, currency, priced, cacheAware);
    }

    public String currency() {
        String currency = properties.deepseek().pricing().currency();
        return currency == null || currency.isBlank() ? "USD" : currency;
    }

    /**
     * @param amount   estimated cost
     * @param currency ISO currency code
     * @param priced   false when no tariff is configured (amount meaningless)
     * @param cacheAware true when the provider-reported cache split was used
     */
    public record CostEstimate(double amount, String currency, boolean priced, boolean cacheAware) {

        public static CostEstimate unknown(String currency) {
            return new CostEstimate(0, currency, false, false);
        }
    }
}
