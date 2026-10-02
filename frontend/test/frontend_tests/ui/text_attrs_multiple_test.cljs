;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.text-attrs-multiple-test
  (:require
   [app.main.ui.workspace.sidebar.options.shapes.multiple :as multiple]
   [cljs.test :as t :include-macros true]))

(defn- content
  [font-family]
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "a" :font-family font-family}]}]}]})

(def ^:private inter (content "Inter"))

(def ^:private objects
  {:board-1 {:id :board-1 :type :frame :shapes [:inner :text-1]}
   :inner   {:id :inner :type :frame :shapes [:text-2]}
   :board-2 {:id :board-2 :type :frame :shapes [:text-3 :rect]}
   :text-1  {:id :text-1 :type :text :content inter :applied-tokens {:typography "body"}}
   :text-2  {:id :text-2 :type :text :content inter :applied-tokens {:typography "body"}}
   :text-3  {:id :text-3 :type :text :content (content "Roboto") :applied-tokens {:typography "body"}}
   :rect    {:id :rect :type :rect}})

(defn- text-attrs
  [labels objects]
  (multiple/get-attrs* (map objects labels) objects :text))

(t/deftest boards-read-nested-text-values
  (let [[ids values tokens] (text-attrs [:board-1] objects)]
    (t/is (= [:text-2 :text-1] ids))
    (t/is (= "Inter" (:font-family values)))
    (t/is (= "body" (:typography tokens))))
  (let [[_ values] (text-attrs [:board-1 :board-2] objects)]
    (t/is (= :multiple (:font-family values)))))
