;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-resources-test
  "The run's local resources: the artifact files parked in the injected
  temp area until the settle uploads them, zipped once a run packs more
  than one."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [clojure.string :as cstr]
   [exporter.resources :as rsc]))

(defn- test-area
  []
  (let [dir (path/join (os/tmpdir) (str "penpot-resources-test." (uuid/next)))]
    dir))

(t/deftest create-parks-the-artifact-under-the-injected-tmpdir
  (let [dir      (test-area)
        resource (rsc/create dir :pdf "a/b:c")]
    (t/testing "the path lives in the injected area, named by type"
      (t/is (cstr/starts-with? (:path resource) dir))
      (t/is (cstr/includes? (:path resource) "penpot.resource.pdf.")))
    (t/testing "the descriptor names the upload"
      (t/is (= "application/pdf" (:mtype resource)))
      (t/is (= "a_b_c.pdf" (:filename resource)))
      (t/is (= "a/b:c" (:name resource))))
    (t/is (uuid? (:id resource)))))

(t/deftest ^:async zip-packs-added-files
  (let [dir      (test-area)
        _        (await (fsp/mkdir dir #js {:recursive true}))
        resource (rsc/create dir :zip "the export")
        member   (path/join dir "member.txt")]
    (try
      (await (fsp/writeFile member "member!"))
      (let [entries (atom [])
            zip     (rsc/create-zip :resource resource
                                    :on-progress (fn [e] (swap! entries conj e)))]
        (rsc/add-to-zip zip member "member.txt")
        (await (rsc/close-zip zip))
        (t/testing "the artifact landed"
          (let [stat (await (fsp/stat (:path resource)))]
            (t/is (pos? (.-size stat)))))
        (t/testing "one entry traveled with its name"
          (t/is (= [{:done 1 :filename "member.txt"}] @entries))))
      (finally
        (await (fsp/rm dir #js {:recursive true :force true}))))))

(defn- fake-zip
  "An event-emitter stand-in for `close-zip`: records its listeners and
  runs `on-finalize` with them once finalized."
  [on-finalize]
  (let [handlers (atom {})
        fake     #js {}]
    (unchecked-set fake "on"
                   (fn [event cb]
                     (swap! handlers assoc event cb)
                     fake))
    (unchecked-set fake "finalize" (fn [] (on-finalize @handlers)))
    fake))

(t/deftest ^:async close-zip-rejects-on-archiver-error
  (let [zip (fake-zip (fn [handlers]
                        ((get handlers "error") (js/Error. "boom"))))]
    (try
      (await (rsc/close-zip zip {:timeout-ms 1000}))
      (t/is false "an archiver error must reject, not hang the poller slot")
      (catch :default cause
        (t/is (= "boom" (ex-message cause)))))))

(t/deftest ^:async close-zip-rejects-when-the-close-never-lands
  (let [zip (fake-zip (fn [_handlers] nil))]
    (try
      (await (rsc/close-zip zip {:timeout-ms 10}))
      (t/is false "a missing close must reject through the timeout")
      (catch :default cause
        (t/is (= "zip close timed out" (ex-message cause)))))))
