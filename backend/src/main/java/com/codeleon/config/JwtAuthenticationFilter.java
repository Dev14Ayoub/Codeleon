package com.codeleon.config;

import com.codeleon.user.User;
import com.codeleon.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserService userService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        // Any failure parsing or validating the JWT (expired, signature
        // mismatch, malformed token, missing user) must NOT abort the
        // request - Spring Security would otherwise route the unhandled
        // exception through the OAuth2 authentication entry point and
        // 302-redirect to /login, which breaks every permitAll endpoint
        // (e.g. /auth/providers) whenever the browser sends a stale
        // Bearer header from a previous session. Instead we log at debug
        // level and let the request proceed without a SecurityContext;
        // protected endpoints then return a clean 401 and public ones
        // keep serving normally.
        try {
            String email = jwtService.extractEmail(token);
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                User user = (User) userService.loadUserByUsername(email);
                if (jwtService.isValid(token, user)) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            user.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (UsernameNotFoundException ex) {
            log.debug("JWT email did not match a known user: {}", ex.getMessage());
        } catch (RuntimeException ex) {
            // Covers ExpiredJwtException, MalformedJwtException,
            // SignatureException, UnsupportedJwtException, etc.
            log.debug("Discarding invalid JWT in Authorization header: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
