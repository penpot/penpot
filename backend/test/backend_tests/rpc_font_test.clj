;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-font-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]])
  (:import
   java.io.RandomAccessFile))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest ttf-font-upload-1
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)
          font-id (uuid/custom 10 1)

          ttfdata (-> (io/resource "backend_tests/test_files/font-1.ttf")
                      (io/read*))

          params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id font-id
                   :font-family "somefont"
                   :font-weight 400
                   :font-style "normal"
                   :data {"font/ttf" ttfdata}}
          out     (th/command! params)]

      (t/is (= 1 (:call-count @mock)))

      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (uuid? (:id result)))
        (t/is (uuid? (:ttf-file-id result)))
        (t/is (uuid? (:otf-file-id result)))
        (t/is (uuid? (:woff1-file-id result)))
        (t/are [k] (= (get params k)
                      (get result k))
          :team-id
          :font-id
          :font-family
          :font-weight
          :font-style)))))

(t/deftest ttf-font-upload-2
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        data    (-> (io/resource "backend_tests/test_files/font-1.woff")
                    (io/read*))

        params  {::th/type :create-font-variant
                 ::rpc/profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :data {"font/woff" data}}
        out     (th/command! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [result (:result out)]
      (t/is (uuid? (:id result)))
      (t/is (uuid? (:ttf-file-id result)))
      (t/is (uuid? (:otf-file-id result)))
      (t/is (uuid? (:woff1-file-id result)))
      (t/are [k] (= (get params k)
                    (get result k))
        :team-id
        :font-id
        :font-family
        :font-weight
        :font-style))))

(t/deftest font-deletion-1
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        data1   (-> (io/resource "backend_tests/test_files/font-1.woff")
                    (io/read*))

        data2   (-> (io/resource "backend_tests/test_files/font-2.woff")
                    (io/read*))]

    ;; Create front variant
    (let [params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id font-id
                   :font-family "somefont"
                   :font-weight 400
                   :font-style "normal"
                   :data {"font/woff" data1}}
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out))))

    (let [params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id font-id
                   :font-family "somefont"
                   :font-weight 500
                   :font-style "normal"
                   :data {"font/woff" data2}}
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out))))

    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 6 (:freeze res))))

    (let [params {::th/type :delete-font
                  ::rpc/profile-id (:id prof)
                  :team-id team-id
                  :id font-id}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 0 (:freeze res)))
      (t/is (= 0 (:delete res))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed res)))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8 :hours 3}))]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 6 (:delete res)))))))

(t/deftest font-deletion-2
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        data1   (-> (io/resource "backend_tests/test_files/font-1.woff")
                    (io/read*))

        data2   (-> (io/resource "backend_tests/test_files/font-2.woff")
                    (io/read*))]

    ;; Create front variant
    (let [params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id font-id
                   :font-family "somefont"
                   :font-weight 400
                   :font-style "normal"
                   :data {"font/woff" data1}}
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out))))

    (let [params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id (uuid/custom 10 2)
                   :font-family "somefont"
                   :font-weight 400
                   :font-style "normal"
                   :data {"font/woff" data2}}
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out))))

    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 6 (:freeze res))))

    (let [params {::th/type :delete-font
                  ::rpc/profile-id (:id prof)
                  :team-id team-id
                  :id font-id}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 0 (:freeze res)))
      (t/is (= 0 (:delete res))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 1 (:processed res)))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8 :hours 3}))]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 3 (:delete res)))))))

(t/deftest font-deletion-3
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)
        data1   (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
        data2   (-> (io/resource "backend_tests/test_files/font-2.woff") (io/read*))
        params1 {::th/type :create-font-variant ::rpc/profile-id (:id prof)
                 :team-id team-id :font-id font-id :font-family "somefont"
                 :font-weight 400 :font-style "normal" :data {"font/woff" data1}}
        params2 {::th/type :create-font-variant ::rpc/profile-id (:id prof)
                 :team-id team-id :font-id font-id :font-family "somefont"
                 :font-weight 500 :font-style "normal" :data {"font/woff" data2}}
        out1    (th/command! params1)
        out2    (th/command! params2)]
    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    ;; freeze with hours 3 clock
    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 6 (:freeze res))))

    (let [params {::th/type :delete-font-variant ::rpc/profile-id (:id prof)
                  :team-id team-id :id (-> out1 :result :id)}
          out    (th/command! params)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    ;; no-op with hours 3 clock (nothing touched yet)
    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 0 (:freeze res)))
      (t/is (= 0 (:delete res))))

    ;; objects-gc at days 8, then storage-gc-touched at days 8 + 3h
    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 1 (:processed res)))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8 :hours 3}))]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 3 (:delete res)))))))

