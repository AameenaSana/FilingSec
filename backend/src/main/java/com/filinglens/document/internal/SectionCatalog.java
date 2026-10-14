package com.filinglens.document.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The standard items of each tracked form, in the order they appear. Section keys
 * are stable across filings so the same section can be compared period over
 * period: {@code 10K.ITEM_1A}, {@code 10Q.PART_II.ITEM_1A}, {@code 8K.ITEM_2.02}.
 */
final class SectionCatalog {

	record ItemDefinition(String key, String part, String item, String title) {
	}

	private static final List<ItemDefinition> TEN_K = List.of(
			item10K("I", "1", "Business"), item10K("I", "1A", "Risk Factors"),
			item10K("I", "1B", "Unresolved Staff Comments"), item10K("I", "1C", "Cybersecurity"),
			item10K("I", "2", "Properties"), item10K("I", "3", "Legal Proceedings"),
			item10K("I", "4", "Mine Safety Disclosures"),
			item10K("II", "5",
					"Market for Registrant's Common Equity, Related Stockholder Matters and Issuer Purchases of Equity Securities"),
			item10K("II", "6", "[Reserved]"),
			item10K("II", "7", "Management's Discussion and Analysis of Financial Condition and Results of Operations"),
			item10K("II", "7A", "Quantitative and Qualitative Disclosures About Market Risk"),
			item10K("II", "8", "Financial Statements and Supplementary Data"),
			item10K("II", "9", "Changes in and Disagreements with Accountants on Accounting and Financial Disclosure"),
			item10K("II", "9A", "Controls and Procedures"), item10K("II", "9B", "Other Information"),
			item10K("II", "9C", "Disclosure Regarding Foreign Jurisdictions that Prevent Inspections"),
			item10K("III", "10", "Directors, Executive Officers and Corporate Governance"),
			item10K("III", "11", "Executive Compensation"),
			item10K("III", "12",
					"Security Ownership of Certain Beneficial Owners and Management and Related Stockholder Matters"),
			item10K("III", "13", "Certain Relationships and Related Transactions, and Director Independence"),
			item10K("III", "14", "Principal Accountant Fees and Services"),
			item10K("IV", "15", "Exhibit and Financial Statement Schedules"), item10K("IV", "16", "Form 10-K Summary"));

	private static final List<ItemDefinition> TEN_Q = List.of(item10Q("I", "1", "Financial Statements"),
			item10Q("I", "2", "Management's Discussion and Analysis of Financial Condition and Results of Operations"),
			item10Q("I", "3", "Quantitative and Qualitative Disclosures About Market Risk"),
			item10Q("I", "4", "Controls and Procedures"), item10Q("II", "1", "Legal Proceedings"),
			item10Q("II", "1A", "Risk Factors"),
			item10Q("II", "2", "Unregistered Sales of Equity Securities and Use of Proceeds"),
			item10Q("II", "3", "Defaults Upon Senior Securities"), item10Q("II", "4", "Mine Safety Disclosures"),
			item10Q("II", "5", "Other Information"), item10Q("II", "6", "Exhibits"));

	private static final List<ItemDefinition> EIGHT_K = List.of(
			item8K("1.01", "Entry into a Material Definitive Agreement"),
			item8K("1.02", "Termination of a Material Definitive Agreement"), item8K("1.03", "Bankruptcy or Receivership"),
			item8K("1.04", "Mine Safety"), item8K("1.05", "Material Cybersecurity Incidents"),
			item8K("2.01", "Completion of Acquisition or Disposition of Assets"),
			item8K("2.02", "Results of Operations and Financial Condition"),
			item8K("2.03", "Creation of a Direct Financial Obligation"),
			item8K("2.04", "Triggering Events That Accelerate a Financial Obligation"),
			item8K("2.05", "Costs Associated with Exit or Disposal Activities"), item8K("2.06", "Material Impairments"),
			item8K("3.01", "Notice of Delisting or Failure to Satisfy a Listing Rule"),
			item8K("3.02", "Unregistered Sales of Equity Securities"),
			item8K("3.03", "Material Modification to Rights of Security Holders"),
			item8K("4.01", "Changes in Registrant's Certifying Accountant"),
			item8K("4.02", "Non-Reliance on Previously Issued Financial Statements"),
			item8K("5.01", "Changes in Control of Registrant"),
			item8K("5.02", "Departure or Appointment of Directors or Officers; Compensation Arrangements"),
			item8K("5.03", "Amendments to Articles of Incorporation or Bylaws; Change in Fiscal Year"),
			item8K("5.04", "Temporary Suspension of Trading Under Employee Benefit Plans"),
			item8K("5.05", "Amendments to the Code of Ethics, or Waiver"), item8K("5.06", "Change in Shell Company Status"),
			item8K("5.07", "Submission of Matters to a Vote of Security Holders"),
			item8K("5.08", "Shareholder Director Nominations"), item8K("7.01", "Regulation FD Disclosure"),
			item8K("8.01", "Other Events"), item8K("9.01", "Financial Statements and Exhibits"));

	static final String COVER_KEY = "COVER";

	static final String DOCUMENT_KEY = "DOCUMENT";

	private SectionCatalog() {
	}

	/** @param formType e.g. {@code 10-K} or {@code 10-K/A}; amendments use the base form's items */
	static List<ItemDefinition> forForm(String formType) {
		String base = formType == null ? "" : formType.replace("/A", "");
		return switch (base) {
			case "10-K" -> TEN_K;
			case "10-Q" -> TEN_Q;
			case "8-K" -> EIGHT_K;
			default -> List.of();
		};
	}

	static boolean usesParts(String formType) {
		return formType != null && formType.startsWith("10-Q");
	}

	/** @param part roman numeral, or {@code null} when the form has no parts or none was seen */
	static Optional<ItemDefinition> find(String formType, String part, String item) {
		List<ItemDefinition> items = forForm(formType);
		if (!usesParts(formType)) {
			return items.stream().filter(d -> d.item().equalsIgnoreCase(item)).findFirst();
		}
		List<ItemDefinition> matches = new ArrayList<>(items.stream().filter(d -> d.item().equalsIgnoreCase(item)).toList());
		if (part != null) {
			return matches.stream().filter(d -> d.part().equals(part)).findFirst();
		}
		// Without a part heading, an item number present in only one part is unambiguous.
		return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
	}

	static int order(String formType, String key) {
		List<ItemDefinition> items = forForm(formType);
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).key().equals(key)) {
				return i;
			}
		}
		return Integer.MAX_VALUE;
	}

	private static ItemDefinition item10K(String part, String item, String title) {
		return new ItemDefinition("10K.ITEM_" + item, part, item, title);
	}

	private static ItemDefinition item10Q(String part, String item, String title) {
		return new ItemDefinition("10Q.PART_" + part + ".ITEM_" + item, part, item, title);
	}

	private static ItemDefinition item8K(String item, String title) {
		return new ItemDefinition("8K.ITEM_" + item, null, item, title);
	}

}
