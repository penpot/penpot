;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-override-operations-test
  "The text override groups of a copy through the operations that carry or
   clear overrides: detach, duplicate and reset
   (`mem:common/component-sync-contract`); update main is in case BA.
   Texts are written in the
   notation of `app.common.test-helpers.texts`."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.instances :as cli]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private label [[["Add to cart"]]])
(def ^:private red-label [[["Add to cart" {:color :red}]]])
(def ^:private big-label [[["Add to cart" {:size 18}]]])

(defn- grow-main-label
  "The main of :component1 changes its size, and its copies sync."
  [file]
  (-> file
      (thtx/set-text :main-child big-label)
      (tho/propagate-component-changes :component1)))

(t/deftest a-detached-nested-text-keeps-the-style-its-main-overrode
  ;; :card nests a copy of the label and colors its text red; detaching a
  ;; copy of :card makes its label a copy of the label component, which
  ;; keeps the red as an override and still takes the label's size
  (let [file   (-> (thf/sample-file :file1)
                   (thtx/add-text-component-with-copy label)
                   (tho/add-frame :card-root)
                   (thc/instantiate-component :component1 :card-label
                                              {:parent-label :card-root
                                               :children-labels [:card-label-text]})
                   (thc/make-component :card :card-root)
                   (thtx/set-text :card-label-text red-label)
                   (thc/instantiate-component :card :copy-card
                                              {:children-labels [:copy-label :copy-label-text]}))
        page   (thf/current-page file)
        file'  (thf/apply-changes file
                                  (cli/generate-detach-instance (-> (pcb/empty-changes nil)
                                                                    (pcb/with-page page)
                                                                    (pcb/with-objects (:objects page)))
                                                                page
                                                                {(:id file) file}
                                                                (thi/id :copy-card)))
        text   (ths/get-shape file' :copy-label-text)
        file'' (grow-main-label file')]
    (t/is (= (thi/id :main-child) (:shape-ref text)))
    (t/is (= #{:content-group :text-content-fills} (:touched text)))
    (t/is (= [[["Add to cart" {:size 18 :color :red}]]]
             (thtx/text-of file'' :copy-label-text)))))

(t/deftest a-duplicated-copy-text-keeps-its-overrides
  (let [file   (-> (thf/sample-file :file1)
                   (thtx/add-text-component-with-copy label)
                   (thtx/set-text :copy-child red-label)
                   (tho/duplicate-shape :copy-root))
        dup-id (->> (vals (:objects (thf/current-page file)))
                    (some #(when (and (= (thi/id :main-child) (:shape-ref %))
                                      (not= (thi/id :copy-child) (:id %)))
                             (:id %))))
        file'  (grow-main-label file)
        dup    (ths/get-shape-by-id file' dup-id)]
    (t/is (= #{:content-group :text-content-fills} (:touched dup)))
    (t/is (= [[["Add to cart" {:size 18 :color :red}]]]
             (thtx/view (:content dup))))))

(t/deftest a-reset-clears-the-text-overrides
  (let [file  (-> (thf/sample-file :file1)
                  (thtx/add-text-component-with-copy label)
                  (thtx/set-text :copy-child [[["Buy now" {:color :red}]]]))
        file' (tho/reset-overrides file (ths/get-shape file :copy-root))]
    (t/is (= #{:content-group :text-content-text :text-content-fills}
             (:touched (ths/get-shape file :copy-child))))
    (t/is (= label (thtx/text-of file' :copy-child)))
    (t/is (empty? (:touched (ths/get-shape file' :copy-child))))))