(t/deftest input-sanitization-1
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)
          font-id (uuid/custom 10 1)

          ttfdata (-> (io/resource "backend_tests/test_files/font-1.ttf")
                      (io/read*))

          params  {::th/type :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :font-id font-id
                   :font-family "somefont"
                   :font-weight 400
                   :font-style "normal"
                   :data {"font/ttf" "/etc/passwd"}}
          out     (th/command! params)]

      (t/is (= 0 (:call-count @mock)))
      ;; (th/print-result! out)

      (let [error      (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))))))

;; -----------------------------------------------------------------------
;; Helpers for chunked-upload font tests
;; -----------------------------------------------------------------------

(defn- split-bytes-into-chunks
  "Splits `data` (byte array) into chunks of at most `chunk-size` bytes.
  Returns a vector of byte arrays."
  [^bytes data chunk-size]
  (let [length (alength data)]
    (loop [offset 0 chunks []]
      (if (>= offset length)
        chunks
        (let [remaining (- length offset)
              size      (min chunk-size remaining)
              buf       (byte-array size)]
          (System/arraycopy data offset buf 0 size)
          (recur (+ offset size) (conj chunks buf)))))))

(defn- make-chunk-mfile
  "Writes `data` (byte array) to a tempfile and returns a map
  compatible with the upload-chunk :content parameter."
  [^bytes data mtype]
  (let [tmp (fs/create-tempfile :dir "/tmp/penpot" :prefix "test-font-chunk-")]
    (io/write* tmp data)
    {:filename "chunk"
     :path     tmp
     :mtype    mtype
     :size     (alength data)}))

(defn- create-upload-session!
  "Creates an upload session for `prof` with `total-chunks`. Returns the session-id UUID."
  [prof total-chunks]
  (let [out (th/command! {::th/type        :create-upload-session
                          ::rpc/profile-id (:id prof)
                          :total-chunks    total-chunks})]
    (t/is (nil? (:error out)))
    (:session-id (:result out))))

(defn- upload-font-chunked!
  "Splits `font-bytes` into chunks of `chunk-size` bytes, creates an upload
  session, uploads all chunks, and returns the session-id UUID."
  [prof ^bytes font-bytes mtype chunk-size]
  (let [chunks     (split-bytes-into-chunks font-bytes chunk-size)
        session-id (create-upload-session! prof (count chunks))]
    (doseq [[idx chunk-data] (map-indexed vector chunks)]
      (let [mfile (make-chunk-mfile chunk-data mtype)
            out   (th/command! {::th/type        :upload-chunk
                                ::rpc/profile-id (:id prof)
                                :session-id      session-id
                                :index           idx
                                :content         mfile})]
        (t/is (nil? (:error out)))))
    session-id))

(defn- assert-font-variant-result
  "Checks that a successful create-font-variant result has valid UUIDs and
  the expected scalar fields matching `params`."
  [params result]
  (t/is (uuid? (:id result)))
  (t/is (uuid? (:ttf-file-id result)))
  (t/is (uuid? (:otf-file-id result)))
  (t/is (uuid? (:woff1-file-id result)))
  (t/are [k] (= (get params k) (get result k))
    :team-id
    :font-id
    :font-family
    :font-weight
    :font-style))

;; -----------------------------------------------------------------------
;; Path 1 – Normal (direct :data bytes)
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-normal-ttf
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 10)
          data    (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          params  {::th/type    :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id     team-id
                   :font-id     font-id
                   :font-family "chunked-test"
                   :font-weight 400
                   :font-style  "normal"
                   :data        {"font/ttf" data}}
          out     (th/command! params)]
      (t/is (= 1 (:call-count @mock)))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-normal-otf
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 11)
          data    (-> (io/resource "backend_tests/test_files/font-1.otf") (io/read*))
          params  {::th/type    :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id     team-id
                   :font-id     font-id
                   :font-family "chunked-test"
                   :font-weight 400
                   :font-style  "normal"
                   :data        {"font/otf" data}}
          out     (th/command! params)]
      (t/is (= 1 (:call-count @mock)))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-normal-woff
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 12)
          data    (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
          params  {::th/type    :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id     team-id
                   :font-id     font-id
                   :font-family "chunked-test"
                   :font-weight 400
                   :font-style  "normal"
                   :data        {"font/woff" data}}
          out     (th/command! params)]
      (t/is (= 1 (:call-count @mock)))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

