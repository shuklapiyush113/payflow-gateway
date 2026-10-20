package dev.payflow.payment.processor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.PaymentMethod;

/**
 * Factory that maps a {@link PaymentMethod} to its {@link PaymentMethodProcessor}. Spring injects
 * every processor bean, so adding a payment method means adding one class (open/closed principle).
 */
@Component
public class ProcessorRegistry {

    private final Map<PaymentMethod, PaymentMethodProcessor> processors = new EnumMap<>(PaymentMethod.class);

    public ProcessorRegistry(List<PaymentMethodProcessor> beans) {
        for (PaymentMethodProcessor processor : beans) {
            if (processors.put(processor.method(), processor) != null) {
                throw new IllegalStateException("two processors registered for " + processor.method());
            }
        }
    }

    public PaymentMethodProcessor forMethod(PaymentMethod method) {
        PaymentMethodProcessor processor = processors.get(method);
        if (processor == null) {
            throw new IllegalArgumentException("unsupported payment method " + method);
        }
        return processor;
    }
}
