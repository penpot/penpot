use cxx::UniquePtr;
use std::cell::UnsafeCell;
use std::fmt;
use std::path::Path;

use crate::error::Error;
use crate::ffi::ffi;

/// The Database class is the main class of `LbugDB`. It manages all database components.
pub struct Database {
    pub(crate) db: UnsafeCell<UniquePtr<ffi::Database>>,
}

unsafe impl Send for Database {}
unsafe impl Sync for Database {}

#[derive(Clone, Debug)]
/// Configuration options for the database.
pub struct SystemConfig {
    /// Max size of the buffer pool in bytes
    ///
    /// The larger the buffer pool, the more data from the database files is kept in memory,
    /// reducing the amount of File I/O.
    ///
    /// Defaults to an auto-detected size
    buffer_pool_size: u64,
    /// The maximum number of threads to use when executing queries
    /// Defaults to auto-detect from the number of processors
    max_num_threads: u64,
    /// When true, new columns will be compressed if possible
    /// Defaults to true
    enable_compression: bool,
    /// Whether or not the database can be written to from this instance
    /// Defaults to not read-only
    read_only: bool,
    /// The maximum size of the database. Defaults to 8TB
    max_db_size: u64,
    /// If true, the database will automatically checkpoint when the size of the WAL file exceeds the checkpoint threshold.
    auto_checkpoint: bool,
    /// The threshold of the WAL file size in bytes. When the size of the WAL file exceeds this threshold, the database will checkpoint if autoCheckpoint is true.
    checkpoint_threshold: i64,
    /// If true, any WAL replaying failure when loading the database will throw an error. Otherwise, Lbug will silently ignore the failure and replay up to where the error occured.
    throw_on_wal_replay_failure: bool,
    /// If true, the database will use checksums to detect corruption in the WAL file.
    enable_checksums: bool,
}

#[cfg(test)]
pub(crate) const SYSTEM_CONFIG_FOR_TESTS: SystemConfig = SystemConfig {
    buffer_pool_size: 0,
    max_num_threads: 0,
    enable_compression: true,
    read_only: false,
    // Use a smaller max DB size since these tests don't need a large DB and it limits the number of
    // databases which can be open in a single process
    max_db_size: 16 * 1024 * 1024 * 1024,
    auto_checkpoint: true,
    checkpoint_threshold: -1_i64,
    throw_on_wal_replay_failure: true,
    enable_checksums: true,
};

impl Default for SystemConfig {
    fn default() -> SystemConfig {
        SystemConfig {
            buffer_pool_size: 0,
            max_num_threads: 0,
            enable_compression: true,
            read_only: false,
            // This is a little weird, but it's a temporary interface
            max_db_size: u64::from(u32::MAX),
            auto_checkpoint: true,
            checkpoint_threshold: -1_i64,
            throw_on_wal_replay_failure: true,
            enable_checksums: true,
        }
    }
}

impl SystemConfig {
    pub fn buffer_pool_size(mut self, buffer_pool_size: u64) -> Self {
        self.buffer_pool_size = buffer_pool_size;
        self
    }
    pub fn max_num_threads(mut self, max_num_threads: u64) -> Self {
        self.max_num_threads = max_num_threads;
        self
    }
    pub fn enable_compression(mut self, enable_compression: bool) -> Self {
        self.enable_compression = enable_compression;
        self
    }
    pub fn read_only(mut self, read_only: bool) -> Self {
        self.read_only = read_only;
        self
    }
    pub fn max_db_size(mut self, max_db_size: u64) -> Self {
        self.max_db_size = max_db_size;
        self
    }
    pub fn auto_checkpoint(mut self, auto_checkpoint: bool) -> Self {
        self.auto_checkpoint = auto_checkpoint;
        self
    }
    pub fn checkpoint_threshold(mut self, checkpoint_threshold: i64) -> Self {
        self.checkpoint_threshold = checkpoint_threshold;
        self
    }
    pub fn throw_on_wal_replay_failure(mut self, throw_on_wal_replay_failure: bool) -> Self {
        self.throw_on_wal_replay_failure = throw_on_wal_replay_failure;
        self
    }
    pub fn enable_checksums(mut self, enable_checksums: bool) -> Self {
        self.enable_checksums = enable_checksums;
        self
    }
}

pub(crate) const IN_MEMORY_DB_NAME: &str = ":memory:";

impl Database {
    /// Creates a database object
    ///
    /// # Arguments:
    /// * `path`: Path of the database. If the database does not already exist, it will be created.
    ///   If the path is empty, or equal to `:memory:`, the database will be created in-memory.
    /// * `config`: Database configuration to use
    pub fn new<P: AsRef<Path>>(path: P, config: SystemConfig) -> Result<Self, Error> {
        Ok(Database {
            db: UnsafeCell::new(ffi::new_database(
                ffi::StringView::new(&path.as_ref().display().to_string()),
                config.buffer_pool_size,
                config.max_num_threads,
                config.enable_compression,
                config.read_only,
                config.max_db_size,
                config.auto_checkpoint,
                config.checkpoint_threshold,
                config.throw_on_wal_replay_failure,
                config.enable_checksums,
            )?),
        })
    }

    /// Creates an in-memory database
    ///
    /// Alias for `Database::new(":memory:", config)`
    pub fn in_memory(config: SystemConfig) -> Result<Self, Error> {
        Self::new(IN_MEMORY_DB_NAME, config)
    }
}

impl fmt::Debug for Database {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Database")
            .field("db", &"Opaque C++ data".to_string())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use anyhow::{Error, Result};

    use crate::connection::Connection;
    use crate::database::SYSTEM_CONFIG_FOR_TESTS;
    use crate::database::{Database, SystemConfig};
    use crate::value::Value;
    use std::collections::HashSet;
    use std::fs::File;
    use std::io::Write;

