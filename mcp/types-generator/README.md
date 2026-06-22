# Types Generator

This subproject contains helper scripts used in the development of the
Penpot MCP server, specifically for the generation of a YAML file containing 
Penpot plugin API types and their documentation.

## Setup

This project uses [pixi](https://pixi.sh) for environment management
(already included in devenv).

Install the environment via (optional, already handled by `build` script)

    pixi install

## Running the API Documentation Preparation Script

The script `prepare_api_docs.py` reads API documentation from a Web URL
and collects it in a single YAML file, which is then used by an MCP
tool to provide API documentation to an LLM on demand.

Successful execution will generate the output file `../packages/server/data/api_types.yml`.

### Generating the YAML File for a Given URL

Running the script:

    pixi run python prepare_api_docs.py <url>

You can alternatively run `./build <url>`, which additionally performs pixi environment installation. 

For example, to generate the API documentation based on the current PROD Penpot API documentation,
use the URL

    https://doc.plugins.penpot.app

### Generating the YAML File Based on the Current Documentation in the Repository  

Requirement: [Caddy](https://caddyserver.com/download) must be installed and available in the system path.

To generate the API documentation based on the current documentation in the repository,
run the `build:types` script in the parent directory, i.e.

    cd ..
    pnpm run build:types

This will spawn a local HTTP server on port 9090 and run the `prepare_api_docs.py` script with the 
URL `http://localhost:9090`.  
To run only the server without executing the script, run

    cd ..
    caddy file-server --root ../plugins/dist/doc/ --listen 127.0.0.1:9090

