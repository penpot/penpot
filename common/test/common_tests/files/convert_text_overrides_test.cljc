;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.convert-text-overrides-test
  "The conversion of the text override groups of files older than the
   feature components/text-overrides (`:text-content-attribute` and
   `:text-content-structure`) to one group for the letters and one per
   style. Texts are written in the notation of
   `app.common.test-helpers.texts`."
  (:require
   [app.common.features :as cfeat]
   [app.common.files.comp-processors :as cfcp]
   [app.common.files.migrations :as fmg]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private main [[["Price: "] ["$10" {:weight 700}]]])

(defn- old-copy
  "A file whose copy text is written as `copy` and holds the old touched
   groups `touched`."
  [copy touched]
  (-> (thf/sample-file :file1)
      (thtx/add-text-component-with-copy main)
      (ths/update-shape :copy-child :content (thtx/content copy))
      (ths/update-shape :copy-child :touched touched)))

(defn- convert
  ([file] (convert file {}))
  ([file libraries]
   (update file :data cfcp/convert-text-overrides libraries)))

(defn- touched
  [file label]
  (:touched (ths/get-shape file label)))

(t/deftest a-style-flag-becomes-the-styles-that-differ
  (t/is (= #{:content-group :text-content-fills}
           (-> (old-copy [[["Price: " {:color :red}] ["$10" {:weight 700 :color :red}]]]
                         #{:content-group :text-content-attribute})
               (convert)
               (touched :copy-child))))
  (t/testing "letters that differ are not an override the old groups had"
    (t/is (= #{:content-group :text-content-fills}
             (-> (old-copy [[["Cost: " {:color :red}] ["$10" {:weight 700 :color :red}]]]
                           #{:content-group :text-content-attribute})
                 (convert)
                 (touched :copy-child))))))

(t/deftest a-structure-flag-becomes-the-letters-and-styles-that-differ
  (t/is (= #{:content-group :text-content-text :text-content-weight}
           (-> (old-copy [[["Price: " {:weight 700}] ["$10" {:weight 700}]] [["Ends Sunday"]]]
                         #{:content-group :text-content-structure})
               (convert)
               (touched :copy-child)))))

(t/deftest an-old-flag-where-nothing-differs-sets-nothing
  (t/is (= #{:fill-group}
           (-> (old-copy main #{:content-group :text-content-attribute :fill-group})
               (convert)
               (touched :copy-child)))))

(t/deftest a-letters-flag-alone-stays
  (let [file (old-copy [[["Price: "] ["$20" {:weight 700}]]] #{:content-group :text-content-text})]
    (t/is (= (touched file :copy-child) (touched (convert file) :copy-child)))))

(t/deftest a-content-group-alone-becomes-the-letters-and-styles-that-differ
  ;; older files mark a copy text whose content changed with
  ;; `:content-group` alone
  (t/is (= #{:content-group :text-content-text :text-content-fills}
           (-> (old-copy [[["Cost: " {:color :red}] ["$10" {:weight 700 :color :red}]]]
                         #{:content-group})
               (convert)
               (touched :copy-child))))
  (t/testing "where nothing differs it sets nothing"
    (t/is (nil? (-> (old-copy main #{:content-group})
                    (convert)
                    (touched :copy-child)
                    (not-empty))))))

(defn- library-and-file
  "A library with the text component :component1, and a file with a copy of
   it whose text is red and holds the old style flag."
  []
  (let [library (-> (thf/sample-file :library)
                    (thtx/add-text-component-with-copy main))
        file    (-> (thf/sample-file :file1)
                    (thc/instantiate-component :component1 :lib-copy
                                               :library library
                                               :children-labels [:lib-copy-text])
                    (ths/update-shape :lib-copy-text :content
                                      (thtx/content [[["Price: " {:color :red}] ["$10" {:weight 700 :color :red}]]]))
                    (ths/update-shape :lib-copy-text :touched #{:content-group :text-content-attribute}))]
    [library file]))

(t/deftest an-unreachable-near-main-keeps-every-group-the-old-flag-allowed
  (let [[library file] (library-and-file)
        all-styles     #{:content-group :text-content-family :text-content-weight :text-content-size
                         :text-content-line-height :text-content-spacing :text-content-decoration
                         :text-content-case :text-content-fills :text-content-typography
                         :text-content-align :text-content-direction :text-content-valign}]
    (t/is (= all-styles (touched (convert file) :lib-copy-text)))
    (t/is (= #{:content-group :text-content-fills}
             (touched (convert file {(:id library) library}) :lib-copy-text)))))

(t/deftest an-unreachable-near-main-can-be-left-for-later
  (let [[library file] (library-and-file)
        file'          (binding [cfcp/*defer-unreachable* true]
                         (convert file))]
    (t/is (= (touched file :lib-copy-text) (touched file' :lib-copy-text)))
    (t/is (::cfcp/deferred-texts (meta (:data file'))))
    (t/is (= #{:content-group :text-content-fills}
             (touched (convert file' {(:id library) library}) :lib-copy-text)))))

(t/deftest migration-converts-and-adds-the-feature
  (let [old  (-> (old-copy [[["Price: " {:color :red}] ["$10" {:weight 700 :color :red}]]]
                           #{:content-group :text-content-attribute})
                 (update :features disj "components/text-overrides")
                 (assoc :migrations (disj fmg/available-migrations "0035-recompute-text-overrides")))
        file (fmg/migrate-file old (delay {}))]
    (t/is (contains? (:features file) "components/text-overrides"))
    (t/is (contains? cfeat/default-features "components/text-overrides"))
    (t/is (= #{:content-group :text-content-fills} (touched file :copy-child)))))

(t/deftest migration-converts-a-content-group-alone
  (let [old  (-> (old-copy [[["Cost: "] ["$10" {:weight 700}]]] #{:content-group})
                 (assoc :migrations (disj fmg/available-migrations "0036-recompute-content-group-alone")))
        file (fmg/migrate-file old (delay {}))]
    (t/is (= #{:content-group :text-content-text} (touched file :copy-child)))))

