;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.grouped-dropdown-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.ui.components.grouped-dropdown :refer [grouped-dropdown*]]
   [app.main.ui.workspace.sidebar.options.menus.measures :as measures]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private sample-options
  [{:id "fruits" :label "FRUITS" :header true}
   {:id "apple" :label "Apple" :detail "red"}
   {:id "pear" :label "Pear" :selected true}])

(defn- render
  [{:keys [show options search-term on-search-change empty-label]
    :or {show true}}]
  (rds/renderToStaticMarkup
   (mf/element grouped-dropdown*
               (mf/props {:show show
                          :on-close identity
                          :on-select identity
                          :options options
                          :search-term search-term
                          :on-search-change on-search-change
                          :empty-label empty-label}))))

(t/deftest headers-are-not-options
  (let [markup (render {:options sample-options})]
    (t/is (= 2 (count (re-seq #"role=\"option\"" markup))))
    (t/is (re-find #"role=\"presentation\"[^>]*>FRUITS</li>" markup))
    (t/is (str/includes? markup "red"))
    (t/is (= ["false" "true"]
             (mapv second (re-seq #"aria-selected=\"([^\"]+)\"" markup))))))

(t/deftest search-bar-shows-only-with-a-search-handler
  (t/is (not (str/includes? (render {:options sample-options}) "<input")))
  (t/is (str/includes? (render {:options sample-options
                                :search-term ""
                                :on-search-change identity})
                       "<input")))

(t/deftest empty-options-show-the-empty-label
  (let [markup (render {:options [] :empty-label "Nothing found"})]
    (t/is (str/includes? markup "Nothing found"))
    (t/is (not (str/includes? markup "role=\"option\"")))))

(t/deftest hidden-dropdown-renders-nothing
  (t/is (= "" (render {:show false :options sample-options}))))

(t/deftest size-presets-become-grouped-rows
  (let [rows (measures/size-preset-options
              [{:name "APPLE"}
               {:name "iPhone 16" :width 393 :height 852}
               {:name "iPhone 16 Pro" :width 402 :height 874}]
              402 874)]
    (t/is (= [{:id "APPLE" :label "APPLE" :header true}
              {:id "iPhone 16" :label "iPhone 16" :detail "393 x 852" :selected false}
              {:id "iPhone 16 Pro" :label "iPhone 16 Pro" :detail "402 x 874" :selected true}]
             rows))))
