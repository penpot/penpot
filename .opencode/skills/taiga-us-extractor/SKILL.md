---
name: taiga-us-extractor
description: Extract user story information from Taiga project management platform using the public REST API.
homepage: https://docs.taiga.io/api.html
metadata: {"clawdbot":{"emoji":"📋","requires":{"bins":["curl","jq"]},"install":[]}}
---

# Taiga User Story Extractor

Extract structured user story information from Taiga using the public REST API. No authentication required for publicly visible projects.

## API Endpoints

Taiga provides a public REST API. For Penpot's project:
- Base URL: `https://api.taiga.io/api/v1`

## Quick Start

### Get project information by slug
```bash
# Returns project metadata including the project ID
curl -s "https://api.taiga.io/api/v1/projects/by_slug?slug=penpot" | jq '{id, name, slug}'
```

### Get user story by reference number
```bash
# project=345963 is the Penpot project ID
# ref=13976 is the user story reference number
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq .
```

## Extracting Key Fields

### Title (subject)
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq -r '.subject'
```

### Description (markdown)
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq -r '.description'
```

### Tags
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq -r '.tags | map(.[0]) | join(", ")'
```

### Status
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq -r '.status_extra_info.name'
```

### Epic information
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq -r '.epics | map(.subject) | join(", ")'
```

## Full Extraction Script

```bash
#!/bin/bash
# Usage: ./extract-taiga-us.sh <project_slug> <us_ref>

PROJECT_SLUG="${1:-penpot}"
US_REF="${2:-13976}"

# Step 1: Get project ID
PROJECT_ID=$(curl -s "https://api.taiga.io/api/v1/projects/by_slug?slug=${PROJECT_SLUG}" | jq -r '.id')

if [ "$PROJECT_ID" = "null" ] || [ -z "$PROJECT_ID" ]; then
    echo "Error: Could not find project with slug '${PROJECT_SLUG}'"
    exit 1
fi

echo "Project ID: $PROJECT_ID"

# Step 2: Get user story
US_DATA=$(curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=${PROJECT_ID}&ref=${US_REF}")

if [ "$(echo "$US_DATA" | jq -r '.id')" = "null" ]; then
    echo "Error: Could not find user story #${US_REF}"
    exit 1
fi

# Extract and display key information
echo ""
echo "=== User Story #${US_REF} ==="
echo "Title: $(echo "$US_DATA" | jq -r '.subject')"
echo "Status: $(echo "$US_DATA" | jq -r '.status_extra_info.name')"
echo "Tags: $(echo "$US_DATA" | jq -r '.tags | map(.[0]) | join(", ")')"
echo "Epics: $(echo "$US_DATA" | jq -r '.epics | map(.subject) | join(", ")')"
echo ""
echo "=== Description ==="
echo "$US_DATA" | jq -r '.description'
```

## Available Fields

The user story API returns many fields. Key ones include:

| Field | Description |
|-------|-------------|
| `id` | Internal numeric ID |
| `ref` | User-facing reference number (e.g., 13976) |
| `subject` | Title of the user story |
| `description` | Markdown description |
| `description_html` | HTML rendered description |
| `status_extra_info` | Status name, color, closed state |
| `tags` | Array of [name, color] pairs |
| `epics` | Associated epics |
| `owner_extra_info` | Creator information |
| `assigned_to_extra_info` | Assignee information |
| `project_extra_info` | Project name, slug, logo |
| `created_date` / `modified_date` | Timestamps |
| `neighbors` | Previous and next user stories |

## Advanced Usage

### Filter with jq
```bash
# Get only specific fields
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq '{
    ref,
    subject,
    status: .status_extra_info.name,
    tags: [.tags[][0]],
    epics: [.epics[].subject],
    description
}'
```

### Save to file
```bash
curl -s "https://api.taiga.io/api/v1/userstories/by_ref?project=345963&ref=13976" | jq . > us_13976.json
```

### List project user stories
```bash
# Requires authentication for private projects
curl -s "https://api.taiga.io/api/v1/userstories?project=345963" | jq '.[] | {ref, subject, status: .status_extra_info.name}'
```

## Notes

- The Penpot project ID is `345963` and slug is `penpot`
- User story URLs like `https://tree.taiga.io/project/penpot/us/13976` map to:
  - Project slug: `penpot`
  - US reference: `13976`
- No authentication is required for publicly visible projects
- Rate limits may apply for heavy usage
