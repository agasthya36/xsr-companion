package com.xsr.companion.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuthValidationTest {

    @Test
    public void validEmailsAccepted() {
        assertTrue(AuthManager.isValidEmail("rider@example.com"));
        assertTrue(AuthManager.isValidEmail("user.name+tag@domain.co.uk"));
        assertTrue(AuthManager.isValidEmail("rider155@yamaha-xsr.club"));
    }

    @Test
    public void invalidEmailsRejected() {
        assertFalse(AuthManager.isValidEmail(null));
        assertFalse(AuthManager.isValidEmail(""));
        assertFalse(AuthManager.isValidEmail("   "));
        assertFalse(AuthManager.isValidEmail("plainaddress"));
        assertFalse(AuthManager.isValidEmail("@missingusername.com"));
        assertFalse(AuthManager.isValidEmail("username@.com"));
    }
}
