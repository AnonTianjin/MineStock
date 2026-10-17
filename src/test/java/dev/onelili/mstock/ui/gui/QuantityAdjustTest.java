package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「数量」按钮步进逻辑的结构与行为不变量。
 *
 * <p>用户裁决（聊天确认）：左键 +1 / 右键 -1 / Shift+左键 +10，聊天输入兜底保留。
 * 三个交易页（外汇 / 期货 / 模拟盘）共用 {@link GuiHolder#adjustQuantity} 一条实现，
 * 这里同时锁定步长语义与监听器接线，防止未来只改某一页造成三页行为分叉。
 */
class QuantityAdjustTest {

    private static final Path LISTENER =
            Path.of("src/main/java/dev/onelili/mstock/listener/TradingGuiListener.java");

    // ── 纯逻辑：步长与夹紧 ──────────────────────────────────────────────────

    private GuiHolder holderWith(double quantity) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.FOREX_TRADE);
        holder.quantity(quantity);
        return holder;
    }

    @Test
    void leftClickStepsUpByOne() {
        GuiHolder holder = holderWith(1.0);
        assertTrue(holder.adjustQuantity(true, false, false));
        assertEquals(2.0, holder.getQuantity());
    }

    @Test
    void rightClickStepsDownByOne() {
        GuiHolder holder = holderWith(5.0);
        assertTrue(holder.adjustQuantity(false, true, false));
        assertEquals(4.0, holder.getQuantity());
    }

    @Test
    void shiftLeftClickStepsUpByTen() {
        GuiHolder holder = holderWith(3.0);
        assertTrue(holder.adjustQuantity(true, false, true));
        assertEquals(13.0, holder.getQuantity());
    }

    @Test
    void shiftRightClickStepsDownByTen() {
        GuiHolder holder = holderWith(15.0);
        assertTrue(holder.adjustQuantity(false, true, true));
        assertEquals(5.0, holder.getQuantity());
    }

    @Test
    void quantityNeverDropsBelowOne() {
        GuiHolder holder = holderWith(1.0);
        assertTrue(holder.adjustQuantity(false, true, false));
        assertEquals(GuiHolder.MIN_QUANTITY, holder.getQuantity());
    }

    @Test
    void quantityIsCappedAtMax() {
        GuiHolder holder = holderWith(GuiHolder.MAX_QUANTITY);
        assertTrue(holder.adjustQuantity(true, false, true));
        assertEquals(GuiHolder.MAX_QUANTITY, holder.getQuantity());
    }

    @Test
    void foreignClickTypesAreRejectedSoListenerFallsBackToChat() {
        // 中键 / 数字键 / 丢弃等非左右键点击：不步进，返回 false => 监听器走聊天输入兜底
        GuiHolder holder = holderWith(4.0);
        assertFalse(holder.adjustQuantity(false, false, false));
        assertFalse(holder.adjustQuantity(false, false, true)); // isShiftClick 但非左右键
        assertEquals(4.0, holder.getQuantity());
    }

    @Test
    void decimalQuantityIsPreservedByIntegerSteps() {
        GuiHolder holder = holderWith(2.5);
        assertTrue(holder.adjustQuantity(true, false, false));
        assertEquals(3.5, holder.getQuantity());
        assertTrue(holder.adjustQuantity(false, true, false));
        assertEquals(2.5, holder.getQuantity());
    }

    // ── 结构：三个交易页必须走同一条共享实现，且兜底通道保留 ────────────────

    @Test
    void listenerRoutesAllThreeTradePagesThroughSharedHandler() throws Exception {
        assertTrue(JavaSource.isReadable(LISTENER), "读不到 TradingGuiListener.java；本测试依赖 CWD = 项目根");
        List<String> lines = JavaSource.read(LISTENER);
        String body = JavaSource.methodBody(lines, "private void handleQuantityClick");
        assertTrue(body != null, "TradingGuiListener 必须保留共享入口 handleQuantityClick");
        // 必须调用 GuiHolder 的共享步进实现
        assertTrue(body.contains("adjustQuantity("),
                "handleQuantityClick 必须经由 GuiHolder.adjustQuantity 步进，不得各页自算");
        // 步进失败必须回落聊天输入通道
        assertTrue(JavaSource.appearsBefore(body, "adjustQuantity(", "openChatInput.run()"),
                "数量按钮步进失败时必须回落 openChatInput（聊天输入兜底是用户裁决）");
    }

    @Test
    void allThreeTradePagesDelegateQuantitySlotToSharedHandler() throws Exception {
        List<String> lines = JavaSource.read(LISTENER);
        for (String handler : new String[] {
                "private void handleForexTrade(Player player, GuiHolder holder, int slot, ClickType click)",
                "private void handleFutures(Player player, GuiHolder holder, int slot, ClickType click)",
                "private void handleSimulationTrade(Player player, GuiHolder holder, int slot, ClickType click)"}) {
            String body = JavaSource.methodBody(lines, handler);
            assertTrue(body != null, "找不到交易页处理器签名（可能被改动）: " + handler);
            assertTrue(body.contains("handleQuantityClick("),
                    "交易页处理器必须复用共享数量入口: " + handler);
        }
    }

    @Test
    void listenerDerivesClickGesturesFromClickType() throws Exception {
        List<String> lines = JavaSource.read(LISTENER);
        String body = JavaSource.methodBody(lines, "public void onClick(InventoryClickEvent event)");
        assertTrue(body != null, "找不到 onClick");
        assertTrue(body.contains("event.getClick()"),
                "onClick 必须用 event.getClick() 取点击方式（ClickType 布尔三态有重叠语义，禁止散落判断）");
    }
}
