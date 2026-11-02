# OpenMRS ICD-11 Backend Module – Build Instructions

**Target**: `openmrs-module-icd11`  
**Platform**: OpenMRS Core 2.6+  
**Purpose**: Backend for capturing WHO ICD-11 diagnoses via the Embedded Coding Tool (ECT), using on-demand stem code concepts + core `Diagnosis` with `DiagnosisAttribute`s for rich data.

## Decisions (2026-10-29) — supersede the original text below where they conflict

| # | Topic | Decision | Why |
|---|-------|----------|-----|
| D1 | Clinical record | Save a **core `org.openmrs.Diagnosis`** (`encounter_diagnosis`), coded to the stem concept, instead of an Obs Group. | O3 visit summary, diagnosis lists, FHIR `Condition`/`encounter-diagnosis` and reports all read core diagnoses. Obs-only diagnoses would be invisible. |
| D2 | Rich ECT data | Stored as **`DiagnosisAttribute`s** (core 2.5+) with `FreeTextDatatype`. The attribute types are created by the activator with fixed UUIDs. No helper concepts. | Keeps everything on one row family, needs no extra concepts and stays queryable. |
| D3 | Concept source | Look up and create mappings in the concept source **`ICD-11-WHO`** (the name CIEL uses), map type `SAME-AS`. | If CIEL is loaded, existing CIEL concepts mapped to the stem are **reused** instead of creating duplicates. |
| D4 | Concept name | FSN = `"{title} [{stemCode}]"` (the code keeps the FSN unique), SHORT name = `stemCode`, class `Diagnosis`, datatype `N/A`. | ICD titles such as "Other specified …" are not unique, and core rejects duplicate FSNs. |
| D5 | Privileges | The endpoint needs core `Edit Diagnoses` (POST/DELETE) / `Get Diagnoses` (GET). Concept creation runs with **proxy privileges** (`Manage Concepts`, `Manage Concept Reference Terms`, `Manage Concept Sources`). | Clinicians must not need dictionary-admin rights to record a diagnosis. |
| D6 | Concurrency | A per-stem-code lock in the service plus a re-check inside the lock. Lookups pick the **oldest** matching concept, so a rare duplicate (multi-node, or the ms between lock release and commit) is harmless. No REQUIRES_NEW transaction. | Core has no DB unique constraint on (source, code). REQUIRES_NEW would not see uncommitted encounter or test data. |
| D7 | Stem vs expression | `stemCode` is optional. When it is missing, it is derived as the first stem of `fullExpression` (split on `&` and `/`). If both are sent, the stem must be the prefix of the expression. Stem-code *clusters* (`A/B`) are coded on the first stem; the full cluster stays in the `fullExpression` attribute. | ECT's `code` may contain post-coordination. The coded concept must be a stem. |
| D8 | Encounter | `encounter` is optional. When it is missing, the backend creates an encounter (type from GP `icd11.encounterTypeUuid`, default *Visit Note*; location from payload `location` or the session location) and attaches it to the patient's active visit if there is one. | The O3 workspace does not always have an encounter yet. |
| D9 | Certainty / rank | The payload carries `certainty` (`PROVISIONAL`\|`CONFIRMED`, default `PROVISIONAL`) and `rank` (1 = primary, 2 = secondary, default 1). | These are required by core `Diagnosis`. |
| D10 | REST | A Spring controller extending `BaseRestController` at `/ws/rest/v1/icd11/diagnosis`: POST, GET `{uuid}`, GET `?patient=`, DELETE `{uuid}` (void). It requires the `webservices.rest` module. | Atomic single transaction (concept + encounter + diagnosis + attributes). |
| D11 | Scaffold | SDK sample code (`Item`, DAO, `AdminList`, JSP, legacyui awareness) is removed. The repo is flattened (pom at the root). | Not used. |
| D12 | Build | Build with JDK 11 in Docker (`maven:3.9-eclipse-temurin-11`, `~/.m2` mounted). The host JDK (21/25) is not used for the core 2.6 test harness. | Reproducible, and works offline once `~/.m2` is warm. |
| D14 | Input decoding | The REST controller HTML-unescapes text fields. | The `legacyui` XSSFilter shipped in O3 3.8 HTML-escapes JSON bodies (`BA20.0&XB7Q.0` became `BA20.0&amp;XB7Q.0`). Found by the E2E smoke test. |
| D15 | Build | Parent `maven-parent-openmrs-module` **2.3.0** (the SDK template's 1.1.1 pulls JUnit 4.11, PowerMock 1.5 and `-source 6`, which are incompatible with the core 2.6 test harness). JUnit pinned to 4.13.2. Spotless files (`OpenMRSFormatter.xml`, `license-header.txt`) are at the repo root. | Tests could not run otherwise. |
| D13 | Dev stack | `../docker-compose.yml` (workspace root) runs MariaDB + O3 3.8.0 backend (with this omod bind-mounted) + O3 frontend + WHO ICD-API + nginx gateway. Everything works offline after the first run. | Offline BE+FE testing. |

> **Status (2026-10-29):** implemented. There are 36 backend tests (8 unit, 16 service integration on H2, 12 REST controller), plus `make smoke` (API E2E) and `make e2e` (Playwright, in the frontend repo). Everything was verified against O3 3.8.0 / core 2.8.10, including with no internet egress (`make offline-check`). The refapp's CIEL dictionary already maps many concepts SAME-AS `ICD-11-WHO`, and these were reused live, e.g. `1A00` → CIEL 122604 "Cholera due to Vibrio cholerae" (D3).
>
> Sections 2–5 below describe the original Obs Group design and are kept for reference; D1/D2 replace them.

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
