/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icd11.api;

import org.openmrs.Concept;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.APIException;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.icd11.api.model.Icd11Entity;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Records ICD-11 diagnoses as core {@link Diagnosis} objects coded to an on-demand "stem code"
 * concept, with the rich Embedded Coding Tool data stored as diagnosis attributes.
 */
@Transactional
public interface ICD11DiagnosisService extends OpenmrsService {
	
	/**
	 * Creates the concept source and diagnosis attribute types used by this module if missing.
	 * Idempotent.
	 */
	void ensureMetadata() throws APIException;
	
	/**
	 * Returns the non-retired concept mapped SAME-AS to the given ICD-11 stem code (the oldest one if
	 * several exist), creating a Diagnosis-class concept and its mapping if none exists. Concept
	 * creation runs with proxy privileges, so callers only need to record diagnoses.
	 * 
	 * @param stemCode an ICD-11 MMS stem code, e.g. BA20.0
	 * @param title the ICD-11 title of the stem code, used to name a new concept
	 */
	@Authorized(PrivilegeConstants.EDIT_DIAGNOSES)
	Concept getOrCreateStemConcept(String stemCode, String title) throws APIException;
	
	/**
	 * Saves a diagnosis for the given ICD-11 entity. When encounter is null, a new encounter is created
	 * (type from the icd11.encounterTypeUuid global property) in the given visit, or the patient's
	 * active visit.
	 * 
	 * @param certainty defaults to PROVISIONAL
	 * @param rank 1 for primary, 2 for secondary; defaults to 1
	 */
	@Authorized({ PrivilegeConstants.EDIT_DIAGNOSES })
	Diagnosis saveIcd11Diagnosis(Patient patient, Encounter encounter, Visit visit, Location location, Icd11Entity entity,
	        ConditionVerificationStatus certainty, Integer rank) throws APIException;
	
	/**
	 * @return the diagnosis if it is an ICD-11 diagnosis recorded by this module, null otherwise
	 */
	@Authorized(PrivilegeConstants.GET_DIAGNOSES)
	@Transactional(readOnly = true)
	Diagnosis getIcd11DiagnosisByUuid(String uuid) throws APIException;
	
	/**
	 * @return the non-voided ICD-11 diagnoses of the patient, most recent first
	 */
	@Authorized(PrivilegeConstants.GET_DIAGNOSES)
	@Transactional(readOnly = true)
	List<Diagnosis> getIcd11Diagnoses(Patient patient) throws APIException;
	
	/**
	 * @return the ICD-11 fields of the diagnosis, keyed by {@link Icd11Entity} property name
	 */
	@Authorized(PrivilegeConstants.GET_DIAGNOSES)
	@Transactional(readOnly = true)
	Map<String, String> getIcd11Fields(Diagnosis diagnosis) throws APIException;
	
	@Authorized(PrivilegeConstants.DELETE_DIAGNOSES)
	Diagnosis voidIcd11Diagnosis(Diagnosis diagnosis, String reason) throws APIException;
}
