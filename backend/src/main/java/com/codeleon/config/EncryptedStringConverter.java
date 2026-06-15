package com.codeleon.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA converter that transparently encrypts a String column at rest via
 * {@link TokenCipher}. Applied explicitly with {@code @Convert} (not
 * auto-applied) to the columns that hold secrets.
 *
 * <p>JPA may instantiate converters outside the Spring context, so the cipher
 * is bridged in through a static reference populated by the Spring-managed
 * instance. If, for any reason, the cipher is not yet wired, the converter
 * degrades to a pass-through rather than corrupting data.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static volatile TokenCipher cipher;

    @Autowired
    public void setCipher(TokenCipher cipher) {
        EncryptedStringConverter.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        TokenCipher c = cipher;
        return (c == null || attribute == null) ? attribute : c.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        TokenCipher c = cipher;
        return (c == null || dbData == null) ? dbData : c.decrypt(dbData);
    }
}
