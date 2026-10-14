/**
 * Identity: registration, email verification, login sessions, password reset and
 * account management.
 * <p>
 * Public API: {@link com.filinglens.identity.UserPrincipal}, the authenticated
 * principal other modules receive via {@code @AuthenticationPrincipal}, and
 * {@link com.filinglens.identity.UserDirectory} for contact details.
 */
@ApplicationModule(displayName = "Identity")
package com.filinglens.identity;

import org.springframework.modulith.ApplicationModule;
