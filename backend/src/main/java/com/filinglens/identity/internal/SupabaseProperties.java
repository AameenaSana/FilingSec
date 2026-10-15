package com.filinglens.identity.internal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in through Supabase Auth. Both values are public (the same ones the browser
 * uses); without them the feature is off.
 *
 * @param url the project URL, e.g. {@code https://abcd.supabase.co}
 * @param publishableKey the project's publishable (or legacy anon) key
 * @param providers the Supabase auth providers accepted for sign-in
 */
@ConfigurationProperties(prefix = "filinglens.identity.supabase")
record SupabaseProperties(String url, String publishableKey, List<String> providers) {

	SupabaseProperties {
		url = url == null ? "" : url.strip().replaceAll("/+$", "");
		publishableKey = publishableKey == null ? "" : publishableKey.strip();
		providers = providers == null ? List.of() : List.copyOf(providers);
	}

	boolean configured() {
		return !url.isEmpty() && !publishableKey.isEmpty();
	}

}
