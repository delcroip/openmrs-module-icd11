# OpenMRS ICD-11 Backend Module – Build Instructions

**Target**: `openmrs-module-icd11`  
**Platform**: OpenMRS Core 2.6+  
**Purpose**: Backend for capturing WHO ICD-11 diagnoses via the Embedded Coding Tool (ECT), using on-demand stem code concepts + Obs Groups for rich data.

## High-Level Architecture

- Create lightweight **concepts on demand** for ICD-11 **stem codes** only.
- Store the full selected entity (search query, post-coordinated expression, foundationUri, linearizationUri, selectedText, etc.) as an **Obs Group**.
- Expose a clean REST API for the frontend to save and retrieve diagnoses.
- Link diagnoses to Encounter / Patient.

## Project Setup

1. Create the module with OpenMRS SDK:
   ```bash
   mvn openmrs-sdk:create-project
   ```
   - Type: Platform module
   - Module ID: `icd11`
   - Module name: `ICD-11 Diagnosis`
   - Lowest platform version: `2.6`

2. Package structure (suggested):
   ```
   org.openmrs.module.icd11
   ├── api
   │   ├── Icd11Service
   │   ├── Icd11ServiceImpl
   │   └── model (if needed)
   ├── web
   │   └── resource (REST resources)
   └── activator
   ```

## Core Requirements

### 1. On-Demand Stem Code Concept Creation

Implement a method:

```java
Concept getOrCreateStemConcept(String stemCode, String title);
```

Behavior:
- Search for an existing concept that has a mapping to the given ICD-11 stem code (using `ConceptReferenceTerm` + `ConceptMap`).
- If none exists:
  - Create a new `Concept`
  - Datatype: `N/A`
  - Class: `Diagnosis`
  - Name: `"ICD-11: {stemCode} - {title}"`
  - Create a `ConceptReferenceTerm` for the ICD-11 stem code
  - Create a `ConceptMap` linking the concept to that term
- Handle concurrent creation safely (unique constraint or synchronized check).

### 2. Obs Group for Rich ICD-11 Data

Create the following concepts once (during module startup or via liquibase):

| Concept Name                  | Datatype | Purpose                          |
|-------------------------------|----------|----------------------------------|
| ICD-11 Search Query           | Text     | Original physician search text   |
| ICD-11 Full Expression        | Text     | Complete code with post-coordination |
| ICD-11 Foundation URI         | Text     | `foundationUri` from ECT         |
| ICD-11 Linearization URI      | Text     | `linearizationUri` from ECT      |
| ICD-11 Selected Text          | Text     | Full human-readable selected text|

When saving a diagnosis:
- Create a parent Observation whose concept is the **stem code concept**.
- Add member observations for each of the fields above.
- Attach the parent Obs to the current Encounter.

### 3. REST API

Create a resource under `/ws/rest/v1/icd11/diagnosis`:

**POST** `/ws/rest/v1/icd11/diagnosis`

Expected payload (example):
```json
{
  "patient": "uuid",
  "encounter": "uuid",
  "stemCode": "BA20.0",
  "title": "COVID-19",
  "searchQuery": "covid pneumonia",
  "fullExpression": "BA20.0&XB7Q.0",
  "foundationUri": "http://id.who.int/icd/entity/...",
  "linearizationUri": "http://id.who.int/icd/release/11/mms/...",
  "selectedText": "COVID-19, virus identified, with pneumonia"
}
```

Response: the created Observation (or a custom representation containing the obs UUID + stem concept UUID).

Also implement:
- `GET /ws/rest/v1/icd11/diagnosis/{uuid}`
- `GET /ws/rest/v1/icd11/diagnosis?patient={uuid}` (list for a patient)

### 4. Database / Liquibase

- Prefer using existing `obs` and `concept` tables (no custom table required for the hybrid approach).
- If you later decide to add a custom `icd11_diagnosis` table for richer querying, keep the Obs Group as the primary clinical record and link them.

### 5. Module Activator

In `ModuleActivator`:
- Ensure the supporting concepts (Search Query, Full Expression, etc.) exist.
- Register any necessary privilege or global properties.

## Suggested Implementation Order

1. Scaffold the module with the SDK.
2. Create the supporting concepts (Obs Group members).
3. Implement `getOrCreateStemConcept()`.
4. Implement the service method that builds the Obs Group.
5. Expose the REST resource.
6. Write unit tests for concept creation and obs group building.
7. Document the REST contract in the README.

## Key Principles

- Do **not** import the full ICD-11 dictionary.
- Prefer on-demand stem concepts + structured Obs Group over a pure custom table.
- Keep the module compatible with OpenMRS Core 2.6+.
- Make the REST API simple for the O3 frontend to consume.
