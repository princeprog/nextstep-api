package com.nextstep.api.identity;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * Spring Session stores session attributes with Java serialization. Stored classes can change between releases (Spring
 * Security changes its serialization version with each minor release), so an attribute that can no longer be read is
 * dropped. The browser is then simply signed out instead of getting server errors until the session expires.
 */
@Configuration(proxyBeanMethods = false)
class SessionStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfig.class);
    private static final long WARNING_INTERVAL_MILLIS = 60_000;
    private static final AtomicLong lastWarning = new AtomicLong();

    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService() {
        DeserializingConverter deserializer = new DeserializingConverter(SessionStoreConfig.class.getClassLoader());
        GenericConversionService service = new GenericConversionService();
        service.addConverter(Object.class, byte[].class, new SerializingConverter());
        service.addConverter(byte[].class, Object.class, source -> {
            try {
                return deserializer.convert(source);
            } catch (RuntimeException ex) {
                // Each request can read an attribute several times, so warn at most once a minute.
                long now = System.currentTimeMillis();
                long last = lastWarning.get();
                if (now - last >= WARNING_INTERVAL_MILLIS && lastWarning.compareAndSet(last, now)) {
                    log.warn("Dropped stored session data that could not be read ({}); affected users are signed out",
                            ex.getClass().getSimpleName());
                }
                return null;
            }
        });
        return service;
    }
}
