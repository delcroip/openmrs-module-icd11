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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.ConceptService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.icd11.ICD11DiagnosisConfig;
import org.openmrs.module.icd11.api.model.Icd11Entity;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

import java.util.Map;

/**
 * Integration tests against the in-memory database and the core standard test dataset.
 */
public class ICD11DiagnosisServiceTest extends BaseModuleContextSensitiveTest {
	
	/** Patient 7 of the standard test dataset, owner of encounter 3. */
	private static final String PATIENT_UUID = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String ENCOUNTER_UUID = "6519d653-393b-4118-9c83-a3715b82d4ac";
	
	/** Patient 2 of the standard test dataset, does not own encounter 3. */
	private static final String OTHER_PATIENT_UUID = "da7f524f-27ce-4bb2-86d6-6d1d05312bd5";
	
	/** "Scheduled" encounter type of the standard test dataset. */
	private static final String SCHEDULED_ENCOUNTER_TYPE_UUID = "61ae96f4-6afe-4351-b6f8-cd4fc383cce1";
	
	private ICD11DiagnosisService service;
	
	private ConceptService conceptService;
	
	@Before
	public void setUp() {
		service = Context.getService(ICD11DiagnosisService.class);
		conceptService = Context.getConceptService();
		service.ensureMetadata();
	}
	
	@Test
	public void ensureMetadata_shouldCreateSourceAndAttributeTypesIdempotently() {
		service.ensureMetadata();
		
		assertNotNull(conceptService.getConceptSourceByName(ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME));
		for (String uuid : new String[] { ICD11DiagnosisConfig.ATTR_SEARCH_QUERY_UUID,
		        ICD11DiagnosisConfig.ATTR_FULL_EXPRESSION_UUID, ICD11DiagnosisConfig.ATTR_FOUNDATION_URI_UUID,
		        ICD11DiagnosisConfig.ATTR_LINEARIZATION_URI_UUID, ICD11DiagnosisConfig.ATTR_SELECTED_TEXT_UUID }) {
			assertNotNull(uuid, Context.getDiagnosisService().getDiagnosisAttributeTypeByUuid(uuid));
		}
	}
	
	@Test
	public void getOrCreateStemConcept_shouldCreateMappedDiagnosisConcept() {
		Concept concept = service.getOrCreateStemConcept("BA20.0", "COVID-19");
		
		assertNotNull(concept.getConceptId());
		assertEquals("Diagnosis", concept.getConceptClass().getName());
		assertEquals("N/A", concept.getDatatype().getName());
		assertEquals("COVID-19 [BA20.0]", concept.getFullySpecifiedName(Context.getLocale()).getName());
		assertEquals("BA20.0", concept.getShortNameInLocale(Context.getLocale()).getName());
		assertEquals(1, concept.getConceptMappings().size());
		ConceptMap map = concept.getConceptMappings().iterator().next();
		assertEquals(ConceptMapType.SAME_AS_MAP_TYPE_UUID, map.getConceptMapType().getUuid());
		assertEquals("BA20.0", map.getConceptReferenceTerm().getCode());
		assertEquals(ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME, map.getConceptReferenceTerm().getConceptSource().getName());
	}
	
	@Test
	public void getOrCreateStemConcept_shouldReturnTheSameConceptOnSecondCall() {
		Concept first = service.getOrCreateStemConcept("BA20.0", "COVID-19");
		Concept second = service.getOrCreateStemConcept(" ba20.0 ", "a different title");
		
		assertEquals(first.getConceptId(), second.getConceptId());
	}
	
	@Test
	public void getOrCreateStemConcept_shouldReuseAnExistingSameAsMappedConcept() {
		Concept existing = conceptService.getConcept(5497);
		existing.addConceptMapping(
		    new ConceptMap(newTerm("1A00"), conceptService.getConceptMapTypeByUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID)));
		conceptService.saveConcept(existing);
		
