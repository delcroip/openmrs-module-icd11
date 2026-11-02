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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.openmrs.api.APIException;

/**
 * Unit tests (no Spring context) for ICD-11 expression parsing.
 */
public class StemCodeTest {
	
	@Test
	public void deriveStemCode_shouldReturnPlainStemCode() {
		assertEquals("BA20.0", ICD11DiagnosisServiceImpl.deriveStemCode("BA20.0"));
	}
	
	@Test
	public void deriveStemCode_shouldStripPostCoordinationExtensions() {
		assertEquals("BA20.0", ICD11DiagnosisServiceImpl.deriveStemCode("BA20.0&XB7Q.0"));
		assertEquals("2C25.0", ICD11DiagnosisServiceImpl.deriveStemCode(" 2C25.0 & XH7SY1 & XK8G "));
	}
	
	@Test
	public void deriveStemCode_shouldReturnFirstStemOfACluster() {
		assertEquals("NC72.4", ICD11DiagnosisServiceImpl.deriveStemCode("NC72.4/XJ8VG"));
		assertEquals("DA63.Z", ICD11DiagnosisServiceImpl.deriveStemCode("DA63.Z/ME24.A1&XT5R"));
	}
	
	@Test
	public void deriveStemCode_shouldReturnNullForBlank() {
		assertNull(ICD11DiagnosisServiceImpl.deriveStemCode(null));
		assertNull(ICD11DiagnosisServiceImpl.deriveStemCode("  "));
	}
	
	@Test
	public void normalizeStemCode_shouldUpperCaseAndTrim() {
		assertEquals("BA20.0", ICD11DiagnosisServiceImpl.normalizeStemCode(" ba20.0 "));
		assertEquals("1A00", ICD11DiagnosisServiceImpl.normalizeStemCode("1A00"));
		assertEquals("2C25.Y", ICD11DiagnosisServiceImpl.normalizeStemCode("2C25.Y"));
	}
	
	@Test(expected = APIException.class)
	public void normalizeStemCode_shouldRejectExtensionCodes() {
		ICD11DiagnosisServiceImpl.normalizeStemCode("XB7Q.0");
	}
	
	@Test(expected = APIException.class)
	public void normalizeStemCode_shouldRejectPostCoordinatedExpressions() {
		ICD11DiagnosisServiceImpl.normalizeStemCode("BA20.0&XB7Q.0");
	}
	
	@Test(expected = APIException.class)
	public void normalizeStemCode_shouldRejectBlank() {
		ICD11DiagnosisServiceImpl.normalizeStemCode(" ");
	}
}
