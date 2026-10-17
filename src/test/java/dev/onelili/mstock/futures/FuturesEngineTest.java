package dev.onelili.mstock.futures;

import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.TradeDirection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuturesEngineTest {

    private static final double EPS = 1e-6;
    private final FuturesEngine engine = new FuturesEngine();

    /** 0.1 的初始保证金率即 10 倍杠杆。 */
    private static FuturesContract contract() {
        return new FuturesContract("EURUSD", "2026-12-31", 100000, 0.00001, 0.1);
    }

    private static FuturesPosition position(TradeDirection direction, double entry,
                                            double quantity, double margin) {
        return new FuturesPosition(1L, UUID.randomUUID(), "EURUSD", direction, quantity,
                entry, 100000, 0.1, margin, "2026-12-31", entry, 0.0,
                System.currentTimeMillis());
    }

    @Test
    void derivesLeverageFromInitialMarginRate() {
        assertEquals(10, contract().getLeverage());
        assertEquals(50, new FuturesContract("X", "2026-12-31", 1, 0.01, 0.02).getLeverage());
        assertEquals(1, new FuturesContract("X", "2026-12-31", 1, 0.01, 1.0).getLeverage());
    }

    @Test
    void requiresNotionalTimesInitialMarginRateAsMargin() {
        // 1.10 × 100000 × 1 × 0.1 = 11000
        assertEquals(11000.0, engine.requiredMargin(contract(), 1.10, 1), EPS);
        // 数量 2 手 → 22000
        assertEquals(22000.0, engine.requiredMargin(contract(), 1.10, 2), EPS);
    }

    @Test
    void settlementPnlUsesContractSizeAndDirection() {
        FuturesPosition longPosition = position(TradeDirection.LONG, 1.10, 1, 11000);
        FuturesPosition shortPosition = position(TradeDirection.SHORT, 1.10, 1, 11000);

        // 结算价从 1.10 涨到 1.11：涨 0.01 × 100000 × 1 = 1000
        assertEquals(1000.0, engine.settlementPnl(longPosition, 1.11), EPS);
        assertEquals(-1000.0, engine.settlementPnl(shortPosition, 1.11), EPS);

        // 跌到 1.09
        assertEquals(-1000.0, engine.settlementPnl(longPosition, 1.09), EPS);
        assertEquals(1000.0, engine.settlementPnl(shortPosition, 1.09), EPS);
    }

    @Test
    void settlementUsesLastSettlePriceAfterFirstMark() {
        FuturesPosition position = position(TradeDirection.LONG, 1.10, 1, 11000);
        position.setLastSettlePrice(1.11);
        position.setRealizedPnl(1000.0);

        // 第二次盯市从 1.11 到 1.12，只结算增量
        assertEquals(1000.0, engine.settlementPnl(position, 1.12), EPS);
        assertEquals(2000.0, position.getRealizedPnl() + 1000.0, EPS);
    }

    @Test
    void closePayoutIsMarginPlusTotalPnlAndNeverNegative() {
        FuturesPosition position = position(TradeDirection.LONG, 1.10, 1, 11000);

        // 涨到 1.12：11000 + (0.02 × 100000) = 13000
        assertEquals(13000.0, engine.closePayout(position, 1.12), EPS);
        // 暴跌到 0.80：亏损超过保证金，只退 0
        assertEquals(0.0, engine.closePayout(position, 0.80), EPS);
    }

    @Test
    void expiryIsDetectedOnAndAfterExpiryDate() {
        FuturesContract contract = contract();
        LocalDate expiry = LocalDate.parse("2026-12-31");

        assertFalse(engine.isExpired(contract, expiry.minusDays(1)));
        assertTrue(engine.isExpired(contract, expiry));
        assertTrue(engine.isExpired(contract, expiry.plusDays(1)));
    }

    @Test
    void settlePricePrefersLastClosedDailyBarOverLiveMid() {
        // 日结必须用已收盘 bar；limit=1 会拿到未收盘的当日 bar。
        assertEquals(1.1050, engine.chooseSettlePrice(1.1050, 1.1238), EPS);
        // 没有已收盘 bar 时才退回最新 mid
        assertEquals(1.1238, engine.chooseSettlePrice(null, 1.1238), EPS);
        assertEquals(0.0, engine.chooseSettlePrice(null, null), EPS);
    }

    @Test
    void nextQuarterEndIsStrictlyAfterTheGivenDay() {
        assertEquals(LocalDate.of(2026, 3, 31),
                FuturesContractRegistry.nextQuarterEnd(LocalDate.of(2026, 1, 15)));
        assertEquals(LocalDate.of(2026, 6, 30),
                FuturesContractRegistry.nextQuarterEnd(LocalDate.of(2026, 4, 1)));
        // 季末当天派生的合约不能"一出生就到期"，因此跳过当天取下一个季末
        assertEquals(LocalDate.of(2026, 9, 30),
                FuturesContractRegistry.nextQuarterEnd(LocalDate.of(2026, 6, 30)));
        // 已过 12 月末 → 进入下一年 3 月末
        assertEquals(LocalDate.of(2027, 3, 31),
                FuturesContractRegistry.nextQuarterEnd(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void fallbackRegistryCoversSeveralAssetClasses() {
        Map<String, FuturesContract> fallback =
                FuturesContractRegistry.fallback(0.1, LocalDate.of(2026, 1, 1));

        assertTrue(fallback.containsKey("EURUSD"));
        assertTrue(fallback.containsKey("XAUUSD"));
        assertTrue(fallback.containsKey("BTCUSD"));
        assertEquals(10, fallback.get("EURUSD").getLeverage());
        assertEquals("2026-03-31", fallback.get("EURUSD").getExpiryDate());
        // 兜底合约必须立即可交易，不能一出生就是到期状态
        assertFalse(fallback.get("EURUSD").isExpired(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void configuredContractsKeepTheirOwnExpiryAndMarginRate() {
        Map<String, FuturesContract> fromConfig = FuturesContractRegistry.fromConfig(
                java.util.List.of(java.util.Map.of(
                        "symbol", "eurusd",
                        "expiry-date", "2026-09-30",
                        "contract-size", 100000,
                        "tick-size", 0.00001,
                        "initial-margin-rate", 0.01)),
                0.1);

        FuturesContract contract = fromConfig.get("EURUSD");
        assertEquals("2026-09-30", contract.getExpiryDate());
        assertEquals(0.01, contract.getInitialMarginRate(), EPS);
        assertEquals(100, contract.getLeverage());
    }
}
