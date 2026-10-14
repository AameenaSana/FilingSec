/**
 * AI: access to the large language model used to generate answers and
 * interpretations, and per-user usage accounting.
 * <p>
 * Every request is grounded: callers pass source passages as citable search
 * results, and responses carry citations to exact passage blocks. Output of this
 * module is always presented to users as AI-generated.
 */
@ApplicationModule(displayName = "AI")
package com.filinglens.ai;

import org.springframework.modulith.ApplicationModule;
