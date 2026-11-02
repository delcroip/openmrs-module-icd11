/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icd11.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.icd11.api.ICD11DiagnosisService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;

import java.util.List;
import java.util.Map;

public class Icd11DiagnosisRestControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String PATIENT_UUID = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String ENCOUNTER_UUID = "6519d653-393b-4118-9c83-a3715b82d4ac";
	
	private Icd11DiagnosisRestController controller;
	
	@Before
	public void setUp() {
		Context.getService(ICD11DiagnosisService.class).ensureMetadata();
		controller = new Icd11DiagnosisRestController();
	}
	
	@Test
	public void create_shouldReturnTheSavedDiagnosis() {
		SimpleObject created = controller.create(payload());
		
		assertNotNull(created.get("uuid"));
		assertEquals("BA20.0", created.get("stemCode"));
		assertEquals("BA20.0&XB7Q.0", created.get("fullExpression"));
		assertEquals("covid pneumonia", created.get("searchQuery"));
		assertEquals("CONFIRMED", created.get("certainty"));
		assertEquals(Integer.valueOf(1), created.get("rank"));
		assertEquals(PATIENT_UUID, uuidOf(created.get("patient")));
		assertEquals(ENCOUNTER_UUID, uuidOf(created.get("encounter")));
		assertEquals("COVID-19 [BA20.0]", ((Map<?, ?>) created.get("concept")).get("display"));
	}
	
	@Test
	public void create_shouldDecodeHtmlEscapedInput() {
		// what the legacyui XSSFilter delivers for "BA20.0&XB7Q.0"
		SimpleObject body = payload();
		body.put("fullExpression", "BA20.0&amp;XB7Q.0");
		body.put("selectedText", "COVID-19 &lt;pneumonia&gt;");
		
		SimpleObject created = controller.create(body);
		
		assertEquals("BA20.0&XB7Q.0", created.get("fullExpression"));
		assertEquals("COVID-19 <pneumonia>", created.get("selectedText"));
	}
	
	@Test
	public void create_shouldFormatDateCreatedLikeOtherRestResources() {
		String dateCreated = (String) controller.create(payload()).get("dateCreated");
		
		assertTrue(dateCreated, dateCreated.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}"));
	}
	
	@Test
	public void create_shouldAcceptReferencesAsObjects() {
		SimpleObject body = payload();
		body.put("patient", new SimpleObject().add("uuid", PATIENT_UUID));
		
		assertNotNull(controller.create(body).get("uuid"));
	}
	
	@Test
	public void get_shouldReturnTheDiagnosisByUuid() {
		String uuid = (String) controller.create(payload()).get("uuid");
		
		assertEquals("COVID-19, virus identified, with pneumonia", controller.get(uuid).get("selectedText"));
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void search_shouldListDiagnosesOfThePatient() {
		String uuid = (String) controller.create(payload()).get("uuid");
		
		List<SimpleObject> results = (List<SimpleObject>) controller.search(PATIENT_UUID).get("results");
		
		assertEquals(1, results.size());
		assertEquals(uuid, results.get(0).get("uuid"));
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void delete_shouldVoidTheDiagnosis() {
		String uuid = (String) controller.create(payload()).get("uuid");
		
		controller.delete(uuid, "entered in error");
		
		assertTrue(((List<?>) controller.search(PATIENT_UUID).get("results")).isEmpty());
		controller.get(uuid);
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void get_shouldFailForUnknownUuid() {
		controller.get("does-not-exist");
	}
	
	@Test(expected = IllegalRequestException.class)
	public void create_shouldRejectUnknownPatient() {
		SimpleObject body = payload();
		body.put("patient", "does-not-exist");
		controller.create(body);
	}
	
	@Test(expected = IllegalRequestException.class)
	public void create_shouldRejectInvalidCertainty() {
		SimpleObject body = payload();
		body.put("certainty", "MAYBE");
		controller.create(body);
	}
	
	@Test(expected = IllegalRequestException.class)
	public void create_shouldRejectInvalidRank() {
		SimpleObject body = payload();
		body.put("rank", 3);
		controller.create(body);
	}
	
	@Test(expected = IllegalRequestException.class)
	public void create_shouldTurnServiceValidationErrorsIntoBadRequests() {
		SimpleObject body = payload();
		body.put("stemCode", "XB7Q.0");
		body.put("fullExpression", "XB7Q.0");
		controller.create(body);
	}
	
	private static SimpleObject payload() {
		return new SimpleObject().add("patient", PATIENT_UUID).add("encounter", ENCOUNTER_UUID).add("stemCode", "BA20.0")
		        .add("title", "COVID-19").add("searchQuery", "covid pneumonia").add("fullExpression", "BA20.0&XB7Q.0")
		        .add("foundationUri", "http://id.who.int/icd/entity/1790791774")
		        .add("linearizationUri", "http://id.who.int/icd/release/11/2026-01/mms/1790791774")
		        .add("selectedText", "COVID-19, virus identified, with pneumonia").add("certainty", "confirmed")
		        .add("rank", 1);
	}
	
	private static Object uuidOf(Object ref) {
		return ((Map<?, ?>) ref).get("uuid");
	}
}
