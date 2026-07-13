package dev.hylfrd.farmhelper.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperRuntimeTest {
    @Test
    void eachRuntimeOwnsIndependentMutableServices() {
        FarmHelperRuntime first = new FarmHelperRuntime();
        FarmHelperRuntime second = new FarmHelperRuntime();

        first.config().setTargetYaw(45.0F);
        first.macroManager().start();

        assertNotSame(first.config(), second.config());
        assertNotSame(first.macroManager(), second.macroManager());
        assertEquals(45.0F, first.config().targetYaw());
        assertEquals(0.0F, second.config().targetYaw());
        assertTrue(first.macroManager().enabled());
        assertFalse(second.macroManager().enabled());
    }
}
