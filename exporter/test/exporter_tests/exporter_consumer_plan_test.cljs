;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-consumer-plan-test
  "The render plan of the new tree: same chunks and names as the
  legacy planner, dispatching on the wasm flag."
  (:require
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [exporter.consumer.plan :as plan]))

(defn- exports
  [n type scale]
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    (mapv (fn [i]
            {:file-id   file-id
             :page-id   page-id
             :object-id (uuid/next)
             :name      (str "shape-" i)
             :suffix    ""
             :scale     scale
             :type      type})
          (range n))))

(t/deftest browser-exports-are-chunked
  (let [parts (plan/prepare-exports (exports 120 :png 1) "token" false)]
    (t/is (= 3 (count parts)))
    (t/is (= [50 50 20] (mapv (comp count :objects) parts)))))

(t/deftest wasm-exports-render-as-one-group
  (let [parts (plan/prepare-exports (exports 120 :png 1) "token" true)]
    (t/is (= 1 (count parts)))
    (t/is (= 120 (count (:objects (first parts)))))))

(t/deftest duplicate-names-get-suffixed
  (let [items [(assoc ((exports 1 :png 1) 0) :name "same")
               (assoc ((exports 1 :png 1) 0) :name "same")]
        parts (plan/prepare-exports items "token" false)]
    (t/is (= ["same.png" "same-2.png"]
             (mapv :filename (:objects (first parts)))))))
