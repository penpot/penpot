;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.messages-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.types.objects-map :as omap]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]
   [app.worker.messages :as wm]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(defn- make-page
  []
  (-> (fb/create-state)
      (fb/add-file {:name "Test"})
      (fb/add-page {:name "Page 1"})
      (fb/add-shape {:type :rect :name "unique-shape-name" :x 0 :y 0 :width 100 :height 100})
      (fb/get-current-page)))

(t/deftest objects-map-shapes-skip-transit
  (let [page    (update (make-page) :objects omap/wrap)
        encoded (wm/encode {:sender-id (uuid/next)
                            :payload {:cmd :index/initialize :page page}})]
    (t/is (not (str/includes? (obj/get encoded "payload") "unique-shape-name")))
    (t/is (instance? js/Map (obj/get encoded "pageObjects")))
    (t/is (= (count (:objects page))
             (.-size (obj/get encoded "pageObjects"))))))
