/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icd11.api.model;

/**
 * The ICD-11 entity selected by the clinician in the WHO Embedded Coding Tool (ECT).
 */
public class Icd11Entity {
	
	private String stemCode;
	
	private String title;
	
	private String searchQuery;
	
	private String fullExpression;
	
	private String foundationUri;
	
	private String linearizationUri;
	
	private String selectedText;
	
	public String getStemCode() {
		return stemCode;
	}
	
	public void setStemCode(String stemCode) {
		this.stemCode = stemCode;
	}
	
	public String getTitle() {
		return title;
	}
	
	public void setTitle(String title) {
		this.title = title;
	}
	
	public String getSearchQuery() {
		return searchQuery;
	}
	
	public void setSearchQuery(String searchQuery) {
		this.searchQuery = searchQuery;
	}
	
	public String getFullExpression() {
		return fullExpression;
	}
	
	public void setFullExpression(String fullExpression) {
		this.fullExpression = fullExpression;
	}
	
	public String getFoundationUri() {
		return foundationUri;
	}
	
	public void setFoundationUri(String foundationUri) {
		this.foundationUri = foundationUri;
	}
	
	public String getLinearizationUri() {
		return linearizationUri;
	}
	
	public void setLinearizationUri(String linearizationUri) {
		this.linearizationUri = linearizationUri;
	}
	
	public String getSelectedText() {
		return selectedText;
	}
	
	public void setSelectedText(String selectedText) {
		this.selectedText = selectedText;
	}
}
