/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icd11;

/**
 * Constants (metadata UUIDs, names, global properties) used by the ICD-11 module.
 */
public final class ICD11DiagnosisConfig {
	
	public static final String MODULE_ID = "icd11";
	
	/**
	 * Name of the concept source holding ICD-11 MMS codes. Same name as used by CIEL so that CIEL
	 * concepts already mapped to ICD-11 are reused.
	 */
	public static final String CONCEPT_SOURCE_NAME = "ICD-11-WHO";
	
	public static final String CONCEPT_SOURCE_UUID = "521896b2-ad2b-4c73-b4ac-8875ed0a2c46";
	
	public static final String ATTR_SEARCH_QUERY_UUID = "027c5f14-10fc-4607-880a-bb4b1df21f0e";
	
	public static final String ATTR_FULL_EXPRESSION_UUID = "1b006704-d577-476f-b864-fb517a2b1e64";
	
	public static final String ATTR_FOUNDATION_URI_UUID = "80b89f9d-5d6d-4bf4-9784-7378338bf6f6";
	
	public static final String ATTR_LINEARIZATION_URI_UUID = "280f6afa-07de-4aec-a734-15490fcaaece";
	
	public static final String ATTR_SELECTED_TEXT_UUID = "b3a4e17c-cb04-4ca6-9016-86a20c151d8a";
	
	/** Encounter type used when the client does not provide an encounter. */
	public static final String GP_ENCOUNTER_TYPE_UUID = "icd11.encounterTypeUuid";
	
	/** "Visit Note" encounter type of the O3 reference application. */
	public static final String DEFAULT_ENCOUNTER_TYPE_UUID = "d7151f82-c1f3-4152-a605-2f9ea7414a79";
	
	private ICD11DiagnosisConfig() {
	}
}