;; -----------------------------------------------------------------------
;; Path 2 – Legacy chunking (:data with vector of byte-arrays per mtype)
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-legacy-chunked-ttf
  "Upload a TTF via the legacy :data path where each mtype value is a
   vector of byte-array chunks (4 MiB each) instead of a single byte-array."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 20)
          full-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          ;; Simulate 4 MiB legacy chunks – font is small so a single chunk suffices
          chunks     (split-bytes-into-chunks full-bytes (* 4 1024 1024))
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "legacy-chunked"
                      :font-weight 700
                      :font-style  "italic"
                      :data        {"font/ttf" (vec chunks)}}
          out        (th/command! params)]
      (t/is (= 1 (:call-count @mock)))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-legacy-chunked-woff
  "Upload a WOFF via the legacy :data path with multiple sub-4 KiB chunks
   to exercise the SequenceInputStream concatenation path."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 21)
          full-bytes (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
          ;; Split into small chunks to exercise the SequenceInputStream path
          chunks     (split-bytes-into-chunks full-bytes 512)
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "legacy-chunked-woff"
                      :font-weight 400
                      :font-style  "normal"
                      :data        {"font/woff" (vec chunks)}}
          out        (th/command! params)]
      (t/is (= 1 (:call-count @mock)))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

;; -----------------------------------------------------------------------
;; Path 3 – New standardized chunked upload (:uploads map)
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-chunked-upload-ttf
  "Upload a TTF via the new :uploads path (chunked-upload API)."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 30)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          session-id (upload-font-chunked! prof font-bytes "font/ttf" (* 4 1024 1024))
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "new-chunked"
                      :font-weight 400
                      :font-style  "normal"
                      :uploads     {"font/ttf" session-id}}
          out        (th/command! params)]
      ;; quotes/check! is called at least once (for the font-variant quota) plus
      ;; once during session creation — assert it fired at least once.
      (t/is (>= (:call-count @mock) 1))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-chunked-upload-otf
  "Upload an OTF via the new :uploads path."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 31)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.otf") (io/read*))
          session-id (upload-font-chunked! prof font-bytes "font/otf" (* 4 1024 1024))
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "new-chunked-otf"
                      :font-weight 400
                      :font-style  "normal"
                      :uploads     {"font/otf" session-id}}
          out        (th/command! params)]
      (t/is (>= (:call-count @mock) 1))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-chunked-upload-woff
  "Upload a WOFF via the new :uploads path."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 32)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
          session-id (upload-font-chunked! prof font-bytes "font/woff" (* 4 1024 1024))
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "new-chunked-woff"
                      :font-weight 400
                      :font-style  "normal"
                      :uploads     {"font/woff" session-id}}
          out        (th/command! params)]
      (t/is (>= (:call-count @mock) 1))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

(t/deftest create-font-variant-chunked-upload-multi-chunk
  "Upload a WOFF split into many small chunks to exercise multi-chunk assembly."
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 33)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
          ;; Use a chunk-size smaller than 4 MiB to force multiple chunks while
          ;; staying within the 20-chunk-per-session quota limit (29836 / 2000 = ~15 chunks).
          session-id (upload-font-chunked! prof font-bytes "font/woff" 2000)
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "multi-chunk-woff"
                      :font-weight 400
                      :font-style  "normal"
                      :uploads     {"font/woff" session-id}}
          out        (th/command! params)]
      (t/is (>= (:call-count @mock) 1))
      (t/is (nil? (:error out)))
      (assert-font-variant-result params (:result out)))))

;; -----------------------------------------------------------------------
;; Error cases
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-missing-data-and-uploads
  "Neither :data nor :uploads is present — schema validation must reject it."
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        font-id (uuid/custom 10 40)
        params  {::th/type    :create-font-variant
                 ::rpc/profile-id (:id prof)
                 :team-id     team-id
                 :font-id     font-id
                 :font-family "bad"
                 :font-weight 400
                 :font-style  "normal"}
        out     (th/command! params)]
    (t/is (some? (:error out)))
    (t/is (= :validation (-> out :error ex-data :type)))))

