(ns storages.tests
  (:require [clojure.test :as t]
            [storages.core :as st]
            [storages.fs.local :as fs]
            [storages.fs.misc :as misc])
  (:import java.io.File
           org.apache.commons.io.FileUtils))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- clean-temp-directory
  [next]
  (next)
  (let [directory (File. "/tmp/catacumba/")]
    (FileUtils/deleteDirectory directory)))

(t/use-fixtures :each clean-temp-directory)

;; --- Tests: FileSystemStorage

(t/deftest test-localfs-store-and-lookup
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        rpath  @(st/save storage "test.txt" "my content")
        fpath @(st/lookup storage rpath)
        fdata (slurp fpath)]
    (t/is (= (str fpath) "/tmp/catacumba/test/test.txt"))
    (t/is (= "my content" fdata))))

(t/deftest test-localfs-store-and-get-public-url
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        rpath  @(st/save storage "test.txt" "my content")
        ruri (st/public-url storage rpath)]
    (t/is (= (str ruri) "http://localhost:5050/test.txt"))))

(t/deftest test-localfs-store-and-lookup-with-subdirs
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        rpath  @(st/save storage "somepath/test.txt" "my content")
        fpath @(st/lookup storage rpath)
        fdata (slurp fpath)]
    (t/is (= (str fpath) "/tmp/catacumba/test/somepath/test.txt"))
    (t/is (= "my content" fdata))))

(t/deftest test-localfs-store-and-delete-and-check
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        rpath  @(st/save storage "test.txt" "my content")]
    (t/is @(st/delete storage rpath))
    (t/is (not @(st/exists? storage rpath)))))

(t/deftest test-localfs-store-duplicate-file-raises-exception
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})]
    (t/is @(st/save storage "test.txt" "my content"))
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/save storage "test.txt" "my content")))))

(t/deftest test-localfs-access-unauthorized-path
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})]
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/lookup storage "../test.txt")))
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/lookup storage "/test.txt")))))

;; --- Tests: ScopedPathStorage

(t/deftest test-localfs-scoped-store-and-lookup
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        storage (misc/scoped storage "some/prefix")
        rpath  @(st/save storage "test.txt" "my content")
        fpath @(st/lookup storage rpath)
        fdata (slurp fpath)]
    (t/is (= (str fpath) "/tmp/catacumba/test/some/prefix/test.txt"))
    (t/is (= "my content" fdata))))

(t/deftest test-localfs-scoped-store-and-delete-and-check
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        storage (misc/scoped storage "some/prefix")
        rpath  @(st/save storage "test.txt" "my content")]
    (t/is @(st/delete storage rpath))
    (t/is (not @(st/exists? storage rpath)))))

(t/deftest test-localfs-scoped-store-duplicate-file-raises-exception
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        storage (misc/scoped storage "some/prefix")]
    (t/is @(st/save storage "test.txt" "my content"))
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/save storage "test.txt" "my content")))))

(t/deftest test-localfs-scoped-access-unauthorized-path
  (let [storage (fs/filesystem {:basedir "/tmp/catacumba/test"
                                :baseuri "http://localhost:5050/"})
        storage (misc/scoped storage "some/prefix")]
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/lookup storage "../test.txt")))
    (t/is (thrown? java.util.concurrent.ExecutionException
                   @(st/lookup storage "/test.txt")))))