    #[test]
    fn create_database() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let _db = Database::new(temp_dir.path().join("test"), SYSTEM_CONFIG_FOR_TESTS)?;
        temp_dir.close()?;
        Ok(())
    }

    #[test]
    fn create_database_no_compression() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let _ = Database::new(
            temp_dir.path().join("test"),
            SYSTEM_CONFIG_FOR_TESTS.enable_compression(false),
        )?;
        temp_dir.close()?;
        Ok(())
    }

    #[test]
    fn test_database_read_only() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        // Create database first so that it can be opened read-only
        {
            Database::new(temp_dir.path().join("test"), SYSTEM_CONFIG_FOR_TESTS)?;
        }
        let db = Database::new(
            temp_dir.path().join("test"),
            SYSTEM_CONFIG_FOR_TESTS.read_only(true),
        )?;
        let conn = Connection::new(&db)?;
        let result: Error = conn
            .query("CREATE NODE TABLE Person(name STRING, age INT64, PRIMARY KEY(name));")
            .expect_err("Write query should produce an error on a read-only DB")
            .into();

        assert_eq!(
            result.to_string(),
            "Query execution failed: Connection exception: Cannot execute write operations in a read-only database!"
        );
        Ok(())
    }

    #[test]
    fn test_database_max_size() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        {
            // Max DB size of 4GB should be fine
            Database::new(
                temp_dir.path().join("test"),
                SystemConfig::default().max_db_size(1 << 32),
            )?;
        }
        {
            Database::new(
                temp_dir.path().join("test"),
                SystemConfig::default().max_db_size(0),
            )
            .expect_err("0 is not a valid max DB size");
        }
        Ok(())
    }

    #[test]
    fn test_database_auto_checkpoint() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let db = Database::new(
            temp_dir.path().join("test"),
            SYSTEM_CONFIG_FOR_TESTS.auto_checkpoint(false),
        )?;
        let conn = Connection::new(&db)?;
        let result = conn.query("CALL current_setting('auto_checkpoint') RETURN *");
        assert_eq!(
            result?.next().unwrap()[0],
            Value::String("False".to_string())
        );
        // check default checkpoint threshold
        let result = conn.query("CALL current_setting('checkpoint_threshold') RETURN *");
        assert_eq!(
            result?.next().unwrap()[0],
            Value::String("16777216".to_string())
        );
        Ok(())
    }

    #[test]
    fn test_database_checkpoint_threshold() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let db = Database::new(
            temp_dir.path().join("test"),
            SYSTEM_CONFIG_FOR_TESTS.checkpoint_threshold(1234),
        )?;
        let conn = Connection::new(&db)?;
        let result = conn.query("CALL current_setting('checkpoint_threshold') RETURN *");
        assert_eq!(
            result?.next().unwrap()[0],
            Value::String("1234".to_string())
        );
        Ok(())
    }

    #[test]
    fn test_database_throw_on_wal_replay_failure() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let db_path = temp_dir.path().join("test");
        let wal_path = db_path.with_extension("wal");
        let mut wal_file = File::create(wal_path)?;
        wal_file.write_all(b"aaaaaaaaaaaaaaaaaaaaaaaa")?;
        let db = Database::new(
            db_path,
            SYSTEM_CONFIG_FOR_TESTS.throw_on_wal_replay_failure(false),
        )?;
        let conn = Connection::new(&db)?;
        let result = conn.query("return 1");
        assert_eq!(result?.next().unwrap()[0], Value::Int64(1));
        Ok(())
    }

    #[test]
    fn test_database_enable_checksums() -> Result<()> {
        let temp_dir = tempfile::tempdir()?;
        let db_path = temp_dir.path().join("test");
        {
            let db = Database::new(
                db_path.clone(),
                SYSTEM_CONFIG_FOR_TESTS
                    .auto_checkpoint(false)
                    .enable_checksums(true),
            )?;
            let conn = Connection::new(&db)?;
            conn.query("call force_checkpoint_on_close=false")?;
            conn.query("create node table testtest1(id int64 primary key)")?;
        }
        {
            Database::new(
                db_path.clone(),
                SYSTEM_CONFIG_FOR_TESTS.enable_checksums(false),
            )
            .expect_err("An error should be thrown if the enable_checksums config doesn't match the one used to produce a WAL file");
        }
        Ok(())
    }

    #[test]
    fn test_database_in_memory() -> Result<()> {
        use crate::Value;
        let db = Database::in_memory(SYSTEM_CONFIG_FOR_TESTS)?;
        // If the special name is ever changed (or removed) lbug is likely to just create a db directory with that name
        assert!(!std::path::Path::new(crate::database::IN_MEMORY_DB_NAME).exists());

        let conn = Connection::new(&db)?;
        conn.query("CREATE NODE TABLE Person(name STRING, age INT64, PRIMARY KEY(name))")?;
        conn.query("BEGIN TRANSACTION")?;
        conn.query("CREATE (:Person {name: 'Alice', age: 25})")?;
        conn.query("CREATE (:Person {name: 'Bob', age: 30})")?;
        conn.query("COMMIT")?;
        let results: HashSet<String> = conn
            .query("MATCH (p:Person) RETURN p.name")?
            .map(|tuple| {
                assert!(tuple.len() == 1);
                if let Value::String(value) = &tuple[0] {
                    value.clone()
                } else {
                    panic!(
                        "Expected query values to be strings, but got {:?} instead",
                        tuple[0]
                    )
                }
            })
            .collect();
        assert!(results == HashSet::from(["Alice".to_string(), "Bob".to_string()]));
        Ok(())
    }
}