(t/deftest create-font-variant-chunked-upload-missing-chunks
  "When only some chunks are uploaded the assembly step must fail."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 41)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          ;; 5000-byte chunks → 68640/5000 = 14 chunks; declare 15 but only upload 13
          chunks     (split-bytes-into-chunks font-bytes 5000)
          ;; Declare one extra chunk so assembly will fail (not all chunks present)
          session-id (create-upload-session! prof (inc (count chunks)))]

      ;; Upload all real chunks except the last one (omit it so the session is incomplete)
      (doseq [[idx chunk-data] (map-indexed vector (butlast chunks))]
        (let [mfile (make-chunk-mfile chunk-data "font/ttf")
              out   (th/command! {::th/type        :upload-chunk
                                  ::rpc/profile-id (:id prof)
                                  :session-id      session-id
                                  :index           idx
                                  :content         mfile})]
          (t/is (nil? (:error out)))))

      (let [out (th/command! {::th/type    :create-font-variant
                              ::rpc/profile-id (:id prof)
                              :team-id     team-id
                              :font-id     font-id
                              :font-family "missing-chunks"
                              :font-weight 400
                              :font-style  "normal"
                              :uploads     {"font/ttf" session-id}})]
        (t/is (some? (:error out)))))))

(t/deftest create-font-variant-chunked-upload-invalid-session
  "Passing a non-existent session-id must fail at assembly time."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 42)
          out     (th/command! {::th/type    :create-font-variant
                                ::rpc/profile-id (:id prof)
                                :team-id     team-id
                                :font-id     font-id
                                :font-family "bad-session"
                                :font-weight 400
                                :font-style  "normal"
                                :uploads     {"font/ttf" (uuid/next)}})]
      (t/is (some? (:error out))))))

;; -----------------------------------------------------------------------
;; Font size validation tests
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-size-exceeded-normal
  "Direct :data upload exceeding font-max-file-size must be rejected."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (with-redefs [app.config/config (assoc app.config/config :font-max-file-size 1)]
      (let [prof    (th/create-profile* 1 {:is-active true})
            team-id (:default-team-id prof)
            font-id (uuid/custom 10 50)
            data    (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
            params  {::th/type    :create-font-variant
                     ::rpc/profile-id (:id prof)
                     :team-id     team-id
                     :font-id     font-id
                     :font-family "size-exceeded"
                     :font-weight 400
                     :font-style  "normal"
                     :data        {"font/ttf" data}}
            out     (th/command! params)]
        (t/is (some? (:error out)))
        (t/is (= :restriction (-> out :error ex-data :type)))
        (t/is (= :font-max-file-size-reached (-> out :error ex-data :code)))))))

(t/deftest create-font-variant-size-exceeded-legacy-chunked
  "Legacy :data chunk-vector upload exceeding font-max-file-size must be rejected."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (with-redefs [app.config/config (assoc app.config/config :font-max-file-size 1)]
      (let [prof       (th/create-profile* 1 {:is-active true})
            team-id    (:default-team-id prof)
            font-id    (uuid/custom 10 51)
            full-bytes (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
            chunks     (split-bytes-into-chunks full-bytes (* 4 1024 1024))
            params     {::th/type    :create-font-variant
                        ::rpc/profile-id (:id prof)
                        :team-id     team-id
                        :font-id     font-id
                        :font-family "size-exceeded-legacy"
                        :font-weight 400
                        :font-style  "normal"
                        :data        {"font/woff" (vec chunks)}}
            out        (th/command! params)]
        (t/is (some? (:error out)))
        (t/is (= :restriction (-> out :error ex-data :type)))
        (t/is (= :font-max-file-size-reached (-> out :error ex-data :code)))))))

(t/deftest create-font-variant-size-exceeded-chunked-upload
  "New :uploads path exceeding font-max-file-size must be rejected after assembly."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 52)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          session-id (upload-font-chunked! prof font-bytes "font/ttf" (* 4 1024 1024))]
      (with-redefs [app.config/config (assoc app.config/config :font-max-file-size 1)]
        (let [out (th/command! {::th/type    :create-font-variant
                                ::rpc/profile-id (:id prof)
                                :team-id     team-id
                                :font-id     font-id
                                :font-family "size-exceeded-chunked"
                                :font-weight 400
                                :font-style  "normal"
                                :uploads     {"font/ttf" session-id}})]
          (t/is (some? (:error out)))
          (t/is (= :restriction (-> out :error ex-data :type)))
          (t/is (= :font-max-file-size-reached (-> out :error ex-data :code))))))))

(t/deftest create-font-variant-size-within-limit
  "Upload exactly at the limit must succeed."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 53)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          font-size  (alength ^bytes font-bytes)]
      (with-redefs [app.config/config (assoc app.config/config :font-max-file-size font-size)]
        (let [params {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "size-at-limit"
                      :font-weight 400
                      :font-style  "normal"
                      :data        {"font/ttf" font-bytes}}
              out    (th/command! params)]
          (t/is (nil? (:error out)))
          (assert-font-variant-result params (:result out)))))))