		assertEquals(existing.getConceptId(), service.getOrCreateStemConcept("1A00", "Cholera").getConceptId());
	}
	
	@Test
	public void getOrCreateStemConcept_shouldNotReuseAConceptWithANonSameAsMapping() {
		Concept existing = conceptService.getConcept(5497);
		existing.addConceptMapping(new ConceptMap(newTerm("1A00"), conceptService.getConceptMapTypeByName("related-to")));
		conceptService.saveConcept(existing);
		
		assertNotEquals(existing.getConceptId(), service.getOrCreateStemConcept("1A00", "Cholera").getConceptId());
	}
	
	@Test(expected = APIException.class)
	public void getOrCreateStemConcept_shouldRejectAnExtensionCode() {
		service.getOrCreateStemConcept("XB7Q.0", "Pneumonia");
	}
	
	@Test
	public void getOrCreateStemConcept_shouldWorkForAUserWithoutConceptPrivileges() {
		becomeClinician();
		assertFalse(Context.hasPrivilege(PrivilegeConstants.MANAGE_CONCEPTS));
		
		Concept concept = service.getOrCreateStemConcept("CA40.0", "Bacterial pneumonia");
		
		assertNotNull(concept.getConceptId());
		assertFalse("proxy privileges must be removed afterwards", Context.hasPrivilege(PrivilegeConstants.MANAGE_CONCEPTS));
	}
	
	@Test
	public void saveIcd11Diagnosis_shouldSaveCodedDiagnosisWithAttributes() {
		Patient patient = patient(PATIENT_UUID);
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_UUID);
		
		Diagnosis diagnosis = service.saveIcd11Diagnosis(patient, encounter, null, null, covidEntity(), null, null);
		
		assertNotNull(diagnosis.getId());
		assertEquals(encounter, diagnosis.getEncounter());
		assertEquals(patient, diagnosis.getPatient());
		assertEquals(ConditionVerificationStatus.PROVISIONAL, diagnosis.getCertainty());
		assertEquals(Integer.valueOf(1), diagnosis.getRank());
		assertEquals("BA20.0", diagnosis.getDiagnosis().getCoded().getShortNameInLocale(Context.getLocale()).getName());
		
		Map<String, String> fields = service.getIcd11Fields(diagnosis);
		assertEquals("BA20.0", fields.get("stemCode"));
		assertEquals("BA20.0&XB7Q.0", fields.get("fullExpression"));
		assertEquals("covid pneumonia", fields.get("searchQuery"));
		assertEquals("http://id.who.int/icd/entity/1790791774", fields.get("foundationUri"));
		assertEquals("http://id.who.int/icd/release/11/2026-01/mms/1790791774", fields.get("linearizationUri"));
		assertEquals("COVID-19, virus identified, with pneumonia", fields.get("selectedText"));
	}
	
	@Test
	public void saveIcd11Diagnosis_shouldHonourCertaintyAndRank() {
		Diagnosis diagnosis = service.saveIcd11Diagnosis(patient(PATIENT_UUID), encounter(), null, null, covidEntity(),
		    ConditionVerificationStatus.CONFIRMED, 2);
		
		assertEquals(ConditionVerificationStatus.CONFIRMED, diagnosis.getCertainty());
		assertEquals(Integer.valueOf(2), diagnosis.getRank());
	}
	
	@Test
	public void saveIcd11Diagnosis_shouldDeriveStemCodeFromFullExpression() {
		Icd11Entity entity = covidEntity();
		entity.setStemCode(null);
		
		Diagnosis diagnosis = service.saveIcd11Diagnosis(patient(PATIENT_UUID), encounter(), null, null, entity, null, null);
		
		assertEquals("BA20.0", diagnosis.getDiagnosis().getCoded().getShortNameInLocale(Context.getLocale()).getName());
	}
	
	@Test(expected = APIException.class)
	public void saveIcd11Diagnosis_shouldRejectAStemCodeThatDoesNotMatchTheExpression() {
		Icd11Entity entity = covidEntity();
		entity.setStemCode("CA40.0");
		
		service.saveIcd11Diagnosis(patient(PATIENT_UUID), encounter(), null, null, entity, null, null);
	}
	
	@Test(expected = APIException.class)
	public void saveIcd11Diagnosis_shouldRejectAnEncounterOfAnotherPatient() {
		service.saveIcd11Diagnosis(patient(OTHER_PATIENT_UUID), encounter(), null, null, covidEntity(), null, null);
	}
	
	@Test
	public void saveIcd11Diagnosis_shouldCreateAnEncounterWhenNoneIsGiven() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ICD11DiagnosisConfig.GP_ENCOUNTER_TYPE_UUID, SCHEDULED_ENCOUNTER_TYPE_UUID));
		Patient patient = patient(OTHER_PATIENT_UUID);
		
		Diagnosis diagnosis = service.saveIcd11Diagnosis(patient, null, null, null, covidEntity(), null, null);
		
		Encounter encounter = diagnosis.getEncounter();
		assertNotNull(encounter.getId());
		assertEquals(patient, encounter.getPatient());
		assertEquals(SCHEDULED_ENCOUNTER_TYPE_UUID, encounter.getEncounterType().getUuid());
		assertNotNull(encounter.getLocation());
	}
	
	@Test(expected = APIException.class)
	public void saveIcd11Diagnosis_shouldFailClearlyWhenTheEncounterTypeIsMissing() {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(ICD11DiagnosisConfig.GP_ENCOUNTER_TYPE_UUID, "missing-type"));
		
		service.saveIcd11Diagnosis(patient(PATIENT_UUID), null, null, null, covidEntity(), null, null);
	}
	
	@Test
	public void getIcd11Diagnoses_shouldListOnlyNonVoidedIcd11Diagnoses() {
		Patient patient = patient(PATIENT_UUID);
		Diagnosis kept = service.saveIcd11Diagnosis(patient, encounter(), null, null, covidEntity(), null, null);
		Diagnosis voided = service.saveIcd11Diagnosis(patient, encounter(), null, null, covidEntity(), null, null);
		service.voidIcd11Diagnosis(voided, "entered in error");
		
		assertThat(service.getIcd11Diagnoses(patient), contains(kept));
		assertThat(service.getIcd11Diagnoses(patient), not(hasItem(voided)));
	}
	
	@Test
	public void getIcd11DiagnosisByUuid_shouldReturnNullForNonIcd11Diagnosis() {
		Patient patient = patient(PATIENT_UUID);
		Diagnosis plain = new Diagnosis(encounter(), new org.openmrs.CodedOrFreeText(null, null, "free text"),
		        ConditionVerificationStatus.CONFIRMED, 1, patient);
		Context.getDiagnosisService().save(plain);
		Diagnosis icd11 = service.saveIcd11Diagnosis(patient, encounter(), null, null, covidEntity(), null, null);
		
		assertNull(service.getIcd11DiagnosisByUuid(plain.getUuid()));
		assertEquals(icd11, service.getIcd11DiagnosisByUuid(icd11.getUuid()));
		assertTrue(service.getIcd11Diagnoses(patient).size() == 1);
	}
	
	private static Icd11Entity covidEntity() {
		Icd11Entity entity = new Icd11Entity();
		entity.setStemCode("BA20.0");
		entity.setTitle("COVID-19, virus identified");
		entity.setSearchQuery("covid pneumonia");
		entity.setFullExpression("BA20.0&XB7Q.0");
		entity.setFoundationUri("http://id.who.int/icd/entity/1790791774");
		entity.setLinearizationUri("http://id.who.int/icd/release/11/2026-01/mms/1790791774");
		entity.setSelectedText("COVID-19, virus identified, with pneumonia");
		return entity;
	}
	
	private static Patient patient(String uuid) {
		return Context.getPatientService().getPatientByUuid(uuid);
	}
	
	private static Encounter encounter() {
		return Context.getEncounterService().getEncounterByUuid(ENCOUNTER_UUID);
	}
	
	private ConceptReferenceTerm newTerm(String code) {
		ConceptSource source = conceptService.getConceptSourceByName(ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME);
		return conceptService.saveConceptReferenceTerm(new ConceptReferenceTerm(source, code, null));
	}
	
	/**
	 * Switches to a user who may record diagnoses but has no dictionary privileges.
	 */
	private void becomeClinician() {
		UserService userService = Context.getUserService();
		Role role = new Role("ICD-11 test clinician");
		role.setDescription("test");
		for (String name : new String[] { PrivilegeConstants.EDIT_DIAGNOSES, PrivilegeConstants.GET_DIAGNOSES }) {
			Privilege privilege = userService.getPrivilege(name);
			if (privilege == null) {
				privilege = userService.savePrivilege(new Privilege(name, name));
			}
			role.addPrivilege(privilege);
		}
		userService.saveRole(role);
		
		Person person = new Person();
		person.setGender("F");
		person.addName(new PersonName("Ada", null, "Clinician"));
		User user = new User(person);
		user.setUsername("icd11clinician");
		user.addRole(role);
		userService.createUser(user, "Clinician123");
		Context.becomeUser("icd11clinician");
	}
}
