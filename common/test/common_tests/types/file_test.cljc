;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.file-test
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  []
  ;; {:frame-base} [:name frame-base]                    # [Component :base]
  ;; {:frame-effects} [:name frame-effects]              # [Component :effects]
  ;;     :base-in-effects [:name frame-base]             @--> frame-base
  ;; {:frame-plain} [:name frame-plain]                  # [Component :plain]
  ;; {:frame-inner} [:name frame-inner]                  # [Component :inner]
  ;;     :plain-in-inner [:name frame-plain]             @--> frame-plain
  ;; {:frame-mid} [:name frame-mid]                      # [Component :mid]
  ;;     :inner-in-mid [:name frame-inner]               @--> frame-inner
  ;;         :effects-in-mid [:name frame-effects, :swap-slot-label :plain-in-inner] @--> frame-effects
  ;;             [:name frame-base]                      @--> :base-in-effects
  ;; {:frame-top} [:name frame-top]                      # [Component :top]
  ;;     :mid-in-top [:name frame-mid]                   @--> frame-mid
  ;;         :inner-in-top [:name frame-inner]           @--> :inner-in-mid
  ;;             :effects-in-top [:name frame-effects]   @--> :effects-in-mid
  ;;                 :base-in-top [:name frame-base]     @--> (base in :effects-in-mid)
  ;; :top-copy [:name frame-top]                         #--> frame-top
  ;;     :mid-copy [:name frame-mid]                     @--> :mid-in-top
  ;;         :inner-copy [:name frame-inner]             @--> :inner-in-top
  ;;             :effects-copy [:name frame-effects]     @--> :effects-in-top
  ;;                 :base-copy [:name frame-base]       @--> :base-in-top
  (-> (thf/sample-file :file1)
      (tho/add-frame :frame-base :name "frame-base")
      (thc/make-component :base :frame-base)
      (tho/add-frame :frame-effects :name "frame-effects")
      (thc/make-component :effects :frame-effects)
      (thc/instantiate-component :base :base-in-effects :parent-label :frame-effects)
      (tho/add-frame :frame-plain :name "frame-plain")
      (thc/make-component :plain :frame-plain)
      (tho/add-frame :frame-inner :name "frame-inner")
      (thc/make-component :inner :frame-inner)
      (thc/instantiate-component :plain :plain-in-inner :parent-label :frame-inner)
      (tho/add-frame :frame-mid :name "frame-mid")
      (thc/make-component :mid :frame-mid)
      (thc/instantiate-component :inner :inner-in-mid :parent-label :frame-mid
                                 :children-labels [:plain-in-mid])
      (thc/component-swap :plain-in-mid :effects :effects-in-mid)
      (tho/add-frame :frame-top :name "frame-top")
      (thc/make-component :top :frame-top)
      (thc/instantiate-component :mid :mid-in-top :parent-label :frame-top
                                 :children-labels [:inner-in-top :effects-in-top :base-in-top])
      (thc/instantiate-component :top :top-copy
                                 :children-labels [:mid-copy :inner-copy
                                                   :effects-copy :base-copy])))

(defn- swap-level
  [file label max-levels]
  (let [page (thf/current-page file)]
    (ctf/get-swap-level (ctn/make-container page :page)
                        {(:id file) file}
                        (ths/get-shape file label)
                        max-levels)))

(t/deftest test-get-swap-level-shape-swapped-itself
  (let [file (setup-file)]
    (t/is (= 0 (swap-level file :effects-in-mid 5)))))

(t/deftest test-get-swap-level-swapped-up-the-ref-chain
  (let [file (setup-file)]
    (t/is (= 1 (swap-level file :effects-in-top 5)))
    (t/is (= 2 (swap-level file :effects-copy 5)))))

(t/deftest test-get-swap-level-none-found
  (let [file (setup-file)]
    ;; The ref chain of the copy has no swap and reaches the main
    (t/is (= 5 (swap-level file :inner-copy 5)))
    ;; The base main has no ref chain at all
    (t/is (= 5 (swap-level file :frame-base 5)))))

(t/deftest test-get-swap-level-capped-by-max-levels
  (let [file (setup-file)]
    ;; The swap sits exactly at max-levels
    (t/is (= 2 (swap-level file :effects-copy 2)))
    ;; The swap sits beyond max-levels
    (t/is (= 1 (swap-level file :effects-copy 1)))
    ;; With no levels to look at, not even the shape itself is checked
    (t/is (= 0 (swap-level file :effects-in-mid 0)))))
