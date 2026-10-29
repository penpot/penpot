;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.media-info-test
  (:require
   [app.common.schema :as sm]
   [app.jobs :as jobs]
   [app.media.jobs :as mj]
   [app.util.shell :as shell]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn- materialize!
  [fixture mtype]
  (mj/materialize-input th/*system* {:path (th/tempfile fixture)
                                     :mtype mtype}))

(t/deftest media-info-job-returns-the-typed-result
  (let [ref    (materialize! "backend_tests/test_files/sample.png" "image/png")
        result (th/run-task! :media-info {:input ref})]

    (t/is (true? ((sm/validator mj/schema:media-info-result) result)))
    (t/is (pos? (:width result)))
    (t/is (pos? (:height result)))
    (t/is (= "image/png" (:mtype result)))
    (t/is (= (:size ref) (:size result)))))

(t/deftest media-info-job-reads-an-svg-without-imagemagick
  (with-redefs [shell/exec! (fn [& _]
                              (throw (ex-info "ImageMagick must not run on an SVG" {})))]
    (let [ref    (materialize! "backend_tests/test_files/sample1.svg" "image/svg+xml")
          result (th/run-task! :media-info {:input ref})]

      (t/is (pos? (:width result)))
      (t/is (pos? (:height result)))
      (t/is (= "image/svg+xml" (:mtype result))))))

(t/deftest media-info-job-fails-with-invalid-image
  (let [path (doto (fs/create-tempfile :dir "/tmp/penpot" :prefix "not-an-image-")
               (spit "this is not an image"))
        ref  (mj/materialize-input th/*system* {:path path :mtype "image/png"})
        cause (try
                (th/run-task! :media-info {:input ref})
                nil
                (catch Throwable cause cause))]

    (t/is (some? cause))
    (t/is (= :invalid-image (:code (ex-data cause))))))

(t/deftest media-info-job-def-notifies-and-declares-its-result
  (let [def (get (get th/*system* ::jobs/defs) :media-info)]
    (t/is (some? def))
    (t/is (true? (::jobs/notifies? def))
          "the synchronous caller waits on the reply key")
    (t/is (= mj/schema:media-info-result (::jobs/result-schema def)))))

(t/deftest media-info-job-schema-only-accepts-a-storage-reference
  (let [validator (sm/validator mj/schema:media-info)]
    (t/is (true? (validator {:input {:id (java.util.UUID/randomUUID)
                                     :mtype "image/png"
                                     :size 10}})))
    (t/is (false? (validator {:input {:path "/tmp/upload.png"
                                      :mtype "image/png"}})))))
