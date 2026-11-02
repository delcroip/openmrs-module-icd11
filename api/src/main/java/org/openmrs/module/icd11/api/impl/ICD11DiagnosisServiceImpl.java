/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icd11.api.impl;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.CodedOrFreeText;
import org.openmrs.Concept;
import org.openmrs.ConceptClass;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptName;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.DiagnosisAttribute;
import org.openmrs.DiagnosisAttributeType;
import org.openmrs.Encounter;
import org.openmrs.EncounterRole;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.api.ConceptService;
import org.openmrs.api.DiagnosisService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.customdatatype.datatype.FreeTextDatatype;
import org.openmrs.module.icd11.ICD11DiagnosisConfig;
import org.openmrs.module.icd11.api.ICD11DiagnosisService;
import org.openmrs.module.icd11.api.model.Icd11Entity;
import org.openmrs.util.LocaleUtility;
import org.openmrs.util.PrivilegeConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class ICD11DiagnosisServiceImpl extends BaseOpenmrsService implements ICD11DiagnosisService {
	
	/**
	 * Privileges granted while creating stem concepts and resolving module metadata, so that clinicians
	 * do not need dictionary or admin rights to record a diagnosis.
	 */
	private static final String[] PROXY_PRIVILEGES = { PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.MANAGE_CONCEPTS,
	        PrivilegeConstants.GET_CONCEPT_SOURCES, PrivilegeConstants.MANAGE_CONCEPT_SOURCES,
	        PrivilegeConstants.GET_CONCEPT_REFERENCE_TERMS, PrivilegeConstants.MANAGE_CONCEPT_REFERENCE_TERMS,
	        PrivilegeConstants.GET_CONCEPT_MAP_TYPES, PrivilegeConstants.GET_CONCEPT_CLASSES,
	        PrivilegeConstants.GET_CONCEPT_DATATYPES, PrivilegeConstants.GET_CONCEPT_ATTRIBUTE_TYPES,
	        PrivilegeConstants.GET_DIAGNOSES_ATTRIBUTE_TYPES, PrivilegeConstants.EDIT_DIAGNOSES,
	        PrivilegeConstants.GET_GLOBAL_PROPERTIES, PrivilegeConstants.GET_ENCOUNTER_TYPES,
	        PrivilegeConstants.GET_ENCOUNTER_ROLES, PrivilegeConstants.GET_PROVIDERS, PrivilegeConstants.GET_VISITS,
	        PrivilegeConstants.GET_LOCATIONS };
	
	/** Separates the stem from extension codes (&amp;) and other stems of a cluster (/). */
	private static final Pattern EXPRESSION_SEPARATOR = Pattern.compile("[&/]");
	
	/** ICD-11 MMS stem code, e.g. 1A00, BA20.0, 2C25.Y. Extension codes (X...) are not stems. */
	private static final Pattern STEM_CODE = Pattern.compile("^[0-9A-WYZ][0-9A-Z]{3}(\\.[0-9A-Z]{1,3})?$");
	
	private static final int NAME_MAX_LENGTH = 255;
	
	/**
	 * One monitor per stem code. Core has no unique constraint on (source, code), so this prevents
	 * duplicate stem concepts within this node; lookups pick the oldest concept should one slip
	 * through.
	 */
	private static final Map<String, Object> STEM_LOCKS = new ConcurrentHashMap<>();
	
	private static final Map<String, String> ATTRIBUTE_FIELDS = new LinkedHashMap<>();
	
	static {
		ATTRIBUTE_FIELDS.put(ICD11DiagnosisConfig.ATTR_SEARCH_QUERY_UUID, "searchQuery");
		ATTRIBUTE_FIELDS.put(ICD11DiagnosisConfig.ATTR_FULL_EXPRESSION_UUID, "fullExpression");
		ATTRIBUTE_FIELDS.put(ICD11DiagnosisConfig.ATTR_FOUNDATION_URI_UUID, "foundationUri");
		ATTRIBUTE_FIELDS.put(ICD11DiagnosisConfig.ATTR_LINEARIZATION_URI_UUID, "linearizationUri");
		ATTRIBUTE_FIELDS.put(ICD11DiagnosisConfig.ATTR_SELECTED_TEXT_UUID, "selectedText");
	}
	
	@Override
	public void ensureMetadata() throws APIException {
		withProxyPrivileges(() -> {
			getOrCreateConceptSource();
			ensureAttributeType(ICD11DiagnosisConfig.ATTR_SEARCH_QUERY_UUID, "ICD-11 Search Query",
			    "Text the clinician typed in the ICD-11 Coding Tool");
			ensureAttributeType(ICD11DiagnosisConfig.ATTR_FULL_EXPRESSION_UUID, "ICD-11 Full Expression",
			    "Full ICD-11 code including post-coordination, e.g. BA20.0&XB7Q.0");
			ensureAttributeType(ICD11DiagnosisConfig.ATTR_FOUNDATION_URI_UUID, "ICD-11 Foundation URI",
			    "Foundation entity URI returned by the ICD-11 Coding Tool");
			ensureAttributeType(ICD11DiagnosisConfig.ATTR_LINEARIZATION_URI_UUID, "ICD-11 Linearization URI",
			    "MMS linearization URI returned by the ICD-11 Coding Tool");
			ensureAttributeType(ICD11DiagnosisConfig.ATTR_SELECTED_TEXT_UUID, "ICD-11 Selected Text",
			    "Human readable text of the selected ICD-11 entity");
			return null;
		});
	}
	
	@Override
	public Concept getOrCreateStemConcept(String stemCode, String title) throws APIException {
		String code = normalizeStemCode(stemCode);
		return withProxyPrivileges(() -> {
			synchronized (lockFor(code)) {
				Concept existing = findStemConcept(code);
				return existing != null ? existing : createStemConcept(code, title);
			}
		});
	}
	
	@Override
	public Diagnosis saveIcd11Diagnosis(Patient patient, Encounter encounter, Visit visit, Location location,
	        Icd11Entity entity, ConditionVerificationStatus certainty, Integer rank) throws APIException {
		if (patient == null) {
			throw new APIException("patient is required");
		}
		if (entity == null) {
			throw new APIException("ICD-11 entity is required");
		}
		String expression = StringUtils.trimToNull(entity.getFullExpression());
		String stemCode = StringUtils.trimToNull(entity.getStemCode());
		if (stemCode == null && expression == null) {
			throw new APIException("stemCode or fullExpression is required");
		}
		if (stemCode == null) {
			stemCode = deriveStemCode(expression);
		} else if (expression == null) {
			expression = stemCode;
		} else if (!stemCode.equalsIgnoreCase(deriveStemCode(expression))) {
			throw new APIException("stemCode " + stemCode + " is not the stem of fullExpression " + expression);
		}
		
		Concept concept = getOrCreateStemConcept(stemCode, entity.getTitle());
		
		if (encounter == null) {
			encounter = createEncounter(patient, visit, location);
		} else if (!patient.equals(encounter.getPatient())) {
			throw new APIException("encounter does not belong to the patient");
		} else if (encounter.getVoided()) {
			throw new APIException("encounter is voided");
		}
		
		Diagnosis diagnosis = new Diagnosis(encounter, new CodedOrFreeText(concept, null, null),
		        certainty != null ? certainty : ConditionVerificationStatus.PROVISIONAL, rank != null ? rank : 1, patient);
		Map<String, String> values = new LinkedHashMap<>();
		values.put(ICD11DiagnosisConfig.ATTR_SEARCH_QUERY_UUID, entity.getSearchQuery());
		values.put(ICD11DiagnosisConfig.ATTR_FULL_EXPRESSION_UUID, expression);
		values.put(ICD11DiagnosisConfig.ATTR_FOUNDATION_URI_UUID, entity.getFoundationUri());
		values.put(ICD11DiagnosisConfig.ATTR_LINEARIZATION_URI_UUID, entity.getLinearizationUri());
		values.put(ICD11DiagnosisConfig.ATTR_SELECTED_TEXT_UUID, entity.getSelectedText());
		withProxyPrivileges(() -> {
			for (Map.Entry<String, String> value : values.entrySet()) {
				addAttribute(diagnosis, value.getKey(), value.getValue());
			}
			return null;
		});
		return Context.getDiagnosisService().save(diagnosis);
	}
	
	@Override
	public Diagnosis getIcd11DiagnosisByUuid(String uuid) throws APIException {
		Diagnosis diagnosis = Context.getDiagnosisService().getDiagnosisByUuid(uuid);
		return isIcd11Diagnosis(diagnosis) ? diagnosis : null;
	}
	
	@Override
	public List<Diagnosis> getIcd11Diagnoses(Patient patient) throws APIException {
		List<Diagnosis> result = new ArrayList<>();
		for (Diagnosis diagnosis : Context.getDiagnosisService().getDiagnoses(patient, null)) {
			if (!diagnosis.getVoided() && isIcd11Diagnosis(diagnosis)) {
				result.add(diagnosis);
			}
		}
		result.sort(Comparator.comparing(Diagnosis::getDateCreated, Comparator.nullsLast(Comparator.reverseOrder())));
		return result;
	}
	
	@Override
	public Map<String, String> getIcd11Fields(Diagnosis diagnosis) throws APIException {
		Map<String, String> fields = new LinkedHashMap<>();
		for (DiagnosisAttribute attribute : diagnosis.getActiveAttributes()) {
			String field = ATTRIBUTE_FIELDS.get(attribute.getAttributeType().getUuid());
			if (field != null) {
				fields.put(field, attribute.getValueReference());
			}
		}
		String expression = fields.get("fullExpression");
		if (expression != null) {
			fields.put("stemCode", deriveStemCode(expression));
		}
		return fields;
	}
	
	@Override
	public Diagnosis voidIcd11Diagnosis(Diagnosis diagnosis, String reason) throws APIException {
		return Context.getDiagnosisService().voidDiagnosis(diagnosis, reason);
	}
	
	/**
	 * @return the first stem code of an ICD-11 expression, e.g. BA20.0 for BA20.0&amp;XB7Q.0 and NC72.4
	 *         for NC72.4/XJ8VG
	 */
	public static String deriveStemCode(String expression) {
		if (StringUtils.isBlank(expression)) {
			return null;
		}
		return EXPRESSION_SEPARATOR.split(expression.trim(), 2)[0].trim();
	}
	
	static String normalizeStemCode(String stemCode) {
		String code = StringUtils.upperCase(StringUtils.trimToNull(stemCode));
		if (code == null || !STEM_CODE.matcher(code).matches()) {
			throw new APIException("Not an ICD-11 stem code: " + stemCode);
		}
		return code;
	}
	
	private static Object lockFor(String code) {
		return STEM_LOCKS.computeIfAbsent(code, k -> new Object());
	}
	
	private Concept findStemConcept(String code) {
		Concept oldest = null;
		for (Concept concept : Context.getConceptService().getConceptsByMapping(code,
		    ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME, false)) {
			if (isSameAsMapped(concept, code) && (oldest == null || concept.getConceptId() < oldest.getConceptId())) {
				oldest = concept;
			}
		}
		return oldest;
	}
	
	private boolean isSameAsMapped(Concept concept, String code) {
		for (ConceptMap map : concept.getConceptMappings()) {
			ConceptReferenceTerm term = map.getConceptReferenceTerm();
			if (ConceptMapType.SAME_AS_MAP_TYPE_UUID.equals(map.getConceptMapType().getUuid())
			        && code.equalsIgnoreCase(term.getCode())
			        && ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME.equalsIgnoreCase(term.getConceptSource().getName())) {
				return true;
			}
		}
		return false;
	}
	
	private Concept createStemConcept(String code, String title) {
		ConceptService conceptService = Context.getConceptService();
		ConceptSource source = getOrCreateConceptSource();
		String label = StringUtils.defaultIfBlank(StringUtils.trimToNull(title), code);
		
		ConceptReferenceTerm term = conceptService.getConceptReferenceTermByCode(code, source);
		if (term == null) {
			term = new ConceptReferenceTerm(source, code, StringUtils.abbreviate(label, NAME_MAX_LENGTH));
			term = conceptService.saveConceptReferenceTerm(term);
		}
		
		ConceptClass diagnosisClass = conceptService.getConceptClassByUuid(ConceptClass.DIAGNOSIS_UUID);
		if (diagnosisClass == null) {
			diagnosisClass = conceptService.getConceptClassByName("Diagnosis");
		}
		
		Locale locale = LocaleUtility.getDefaultLocale();
		String suffix = " [" + code + "]";
		Concept concept = new Concept();
		concept.setDatatype(conceptService.getConceptDatatypeByUuid(ConceptDatatype.N_A_UUID));
		concept.setConceptClass(diagnosisClass);
		concept.setFullySpecifiedName(
		    new ConceptName(StringUtils.abbreviate(label, NAME_MAX_LENGTH - suffix.length()) + suffix, locale));
		concept.setShortName(new ConceptName(code, locale));
		concept.addConceptMapping(
		    new ConceptMap(term, conceptService.getConceptMapTypeByUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID)));
		return conceptService.saveConcept(concept);
	}
	
	private ConceptSource getOrCreateConceptSource() {
		ConceptService conceptService = Context.getConceptService();
		ConceptSource source = conceptService.getConceptSourceByName(ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME);
		if (source == null) {
			source = conceptService.getConceptSourceByUuid(ICD11DiagnosisConfig.CONCEPT_SOURCE_UUID);
		}
		if (source == null) {
			source = new ConceptSource();
			source.setUuid(ICD11DiagnosisConfig.CONCEPT_SOURCE_UUID);
			source.setName(ICD11DiagnosisConfig.CONCEPT_SOURCE_NAME);
			source.setDescription("WHO ICD-11 Mortality and Morbidity Statistics (MMS)");
			source = conceptService.saveConceptSource(source);
		}
		return source;
	}
	
	private void ensureAttributeType(String uuid, String name, String description) {
		DiagnosisService diagnosisService = Context.getDiagnosisService();
		if (diagnosisService.getDiagnosisAttributeTypeByUuid(uuid) != null) {
			return;
		}
		DiagnosisAttributeType type = new DiagnosisAttributeType();
		type.setUuid(uuid);
		type.setName(name);
		type.setDescription(description);
		type.setDatatypeClassname(FreeTextDatatype.class.getName());
		type.setMinOccurs(0);
		type.setMaxOccurs(1);
		diagnosisService.saveDiagnosisAttributeType(type);
	}
	
	private void addAttribute(Diagnosis diagnosis, String typeUuid, String value) {
		if (StringUtils.isBlank(value)) {
			return;
		}
		DiagnosisAttributeType type = Context.getDiagnosisService().getDiagnosisAttributeTypeByUuid(typeUuid);
		if (type == null) {
			throw new APIException("Missing diagnosis attribute type " + typeUuid + ", is the icd11 module started?");
		}
		DiagnosisAttribute attribute = new DiagnosisAttribute();
		attribute.setAttributeType(type);
		attribute.setValueReferenceInternal(value.trim());
		diagnosis.addAttribute(attribute);
	}
	
	private boolean isIcd11Diagnosis(Diagnosis diagnosis) {
		if (diagnosis == null) {
			return false;
		}
		for (DiagnosisAttribute attribute : diagnosis.getActiveAttributes()) {
			if (ICD11DiagnosisConfig.ATTR_FULL_EXPRESSION_UUID.equals(attribute.getAttributeType().getUuid())) {
				return true;
			}
		}
		return false;
	}
	
	private Encounter createEncounter(Patient patient, Visit visit, Location location) {
		EncounterService encounterService = Context.getEncounterService();
		return withProxyPrivileges(() -> {
			String typeUuid = Context.getAdministrationService().getGlobalProperty(
			    ICD11DiagnosisConfig.GP_ENCOUNTER_TYPE_UUID, ICD11DiagnosisConfig.DEFAULT_ENCOUNTER_TYPE_UUID);
			EncounterType type = encounterService.getEncounterTypeByUuid(typeUuid);
			if (type == null) {
				throw new APIException("Encounter type " + typeUuid + " not found, check the global property "
				        + ICD11DiagnosisConfig.GP_ENCOUNTER_TYPE_UUID);
			}
			
			Visit encounterVisit = visit;
			if (encounterVisit == null) {
				List<Visit> activeVisits = Context.getVisitService().getActiveVisitsByPatient(patient);
				encounterVisit = activeVisits.isEmpty() ? null : activeVisits.get(0);
			} else if (!patient.equals(encounterVisit.getPatient())) {
				throw new APIException("visit does not belong to the patient");
			}
			
			Location encounterLocation = location;
			if (encounterLocation == null && encounterVisit != null) {
				encounterLocation = encounterVisit.getLocation();
			}
			if (encounterLocation == null) {
				encounterLocation = Context.getUserContext().getLocation();
			}
			if (encounterLocation == null) {
				encounterLocation = Context.getLocationService().getDefaultLocation();
			}
			
			Encounter encounter = new Encounter();
			encounter.setPatient(patient);
			encounter.setEncounterType(type);
			encounter.setLocation(encounterLocation);
			encounter.setVisit(encounterVisit);
			Date now = new Date();
			Date stop = encounterVisit != null ? encounterVisit.getStopDatetime() : null;
			encounter.setEncounterDatetime(stop != null && stop.before(now) ? stop : now);
			
			if (Context.getAuthenticatedUser() != null) {
				Collection<Provider> providers = Context.getProviderService()
				        .getProvidersByPerson(Context.getAuthenticatedUser().getPerson());
				if (!providers.isEmpty()) {
					encounter.addProvider(encounterService.getEncounterRoleByUuid(EncounterRole.UNKNOWN_ENCOUNTER_ROLE_UUID),
					    providers.iterator().next());
				}
			}
			return encounterService.saveEncounter(encounter);
		});
	}
	
	private static <T> T withProxyPrivileges(Supplier<T> work) {
		for (String privilege : PROXY_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			return work.get();
		} finally {
			for (String privilege : PROXY_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
}
