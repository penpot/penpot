<div align="center">
  <picture>
    <!-- <source srcset="https://ladybugdb.com/img/lbug-logo-dark.png" media="(prefers-color-scheme: dark)"> -->
    <img src="https://ladybugdb.com/logo.png" height="100" alt="Ladybug Logo">
  </picture>
</div>

<br>

<p align="center">
  <a href="https://github.com/LadybugDB/ladybug/actions">
    <img src="https://github.com/LadybugDB/ladybug/actions/workflows/ci-workflow.yml/badge.svg?branch=master" alt="Github Actions Badge"></a>
  <a href="https://discord.com/invite/hXyHmvW3Vy">
    <img src="https://img.shields.io/discord/1162999022819225631?logo=discord" alt="discord" /></a>
  <a href="https://twitter.com/lbugdb">
    <img src="https://img.shields.io/badge/follow-@lbugdb-1DA1F2?logo=twitter" alt="twitter"></a>
</p>

# Ladybug
Ladybug is an embedded graph database built for query speed and scalability. Ladybug is optimized for handling complex analytical workloads
on very large databases and provides a set of retrieval features, such as a full text search and vector indices. Our core feature set includes:

- Flexible Property Graph Data Model and Cypher query language
- Embeddable, serverless integration into applications
- Native full text search and vector index
- Columnar disk-based storage
- Columnar sparse row-based (CSR) adjacency list/join indices
- Vectorized and factorized query processor
- Novel and very fast join algorithms
- Multi-core query parallelism
- Serializable ACID transactions
- Wasm (WebAssembly) bindings for fast, secure execution in the browser

Ladybug is being developed by [LadybugDB Developers](https://github.com/LadybugDB) and
is available under a permissible license. So try it out and help us make it better! We welcome your feedback and feature requests.

The database was formerly known as [Kuzu](https://github.com/kuzudb/kuzu).

## Installation

> [!WARNING]
> Many of these binary installation methods are not functional yet. We need to work through package names, availability and convention issues.
> For now, use the build from source method.

| Language | Installation                                                           |
| -------- |------------------------------------------------------------------------|
| Python   | `pip install real_ladybug`                                                     |
| NodeJS   | `npm install lbug`                                                     |
| Rust     | `cargo add lbug`                                                       |
| Go       | `go get github.com/lbugdb/go-lbug`                                     |
| Swift    | [lbug-swift](https://github.com/lbugdb/lbug-swift)                     |
| Java     | [Maven Central](https://central.sonatype.com/artifact/com.ladybugdb/lbug) |
| C/C++    | [precompiled binaries](https://github.com/LadybugDB/ladybug/releases/latest) |
| CLI      | [precompiled binaries](https://github.com/LadybugDB/ladybug/releases/latest) |

To learn more about installation, see our [Installation](https://docs.ladybugdb.com/installation) page.

## Getting Started

Refer to our [Getting Started](https://docs.ladybugdb.com/get-started/) page for your first example.

## Build from Source

You can build from source using the instructions provided in the [developer guide](https://docs.ladybugdb.com/developer-guide/).

## Contributing
We welcome contributions to Ladybug. If you are interested in contributing to Ladybug, please read our [Contributing Guide](CONTRIBUTING.md).

## License
By contributing to Ladybug, you agree that your contributions will be licensed under the [MIT License](LICENSE).

## Contact
You can contact us at [social@ladybugdb.com](mailto:social@ladybugdb.com) or [join our Discord community](https://discord.com/invite/hXyHmvW3Vy).
