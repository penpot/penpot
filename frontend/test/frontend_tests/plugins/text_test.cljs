;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.text-test
  (:require
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.plugins.fonts :as fonts]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.text :as plugins.text]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest font-apply-to-text-uses-font-id-not-shape-id
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        font     (fonts/font-proxy
                  plugin-id
                  {:id "font-id"
                   :family "Inter"
                   :name "Inter"
                   :variants [{:id "regular"
                               :name "Regular"
                               :weight "400"
                               :style "normal"}]})
        text     (shape/shape-proxy plugin-id file-id page-id shape-id)
        captured (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  dwt/update-attrs
                  (fn [id attrs]
                    (reset! captured {:id id :attrs attrs})
                    :update-attrs)
                  st/emit! mock/noop]
      (.applyToText font text nil)
      (t/is (= shape-id (:id @captured)))
      (t/is (= "font-id" (get-in @captured [:attrs :font-id]))))))

(t/deftest font-apply-to-range-uses-hidden-range-bounds
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        font     (fonts/font-proxy
                  plugin-id
                  {:id "font-id"
                   :family "Inter"
                   :name "Inter"
                   :variants [{:id "regular"
                               :name "Regular"
                               :weight "400"
                               :style "normal"}]})
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 1 4)
        captured (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  dwt/update-text-range
                  (fn [id start end attrs]
                    (reset! captured {:id id
                                      :start start
                                      :end end
                                      :attrs attrs})
                    :update-text-range)
                  st/emit! mock/noop]
      (.applyToRange font range nil)
      (t/is (= shape-id (:id @captured)))
      (t/is (= 1 (:start @captured)))
      (t/is (= 4 (:end @captured)))
      (t/is (= "font-id" (get-in @captured [:attrs :font-id]))))))