;; -----------------------------------------------------------------------
;; Font media-type validation tests
;; -----------------------------------------------------------------------

(t/deftest create-font-variant-invalid-type-normal
  "Direct :data upload with a disallowed mtype must be rejected."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 60)
          data    (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          params  {::th/type    :create-font-variant
                   ::rpc/profile-id (:id prof)
                   :team-id     team-id
                   :font-id     font-id
                   :font-family "invalid-type"
                   :font-weight 400
                   :font-style  "normal"
                   :data        {"application/octet-stream" data}}
          out     (th/command! params)]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :media-type-not-allowed (-> out :error ex-data :code))))))

(t/deftest create-font-variant-invalid-type-legacy-chunked
  "Legacy :data chunk-vector upload with a disallowed mtype must be rejected."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 61)
          full-bytes (-> (io/resource "backend_tests/test_files/font-1.woff") (io/read*))
          chunks     (split-bytes-into-chunks full-bytes (* 4 1024 1024))
          params     {::th/type    :create-font-variant
                      ::rpc/profile-id (:id prof)
                      :team-id     team-id
                      :font-id     font-id
                      :font-family "invalid-type-legacy"
                      :font-weight 400
                      :font-style  "normal"
                      :data        {"image/png" (vec chunks)}}
          out        (th/command! params)]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :media-type-not-allowed (-> out :error ex-data :code))))))

(t/deftest create-font-variant-invalid-type-chunked-upload
  "New :uploads path with a disallowed mtype must be rejected after assembly."
  (with-mocks [_mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof       (th/create-profile* 1 {:is-active true})
          team-id    (:default-team-id prof)
          font-id    (uuid/custom 10 62)
          font-bytes (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))
          ;; Upload the bytes under a valid session but lie about the mtype
          ;; when calling create-font-variant.
          session-id (upload-font-chunked! prof font-bytes "font/ttf" (* 4 1024 1024))
          out        (th/command! {::th/type    :create-font-variant
                                   ::rpc/profile-id (:id prof)
                                   :team-id     team-id
                                   :font-id     font-id
                                   :font-family "invalid-type-chunked"
                                   :font-weight 400
                                   :font-style  "normal"
                                   :uploads     {"image/jpeg" session-id}})]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :media-type-not-allowed (-> out :error ex-data :code))))))

;; --- Font family name validation / XSS prevention

(t/deftest create-font-variant-with-invalid-family
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 100)
          data    (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))]

      ;; name with < should fail
      (let [params {::th/type :create-font-variant
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :font-id font-id
                    :font-family "evil<script>alert(1)</script>"
                    :font-weight 400 :font-style "normal"
                    :data {"font/ttf" data}}
            out    (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :params-validation)))

      ;; name with ' should fail
      (let [params {::th/type :create-font-variant
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :font-id font-id
                    :font-family "evil'name"
                    :font-weight 400 :font-style "normal"
                    :data {"font/ttf" data}}
            out    (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation)))

      ;; name with } should fail
      (let [params {::th/type :create-font-variant
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :font-id font-id
                    :font-family "evil}name"
                    :font-weight 400 :font-style "normal"
                    :data {"font/ttf" data}}
            out    (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation)))

      ;; valid name should succeed
      (let [params {::th/type :create-font-variant
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :font-id (uuid/custom 10 101)
                    :font-family "Source Sans Pro"
                    :font-weight 400 :font-style "normal"
                    :data {"font/ttf" data}}
            out    (th/command! params)]
        (t/is (th/success? out))))))

(t/deftest update-font-with-invalid-family
  (with-mocks [mock {:target 'app.rpc.quotes/check! :return nil}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          font-id (uuid/custom 10 102)
          data    (-> (io/resource "backend_tests/test_files/font-1.ttf") (io/read*))]

      ;; Create a valid font first
      (let [params {::th/type :create-font-variant
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :font-id font-id
                    :font-family "ValidFont"
                    :font-weight 400 :font-style "normal"
                    :data {"font/ttf" data}}
            out    (th/command! params)]
        (t/is (th/success? out)))

      ;; rename with < should fail
      (let [params {::th/type :update-font
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :id font-id
                    :name "evil<script>x</script>"}
            out    (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :params-validation)))

      ;; rename with ' should fail
      (let [params {::th/type :update-font
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :id font-id
                    :name "evil'name"}
            out    (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation)))

      ;; valid rename should succeed
      (let [params {::th/type :update-font
                    ::rpc/profile-id (:id prof)
                    :team-id team-id :id font-id
                    :name "Valid Font Name"}
            out    (th/command! params)]
        (t/is (th/success? out))))))
