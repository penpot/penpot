# Changelog

Add changes that are meaningful to the user here after each PR so they can be updated in feature base.

<details>
<summary>Template</summary>

## Template

### <DATE> - <CHANGE_DESCRIPTION>

[Link to PR]()

If possible add video here from PR as well

- Outline of changes
</details>

## Changes

### 2024-06-26 - Disallow special characters in token name

[Video](https://github.com/tokens-studio/tokens-studio-for-penpot/assets/1898374/7c59c0cc-d6e1-4b0d-9646-9a27eafcccc4
)

https://github.com/tokens-studio/tokens-studio-for-penpot/pull/200

### 2024-06-26 - Make Tokens JSON Export DTCG compatible

![Screenshot of sample JSON Export in DTCG format](https://private-user-images.githubusercontent.com/9948167/343043570-b4bb39f7-ec53-409a-a053-b284d60848d9.png?jwt=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3MTk0MDMyMzcsIm5iZiI6MTcxOTQwMjkzNywicGF0aCI6Ii85OTQ4MTY3LzM0MzA0MzU3MC1iNGJiMzlmNy1lYzUzLTQwOWEtYTA1My1iMjg0ZDYwODQ4ZDkucG5nP1gtQW16LUFsZ29yaXRobT1BV1M0LUhNQUMtU0hBMjU2JlgtQW16LUNyZWRlbnRpYWw9QUtJQVZDT0RZTFNBNTNQUUs0WkElMkYyMDI0MDYyNiUyRnVzLWVhc3QtMSUyRnMzJTJGYXdzNF9yZXF1ZXN0JlgtQW16LURhdGU9MjAyNDA2MjZUMTE1NTM3WiZYLUFtei1FeHBpcmVzPTMwMCZYLUFtei1TaWduYXR1cmU9MWEzZTU5OWQ0M2JkZWE5MTA5MDc4MTY1OTkyZWE5MmE5YzBlYmQ2NTcwMmEwZTdmMjViNGU5YTFjNWIxYjU5ZCZYLUFtei1TaWduZWRIZWFkZXJzPWhvc3QmYWN0b3JfaWQ9MCZrZXlfaWQ9MCZyZXBvX2lkPTAifQ.qWJxRa_Y7LZ6EDJg5yPdOUIQkURFmZwMNec_BbdH9Co)

https://github.com/tokens-studio/tokens-studio-for-penpot/issues/197

### 2024-06-25 - Token Insert/Edit Validation + Value Preview

[Video](https://private-user-images.githubusercontent.com/1898374/342781533-06054a7e-3efb-4f48-a063-8b03f4b8fe5c.mp4?jwt=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3MTkzMjgwNzYsIm5iZiI6MTcxOTMyNzc3NiwicGF0aCI6Ii8xODk4Mzc0LzM0Mjc4MTUzMy0wNjA1NGE3ZS0zZWZiLTRmNDgtYTA2My04YjAzZjRiOGZlNWMubXA0P1gtQW16LUFsZ29yaXRobT1BV1M0LUhNQUMtU0hBMjU2JlgtQW16LUNyZWRlbnRpYWw9QUtJQVZDT0RZTFNBNTNQUUs0WkElMkYyMDI0MDYyNSUyRnVzLWVhc3QtMSUyRnMzJTJGYXdzNF9yZXF1ZXN0JlgtQW16LURhdGU9MjAyNDA2MjVUMTUwMjU2WiZYLUFtei1FeHBpcmVzPTMwMCZYLUFtei1TaWduYXR1cmU9ZDliZmUwMzU1MWY3NWQ2NWZkYzA0ODYxYzYzMTYzMjMyOGZjZGMzZDNhMWJmZGI4ZmM3NmU2NzNjYjY2MTdmMCZYLUFtei1TaWduZWRIZWFkZXJzPWhvc3QmYWN0b3JfaWQ9MCZrZXlfaWQ9MCZyZXBvX2lkPTAifQ.44rKA1h3Cvw-vDWevnx7xVUeuZ1ezV4pqEtekVXgVds)

https://github.com/tokens-studio/tokens-studio-for-penpot/pull/194

Adds validation to the token create/edit field

  - Name duplication is not allowed and takes a min/max length
  - Value has to be a resolvable value
  - Description has max value
                         
### 2024-06-24 - Added Ability to Export Tokens in JSON Format

Sample JSON Output - https://github.com/user-attachments/files/15957831/tokens.json

![JSON Export button Screenshot on the left Tokens Panel](https://private-user-images.githubusercontent.com/9948167/342395881-87ceaef0-79e5-4c6f-a25f-5130e47ed205.png?jwt=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3MTk0MDQ2NzQsIm5iZiI6MTcxOTQwNDM3NCwicGF0aCI6Ii85OTQ4MTY3LzM0MjM5NTg4MS04N2NlYWVmMC03OWU1LTRjNmYtYTI1Zi01MTMwZTQ3ZWQyMDUucG5nP1gtQW16LUFsZ29yaXRobT1BV1M0LUhNQUMtU0hBMjU2JlgtQW16LUNyZWRlbnRpYWw9QUtJQVZDT0RZTFNBNTNQUUs0WkElMkYyMDI0MDYyNiUyRnVzLWVhc3QtMSUyRnMzJTJGYXdzNF9yZXF1ZXN0JlgtQW16LURhdGU9MjAyNDA2MjZUMTIxOTM0WiZYLUFtei1FeHBpcmVzPTMwMCZYLUFtei1TaWduYXR1cmU9ZTg4NzAwZWFmNmM5ZDYzNDRmZjdlNWQzOTk3YjI4NTk4ODZiN2RiYTI1ODc0MDhmMzE3M2RkYTQwOGI2ZGU4NCZYLUFtei1TaWduZWRIZWFkZXJzPWhvc3QmYWN0b3JfaWQ9MCZrZXlfaWQ9MCZyZXBvX2lkPTAifQ.LIjsIUutX72A_TtosO_I7f1z9v0nBo5brLl_BMOp-7Y)

https://github.com/tokens-studio/tokens-studio-for-penpot/pull/191


### 2024-06-19 - Added CHANGELOG.md 

Added template for changelog
