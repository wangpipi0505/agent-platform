package cn.agentplatform.common.authority;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.Objects;

/**
 * EX-43 §3.1 fixed chain order.
 *
 * <p>The {@code /internal/**} chain must be consulted before the general application chain; relying
 * on each service to remember {@code @Order} on its beans is a bypass waiting to happen. The common
 * base therefore returns this wrapper with an order lower than any default chain, so chain 1 wins
 * for {@code /internal/**} regardless of bean declaration order.
 */
public final class ServiceTrustChain implements SecurityFilterChain, Ordered {
    public static final int ORDER = -100;

    private final SecurityFilterChain delegate;
    private final RequestMatcher additional;

    public ServiceTrustChain(SecurityFilterChain delegate) {
        this(delegate, request -> false);
    }

    public ServiceTrustChain(SecurityFilterChain delegate, RequestMatcher additional) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.additional = Objects.requireNonNull(additional, "additional");
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return delegate.matches(request) || additional.matches(request);
    }

    @Override
    public List<Filter> getFilters() {
        return delegate.getFilters();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
