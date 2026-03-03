# Agama-Inji-Wallet

[![Contributors][contributors-shield]](contributors-url)
[![Forks][forks-shield]](forks-url)
[![Stargazers][stars-shield]](stars-url)
[![Issues][issues-shield]](issues-url)
[![Apache License][license-shield]](license-url)


## Overview

Agama-Inji-Wallet is an authentication flow project that integrates [MOSIP Inji Wallet](https://injiweb.collab.mosip.net/) with Janssen authentication server. This project enables users to authenticate and register using verifiable credentials from their Inji digital wallet.

The flow validates credentials through the Inji Verify backend, extracts user information from verified credentials (primarily NID - National ID), and creates or updates user accounts in the Janssen server.

### Key Concepts

1. **Flow Input Handling**: The flow accepts flexible input parameters:
   - **String input**: Direct `uidRef` value (e.g., `"user123"`)
   - **Map input**: Metadata object from authentication flow (e.g., `{flowQname: "...", displayName: "..."}`)
   - **Null input**: No user context provided
   
   When a Map object is detected, it's ignored and treated as null, allowing the flow to proceed with credential-based registration.

2. **Credential Storage**: Complete verifiable credentials are stored as JSON in the database:
   - **New Users**: NID credential information is used to register the user with extracted attributes
   - **Existing Users**: Can add additional credentials (NID, TAX, etc.) to their profile
   - **Credential Format**: Stored as JSONB with credential type as key (e.g., `{"NID": {...}, "TAX": {...}}`)
   - **Automatic Merging**: New credentials are merged with existing ones without overwriting

3. **Attribute Mapping**: The flow manually extracts NID attributes from shared credentials:
   - Credentials are validated and verified through Inji Verify backend
   - Specific attributes (fullName, email, phone, etc.) are extracted from the NID credential
   - These attributes are mapped to Janssen user attributes using the `credentialMappings` configuration
   - Only mapped attributes are used for user registration/update

## Features

- **Verifiable Credential Authentication**: Validates NID credentials from MOSIP Inji Wallet
- **OpenID4VP Integration**: Uses OpenID for Verifiable Presentations protocol
- **User Onboarding**: Automatically creates user accounts from verified credential data
- **Verifiable Credentials Storage**: Stores complete verifiable credentials as JSONB in database
- **Intelligent Credential Type Detection**: Automatically identifies credential types (NID, TAX, etc.) based on content
- **Credential Merging**: Supports adding multiple credential types to existing users
- **Editable User Information**: Users can review and edit verified information before registration
- **Password Setup**: Allows users to set passwords during first-time registration
- **Existing User Detection**: Recognizes returning users and logs them in directly
- **Credential Management**: Remove specific credential types while preserving others
- **Extensible Credential Support**: Architecture supports multiple credential types (NID, TAX info, etc.)
- **Configurable Attribute Mapping**: Maps credential claims to Janssen user attributes

## Flows

### 1. Main Flow (`com.gluu.agama.inji.main`)

The orchestrator flow that coordinates the entire authentication process:

- Accepts flexible input: `uidRef` (string, Map, or null)
- Creates VP verification request with Inji Verify backend
- Builds OpenID4VP authorization URL
- Makes RFAC call to Inji Web wallet
- Verifies returned credentials
- Extracts user information from verified credentials
- Triggers authentication flow for existing users
- Triggers registration flow for new users

### 2. Authentication Flow (`com.gluu.agama.inji.authenticate`)

Handles existing user authentication:

- Receives extracted user info and optional uidRef
- Checks if user exists by email or uidRef
- Merges new credentials with existing user profile
- Logs in user directly without password prompt
- Returns user data on success

### 3. Registration Flow (`com.gluu.agama.inji.register`)

Handles new user registration:

- Displays user setup form with extracted NID data
- Allows users to review and edit information
- Requires password creation
- Creates new user account with all attributes
- Stores complete verifiable credentials
- Shows acknowledgment page on success

### 4. Casa Flow (`io.jans.casa.inji.casa`)

Integration flow for Janssen Casa:

- Accepts uidRef for existing Casa users
- Triggers main flow to add credentials to user profile
- Returns uidRef on completion
- Enables credential linking from Casa admin panel

## Flow Sequence

### 1. Input Processing
- Flow receives input parameter (string uidRef, Map object, or null)
- If input is a Map (contains `flowQname`), it's ignored and set to null
- If input is a string, it's used as `uidRef` for user lookup
- If input is null, flow proceeds with credential-based authentication only

### 2. Verification Request
- Creates VP (Verifiable Presentation) verification request
- Sends request to Inji Verify backend with presentation definition
- Receives `requestId` and `transactionId` for tracking

### 3. Authorization & Credential Presentation
- Constructs OpenID4VP authorization URL for Inji Web
- RFAC call redirects user to Inji Web wallet application
- User selects and presents their credential (e.g., NID) from wallet
- User returns to Janssen via callback URL

### 4. Credential Verification
- Validates the presented credential via Inji Verify backend
- Checks request ID status (expects `VP_SUBMITTED`)
- Checks transaction ID status (expects `SUCCESS`)
- Extracts complete verifiable credential data

### 5. Credential Storage & Type Detection
- Stores complete verifiable credentials in JSONB format
- Automatically detects credential type based on content:
  - **NID**: Contains `UIN` field in credentialSubject
  - **TAX**: Contains `taxId` or `taxNumber` fields
  - **Other**: Uses credential type from VC metadata
- Credentials stored as: `{"NID": {credential_data}, "TAX": {credential_data}}`

### 6. Attribute Extraction & Mapping
- Extracts specific attributes from credential's `credentialSubject`
- Uses `credentialMappings` configuration to map VC claims to Janssen attributes
- Example: `fullName` (from NID) → `displayName` (Janssen attribute)
- Only configured attributes are extracted and used

### 7. User Existence Check
- Checks if user already exists by email or uidRef
- **Existing User Path**:
  - Merges new credentials with existing credentials in JSONB column
  - Updates user profile with merged credentials
  - Logs user in directly without password prompt
  - User can have multiple credential types (NID + TAX + others)
- **New User Path**:
  - Proceeds to profile setup step

### 8. Profile Setup (New Users Only)
- Displays `usersetup.ftlh` page with extracted NID attributes
- User can review and edit the information
- User must set a password for account creation
- Password validation: minimum 8 characters, uppercase, lowercase, and number
- Editable fields: name, email, phone, gender, birthdate

### 9. Account Creation
- Creates new user account with:
  - Extracted and edited attributes from NID
  - User-provided password
  - Complete verifiable credentials stored in JSONB column
- User is automatically logged in after creation
- Shows acknowledgment page

### 10. Authentication Complete
- User successfully authenticated into Janssen
- Session established with user profile data
- Flow returns success with user information

## Where To Deploy

The project can be deployed to any IAM server that runs an implementation of 
the [Agama Framework](https://docs.jans.io/head/agama/introduction/) like 
[Janssen Server](https://jans.io) and [Gluu Flex](https://gluu.org/flex/).

## How To Deploy

Different IAM servers may provide different methods and 
user interfaces from where an Agama project can be deployed on that server. 
The steps below show how the **Agama-Inji-Wallet** project can be deployed on the 
[Janssen Server](https://jans.io). 

Deployment of an Agama project involves three steps

- [Downloading the `.gama` package from project repository](#download-the-project)
- [Adding the `.gama` package to the IAM server](#add-the-project-to-the-server)
- [Configure the project](#configure-the-project)

### Download the Project

> [!TIP]
> Skip this step if you use the Janssen Server TUI tool to 
> configure this project. The TUI tool enables the download and adding of this 
> project directly from the tool, as part of the `community projects` listing. 

The project is bundled as 
[.gama package](https://docs.jans.io/head/agama/gama-format/). 
Visit the `Assets` section of the 
[Releases](https://github.com/GluuFederation/agama-inji-wallet/releases) to download 
the `.gama` package.

### Add The Project To The Server

 The Janssen Server provides multiple ways an Agama project can be 
 deployed and configured. Either use the command-line tool, REST API, or a 
 TUI (text-based UI). Refer to 
 [Agama project configuration page](https://docs.jans.io/head/admin/config-guide/auth-server-config/agama-project-configuration/) in the Janssen Server documentation for more 
 details.

### Configure The Project

Agama project accepts configuration parameters in the JSON format. Every Agama 
project comes with a basic sample configuration file for reference.

Below is a typical configuration of the **Agama-Inji-Wallet** project. As show, it contains
Edit the configuration through Janssen TUI:
   ```json
   {
     "injiWebBaseURL": "https://injiweb.collab.mosip.net",
     "injiVerifyBaseURL": "https://injiverify.collab.mosip.net",
     "agamaCallBackUrl": "https://your-janssen-server.com/jans-auth/fl/callback",
     "clientId": "your-client-id",
     "credentialMappings": [...]
   }
   ```

### Credential Mappings

The `credentialMappings` array defines how credential claims are manually extracted and mapped to Janssen user attributes. When a user shares their NID credential from Inji wallet, the flow:

1. Receives the verified credential from Inji Verify backend
2. Extracts the `credentialSubject` section containing user claims
3. Maps each configured claim to the corresponding Janssen attribute
4. Uses these mapped attributes for user registration or profile update

**Configuration Example:**

```json
"credentialMappings": [
  {
    "credentialType": "NID",
    "vcToGluuMapping": {
      "fullName": "displayName",
      "phone": "mobile",
      "gender": "gender",
      "email": "mail",
      "dateOfBirth": "birthdate"
    }
  }
]
```

**Mapping Format:**
- **Left side** (key): Claim name in the verifiable credential's `credentialSubject`
- **Right side** (value): Janssen user attribute name

**How It Works:**
1. User shares NID credential containing: `{credentialSubject: {fullName: "John Doe", email: "john@example.com", ...}}`
2. Flow extracts `fullName` value and maps it to Janssen's `displayName` attribute
3. Flow extracts `email` value and maps it to Janssen's `mail` attribute
4. Only configured mappings are processed; unmapped claims are ignored
5. Complete credential is still stored in JSONB for future reference

**Important Notes:**
- Currently, the flow uses the **first credential mapping** (NID) for user registration
- Existing users can add additional credential types (TAX, etc.) which are merged into their profile
- All credentials are stored in JSONB, but only NID attributes are used for initial registration

### Presentation Definition

Defines the credential requirements for verification:

```json
"presentationDefinition": {
  "id": "unique-id",
  "purpose": "Authentication purpose description",
  "format": {
    "ldp_vc": {
      "proof_type": ["Ed25519Signature2020"]
    }
  },
  "input_descriptors": [...]
}
```

## Extending for Multiple Credential Types

The project supports multiple credential types. To add TAX credential support:

```json
"credentialMappings": [
  {
    "credentialType": "NID",
    "vcToGluuMapping": {
      "fullName": "displayName",
      "email": "mail",
      ...
    }
  },
  {
    "credentialType": "TAX",
    "vcToGluuMapping": {
      "taxId": "taxIdentifier",
      "income": "annualIncome",
      "email": "mail",
      ...
    }
  }
]
```

Currently, the flow uses the first credential mapping (NID). Future enhancements can add credential type selection.

## Verifiable Credentials Storage

### Storage Architecture

The project stores complete verifiable credentials as JSON in the database, separate from the extracted user attributes:

- **User Attributes**: Extracted NID claims (name, email, phone, etc.) stored in standard user attribute columns
- **Verifiable Credentials**: Complete credential data stored in JSONB column for audit and future use

### Storage Format

Credentials are stored with credential type as the key:

```json
{
  "NID": {
    "@context": [...],
    "type": ["VerifiableCredential", "MOSIPVerifiableCredential"],
    "credentialSubject": {
      "UIN": "1234567890",
      "fullName": "John Doe",
      "email": "john@example.com",
      "phone": "+1234567890",
      "gender": "Male",
      "dateOfBirth": "1990/01/01"
    },
    "proof": {...}
  },
  "TAX": {
    "@context": [...],
    "type": ["VerifiableCredential", "TaxCredential"],
    "credentialSubject": {
      "taxId": "TAX123456",
      "income": "50000",
      "email": "john@example.com"
    },
    "proof": {...}
  }
}
```

### Credential Type Detection

The system automatically detects credential types based on content:

1. **NID Detection**: Checks for `UIN` field in `credentialSubject`
2. **TAX Detection**: Checks for `taxId` or `taxNumber` fields
3. **Fallback**: Uses credential `type` array from VC metadata
4. **Unknown**: Assigns unique identifier if type cannot be determined

### User Registration vs. Credential Addition

**New User Registration:**
- NID credential is shared and verified
- NID attributes are extracted using `credentialMappings`
- User account is created with extracted attributes
- Complete NID credential stored in JSONB column
- User sets password during registration

**Existing User Adding Credentials:**
- User already has an account (registered with NID or other method)
- User shares additional credential (e.g., TAX credential)
- New credential is merged with existing credentials in JSONB
- User attributes may be updated if new credential contains mapped fields
- User is logged in directly without password prompt
- Example: User registered with NID, later adds TAX credential → Both stored as `{"NID": {...}, "TAX": {...}}`

### Database Schema

Following [Janssen documentation](https://docs.jans.io/head/janssen-server/reference/database/pgsql-ops/#add-custom-attribute), add the JSONB column:

```sql
ALTER TABLE "jansPerson" ADD COLUMN "verifiableCredentials" jsonb;
```

Then add the attribute definition using Jans-TUI or Admin UI:
- **Attribute Name**: `verifiableCredentials`
- **Display Name**: `Verifiable Credentials`
- **Data Type**: `JSON`
- **Multivalued**: `false`

### Credential Merging Logic

When a user presents a new credential:

1. System retrieves existing credentials from JSONB column
2. Parses existing JSON: `{"NID": {...}}`
3. Adds new credential with detected type: `{"NID": {...}, "TAX": {...}}`
4. If credential type already exists, it's replaced with the new one
5. Updates user record with merged credentials

This allows users to:
- Add multiple credential types over time
- Update existing credentials by presenting new versions
- Maintain complete audit trail of all verified credentials



<!-- This are stats url reference for this repository -->
[contributors-shield]: https://img.shields.io/github/contributors/GluuFederation/agama-inji-wallet.svg?style=for-the-badge
[contributors-url]: https://github.com/GluuFederation/agama-inji-wallet/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/GluuFederation/agama-inji-wallet.svg?style=for-the-badge
[forks-url]: https://github.com/GluuFederation/agama-inji-wallet/network/members
[stars-shield]: https://img.shields.io/github/stars/GluuFederation/agama-inji-wallet?style=for-the-badge
[stars-url]: https://github.com/GluuFederation/agama-inji-wallet/stargazers
[issues-shield]: https://img.shields.io/github/issues/GluuFederation/agama-inji-wallet.svg?style=for-the-badge
[issues-url]: https://github.com/GluuFederation/agama-inji-wallet/issues
[license-shield]: https://img.shields.io/github/license/GluuFederation/agama-inji-wallet.svg?style=for-the-badge
[license-url]: https://github.com/GluuFederation/agama-inji-wallet/blob/main/LICENSE
