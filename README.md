# openmrs-module-icd11

Records **WHO ICD-11** diagnoses captured with the
[Embedded Coding Tool (ECT)](https://icd.who.int/docs/icd-api/icd11ect-1.8/EmbeddedCodingTool/) as core
OpenMRS diagnoses. It is the backend of
[`@openmrs/esm-icd11-diagnosis-app`](https://github.com/delcroip/openmrs-esm-icd11-diagnosis-app).

- Requires OpenMRS Platform **2.6+** and `webservices.rest` **2.40+** (tested on O3 3.8.0 / core 2.8.10).
- The full ICD-11 dictionary is **not** imported. A concept is created **on demand** for each stem code
  that is used.
- The design decisions and their rationale are in
  [ICD11-BACKEND-BUILD-INSTRUCTIONS.md](ICD11-BACKEND-BUILD-INSTRUCTIONS.md#decisions-2026-10-29--supersede-the-original-text-below-where-they-conflict).

## Data model

| ECT field          | Stored as                                                                                     |
|--------------------|-----------------------------------------------------------------------------------------------|
| stem code + title  | `Diagnosis.diagnosis.coded` → concept mapped **SAME-AS** `ICD-11-WHO:<stem>` (reused if it exists, e.g. CIEL) |
| `code` (full expression, may contain `&` / `/`) | diagnosis attribute *ICD-11 Full Expression* |
| search query       | diagnosis attribute *ICD-11 Search Query*                                                     |
| foundation URI     | diagnosis attribute *ICD-11 Foundation URI*                                                   |
| linearization URI  | diagnosis attribute *ICD-11 Linearization URI*                                                |
| selected text      | diagnosis attribute *ICD-11 Selected Text*                                                    |
| certainty / rank   | `Diagnosis.certainty` (`PROVISIONAL`/`CONFIRMED`) and `Diagnosis.rank` (1 primary, 2 secondary) |

The diagnoses are ordinary `encounter_diagnosis` rows, so they show up in the O3 visit summary,
in `/ws/rest/v1/patientdiagnoses`, in FHIR and in reports.

Stem concepts are created with class *Diagnosis* and datatype *N/A*. Their fully specified name is
`"<title> [<code>]"` and their short name is `<code>`. Creating them needs no dictionary privilege
(the service uses proxy privileges).

Metadata with fixed UUIDs is created by the activator (see `ICD11DiagnosisConfig`). This covers the
concept source `ICD-11-WHO` (created only when missing) and the five diagnosis attribute types.

| Global property            | Default                                         | Purpose |
|----------------------------|-------------------------------------------------|---------|
| `icd11.encounterTypeUuid`  | `d7151f82-c1f3-4152-a605-2f9ea7414a79` (Visit Note) | Encounter type used when the client sends no encounter |

## REST API

All endpoints are under `/openmrs/ws/rest/v1/icd11/diagnosis`.

| Method | Path | Privilege | Description |
|--------|------|-----------|-------------|
| POST   | `/icd11/diagnosis` | Edit Diagnoses | Create a diagnosis. Returns `201` and the representation |
| GET    | `/icd11/diagnosis/{uuid}` | Get Diagnoses | One ICD-11 diagnosis (`404` if not an ICD-11 diagnosis) |
| GET    | `/icd11/diagnosis?patient={uuid}` | Get Diagnoses | `{ "results": [...] }`, the non-voided diagnoses, newest first |
| DELETE | `/icd11/diagnosis/{uuid}?reason=...` | Delete Diagnoses | Voids the diagnosis. Returns `204` |

### POST body

```json
{
  "patient": "uuid",                 // required
  "encounter": "uuid",               // optional; when absent an encounter is created...
  "visit": "uuid",                   // ...in this visit (default: the patient's active visit)
  "location": "uuid",                // ...at this location (default: visit / session / default location)
  "stemCode": "BA20.0",              // optional if fullExpression is given; must be its first stem
  "title": "COVID-19, virus identified",
  "searchQuery": "covid pneumonia",
  "fullExpression": "BA20.0&XB7Q.0",
  "foundationUri": "http://id.who.int/icd/entity/1790791774",
  "linearizationUri": "http://id.who.int/icd/release/11/2026-01/mms/1790791774",
  "selectedText": "COVID-19, virus identified, with pneumonia",
  "certainty": "CONFIRMED",          // PROVISIONAL (default) | CONFIRMED
  "rank": 1                          // 1 (default, primary) | 2 (secondary)
}
```

References can be given as plain UUIDs or as `{ "uuid": "..." }` objects. Invalid input returns `400`
with the reason in `error.message`. Examples of invalid input: an unknown patient, an extension code
(`X...`) used as the stem, or a stem that does not match the expression.

### Response

```json
{
  "uuid": "…",
  "patient": { "uuid": "…" },
  "encounter": { "uuid": "…" },
  "concept": { "uuid": "…", "display": "COVID-19, virus identified [BA20.0]" },
  "certainty": "CONFIRMED",
  "rank": 1,
  "searchQuery": "covid pneumonia",
  "fullExpression": "BA20.0&XB7Q.0",
  "foundationUri": "…",
  "linearizationUri": "…",
  "selectedText": "COVID-19, virus identified, with pneumonia",
  "stemCode": "BA20.0",
  "dateCreated": "2026-10-29T16:40:00.000+0000",
  "voided": false
}
```

## Building and testing

Core 2.6 has to be built and tested on JDK 8–11, so the build runs in Docker. The host JDK is not
used. From the workspace root:

```bash
make omod        # mvn package with all tests -> omod/target/icd11-1.0.0-SNAPSHOT.omod
make be-test     # tests only
make be-deploy   # offline build without tests + restart the backend container with the new omod
```

These are equivalent to running this from the module directory:

```bash
docker run --rm -v "$PWD":/src:z -w /src -v "$HOME/.m2":/root/.m2:z maven:3.9-eclipse-temurin-11 mvn -B package
```

The tests are:

- `StemCodeTest`: plain unit tests of expression parsing and stem validation.
- `ICD11DiagnosisServiceTest`: H2 and the core standard dataset. It covers metadata, on-demand
  concepts, CIEL-style reuse, non-SAME-AS mappings, proxy privileges for a clinician without
  dictionary rights, encounter creation and voiding.
- `Icd11DiagnosisRestControllerTest`: the REST contract, including the 400 and 404 mapping.

Spotless (OpenMRS formatter + license header) runs automatically during the build.

## Known limitations

- Concurrent first use of the same stem code on one node is serialised by an in-JVM lock. A
  duplicate concept could only appear across nodes, or in the few milliseconds between lock release
  and commit. Lookups then deterministically use the oldest concept.
- Stem-code clusters (`A/B`) are coded on the first stem. The other stems only live in the *Full
  Expression* attribute.
- Concept names are created in the server default locale with the title sent by the client.
