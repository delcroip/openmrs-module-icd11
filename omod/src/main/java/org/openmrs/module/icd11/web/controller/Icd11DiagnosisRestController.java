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

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.icd11.api.ICD11DiagnosisService;
import org.openmrs.module.icd11.api.model.Icd11Entity;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST API for ICD-11 diagnoses, served at /ws/rest/v1/icd11/diagnosis.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/icd11/diagnosis")
public class Icd11DiagnosisRestController extends BaseRestController {
	
	@RequestMapping(method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	@ResponseBody
	public SimpleObject create(@RequestBody SimpleObject body) {
		Patient patient = required(Context.getPatientService().getPatientByUuid(text(body, "patient")), "patient");
		Encounter encounter = optional(text(body, "encounter"),
		    uuid -> Context.getEncounterService().getEncounterByUuid(uuid), "encounter");
		Visit visit = optional(text(body, "visit"), uuid -> Context.getVisitService().getVisitByUuid(uuid), "visit");
		Location location = optional(text(body, "location"), uuid -> Context.getLocationService().getLocationByUuid(uuid),
		    "location");
		
		Icd11Entity entity = new Icd11Entity();
		entity.setStemCode(text(body, "stemCode"));
		entity.setTitle(text(body, "title"));
		entity.setSearchQuery(text(body, "searchQuery"));
		entity.setFullExpression(text(body, "fullExpression"));
		entity.setFoundationUri(text(body, "foundationUri"));
		entity.setLinearizationUri(text(body, "linearizationUri"));
		entity.setSelectedText(text(body, "selectedText"));
		
		ConditionVerificationStatus certainty = null;
		String certaintyText = text(body, "certainty");
		if (certaintyText != null) {
			try {
				certainty = ConditionVerificationStatus.valueOf(certaintyText.toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new IllegalRequestException("certainty must be PROVISIONAL or CONFIRMED");
			}
		}
		Integer rank = null;
		String rankText = text(body, "rank");
		if (rankText != null) {
			try {
				rank = Integer.valueOf(rankText);
			} catch (NumberFormatException e) {
				throw new IllegalRequestException("rank must be 1 (primary) or 2 (secondary)");
			}
			if (rank != 1 && rank != 2) {
				throw new IllegalRequestException("rank must be 1 (primary) or 2 (secondary)");
			}
		}
		
		try {
			return toRepresentation(
			    getService().saveIcd11Diagnosis(patient, encounter, visit, location, entity, certainty, rank));
		} catch (APIAuthenticationException e) {
			throw e;
		} catch (APIException e) {
			throw new IllegalRequestException(e.getMessage(), e);
		}
	}
	
	@RequestMapping(value = "/{uuid}", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@PathVariable("uuid") String uuid) {
		return toRepresentation(getRequiredDiagnosis(uuid));
	}
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject search(@RequestParam("patient") String patientUuid) {
		Patient patient = required(Context.getPatientService().getPatientByUuid(patientUuid), "patient");
		List<SimpleObject> results = new ArrayList<>();
		for (Diagnosis diagnosis : getService().getIcd11Diagnoses(patient)) {
			results.add(toRepresentation(diagnosis));
		}
		return new SimpleObject().add("results", results);
	}
	
	@RequestMapping(value = "/{uuid}", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable("uuid") String uuid,
	        @RequestParam(value = "reason", defaultValue = "Voided via ICD-11 REST API") String reason) {
		getService().voidIcd11Diagnosis(getRequiredDiagnosis(uuid), reason);
	}
	
	private SimpleObject toRepresentation(Diagnosis diagnosis) {
		Concept concept = diagnosis.getDiagnosis().getCoded();
		SimpleObject result = new SimpleObject();
		result.add("uuid", diagnosis.getUuid());
		result.add("patient", ref(diagnosis.getPatient().getUuid(), null));
		result.add("encounter", ref(diagnosis.getEncounter().getUuid(), null));
		result.add("concept", ref(concept.getUuid(), concept.getDisplayString()));
		result.add("certainty", diagnosis.getCertainty() != null ? diagnosis.getCertainty().name() : null);
		result.add("rank", diagnosis.getRank());
		for (Map.Entry<String, String> field : getService().getIcd11Fields(diagnosis).entrySet()) {
			result.add(field.getKey(), field.getValue());
		}
		result.add("dateCreated", ConversionUtil.convertToRepresentation(diagnosis.getDateCreated(), Representation.REF));
		result.add("voided", diagnosis.getVoided());
		return result;
	}
	
	private Diagnosis getRequiredDiagnosis(String uuid) {
		Diagnosis diagnosis = getService().getIcd11DiagnosisByUuid(uuid);
		if (diagnosis == null) {
			throw new ObjectNotFoundException("No ICD-11 diagnosis with uuid " + uuid);
		}
		return diagnosis;
	}
	
	private static SimpleObject ref(String uuid, String display) {
		SimpleObject ref = new SimpleObject().add("uuid", uuid);
		if (display != null) {
			ref.add("display", display);
		}
		return ref;
	}
	
	/**
	 * Reads a text field of the request body. The legacyui XSSFilter (part of the O3 distribution)
	 * HTML-escapes request bodies, which would turn the ICD-11 expression BA20.0&XB7Q.0 into
	 * BA20.0&amp;XB7Q.0, so entities are decoded back. Values are only ever rendered escaped by the
	 * frontend.
	 */
	private static String text(SimpleObject body, String key) {
		Object value = body.get(key);
		if (value instanceof Map) {
			value = ((Map<?, ?>) value).get("uuid");
		}
		return value == null ? null : StringUtils.trimToNull(StringEscapeUtils.unescapeHtml4(value.toString()));
	}
	
	private static <T> T required(T value, String name) {
		if (value == null) {
			throw new IllegalRequestException(name + " is missing or unknown");
		}
		return value;
	}
	
	private static <T> T optional(String uuid, Function<String, T> lookup, String name) {
		return uuid == null ? null : required(lookup.apply(uuid), name);
	}
	
	private ICD11DiagnosisService getService() {
		return Context.getService(ICD11DiagnosisService.class);
	}
}
