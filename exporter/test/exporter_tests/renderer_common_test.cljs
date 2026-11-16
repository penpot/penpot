;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-common-test
  (:require
   [app.renderer.common :as common]
   [cljs.test :as t :refer [deftest is testing]]
   [clojure.string :as str]
   [promesa.core :as p]))

(defn- fake-page
  "Minimal Playwright page/locator pair. Only the methods the helper uses."
  [width height]
  (let [node #js {:waitFor (fn [_] (p/resolved nil))
                  :evaluate (fn [_] (p/resolved #js {:width width :height height}))}]
    #js {:locator (fn [_] node)}))

(deftest rejects-an-empty-render-box
  (testing "a 0.01 sentinel box fails with the object that caused it"
    (t/async done
      (->> (common/wait-for-object (fake-page 0.01 200)
                                   {:id "board-id" :name "Broken board"})
           (p/then (fn [_]
                     (is false "expected the helper to reject the empty box")
                     (done)))
           (p/catch (fn [cause]
                      (let [data (ex-data cause)]
                        (is (= :validation (:type data)))
                        (is (= :empty-object (:code data)))
                        (is (= "board-id" (:object-id data)))
                        (is (str/includes? (:hint data) "Broken board")))
                      (done)))))))

(deftest accepts-a-drawable-render-box
  (testing "a normal box resolves with its locator"
    (t/async done
      (->> (common/wait-for-object (fake-page 320 200)
                                   {:id "board-id" :name "Board"})
           (p/then (fn [node]
                     (is (some? node))
                     (done)))
           (p/catch (fn [cause]
                      (is false (str "unexpected error: " cause))
                      (done)))))))
