package ru.boostyatv.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhoneCountryTest {
    private static final PhoneCountry RUSSIA = new PhoneCountry("RU", "+7", "Россия", "Russia");
    private static final PhoneCountry USA = new PhoneCountry("US", "+1", "США", "United States");
    private static final PhoneCountry BAHAMAS = new PhoneCountry("BS", "+1242", "Багамы", "Bahamas");
    private static final PhoneCountry UK = new PhoneCountry("GB", "+44", "Великобритания", "United Kingdom");

    @Test public void internationalNumberSelectsLongestMatchingPrefix() {
        List<PhoneCountry> countries = List.of(RUSSIA, USA, BAHAMAS, UK);
        assertEquals(BAHAMAS, PhoneCountry.fromInternational("+1 242 555 0100", USA, countries));
        assertEquals(USA, PhoneCountry.fromInternational("+1 202 555 0100", RUSSIA, countries));
        assertEquals(UK, PhoneCountry.fromInternational("+44 20 7123 4567", RUSSIA, countries));
        assertEquals(RUSSIA, PhoneCountry.fromInternational("8 999 123-45-67", RUSSIA, countries));
    }

    @Test public void stripsCountryPrefixAndFormatting() {
        assertEquals("2071234567", UK.nationalPart("+44 (20) 7123-4567"));
        assertEquals("9991234567", RUSSIA.nationalPart("+7 999 123 45 67"));
        assertEquals("999 123 45 67", RUSSIA.nationalPart("999 123 45 67"));
    }

    @Test public void matchesNameRegionAndPrefix() {
        assertTrue(RUSSIA.matches("россия"));
        assertTrue(RUSSIA.matches("ru"));
        assertTrue(RUSSIA.matches("+7"));
        assertTrue(RUSSIA.matches("russia"));
        assertFalse(RUSSIA.matches("казахстан"));
    }

    @Test public void buildsRegionalIndicatorFlag() {
        assertEquals("🇷🇺", RUSSIA.flag());
        assertEquals("🇬🇧", UK.flag());
    }
}
