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

### 2024-08-05 - Fix opacity updating

[Link to PR](https://github.com/orgs/tokens-studio/projects/69/views/11?pane=issue&itemId=69801248)

Fixes opacity not being applied correctly to shapes.

### 2024-07-25 - UX Improvements for the context menu

[Link to PR](https://github.com/tokens-studio/tokens-studio-for-penpot/pull/224)

Changes context menu behavior according to [Specs](https://github.com/tokens-studio/obsidian-docs/blob/31f0d7f98ff5ac922970f3009fe877cc02d6d0cd/Products/TS%20for%20Penpot/Specs/Token%20State%20Specs.md)

-   Removing a token wont update the shape
-   Mixed selection (shapes with applied, shapes without applied) will always unapply token
-   Multi selection of shapes without token will apply the token to all
-   Every shape change and token applying should be one undo step now
-   Prevent token applying when nothign is selected
-   `All` is a toggle instead of a checkbox if all tokens have been applied
    -   For instance with border radius the context menu can `:r1 :r2 :r3 :r4` which will highlight `All`
    -   If one attribute is missing it will check the single attributes
    -   Clicking a single attribute after clicking `All` will remove the other attributes
-   Fixed some issues for switching between split and uniform border radius
-   Clicking a token wont apply all attributes anymore. We apply only a select collection of attributes, which makes most sense. For instance on `sizing` we only apply `width` and `height` instead of all (`max-width`, `max-height`, `min-heigt`, `min-width`)


### 2024-07-05 - UX Improvements when applying tokens

[Link to PR](https://github.com/tokens-studio/tokens-studio-for-penpot/compare/token-studio-develop...ux-improvements?body=&expand=1)

- When unapplying tokens, the shape doesn't change anymore
- Multi Select behavior according to [Specs](https://github.com/tokens-studio/obsidian-docs/blob/31f0d7f98ff5ac922970f3009fe877cc02d6d0cd/Products/TS%20for%20Penpot/Specs/Token%20State%20Specs.md)
- Undo for applying tokens and change the shape is now one undo step
  (before applying a token created multiple undo steps)
  
[Video](https://github.com/tokens-studio/tokens-studio-for-penpot/assets/1898374/01d9d429-cab1-41cd-a3ff-495003edd3e8
)

### 2024-07-01 - Disallow creating tokens at existing paths

Disallow creating tokens at an existing path.

[Video](https://github.com/tokens-studio/tokens-studio-for-penpot/assets/1898374/557990fe-efe7-445b-8a1d-824396049db7
)

Example:
We've got a token with `borderRadius.sm`, so we can't allow to create a token at `borderRadius` or `borderRadius.sm`.
But we can allow creating a token at `borderRadius.md`.


### 2024-06-26 - Disallow special characters in token name

- Only allows digits, letters and `-` as a part of a token name

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
