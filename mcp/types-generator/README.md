# Types Generator

This subproject contains helper scripts used in the development of the
Penpot MCP server for generate the types yaml.

## Setup

This project uses [pixi](https://pixi.sh) for environment management
(already included in devenv).

Install the environment via (optional, already handled by `build` script)

    pixi install


### Buld API types

The script `prepare_api_docs.py` reads API documentation from the Web
and collects it in a single yaml file, which is then used by an MCP
tool to provide API documentation to an LLM on demand.

Running the script:

    ./build <optional-url>

This will generate `../packages/server/data/api_types.yml`.
